import { AxeBuilder } from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

// ADR-0044: the pivot, the comparison of periods and the page of report datasets meet WCAG 2.1 AA in both themes, on a
// phone and on a wide screen, and are reached by keyboard.

async function audit(page: Page, include: string) {
  // A dialog fades in: its colours are measured once the animations are over.
  await page.waitForFunction(() => document.getAnimations().every(animation => animation.playState !== 'running'));
  const result = await new AxeBuilder({ page }).include(include)
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(result.violations.map(violation => `${violation.id}: ${violation.nodes.map(node => node.target)}`)).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}

const PIVOT = {
  groups: [{ field: 'status', implicit: false }, { field: 'orderDate', trunc: 'month', implicit: false }],
  measures: [{ op: 'sum', field: 'total' }, { op: 'sum', field: 'total', share: 'total' }],
  rows: [
    { groups: ['draft', '2026-09-01'], values: [100, 40], labels: ['Черновик', null] },
    { groups: ['draft', '2026-10-01'], values: [50, 20], labels: ['Черновик', null] },
    { groups: ['posted', '2026-10-01'], values: [100, 40], labels: ['Проведён', null] },
  ],
  totals: [
    { kind: 'row', groups: ['draft', null], values: [150, 60], labels: ['Черновик', null] },
    { kind: 'row', groups: ['posted', null], values: [100, 40], labels: ['Проведён', null] },
    { kind: 'column', groups: [null, '2026-09-01'], values: [100, 40], labels: [null, null] },
    { kind: 'column', groups: [null, '2026-10-01'], values: [150, 60], labels: [null, null] },
    { kind: 'grand', groups: [null, null], values: [250, 100], labels: [null, null] },
  ],
  truncated: false,
  pivot: true,
};

const COMPARED = {
  groups: [],
  measures: ['current', 'previous', 'change', 'change_percent'].map(period => ({ op: 'sum', field: 'total', period })),
  rows: [{ groups: [], values: [210, 30, 180, 600], labels: [] }],
  truncated: false,
  totals: [],
  pivot: false,
  comparison: {
    field: 'orderDate', period: 'month', against: 'previous',
    currentFrom: '2026-10-01', currentTo: '2026-10-31', previousFrom: '2026-09-01', previousTo: '2026-09-30',
  },
};

const DATASET = {
  code: 'order_lines',
  name: { ru: 'Строки заказов', uz: 'Buyurtma qatorlari', en: 'Order lines' },
  entity: 'example.orders',
  lines: 'lines',
  fields: ['status', 'total'],
  filter: [],
  measures: [{ key: 'avgOrder', labels: { ru: 'Средний заказ', uz: 'Средний заказ', en: 'Average order' }, formula: 'sum_total / count' }],
  revision: 2,
  createdAt: '2026-10-18T08:00:00Z',
  modifiedAt: '2026-10-18T09:00:00Z',
};

for (const theme of ['light', 'dark'] as const) {
  test.describe(`report analytics ${theme}`, () => {
    let errors: string[];
    test.beforeEach(async ({ page }) => {
      errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
    });
    test.afterEach(() => expect(errors).toEqual([]));

    test('a pivot widget is a table with scoped headers on a wide screen and cards on a phone', async ({ page }) => {
      await page.route('**/api/v1/entities/example.orders/reports/7', route => route.fulfill({ json: PIVOT }));
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.goto('/analytics');
      const widget = page.getByTestId('analytics-widget');
      const table = widget.getByTestId('report-pivot');
      await expect(table).toBeVisible();
      await expect(table.locator('th[scope="row"]')).toHaveText(['Черновик', 'Проведён', 'Итого']);
      await expect(table.getByTestId('report-pivot-grand').first()).toContainText('250');
      const region = widget.locator('.pivot-wrap');
      await region.focus();
      await expect(region).toBeFocused();
      await audit(page, '[data-testid="analytics-widgets"]');
      await page.setViewportSize({ width: 360, height: 740 });
      await expect(widget.getByTestId('report-pivot-cards')).toBeVisible();
      await expect(table).toBeHidden();
      await audit(page, '[data-testid="analytics-widgets"]');
    });

    test('a comparison of periods is one card with its change and its periods', async ({ page }) => {
      await page.route('**/api/v1/entities/example.orders/reports/7', route => route.fulfill({ json: COMPARED }));
      await page.setViewportSize({ width: 360, height: 740 });
      await page.goto('/analytics');
      const widget = page.getByTestId('analytics-widget');
      await expect(widget.getByTestId('report-kpis')).toContainText('210');
      await expect(widget.getByTestId('kpi-change')).toBeVisible();
      await expect(widget.getByTestId('report-periods')).toBeVisible();
      await audit(page, '[data-testid="analytics-widgets"]');
    });

    test('the report datasets page lists, opens and checks a dataset by keyboard', async ({ page }) => {
      await page.route(url => url.pathname === '/api/v1/report-datasets', route => route.fulfill({ json: { items: [DATASET], hasMore: false } }));
      await page.route(url => url.pathname === '/api/v1/report-datasets/fields', route => route.fulfill({
        json: {
          code: 'example.orders', defaultSort: 'id', defaultLimit: 50, maxLimit: 200, maxConditions: 20, maxInValues: 100,
          fields: [
            { key: 'status', labelKey: 'example.orders.col.status', type: 'enum', ops: ['eq', 'in'], sortable: false, nullable: false, defaultVisible: true, enumValues: ['draft', 'posted'], enumLabelPrefix: null },
            { key: 'total', labelKey: 'example.orders.col.total', type: 'number', ops: ['gt', 'lt'], sortable: false, nullable: true, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
          ],
        },
      }));
      await page.setViewportSize({ width: 360, height: 740 });
      await page.goto('/settings/report-datasets');
      const item = page.getByTestId('report-datasets-item');
      await expect(item).toContainText('Строки заказов');
      await audit(page, '[data-testid="report-datasets"]');
      const edit = item.getByTestId('report-datasets-edit');
      await edit.focus();
      await page.keyboard.press('Enter');
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByTestId('report-dataset-form')).toBeVisible();
      await expect(dialog.getByTestId('report-dataset-fields')).toBeVisible();
      await audit(page, '[role="dialog"]');
      await page.setViewportSize({ width: 1440, height: 1000 });
      await audit(page, '[role="dialog"]');
    });
  });
}
