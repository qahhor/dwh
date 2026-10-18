import { AxeBuilder } from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

// ADR-0045: the mailings of a saved report, the dialog of a mailing and its run history meet WCAG 2.1 AA in both
// themes, on a phone and on a wide screen, and are reached by keyboard.

async function audit(page: Page, include: string) {
  // A dialog fades in: its colours are measured once the animations are over.
  await page.waitForFunction(() => document.getAnimations().every(animation => animation.playState !== 'running'));
  const result = await new AxeBuilder({ page }).include(include)
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(result.violations.map(violation => `${violation.id}: ${violation.nodes.map(node => node.target)}`)).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}

const REPORT = {
  id: 7, entity: 'example.orders', listCode: 'example.orders', kind: 'report', name: 'Заказы по статусам',
  state: {
    groupBy: [{ field: 'status' }], measures: [{ op: 'sum', field: 'total' }], filter: [], chart: 'table', totals: true,
    parameters: [{ key: 'period', field: 'orderDate', required: true }],
  },
  lockVersion: 1, modifiedAt: '2026-10-18T08:00:00Z', canEdit: true, canShare: true, shared: true, owned: true,
};

const MAILING = {
  id: 3, viewId: 7, entity: 'example.orders', name: 'Итоги прошлого месяца',
  recipients: [{ id: 1, name: 'Администратор', login: 'admin', state: 'A' }],
  channels: ['email', 'in_app'], attachXlsx: true, includeLink: true,
  schedule: { frequency: 'monthly', time: '08:00', timeZone: 'Asia/Tashkent', monthDay: 1 },
  parameters: { period: { preset: 'last_month' } }, condition: { kind: 'has_rows' }, active: true,
  nextRunAt: '2026-11-01T03:00:00Z', ownerId: 1, lastRun: { id: 9, status: 'partial', createdAt: '2026-10-19T03:00:00Z' },
  revision: 2, createdAt: '2026-10-18T08:00:00Z', modifiedAt: '2026-10-18T09:00:00Z',
};

const RUN = {
  id: 9, kind: 'manual', slot: '2026-10-19T03:00:00Z', status: 'partial', recipients: 2, sent: 2, skipped: 1, failed: 1,
  createdAt: '2026-10-19T03:00:00Z', finishedAt: '2026-10-19T03:00:05Z',
};

const META = {
  code: 'example.orders', defaultSort: 'id', defaultLimit: 50, maxLimit: 200, maxConditions: 20, maxInValues: 100,
  fields: [
    { key: 'status', labelKey: 'example.orders.col.status', type: 'enum', ops: ['eq', 'in'], sortable: false, nullable: false, defaultVisible: true, enumValues: ['draft', 'posted'], enumLabelPrefix: null },
    { key: 'orderDate', labelKey: 'example.orders.col.order_date', type: 'date', ops: ['between'], sortable: true, nullable: true, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
    { key: 'total', labelKey: 'example.orders.col.total', type: 'number', ops: ['gt', 'lt'], sortable: false, nullable: true, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
  ],
};

async function routes(page: Page) {
  await page.route(url => url.pathname === '/api/v1/report-views/7', route => route.fulfill({ json: REPORT }));
  await page.route(url => url.pathname === '/api/v1/query-meta/example.orders', route => route.fulfill({ json: META }));
  await page.route(url => url.pathname === '/api/v1/report-mailings/options', route => route.fulfill({
    json: { channels: ['email', 'in_app'], maxRecipients: 50, maxAttachmentBytes: 5242880, canManageAll: true },
  }));
  await page.route(url => url.pathname === '/api/v1/report-mailings', route => route.fulfill({ json: { items: [MAILING], hasMore: false } }));
  await page.route(url => url.pathname === '/api/v1/report-mailings/3/runs', route => route.fulfill({ json: { items: [RUN], hasMore: false } }));
  await page.route(url => url.pathname === '/api/v1/report-mailings/3/runs/9', route => route.fulfill({
    json: {
      run: RUN,
      deliveries: [
        { userId: 1, userName: 'Администратор', channel: 'email', status: 'queued', outbox: 'SENT', rows: 2 },
        { userId: 1, userName: 'Администратор', channel: 'in_app', status: 'sent', rows: 2 },
        { userId: 2, userName: 'Продавец', channel: 'email', status: 'skipped', reason: 'condition' },
        { userId: 2, userName: 'Продавец', channel: 'in_app', status: 'failed', reason: 'report_failed' },
      ],
    },
  }));
}

for (const theme of ['light', 'dark'] as const) {
  test.describe(`report mailings ${theme}`, () => {
    let errors: string[];
    test.beforeEach(async ({ page }) => {
      errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
      await routes(page);
    });
    test.afterEach(() => expect(errors).toEqual([]));

    test('the mailings of a report on a phone and a wide screen', async ({ page }) => {
      await page.setViewportSize({ width: 360, height: 740 });
      await page.goto('/reports/7/mailings');
      const card = page.getByTestId('report-mailing');
      await expect(card).toContainText('Итоги прошлого месяца');
      await audit(page, '[data-testid="report-mailings"]');
      await page.setViewportSize({ width: 1440, height: 1000 });
      await audit(page, '[data-testid="report-mailings"]');
    });

    test('the dialog of a mailing opens by keyboard with its parameters and condition', async ({ page }) => {
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.goto('/reports/7/mailings');
      const edit = page.getByTestId('report-mailing-edit');
      await edit.focus();
      await page.keyboard.press('Enter');
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByTestId('report-mailing-form')).toBeVisible();
      await expect(dialog.getByTestId('report-parameters')).toBeVisible();
      await audit(page, '[role="dialog"]');
      await page.setViewportSize({ width: 360, height: 740 });
      await audit(page, '[role="dialog"]');
    });

    test('the run history tells each delivery in words', async ({ page }) => {
      await page.setViewportSize({ width: 360, height: 740 });
      await page.goto('/reports/7/mailings');
      await page.getByTestId('report-mailing-history').click();
      const history = page.getByTestId('report-mailing-runs');
      const open = history.getByTestId('report-mailing-run-open');
      await open.focus();
      await page.keyboard.press('Enter');
      await expect(history.getByTestId('report-mailing-delivery')).toHaveCount(4);
      await audit(page, '[role="dialog"]');
    });
  });
}
