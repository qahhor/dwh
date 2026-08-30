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
  await page.getByRole('button', { name: 'Создать проект' }).click();
  const projectButton = page.getByRole('button', { name: projectName, exact: true });
  await expect(projectButton).toBeVisible();

  await projectButton.click();
  await expect(page).toHaveURL(/\/tasks\?project_id=\d+$/u);
  await page.getByRole('button', { name: 'Новая задача' }).click();
  await page.getByLabel('Название задачи').fill(taskName);
  await page.getByRole('button', { name: 'Высокий' }).click();
  await page.getByLabel('Подробное описание задачи').fill('Created by the browser E2E suite.');
  await page.getByRole('button', { name: 'Создать задачу' }).click();

  const taskRowAction = page.getByRole('button', { name: new RegExp(taskName, 'u') }).first();
  await expect(taskRowAction).toBeVisible();
  await taskRowAction.click();

  const dialog = page.getByRole('dialog', { name: /Задача #\d+/u });
  await expect(dialog.getByRole('heading', { name: taskName })).toBeVisible();
  await dialog.getByPlaceholder(/Написать комментарий/u).fill(comment);
  await dialog.getByRole('button', { name: 'Отправить' }).click();
  await expect(dialog.getByText(comment)).toBeVisible();
  assertNoPageErrors();
});
