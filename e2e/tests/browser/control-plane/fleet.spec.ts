import { expect, test } from '@playwright/test';

import { loginToControlPlane } from '../../../support/auth.js';
import { collectPageErrors, uniqueRunName } from '../../../support/diagnostics.js';

test('creates, publishes and archives an announcement through the UI', async ({ page }) => {
  const title = uniqueRunName('E2E announcement');
  let publicationRequested = false;

  await loginToControlPlane(page);
  const assertNoPageErrors = collectPageErrors(page);
  try {
    await page.goto('/announcements');
    await page.getByRole('button', { name: 'Новое объявление' }).click();
    await page.getByLabel('Тип баннера').selectOption('warning');
    await page.getByLabel('Заголовок').fill(title);
    await page.getByLabel('Текст').fill('Playwright verifies the announcement lifecycle.');
    await page.getByRole('button', { name: 'Сохранить черновик' }).click();

    const row = page.getByRole('row').filter({ hasText: title });
    await expect(row).toContainText('Черновик');
    await row.getByRole('button', { name: /Опубликовать$/u }).click();
    publicationRequested = true;
    await expect(row).toContainText('Опубликовано');
    assertNoPageErrors();
  } finally {
    if (publicationRequested) {
      await page.goto('/announcements');
      const cleanupRow = page.getByRole('row').filter({ hasText: title });
      const archiveButton = cleanupRow.getByRole('button', { name: /В архив$/u });
      await expect(archiveButton).toBeVisible();
      await archiveButton.click();
      await expect(cleanupRow).toContainText('В архиве');
    }
  }
});
