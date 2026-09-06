import {
  expect,
  test,
  type Locator,
  type Page,
  type Request,
  type Response,
  type Route,
} from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors, uniqueRunName } from '../../../support/diagnostics.js';

const PROJECTS_PATH = '/api/v1/tasks/projects';
const EXPECTED_HTTP_503_CONSOLE = /^Failed to load resource: the server responded with a status of 503 \(Service Unavailable\)$/u;

type ExpectedProject503 = {
  method: 'POST' | 'PATCH';
  path: string;
};

function collectExpectedProject503(page: Page): (expected: ExpectedProject503) => void {
  const responses: Array<{ method: string; path: string }> = [];
  const onResponse = (response: Response): void => {
    if (response.status() !== 503) return;
    responses.push({
      method: response.request().method(),
      path: new URL(response.url()).pathname,
    });
  };
  page.on('response', onResponse);
  const assertNoPageErrors = collectPageErrors(page, [EXPECTED_HTTP_503_CONSOLE]);

  return (expected: ExpectedProject503): void => {
    page.off('response', onResponse);
    expect.soft(responses, 'all HTTP 503 responses belong to the controlled project fixture').toEqual([expected]);
    assertNoPageErrors();
  };
}

function projectFixture(id: number, name: string, state: 'A' | 'P', description: string) {
  return {
    id,
    name,
    description,
    state,
    attributes: {},
    createdAt: '2026-09-06T06:00:00Z',
    createdBy: 1,
  };
}

function projectRow(page: Page, name: string): Locator {
  return page.locator('tr.project-row').filter({
    has: page.getByRole('button', { name, exact: true }),
  });
}

function projectCard(page: Page, name: string): Locator {
  return page.locator('.project-card').filter({
    has: page.getByRole('button', { name, exact: true }),
  });
}

function projectEditButton(page: Page, name: string): Locator {
  return page.getByRole('button', { name: `Редактировать проект ${name}`, exact: true });
}

function observeProjectMutations(page: Page): Request[] {
  const requests: Request[] = [];
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname;
    const method = request.method();
    if ((method === 'POST' && path === PROJECTS_PATH)
      || (method === 'PATCH' && /^\/api\/v1\/tasks\/projects\/\d+$/u.test(path))) {
      requests.push(request);
    }
  });
  return requests;
}

async function expectMinimumHitbox(
  locator: Locator,
  minimum: { width?: number; height?: number },
): Promise<void> {
  const box = await locator.boundingBox();
  expect(box, 'interactive control has a rendered hitbox').not.toBeNull();
  if (minimum.width !== undefined) expect(box!.width).toBeGreaterThanOrEqual(minimum.width);
  if (minimum.height !== undefined) expect(box!.height).toBeGreaterThanOrEqual(minimum.height);
}

async function expectTabFocusContained(page: Page, dialog: Locator): Promise<void> {
  await expect(dialog).toBeVisible();
  await expect.poll(() => dialog.evaluate(element => element.contains(element.ownerDocument.activeElement)))
    .toBe(true);

  for (let step = 0; step < 8; step++) {
    await page.keyboard.press('Tab');
    const focusState = await dialog.evaluate((element) => {
      const active = element.ownerDocument.activeElement;
      return {
        contained: active instanceof HTMLElement && element.contains(active),
        visiblyFocused: active instanceof HTMLElement && active.matches(':focus-visible'),
      };
    });
    expect(focusState.contained, `Tab step ${step + 1} remains in the dialog`).toBe(true);
    expect(focusState.visiblyFocused, `Tab step ${step + 1} keeps a visible focus indicator`).toBe(true);
  }

  await page.keyboard.press('Shift+Tab');
  await expect.poll(() => dialog.evaluate(element => element.contains(element.ownerDocument.activeElement)))
    .toBe(true);
}

async function openEditorAndObserveDetail(page: Page, projectName: string): Promise<string> {
  const detailResponse = page.waitForResponse(response => response.request().method() === 'GET'
    && /^\/api\/v1\/tasks\/projects\/\d+$/u.test(new URL(response.url()).pathname));
  await projectEditButton(page, projectName).click();
  const response = await detailResponse;
  expect(response.status()).toBe(200);
  return new URL(response.url()).pathname;
}

