import { randomBytes } from 'node:crypto';
import { expect, request, test, type Page, type Response } from '@playwright/test';
import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors, uniqueRunName } from '../../../support/diagnostics.js';
import { expectNoSeriousAccessibilityViolations } from '../../../support/accessibility.js';

// These fixtures belong to the companion package's disposable, migrated QA
// runtime. Never fall back to the repository's default installation on 4200.
test.beforeEach(async ({ baseURL }) => {
  const configured = process.env.INSTANCE_BASE_URL;
  if (!configured || !baseURL || new URL(configured).origin !== new URL(baseURL).origin) {
    throw new Error('Search reliability E2E requires an explicit isolated INSTANCE_BASE_URL');
  }
  const target = new URL(baseURL);
  if (!['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname)
    || ['4200', '14200', '14203', '14204', '14205', '14206'].includes(target.port)) {
    throw new Error('Search reliability E2E requires a separate loopback QA origin');
  }
});

type Category = 'TASK' | 'PROJECT' | 'USER';
type SearchResponse = {
  source: 'TYPESENSE' | 'POSTGRES'; degraded: boolean;
  hits: Array<{ id: string; entityType: Category; title: string }>;
};

async function api<T>(page: Page, method: 'GET' | 'POST' | 'PATCH' | 'PUT', path: string, expected: number, data?: unknown): Promise<T> {
  if (method === 'GET') {
    const response = await page.request.get(`/api/v1${path}`);
    try {
      if (response.status() !== expected) throw new Error(`Synthetic GET fixture returned HTTP ${response.status()}, expected ${expected}`);
      return expected === 204 ? undefined as T : await response.json() as T;
    } finally {
      await response.dispose();
    }
  }

  const configured = process.env.INSTANCE_BASE_URL;
  if (!configured || new URL(page.url()).origin !== new URL(configured).origin) {
    throw new Error(`Synthetic ${method} fixture rejected a non-fixture origin`);
  }
  const storageState = await page.context().storageState();
  const token = storageState.cookies.find(cookie => cookie.name === 'XSRF-TOKEN')?.value;
  if (!token) throw new Error('Authenticated fixture has no CSRF cookie');

  // A change names the revision it was made from (plan item 3.6): read the record as the screen would first.
  const headers: Record<string, string> = { 'X-XSRF-TOKEN': token };
  if (method === 'PATCH' || method === 'PUT') {
    // The archive switch of a record is PUT .../archived: its revision is the record's.
    const record = path.replace(/\/archived$/u, '');
    const current = await api<{ revision?: number }>(page, 'GET', record, 200);
    headers['If-Match'] = `"${current.revision ?? 1}"`;
  }
  // A record action of an entity names the revision too (ADR-0032 6.7).
  const action = /^(\/entities\/[a-z.]+\/\d+)\/actions\/[a-z_0-9]+$/u.exec(path);
  if (method === 'POST' && action) {
    const current = await api<{ revision?: number }>(page, 'GET', action[1], 200);
    headers['If-Match'] = `"${current.revision ?? 1}"`;
  }

  let isolated;
  let response;
  try {
    try {
      isolated = await request.newContext({
        baseURL: new URL(configured).origin,
        storageState,
        timeout: 30_000,
        maxRedirects: 0,
      });
      response = await isolated.fetch(`/api/v1${path}`, {
        method,
        headers,
        data,
        timeout: 30_000,
        maxRedirects: 0,
        maxRetries: 0,
      });
    } catch {
      throw new Error(`Synthetic ${method} fixture transport failed`);
    }
    if (response.status() !== expected) throw new Error(`Synthetic ${method} fixture returned HTTP ${response.status()}, expected ${expected}`);
    if (expected === 204) return undefined as T;
    try {
      return await response.json() as T;
    } catch {
      throw new Error(`Synthetic ${method} fixture response decoding failed`);
    }
  } finally {
    await response?.dispose();
    await isolated?.dispose();
  }
}

async function indexed(page: Page, query: string, category: Category, id: string, present: boolean): Promise<void> {
  await expect.poll(async () => {
    const response = await api<SearchResponse>(page, 'GET', `/search?q=${encodeURIComponent(query)}&entity=${category}`, 200);
    return {
      source: response.source, degraded: response.degraded,
      present: response.hits.some(hit => hit.entityType === category && hit.id === id),
    };
  }, { message: 'observable indexed-data delivery, never SQL-fallback acceptance', intervals: [1000], timeout: 30_000 })
    .toEqual({ source: 'TYPESENSE', degraded: false, present });
}

async function openPalette(page: Page, query: string, category: Category | 'ALL') {
  await expect(page.getByRole('button', { name: 'Открыть глобальный поиск' })).toBeVisible();
  await page.keyboard.press('ControlOrMeta+k');
  const palette = page.getByRole('dialog', { name: 'Глобальный поиск', exact: true });
  await expect(palette).toBeVisible();
  await palette.locator(`.category-pills [role="tab"][data-category="${category}"]`).click();
  await palette.locator('input[role="combobox"]').fill(query);
  return palette;
}

test('real indexed task/project/user hits open fresh exact records, survive reload/back, and exclude passive records', async ({ page }) => {
  test.setTimeout(120_000);
  await loginToInstance(page);
  const assertHealthy = collectPageErrors(page);
  const marker = uniqueRunName('E2Esearch');
  // Tasks and projects are records of the general runtime (ADR-0032 8).
  const project = await api<{ id: number }>(page, 'POST', '/entities/ms.projects', 201,
    { name: `${marker} project`, description: 'Synthetic search project' });
  const task = await api<{ id: number }>(page, 'POST', '/entities/ms.tasks', 201,
    { title: `${marker} task`, descriptionMarkdown: 'Synthetic search task', typeCode: 'task', projectId: project.id, priority: 'medium' });
  const login = `search${randomBytes(8).toString('hex')}`;
  // The user is an entity of the general runtime (ADR-0032 8): created without a password, invited by mail.
  const user = await api<{ id: number }>(page, 'POST', '/entities/md.users', 201,
    { name: `${marker} user`, login, email: `${login}@example.invalid`, language: 'ru', timezone: 'Asia/Tashkent' });
  const records = [
    { category: 'TASK' as const, id: String(task.id), endpoint: `/entities/ms.tasks/${task.id}`, route: `/tasks/items/${task.id}`, name: 'task', field: 'title' },
    { category: 'PROJECT' as const, id: String(project.id), endpoint: `/entities/ms.projects/${project.id}`, route: `/tasks/projects/${project.id}`, name: 'project', field: 'name' },
    { category: 'USER' as const, id: String(user.id), endpoint: `/entities/md.users/${user.id}`, route: `/e/md.users/${user.id}`, name: 'user', field: 'name' },
  ];

  for (const record of records) {
    await indexed(page, marker, record.category, record.id, true);
    await page.goto('/tasks');
    // Real category/ID lookup is intentionally PostgreSQL; indexing was proven
    // separately above with an ordinary query and explicit Typesense provenance.
    const palette = await openPalette(page, `#${record.id}`, record.category);
    const hit = palette.getByRole('option').filter({ hasText: `${marker} ${record.name}` });
    await expect(hit).toHaveCount(1);
    await expect(palette.locator('.palette-degraded')).toHaveCount(0);
    const freshName = `${marker} refreshed ${record.name}`;
    // Each of them is a record of the general runtime, which answers a change with the record (200).
    await api(page, 'PATCH', record.endpoint, 200, { [record.field]: freshName });
    const detailResponse = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === `/api/v1${record.endpoint}`);
    await hit.click();
    expect((await detailResponse).status()).toBe(200);
    await expect(page).toHaveURL(new RegExp(`${record.route}$`, 'u'));
    // The user's record is the general entity screen: its heading names the record.
    const detail = record.category === 'USER'
      ? page.getByRole('heading', { level: 1 })
      : page.locator(`[data-record-id="${record.id}"]`);
    await expect(detail).toContainText(freshName);
    if (record.category === 'PROJECT') await expect(page.locator('.project-edit-form')).toHaveCount(0);
    await page.reload();
    await expect(page).toHaveURL(new RegExp(`${record.route}$`, 'u'));
    await expect(detail).toContainText(freshName);
    await page.goBack();
    await expect(page).toHaveURL(/\/tasks$/u);
    await expect(page.getByRole('dialog')).toHaveCount(0);
  }

  // A change of the user on the general form, from the revision on screen, and back to the record.
  await page.goto(`/e/md.users/${user.id}/edit`);
  const nameBox = page.getByRole('textbox', { name: 'Имя', exact: true });
  await expect(nameBox).toHaveValue(`${marker} refreshed user`);
  await nameBox.fill(`${marker} edited user`);
  const savedUser = page.waitForResponse(response => response.request().method() === 'PATCH'
    && new URL(response.url()).pathname === `/api/v1/entities/md.users/${user.id}`);
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click();
  expect((await savedUser).status()).toBe(200);
  await expect(page).toHaveURL(new RegExp(`/e/md\\.users/${user.id}$`, 'u'));
  await expect(page.getByRole('heading', { level: 1 })).toContainText(`${marker} edited user`);

  await api(page, 'PUT', `/entities/ms.projects/${project.id}/archived`, 200, { archived: true });
  await api(page, 'POST', `/entities/md.users/${user.id}/actions/block`, 200, {});
  await indexed(page, marker, 'PROJECT', String(project.id), false);
  await indexed(page, marker, 'USER', String(user.id), false);
  // Passive search exclusion does not redefine the existing detail policy.
  expect((await api<{ archived: boolean }>(page, 'GET', `/entities/ms.projects/${project.id}`, 200)).archived).toBe(true);
  expect((await api<{ state: string }>(page, 'GET', `/entities/md.users/${user.id}`, 200)).state).toBe('P');
  assertHealthy();
  // No task/project delete endpoint exists. The final QA runtime/volumes are
  // disposable; passive synthetic project/user records remain for evidence.
});

