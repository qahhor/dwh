import { expect, test } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors, uniqueRunName } from '../../../support/diagnostics.js';

test('project to task to comment works through the visible UI', async ({ page }) => {
  const projectName = uniqueRunName('E2E project');
  const taskName = uniqueRunName('E2E task');
  const comment = uniqueRunName('E2E comment');

  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page);
  await page.goto('/tasks/projects');

  await page.getByRole('button', { name: 'Новый проект' }).click();
  await page.getByLabel('Название проекта').fill(projectName);
  await page.getByLabel('Описание проекта').fill('Playwright critical vertical slice');
  const projectResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && response.url().endsWith('/api/v1/tasks/projects')
  );
  await page.getByRole('button', { name: 'Создать проект' }).click();
  expect((await projectResponse).ok()).toBe(true);
  const projectButton = page.getByRole('button', { name: projectName, exact: true });
  await expect(projectButton).toBeVisible();

  await projectButton.click();
  await expect(page).toHaveURL(/\/tasks\?project_id=\d+$/u);
  const projectId = new URL(page.url()).searchParams.get('project_id');
  // The project pickers search the paged project list and name a chosen project by its card (plan 10/10, item 3.5).
  const projectFilter = page.getByRole('combobox', { name: 'Фильтр по проекту' });
  await expect(projectFilter).toContainText(projectName);
  await page.getByRole('button', { name: 'Новая задача' }).click();
  await expect(page.getByRole('dialog').getByRole('combobox', { name: 'Проект', exact: true })).toContainText(projectName);
  await page.getByLabel('Название задачи').fill(taskName);
  // Priority is a segmented radio group (smt-radio-group).
  await page.getByRole('radio', { name: 'Высокий' }).click();
  await page.getByLabel('Описание', { exact: true }).fill('Created by the browser E2E suite.');
  const taskResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && response.url().endsWith('/api/v1/tasks')
  );
  await page.getByRole('button', { name: 'Создать задачу' }).click();
  expect((await taskResponse).ok()).toBe(true);

  const taskRowAction = page.getByRole('button', { name: new RegExp(taskName, 'u') }).first();
  await expect(taskRowAction).toBeVisible();
  await taskRowAction.click();

  const dialog = page.getByRole('dialog', { name: /Задача #\d+/u });
  await expect(dialog.getByRole('heading', { name: taskName })).toBeVisible();
  await dialog.getByPlaceholder(/Написать комментарий/u).fill(comment);
  const commentResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && /\/api\/v1\/tasks\/\d+\/comments$/u.test(response.url())
  );
  await dialog.getByRole('button', { name: 'Отправить' }).click();
  expect((await commentResponse).ok()).toBe(true);
  await expect(dialog.getByText(comment)).toBeVisible();

  // Choose the project again through the filter: type to search the server, pick it, the list narrows to it.
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await page.getByRole('button', { name: 'Сбросить все фильтры' }).click();
  await expect(projectFilter).toContainText('Все проекты');
  await projectFilter.click();
  const projectSearch = page.waitForResponse(response => {
    const url = new URL(response.url());
    return url.pathname === '/api/v1/tasks/projects/page' && url.searchParams.get('q') === projectName;
  });
  await page.getByPlaceholder('Найти проект по названию').fill(projectName);
  expect((await projectSearch).ok()).toBe(true);
  const narrowed = page.waitForResponse(response => {
    const url = new URL(response.url());
    return url.pathname === '/api/v1/tasks' && url.searchParams.get('projectId') === projectId;
  });
  await page.getByRole('option', { name: projectName, exact: true }).click();
  expect((await narrowed).ok()).toBe(true);
  await expect(projectFilter).toContainText(projectName);
  await expect(page.getByRole('button', { name: new RegExp(taskName, 'u') }).first()).toBeVisible();
  assertNoPageErrors();
});