async function createProjectWithButton(page: Page, name: string, description: string): Promise<void> {
  await page.getByRole('button', { name: 'Новый проект', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Создание нового проекта', exact: true });
  await dialog.getByLabel('Название проекта', { exact: true }).fill(name);
  await dialog.getByLabel('Описание проекта', { exact: true }).fill(description);
  const responsePromise = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === PROJECTS_PATH);
  await dialog.getByRole('button', { name: 'Создать проект', exact: true }).click();
  expect((await responsePromise).status()).toBe(201);
  await expect(page.getByRole('button', { name, exact: true })).toBeVisible();
}

test('native Enter creates and edits exactly once while description newlines and sparse persistence round-trip', async ({ page }) => {
  const originalName = uniqueRunName('E2E project Enter');
  const editedName = `${originalName} renamed`;
  const originalDescription = 'Description preserved through a name-only edit';

  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page);
  await page.goto('/tasks/projects');
  const mutations = observeProjectMutations(page);

  await page.getByRole('button', { name: 'Новый проект', exact: true }).click();
  const createDialog = page.getByRole('dialog', { name: 'Создание нового проекта', exact: true });
  const createName = createDialog.getByLabel('Название проекта', { exact: true });
  const createDescription = createDialog.getByLabel('Описание проекта', { exact: true });
  await createName.fill(originalName);
  await createDescription.fill(originalDescription);
  await createDescription.press('Enter');
  await expect(createDescription).toHaveValue(`${originalDescription}\n`);
  expect(mutations).toHaveLength(0);
  await createDescription.fill(originalDescription);

  const createResponse = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === PROJECTS_PATH);
  await createName.press('Enter');
  expect((await createResponse).status()).toBe(201);
  expect(mutations.filter(request => request.method() === 'POST')).toHaveLength(1);
  expect(mutations[0].postDataJSON()).toEqual({
    name: originalName,
    description: originalDescription,
  });
  await expect(page.getByRole('button', { name: originalName, exact: true })).toBeVisible();

  const detailPath = await openEditorAndObserveDetail(page, originalName);
  const editDialog = page.getByRole('dialog', { name: 'Редактирование проекта', exact: true });
  await expect(editDialog.getByLabel('Описание', { exact: true })).toHaveValue(originalDescription);
  const editName = editDialog.getByLabel('Название проекта', { exact: true });
  await editName.fill(editedName);
  const namePatch = page.waitForResponse(response => response.request().method() === 'PATCH'
    && new URL(response.url()).pathname === detailPath);
  await editName.press('Enter');
  const namePatchResponse = await namePatch;
  expect(namePatchResponse.status()).toBe(204);
  expect(namePatchResponse.request().postDataJSON()).toEqual({ name: editedName });
  expect(mutations.filter(request => request.method() === 'PATCH')).toHaveLength(1);
  await expect(page.getByRole('button', { name: editedName, exact: true })).toBeVisible();

  expect(await openEditorAndObserveDetail(page, editedName)).toBe(detailPath);
  await expect(editDialog.getByLabel('Описание', { exact: true })).toHaveValue(originalDescription);
  await editDialog.getByLabel('Описание', { exact: true }).fill('');
  const clearPatch = page.waitForResponse(response => response.request().method() === 'PATCH'
    && new URL(response.url()).pathname === detailPath);
  await editDialog.getByRole('button', { name: 'Сохранить', exact: true }).click();
  const clearPatchResponse = await clearPatch;
  expect(clearPatchResponse.status()).toBe(204);
  expect(clearPatchResponse.request().postDataJSON()).toEqual({ description: '' });

  expect(await openEditorAndObserveDetail(page, editedName)).toBe(detailPath);
  await expect(editDialog.getByLabel('Описание', { exact: true })).toHaveValue('');
  await editDialog.getByRole('button', { name: 'Отмена', exact: true }).click();
  assertNoPageErrors();
});