test('real missing canonical IDs show localized 404 and a working list action', async ({ page }) => {
  await loginToInstance(page);
  const failures: Array<{ method: string; path: string }> = [];
  page.on('response', response => {
    if (response.status() === 404) failures.push({ method: response.request().method(), path: new URL(response.url()).pathname });
  });
  const assertHealthy = collectPageErrors(page, [/^Failed to load resource: the server responded with a status of 404 \((?:Not Found)?\)$/u]);
  const absentId = '9223372036854775807';
  for (const record of [
    { route: '/tasks/items', endpoint: '/entities/ms.tasks' },
    { route: '/tasks/projects', endpoint: '/entities/ms.projects' },
  ]) {
    // Verify this is an actually absent ID, not a passive or anonymized record.
    const preflight = await page.request.get(`/api/v1${record.endpoint}/${absentId}`);
    const status = preflight.status(); await preflight.dispose();
    expect(status).toBe(404);
    const missing = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === `/api/v1${record.endpoint}/${absentId}`);
    await page.goto(`${record.route}/${absentId}`);
    expect((await missing).status()).toBe(404);
    await expect(page.getByRole('alert')).toHaveText(/404 — Запись не найдена или недоступна/u);
    await page.getByRole('button', { name: 'Вернуться к списку', exact: true }).first().click();
    await expect(page).toHaveURL(new RegExp(`${record.route}$`, 'u'));
    await expect(page.getByRole('dialog')).toHaveCount(0);
  }
  // A user is the general entity screen (ADR-0032 8): its own "not found" state with the way back to the list. The
  // general screen reads a record by a safe integer id, so the absent user is the largest one, verified absent first.
  const absentUserId = String(Number.MAX_SAFE_INTEGER);
  const userPreflight = await page.request.get(`/api/v1/entities/md.users/${absentUserId}`);
  const userStatus = userPreflight.status(); await userPreflight.dispose();
  expect(userStatus).toBe(404);
  const missingUser = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname === `/api/v1/entities/md.users/${absentUserId}`);
  await page.goto(`/e/md.users/${absentUserId}`);
  expect((await missingUser).status()).toBe(404);
  await expect(page.getByTestId('entity-page-state')).toContainText('Запись не найдена');
  await page.getByTestId('entity-page-state').getByRole('link', { name: 'К списку', exact: true }).click();
  await expect(page).toHaveURL(/\/e\/md\.users$/u);
  // A task's card also reads its comments, participants and files: each of them answers 404 for a missing task.
  const subresource = /\/(?:comments|members|files)$/u;
  expect(failures.filter(failure => !subresource.test(failure.path))).toEqual([
    { method: 'GET', path: `/api/v1/entities/ms.tasks/${absentId}` },
    { method: 'GET', path: `/api/v1/entities/ms.projects/${absentId}` },
    { method: 'GET', path: `/api/v1/entities/md.users/${absentUserId}` },
  ]);
  expect(failures.filter(failure => subresource.test(failure.path))
    .every(failure => failure.method === 'GET' && failure.path.startsWith(`/api/v1/tasks/${absentId}/`))).toBe(true);
  assertHealthy();
});

