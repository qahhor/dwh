import { expect, test } from '@playwright/test';

import { expectNoSeriousAccessibilityViolations } from '../../../support/accessibility.js';
import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors, uniqueRunName } from '../../../support/diagnostics.js';

test('administrator creates, publishes, and archives a local announcement', async ({ page }) => {
  const title = uniqueRunName('E2E announcement');
  const body = uniqueRunName('Release message');

  await page.setViewportSize({ width: 390, height: 844 });
  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page);
  const origin = new URL(page.url()).origin;
  await page.goto('/announcements');

  await expect(page.getByRole('heading', { level: 1, name: 'Объявления' })).toBeVisible();
  const create = page.getByRole('button', { name: 'Создать объявление' });
  await create.focus();
  await expect(create).toBeFocused();
  await page.keyboard.press('Enter');

  const editor = page.getByRole('dialog', { name: 'Новое объявление' });
  await expect(editor).toBeVisible();
  await expect(editor.getByLabel('Заголовок (RU)')).toBeFocused();
  await editor.getByLabel('Заголовок (RU)').fill(title);
  await editor.getByLabel('Текст объявления (RU)').fill(body);
  await editor.getByLabel('Уровень сообщения').selectOption('WARNING');
  await expectNoSeriousAccessibilityViolations(page, '[role="dialog"]');

  const createResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && response.url().endsWith('/api/v1/announcements')
  );
  await editor.getByTestId('save-draft').click();
  const created = await createResponse;
  expect(created.ok()).toBe(true);
  expect(new URL(created.url()).origin).toBe(origin);

  const card = page.locator('article.announcement-card').filter({ has: page.getByRole('heading', { name: title }) });
  await expect(card).toContainText('Черновик');
  await expect(card).toContainText(body);

  await card.getByRole('button', { name: 'Опубликовать' }).click();
  const publishDialog = page.getByRole('dialog', { name: 'Опубликовать объявление?' });
  const publishResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && response.url().endsWith('/publish')
  );
  await publishDialog.getByRole('button', { name: 'Подтвердить' }).click();
  expect((await publishResponse).ok()).toBe(true);
  await expect(card).toContainText('Опубликовано');

  await card.getByRole('button', { name: 'Архивировать' }).click();
  const archiveDialog = page.getByRole('dialog', { name: 'Архивировать объявление?' });
  const archiveResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && response.url().endsWith('/archive')
  );
  await archiveDialog.getByRole('button', { name: 'Подтвердить' }).click();
  expect((await archiveResponse).ok()).toBe(true);
  await expect(card).toContainText('Архив');

  const hasHorizontalOverflow = await page.evaluate(() =>
    document.documentElement.scrollWidth > window.innerWidth
  );
  expect(hasHorizontalOverflow).toBe(false);
  await expectNoSeriousAccessibilityViolations(page);
  assertNoPageErrors();
});