test('dirty create Cancel and Escape preserve or discard the draft with trapped visible focus', async ({ page }) => {
  const discardedName = uniqueRunName('E2E discarded create draft');

  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page);
  await page.goto('/tasks/projects');
  const mutations = observeProjectMutations(page);
  const opener = page.getByRole('button', { name: 'Новый проект', exact: true });
  await opener.click();
  const createDialog = page.getByRole('dialog', { name: 'Создание нового проекта', exact: true });
  await expectTabFocusContained(page, createDialog);
  const nameInput = createDialog.getByLabel('Название проекта', { exact: true });
  await nameInput.fill(discardedName);

  await createDialog.getByRole('button', { name: 'Отмена', exact: true }).click();
  const discardDialog = page.getByRole('dialog', { name: 'Отменить создание проекта?', exact: true });
  await expect(discardDialog).toBeVisible();
  await discardDialog.getByRole('button', { name: 'Отмена', exact: true }).click();
  await expect(discardDialog).toBeHidden();
  await expect(nameInput).toHaveValue(discardedName);

  await page.keyboard.press('Escape');
  await expect(discardDialog).toBeVisible();
  await discardDialog.getByRole('button', { name: 'Удалить черновик', exact: true }).click();
  await expect(createDialog).toBeHidden();
  await expect(opener).toBeFocused();
  expect(mutations).toHaveLength(0);
  assertNoPageErrors();
});

test('controlled HTTP 503 create retry preserves the draft and reaches the real server once', async ({ page }) => {
  const retriedName = uniqueRunName('E2E retried create');
  const retriedDescription = 'Create draft survives a controlled save failure';

  await loginToInstance(page);
  const assertExpectedProject503 = collectExpectedProject503(page);
  await page.goto('/tasks/projects');
  const mutations = observeProjectMutations(page);
  const opener = page.getByRole('button', { name: 'Новый проект', exact: true });
  await opener.click();
  const createDialog = page.getByRole('dialog', { name: 'Создание нового проекта', exact: true });
  await createDialog.getByLabel('Название проекта', { exact: true }).fill(retriedName);
  await createDialog.getByLabel('Описание проекта', { exact: true }).fill(retriedDescription);
  let controlledAttempts = 0;
  const controlledCreateFailure = async (route: Route): Promise<void> => {
    const request = route.request();
    if (request.method() !== 'POST' || new URL(request.url()).pathname !== PROJECTS_PATH) {
      await route.continue();
      return;
    }
    controlledAttempts++;
    await route.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      json: {
        title: 'Controlled E2E failure',
        status: 503,
        code: 'PROJECT_CREATE_E2E_FAILURE',
        detail: 'Controlled create failure',
      },
    });
  };

  await page.route('**/api/v1/tasks/projects', controlledCreateFailure);
  try {
    await createDialog.getByRole('button', { name: 'Создать проект', exact: true }).click();
    await expect(createDialog.getByTestId('project-create-save-error')).toHaveText('Controlled create failure');
    await expect(createDialog.getByLabel('Название проекта', { exact: true })).toHaveValue(retriedName);
    await expect(createDialog.getByLabel('Описание проекта', { exact: true })).toHaveValue(retriedDescription);
    expect(controlledAttempts).toBe(1);
  } finally {
    await page.unroute('**/api/v1/tasks/projects', controlledCreateFailure);
  }

  const retryResponse = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === PROJECTS_PATH);
  await createDialog.getByRole('button', { name: 'Создать проект', exact: true }).click();
  expect((await retryResponse).status()).toBe(201);
  expect(mutations.filter(request => request.method() === 'POST')).toHaveLength(2);
  await expect(page.getByRole('button', { name: retriedName, exact: true })).toBeVisible();
  assertExpectedProject503({ method: 'POST', path: PROJECTS_PATH });
});

test('dirty edit Cancel and Escape preserve or discard the draft and return focus', async ({ page }) => {
  const originalName = uniqueRunName('E2E discarded edit draft');
  const discardedName = `${originalName} discarded`;
  const description = 'Edit draft description';

  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page);
  await page.goto('/tasks/projects');
  await createProjectWithButton(page, originalName, description);
  const mutations = observeProjectMutations(page);
  await openEditorAndObserveDetail(page, originalName);
  const editDialog = page.getByRole('dialog', { name: 'Редактирование проекта', exact: true });
  const editName = editDialog.getByLabel('Название проекта', { exact: true });
  await editName.fill(discardedName);

  await editDialog.getByRole('button', { name: 'Отмена', exact: true }).click();
  const discardDialog = page.getByRole('dialog', { name: 'Отменить изменения проекта?', exact: true });
  await expect(discardDialog).toBeVisible();
  await discardDialog.getByRole('button', { name: 'Отмена', exact: true }).click();
  await expect(discardDialog).toBeHidden();
  await expect(editName).toHaveValue(discardedName);

  await page.keyboard.press('Escape');
  await expect(discardDialog).toBeVisible();
  const opener = projectEditButton(page, originalName);
  await discardDialog.getByRole('button', { name: 'Отменить изменения', exact: true }).click();
  await expect(editDialog).toBeHidden();
  await expect(opener).toBeFocused();
  expect(mutations).toHaveLength(0);
  assertNoPageErrors();
});

