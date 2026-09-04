import { expect, test } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';

test('translation override repaints live, persists across sessions, and keeps Russian fallback', async ({ browser, page }) => {
  const marker = `E2E-Save-${Date.now()}`;
  await loginToInstance(page);
  const languageSelector = page.locator('#app-language-selector');
  const originalLanguage = await languageSelector.inputValue();
  let originalGerman = '';
  let overrideSaved = false;

  const openGermanEditor = async () => {
    await page.goto('/settings');
    await page.locator('#settings-languages-tab').click();
    await page.getByTestId('edit-language-de').click();
    return page.locator('[data-translation-key="common.save"] input');
  };

  try {
    const translation = await openGermanEditor();
    originalGerman = await translation.inputValue();
    await translation.fill(marker);
    await page.getByTestId('language-editor-save').click();
    await expect(page.getByText('Переводы успешно сохранены')).toBeVisible();
    overrideSaved = true;

    await page.getByTestId('language-editor-close').click();
    await page.getByTestId('switch-language-de').click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'de');

    await page.goto('/settings');
    await expect(page.getByRole('button', { name: marker, exact: true })).toBeVisible();
    await page.locator('#settings-languages-tab').click();
    await expect(page.getByRole('heading', {
      name: 'Управление языковыми пакетами и локализацией'
    })).toBeVisible();

    const secondContext = await browser.newContext();
    const secondPage = await secondContext.newPage();
    try {
      await loginToInstance(secondPage);
      await expect(secondPage.locator('html')).toHaveAttribute('lang', 'de');
      await secondPage.goto('/settings');
      await expect(secondPage.getByRole('button', { name: marker, exact: true })).toBeVisible();
    } finally {
      await secondContext.close();
    }
  } finally {
    if (overrideSaved) {
      const translation = await openGermanEditor();
      await translation.fill(originalGerman);
      await page.getByTestId('language-editor-save').click();
      await expect(page.getByText('Переводы успешно сохранены')).toBeVisible();
    }
    await page.locator('#app-language-selector').selectOption(originalLanguage || 'ru');
    await expect(page.locator('html')).toHaveAttribute('lang', originalLanguage || 'ru');
  }
});
