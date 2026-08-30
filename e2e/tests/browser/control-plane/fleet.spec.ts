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
    await page.getByLabel('Тип баннера').selectOption('warning');
    await page.getByLabel('Заголовок').fill(title);
    await page.getByLabel('Текст').fill('Playwright verifies the announcement lifecycle.');
    await page.getByRole('button', { name: 'Сохранить черновик' }).click();

    const row = page.getByRole('row').filter({ hasText: title });
    await expect(row).toContainText('черновик');
    await row.getByRole('button', { name: `Опубликовать ${title}` }).click();
    publicationRequested = true;
    await expect(row).toContainText('опубликовано');
    assertNoPageErrors();
  } finally {
    if (publicationRequested) {
      await page.goto('/announcements');
      const cleanupRow = page.getByRole('row').filter({ hasText: title });
      const archiveButton = cleanupRow.getByRole('button', { name: `Архивировать ${title}` });
      await expect(archiveButton).toBeVisible();
      await archiveButton.click();
      await expect(cleanupRow).toContainText('в архиве');
    }
  }
});