test('controlled HTTP 503 edit retry preserves the draft and reaches the real server once', async ({ page }) => {
  const originalName = uniqueRunName('E2E edit retry seed');
  const retriedName = `${originalName} retried`;
  const description = 'Edit failure seed description';

  await loginToInstance(page);
  const assertExpectedProject503 = collectExpectedProject503(page);
  await page.goto('/tasks/projects');
  await createProjectWithButton(page, originalName, description);
  const mutations = observeProjectMutations(page);
  const detailPath = await openEditorAndObserveDetail(page, originalName);
  const editDialog = page.getByRole('dialog', { name: 'Редактирование проекта', exact: true });
  await editDialog.getByLabel('Название проекта', { exact: true }).fill(retriedName);
  let controlledAttempts = 0;
  const controlledEditFailure = async (route: Route): Promise<void> => {
    const request = route.request();
    if (request.method() !== 'PATCH' || new URL(request.url()).pathname !== detailPath) {
      await route.continue();
      return;
    }
    controlledAttempts++;
    await route.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      json: {
        title: 'Controlled E2E failure',
        status: 503,
        code: 'PROJECT_EDIT_E2E_FAILURE',
        detail: 'Controlled edit failure',
      },
    });
  };

  const detailGlob = `**${detailPath}`;
  await page.route(detailGlob, controlledEditFailure);
  try {
    await editDialog.getByRole('button', { name: 'Сохранить', exact: true }).click();
    await expect(editDialog.getByTestId('project-edit-save-error')).toHaveText('Controlled edit failure');
    await expect(editDialog.getByLabel('Название проекта', { exact: true })).toHaveValue(retriedName);
    await expect(editDialog.getByLabel('Описание', { exact: true })).toHaveValue(description);
    expect(controlledAttempts).toBe(1);
  } finally {
    await page.unroute(detailGlob, controlledEditFailure);
  }

  const retryResponse = page.waitForResponse(response => response.request().method() === 'PATCH'
    && new URL(response.url()).pathname === detailPath);
  await editDialog.getByRole('button', { name: 'Сохранить', exact: true }).click();
  const response = await retryResponse;
  expect(response.status()).toBe(204);
  expect(response.request().postDataJSON()).toEqual({ name: retriedName });
  expect(mutations.filter(request => request.method() === 'PATCH')).toHaveLength(2);
  await expect(page.getByRole('button', { name: retriedName, exact: true })).toBeVisible();
  assertExpectedProject503({ method: 'PATCH', path: detailPath });
});

