import { expect, test } from '@playwright/test';

for (const theme of ['light', 'dark']) {
  test(`mobile note and audit actions remain easy to target in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
    await page.goto('/notes');
    const edit = page.locator('app-note-card').first().getByRole('button', { name: 'Редактировать', exact: true });
    await expect(edit).toBeVisible();
    for (const button of await page.locator('app-note-card').first().locator('.card-actions button').all()) {
      const box = (await button.boundingBox())!;
      expect(box.width).toBeGreaterThanOrEqual(44);
      expect(box.height).toBeGreaterThanOrEqual(44);
    }
    await edit.click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.getByRole('dialog').getByRole('button', { name: 'Отмена', exact: true }).click();
    await expect(edit).toBeFocused();
    await page.goto('/audit');
    const diff = page.getByRole('button', { name: /^Просмотреть изменение/ }).first();
    await expect(diff).toBeVisible();
    const box = (await diff.boundingBox())!;
    expect(box.width).toBeGreaterThanOrEqual(44);
    expect(box.height).toBeGreaterThanOrEqual(44);
  });

  test(`chart data remains accessible without mobile page overflow in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
    await page.route('**/api/v1/modules/active', route => route.fulfill({ json: [
      { code: 'upl', name: 'Uploads', isActive: true, status: 'ACTIVE' },
    ] }));
    const totals = { uploads: 12, received: 0, verified: 6, rejected: 0, applied: 6, rowsApplied: 18000 };
    await page.route('**/api/v1/upl/overview?*', route => route.fulfill({ json: {
      days: 30, generatedAt: '2026-10-14T10:00:00Z', totals, previous: totals,
      daily: [{ day: '2026-10-14', applied: 6, rejected: 0, other: 6 }], freshness: [], attention: [],
    } }));
    await page.goto('/upl/overview');
    await expect(page.getByTestId('bar-chart-table')).toBeAttached();
    await expect(page.getByTestId('bar-chart-table').getByRole('columnheader')).toHaveCount(4);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(360);
  });
}
