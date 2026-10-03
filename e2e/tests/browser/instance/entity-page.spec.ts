import { expect, test } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors, uniqueRunName } from '../../../support/diagnostics.js';
import { openEntity } from '../../../support/entity-page.js';

/*
 * The general entity screen /e/:code (ADR-0032 7.1, 7.3; plan 10/10, item 5.5) on the notes: the list, the form and
 * the record are drawn from form-meta and query-meta, the records go through the general runtime
 * /api/v1/entities/ms.notes. No web code of the notes takes part: their board stays at /notes.
 */
test('a record is created, changed, archived and deleted on the general entity screen', async ({ page }) => {
  const title = uniqueRunName('E2E entity note');
  const changedTitle = `${title} (changed)`;

  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page);

  const notes = await openEntity(page, 'ms.notes', 'Заметки');
  await expect(page.getByRole('table', { name: 'Заметки' })).toBeVisible();

  // Create: the form of the notes from form-meta.
  await page.getByRole('link', { name: 'Создать' }).click();
  await expect(page).toHaveURL(/\/e\/ms\.notes\/new$/u);
  await notes.fillField('title', title);
  await notes.fillField('contentMd', 'Created on the **general** entity screen.');
  const created = await notes.save();
  expect(created.request().method()).toBe('POST');
  expect(created.status()).toBe(201);
  await expect(page).toHaveURL(/\/e\/ms\.notes\/\d+$/u);
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
  const recordUrl = new URL(page.url()).pathname;

  // The list shows it, and its link opens the record.
  await page.getByRole('link', { name: 'К списку' }).click();
  await page.getByRole('searchbox', { name: 'Поиск' }).fill(title);
  const link = page.getByRole('link', { name: title, exact: true });
  await expect(link).toBeVisible();
  await link.click();
  await expect(page).toHaveURL(new RegExp(`${recordUrl.replace(/\./gu, '\\.')}$`, 'u'));

  // Change: PATCH from the revision on screen.
  await page.getByRole('link', { name: 'Редактировать' }).click();
  await expect(page).toHaveURL(/\/edit$/u);
  const titleBox = page.getByRole('textbox', { name: 'Заголовок' });
  await expect(titleBox).toHaveValue(title);
  await notes.fillField('title', changedTitle);
  const changeResponse = await notes.save();
  expect(changeResponse.request().method()).toBe('PATCH');
  expect(changeResponse.status()).toBe(200);
  expect(changeResponse.request().headers()['if-match']).toMatch(/^"\d+"$/u);
  await expect(page.getByRole('heading', { level: 1, name: changedTitle })).toBeVisible();

  // History: the creation and the change of the title.
  await page.getByRole('tab', { name: 'История' }).click();
  const history = page.getByTestId('record-history-list');
  await expect(history).toContainText('Создание');
  await expect(history).toContainText(changedTitle);

  // Archive and restore.
  await page.getByRole('tab', { name: 'Поля' }).click();
  const archived = page.waitForResponse(response =>
    response.request().method() === 'PUT' && /\/api\/v1\/entities\/ms\.notes\/\d+\/archived$/u.test(response.url())
  );
  await page.getByRole('button', { name: 'В архив' }).click();
  expect((await archived).ok()).toBe(true);
  await expect(page.getByTestId('entity-archived')).toBeVisible();
  const restored = page.waitForResponse(response =>
    response.request().method() === 'PUT' && /\/archived$/u.test(response.url())
  );
  await page.getByRole('button', { name: 'Вернуть из архива' }).click();
  expect((await restored).ok()).toBe(true);
  await expect(page.getByTestId('entity-archived')).toHaveCount(0);

  // Delete after confirming: back to the list, where the note is gone.
  await page.getByRole('button', { name: 'Удалить', exact: true }).click();
  const deleteDialog = page.getByRole('alertdialog');
  const deleted = page.waitForResponse(response =>
    response.request().method() === 'DELETE' && /\/api\/v1\/entities\/ms\.notes\/\d+$/u.test(response.url())
  );
  await deleteDialog.getByRole('button', { name: 'Удалить', exact: true }).click();
  expect((await deleted).status()).toBe(204);
  await expect(page).toHaveURL(/\/e\/ms\.notes$/u);
  await page.getByRole('searchbox', { name: 'Поиск' }).fill(changedTitle);
  await expect(page.getByTestId('entity-empty')).toBeVisible();

  assertNoPageErrors();
});

test('an unknown entity is not found on the general screen', async ({ page }) => {
  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page, [/404/u]);

  await page.goto('/e/nope.missing');
  await expect(page.getByRole('heading', { name: 'Раздел не найден' })).toBeVisible();

  assertNoPageErrors();
});
