import { AxeBuilder } from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

async function auditWidgets(page: Page) {
  const result = await new AxeBuilder({ page }).include('[data-testid="analytics-widgets"]')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(result.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}

for (const theme of ['light', 'dark'] as const) {
  test.describe(`report accuracy in ${theme}`, () => {
    test.beforeEach(async ({ page }) => {
      await page.setViewportSize({ width: 360, height: 740 });
      await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
    });

    test('widget list failure has an accessible retry and recovers', async ({ page }) => {
      let fail = true;
      await page.route('**/api/v1/report-widgets', route => fail
        ? route.fulfill({ status: 500, json: { detail: 'Widget list unavailable' } })
        : route.continue());
      await page.goto('/analytics');
      const widgets = page.getByTestId('analytics-widgets');
      await expect(widgets.getByRole('alert')).toBeVisible();
      await expect(widgets.getByTestId('analytics-widgets-empty')).toHaveCount(0);
      await auditWidgets(page);
      const retry = widgets.getByRole('button', { name: 'Повторить', exact: true });
      await retry.focus();
      await expect(retry).toBeFocused();
      fail = false;
      await page.keyboard.press('Enter');
      await expect(widgets.getByRole('alert')).toHaveCount(0);
      await expect(widgets.getByTestId('analytics-widget')).toHaveCount(1);
      await expect(widgets.getByTestId('report-table')).toBeVisible();
      await auditWidgets(page);
    });

    test('nonadditive widget shows exact values as an accessible table', async ({ page }) => {
      await page.route('**/api/v1/entities/example.orders/reports/7', route => route.fulfill({
        json: {
          groups: [{ field: 'status', implicit: false }, { field: 'orderDate', implicit: false }],
          measures: [{ op: 'avg', field: 'total' }],
          rows: ['01', '02', '03', '04', '05', '06', '07'].map((day, index) => ({
            groups: ['draft', `2026-01-${day}`], values: [index + 1],
          })),
          truncated: false,
        },
      }));
      await page.goto('/analytics');
      const widget = page.getByTestId('analytics-widget');
      await expect(widget.getByTestId('report-chart-fallback')).toBeVisible();
      await expect(widget.getByTestId('report-chart')).toHaveCount(0);
      await expect(widget.getByTestId('report-table').locator('tbody tr')).toHaveCount(7);
      await expect(widget.getByTestId('report-table').locator('tbody td:last-child'))
        .toHaveText(['1', '2', '3', '4', '5', '6', '7']);
      await auditWidgets(page);
    });

    test('missing KPI is distinct from zero', async ({ page }) => {
      await page.route('**/api/v1/entities/example.orders/reports/7', route => route.fulfill({
        json: {
          groups: [], measures: [{ op: 'avg', field: 'total' }],
          rows: [{ groups: [], values: [null] }], truncated: false,
        },
      }));
      await page.goto('/analytics');
      await expect(page.getByTestId('analytics-widget').locator('.kpi__value')).toHaveText('—');
      await auditWidgets(page);
    });
  });
}
