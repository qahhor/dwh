import { AxeBuilder } from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

for (const theme of ['light', 'dark'] as const) {
  test.describe(`${theme} notification preferences`, () => {
    test.beforeEach(async ({ page }) => {
      await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
      await page.goto('/notifications');
    });

    test('has named controls, saved values and no WCAG violations', async ({ page }) => {
      const opener = page.getByRole('button', { name: 'Настройки уведомлений', exact: true });
      await opener.click();
      const dialog = page.getByRole('dialog', { name: 'Настройки уведомлений', exact: true });
      await expect(dialog.getByRole('checkbox', { name: 'Изменение статуса задачи — Email', exact: true })).not.toBeChecked();
      await expect(dialog.getByRole('checkbox')).toHaveCount(18);
      const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      expect(result.violations).toEqual([]);
      await dialog.getByRole('button', { name: 'Отмена', exact: true }).click();
      await expect(opener).toBeFocused();
    });

    test('keeps every channel reachable in the narrow matrix', async ({ page }) => {
      await page.setViewportSize({ width: 360, height: 800 });
      await page.getByRole('button', { name: 'Настройки уведомлений', exact: true }).click();
      const region = page.getByRole('region', { name: 'Настройки уведомлений', exact: true });
      await expect(region).toBeFocused();
      await expect(region).toHaveCSS('overflow-x', 'auto');
      await page.keyboard.press('Tab');
      await expect(page.getByRole('checkbox', { name: 'Назначение на задачу — В приложении', exact: true })).toBeFocused();
      await page.keyboard.press('Tab');
      await page.keyboard.press('Tab');
      const telegram = page.getByRole('checkbox', { name: 'Назначение на задачу — Telegram', exact: true });
      await expect(telegram).toBeFocused();
      await expect(telegram).toBeInViewport();
      const regionBox = await region.boundingBox();
      const box = await telegram.boundingBox();
      expect(box!.x).toBeGreaterThanOrEqual(regionBox!.x);
      expect(box!.x + box!.width).toBeLessThanOrEqual(regionBox!.x + regionBox!.width);
      await page.keyboard.press('Space');
      await expect(telegram).not.toBeChecked();
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(360);
    });

    test('a failed load cannot expose defaults as saved preferences', async ({ page }) => {
      await page.route('**/notifications/preferences', route => route.fulfill({
        status: 503, contentType: 'application/problem+json', body: JSON.stringify({ status: 503, title: 'Unavailable' }),
      }));
      const opener = page.getByRole('button', { name: 'Настройки уведомлений', exact: true });
      await opener.click();
      await expect(page.getByText('Не удалось загрузить настройки уведомлений. Попробуйте ещё раз.', { exact: true })).toBeVisible();
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(opener).toBeEnabled();
      await expect(opener).toBeFocused();
      await page.unroute('**/notifications/preferences');
      await opener.click();
      await expect(page.getByRole('checkbox', { name: 'Изменение статуса задачи — Email', exact: true })).not.toBeChecked();
    });

    test('locks a pending save and preserves choices when it fails', async ({ page }) => {
      let finish: () => void = () => {};
      const pending = new Promise<void>(resolve => { finish = resolve; });
      await page.route('**/notifications/preferences', async route => {
        if (route.request().method() !== 'PUT') return route.continue();
        await pending;
        await route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ status: 503, title: 'Unavailable' }) });
      });
      await page.getByRole('button', { name: 'Настройки уведомлений', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'Настройки уведомлений', exact: true });
      const choice = dialog.getByRole('checkbox', { name: 'Назначение на задачу — Telegram', exact: true });
      await choice.click();
      await dialog.getByRole('button', { name: 'Сохранить', exact: true }).click();
      try {
        await expect(choice).toBeDisabled();
        await expect(dialog.getByRole('button', { name: 'Отмена', exact: true })).toBeDisabled();
        await expect(dialog.getByRole('button', { name: 'Закрыть', exact: true })).toHaveCount(0);
      } finally {
        finish();
      }
      await expect(choice).toBeEnabled();
      await expect(choice).not.toBeChecked();
      await expect(dialog).toBeVisible();
    });
  });
}