test('controlled search outage and 429: one local error, visible cooldown and manual retry on mobile', async ({ page }) => {
  await loginToInstance(page);
  await page.setViewportSize({ width: 390, height: 844 });
  const errors: Array<{ method: string; path: string; status: number }> = [];
  const onResponse = (response: Response) => {
    if ([429, 503].includes(response.status())) errors.push({ method: response.request().method(), path: new URL(response.url()).pathname, status: response.status() });
  };
  page.on('response', onResponse);
  const assertHealthy = collectPageErrors(page, [
    /^Failed to load resource: the server responded with a status of 503 \(Service Unavailable\)$/u,
    /^Failed to load resource: the server responded with a status of 429 \(Too Many Requests\)$/u,
  ]);
  let calls = 0;
  await page.route('**/api/v1/search?*', async route => {
    if (route.request().method() !== 'GET' || new URL(route.request().url()).pathname !== '/api/v1/search') return route.continue();
    calls++;
    if (calls === 1) return route.fulfill({ status: 503, json: { code: 'SEARCH_UNAVAILABLE', detail: 'Поиск временно недоступен' } });
    if (calls === 2) return route.fulfill({ status: 429, headers: { 'Retry-After': '2' }, json: { code: 'RATE_LIMITED', detail: 'rate limited' } });
    return route.fulfill({ status: 200, json: { query: 'new', totalHits: 0, hits: [], foundHits: null, hasMore: false, source: 'POSTGRES', degraded: true } });
  });
  const palette = await openPalette(page, 'outage', 'ALL');
  await expect(palette.getByRole('alert')).toContainText('Поиск временно недоступен');
  await expect(page.locator('.toast-container')).toHaveCount(0);
  await palette.getByRole('button', { name: 'Повторить', exact: true }).click();
  const retry = palette.getByRole('button', { name: 'Повторить', exact: true });
  await expect(retry).toBeDisabled();
  await expect(palette.getByRole('alert')).toContainText('Повторить можно через');
  await palette.locator('input[role="combobox"]').fill('new');
  await expect(retry).toBeEnabled();
  expect(calls).toBe(2); // Countdown expiration did not start a retry.
  await retry.click();
  await expect(palette.locator('.palette-empty')).toBeVisible();
  await expect(palette.locator('.palette-degraded')).toBeVisible();
  expect(calls).toBe(3);
  const box = await palette.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(390);
  await expectNoSeriousAccessibilityViolations(page, '.palette-dialog');
  await palette.getByRole('button', { name: 'Закрыть поиск', exact: true }).click();
  await expect(palette).toHaveCount(0);
  page.off('response', onResponse);
  expect(errors).toEqual([
    { method: 'GET', path: '/api/v1/search', status: 503 },
    { method: 'GET', path: '/api/v1/search', status: 429 },
  ]);
  assertHealthy();
});