test('controlled project fixtures keep filter, copy, hitbox, card, and mobile contracts', async ({ page }) => {
  const fixtures = [
    projectFixture(781_001, 'Fixture active anchor', 'A', 'Active fixture description'),
    projectFixture(781_002, 'Fixture archived anchor', 'P', 'Archived fixture description'),
    projectFixture(781_003, 'Fixture unknown statistics', 'A', 'No statistics fixture'),
    ...Array.from({ length: 9 }, (_, index) => projectFixture(
      781_004 + index,
      `Fixture filler ${String(index + 1).padStart(2, '0')}`,
      'A',
      `Filler description ${index + 1}`,
    )),
  ];
  const stats = [
    { projectId: 781_001, totalTasks: 3, activeTasks: 1, doneTasks: 2 },
    { projectId: 781_002, totalTasks: 4, activeTasks: 0, doneTasks: 4 },
    ...fixtures.slice(3).map(project => ({
      projectId: project.id,
      totalTasks: 1,
      activeTasks: 1,
      doneTasks: 0,
    })),
  ];

  await loginToInstance(page);
  await page.goto('/files');
  const assertNoPageErrors = collectPageErrors(page);
  const unexpectedMutations: string[] = [];
  const projectFixtureRoute = async (route: Route): Promise<void> => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() !== 'GET') {
      unexpectedMutations.push(`${request.method()} ${path}`);
      await route.abort();
      return;
    }
    if (path === PROJECTS_PATH) {
      await route.fulfill({ contentType: 'application/json', json: fixtures });
      return;
    }
    if (path === `${PROJECTS_PATH}/stats`) {
      await route.fulfill({ contentType: 'application/json', json: stats });
      return;
    }
    const fixture = fixtures.find(project => path === `${PROJECTS_PATH}/${project.id}`);
    if (fixture) {
      await route.fulfill({ contentType: 'application/json', json: fixture });
      return;
    }
    await route.continue();
  };
  await page.route('**/api/v1/tasks/projects**', projectFixtureRoute);

  try {
    await page.goto('/tasks/projects');
    const viewGroup = page.getByRole('group', { name: 'Режим отображения проектов' });
    const listButton = viewGroup.getByRole('button', { name: 'Список', exact: true });
    const cardsButton = viewGroup.getByRole('button', { name: 'Карточки', exact: true });
    await expect(listButton).toHaveAttribute('aria-pressed', 'true');
    await expect(cardsButton).toHaveAttribute('aria-pressed', 'false');

    const filterGroup = page.getByRole('group', { name: 'Фильтр проектов по статусу' });
    await expect(filterGroup.getByRole('button', { name: 'Активные', exact: true })).toBeVisible();
    await expect(filterGroup.getByRole('button', { name: 'Архив', exact: true })).toBeVisible();
    await expect(projectRow(page, 'Fixture active anchor')).toContainText('Активен');
    await expect(projectRow(page, 'Fixture archived anchor')).toContainText('В архиве');
    await expect(projectRow(page, 'Fixture active anchor')).toContainText('2 / 3 закрыто');
    await expect(projectRow(page, 'Fixture unknown statistics').getByText('Статистика недоступна', { exact: true }))
      .toBeVisible();
    await expect(page.getByText(
      'Статистика учитывает только доступные вам задачи. Закрытые задачи — задачи в конечных статусах, включая выполненные и отменённые.',
      { exact: true },
    )).toBeVisible();

    const listEdit = projectEditButton(page, 'Fixture active anchor');
    await expectMinimumHitbox(listEdit, { width: 28, height: 28 });
    await page.getByRole('button', { name: 'Страница 2', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Страница 2', exact: true })).toHaveAttribute('aria-current', 'page');
    const search = page.getByRole('textbox', { name: 'Поиск проектов', exact: true });
    await search.fill('Fixture active anchor');
    await expect(projectRow(page, 'Fixture active anchor')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Страница 1', exact: true })).toHaveAttribute('aria-current', 'page');
    await page.getByRole('button', { name: 'Очистить поиск проектов', exact: true }).click();

    await cardsButton.click();
    await expect(cardsButton).toHaveAttribute('aria-pressed', 'true');
    await expect(projectCard(page, 'Fixture active anchor')).toContainText('Активен');
    await expect(projectCard(page, 'Fixture archived anchor')).toContainText('В архиве');
    await expectMinimumHitbox(projectEditButton(page, 'Fixture active anchor'), { width: 28, height: 28 });
    await expectMinimumHitbox(
      projectCard(page, 'Fixture active anchor').getByRole('button', { name: 'Задачи (3) →', exact: true }),
      { height: 28 },
    );

    await page.setViewportSize({ width: 390, height: 844 });
    await expect.poll(async () => page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }))).toEqual({ clientWidth: 390, scrollWidth: 390 });
    await filterGroup.getByRole('button', { name: 'Архив', exact: true }).click();
    await expect(filterGroup.getByRole('button', { name: 'Архив', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.project-card')).toHaveCount(1);
    const archivedCard = projectCard(page, 'Fixture archived anchor');
    await expect(archivedCard).toContainText('В архиве');

    const mobileEdit = projectEditButton(page, 'Fixture archived anchor');
    const detailResponse = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === `${PROJECTS_PATH}/781002`);
    await mobileEdit.click();
    expect((await detailResponse).status()).toBe(200);
    const editDialog = page.getByRole('dialog', { name: 'Редактирование проекта', exact: true });
    const state = editDialog.getByLabel('Статус активности', { exact: true });
    await expect(state).toHaveValue('P');
    await expect(state.locator('option')).toHaveText(['Активен', 'В архиве']);
    await editDialog.getByRole('button', { name: 'Отмена', exact: true }).click();
    await expect(editDialog).toBeHidden();
    await expect(mobileEdit).toBeFocused();
    expect(unexpectedMutations).toEqual([]);
    assertNoPageErrors();
  } finally {
    await page.unroute('**/api/v1/tasks/projects**', projectFixtureRoute);
  }
});
