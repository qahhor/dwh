import { AxeBuilder } from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

for (const theme of ['light', 'dark'] as const) {
  test.describe(`${theme} translation editor`, () => {
    test.beforeEach(async ({ page }) => {
      await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
      await page.goto('/settings?tab=languages');
      await page.getByTestId('edit-language-en').click();
      await expect(page.locator('#translation-common-save')).toHaveValue('Save');
    });

    test('uses common actions, accessible fields and a mobile layout', async ({ page }) => {
      await page.setViewportSize({ width: 360, height: 800 });
      const actions = page.getByTestId('language-editor-actions');
      await expect(actions.getByRole('button', { name: 'Отмена', exact: true })).toBeVisible();
      await expect(actions.getByRole('button', { name: 'Сохранить переводы', exact: true })).toBeDisabled();
      const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      expect(result.violations).toEqual([]);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(360);
      await page.locator('#translation-common-save').fill('Save draft');
      await actions.getByRole('button', { name: 'Отмена', exact: true }).click();
      const confirm = page.getByRole('alertdialog', { name: 'Отменить изменения?' });
      await expect(confirm).toBeVisible();
      await expect(confirm.getByRole('button', { name: 'Продолжить редактирование' })).toBeFocused();
      await confirm.getByRole('button', { name: 'Продолжить редактирование' }).click();
      await expect(page.locator('#translation-common-save')).toHaveValue('Save draft');
    });

    test('protects translations across tab changes and route navigation', async ({ page }) => {
      await page.locator('#translation-common-save').fill('Save draft');
      await page.locator('#settings-general-tab').click();
      const confirm = page.getByRole('alertdialog', { name: 'Отменить изменения?' });
      await confirm.getByRole('button', { name: 'Продолжить редактирование' }).click();
      await expect(page.locator('#settings-languages-tab')).toHaveAttribute('aria-selected', 'true');
      await expect(page.locator('#translation-common-save')).toHaveValue('Save draft');
      await page.getByRole('link', { name: 'Пользователи', exact: true }).click();
      await confirm.getByRole('button', { name: 'Продолжить редактирование' }).click();
      await expect(page).toHaveURL(/\/settings\?tab=languages$/);
      await expect(page.locator('#translation-common-save')).toHaveValue('Save draft');
    });

    test('Enter submits once, a conflict keeps the draft and pending fields are locked', async ({ page }) => {
      let finish: () => void = () => {};
      const pending = new Promise<void>(resolve => { finish = resolve; });
      let saves = 0;
      await page.route('**/i18n/admin/languages/en/translations', async route => {
        if (route.request().method() !== 'PUT') return route.continue();
        saves += 1;
        expect(route.request().postDataJSON()).toEqual({ expectedRevision: 1, translations: { 'common.save': 'Save draft' } });
        await pending;
        await route.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify({ status: 409, title: 'Conflict' }) });
      });
      const field = page.locator('#translation-common-save');
      await field.fill('Save draft');
      await field.press('Enter');
      try {
        await expect(field).toBeDisabled();
        await expect(page.getByTestId('language-editor-close')).toBeDisabled();
        await expect(page.getByTestId('language-import')).toBeDisabled();
      } finally {
        finish();
      }
      await expect(field).toBeEnabled();
      await expect(field).toHaveValue('Save draft');
      await expect(page.locator('.save-error')).toContainText('другим администратором');
      expect(saves).toBe(1);
    });
  });
}
