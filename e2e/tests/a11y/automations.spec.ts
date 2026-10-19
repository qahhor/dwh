import { AxeBuilder } from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

// ADR-0046: the automations of live forms — the list, the editor with its actions and dry run, and the run log —
// meet WCAG 2.1 AA in both themes, on a phone and on a wide screen, and are reached by keyboard.

async function audit(page: Page, include: string) {
  // A dialog fades in: its colours are measured once the animations are over.
  await page.waitForFunction(() => document.getAnimations().every(animation => animation.playState !== 'running'));
  const result = await new AxeBuilder({ page }).include(include)
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(result.violations.map(violation => `${violation.id}: ${violation.nodes.map(node => node.target)}`)).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}

const labels = (text: string) => ({ ru: text, uz: text, en: text });

const AUTOMATION = {
  id: 5, form: 'studio.orders', name: 'Уведомить менеджера', active: true, formArchived: false,
  trigger: { kind: 'transition', to: ['pending'] }, condition: 'total > 0',
  actions: [
    { type: 'notify', channels: ['in_app', 'email'], recipients: { fields: ['manager'], author: true },
      title: { ru: 'Заказ {docNumber} на согласовании' }, body: { ru: 'Сумма {total}' } },
    { type: 'set_field', values: [{ field: 'review', formula: 'discount > 20' }] },
    { type: 'create_record', form: 'studio.tasks', values: [{ field: 'ordernum', formula: 'docNumber' }] },
    { type: 'transition', transition: 'approve', comment: 'Согласовано автоматически' },
  ],
  timeZone: 'Asia/Tashkent', author: { id: 1, name: 'Администратор' },
  lastRun: { id: 9, status: 'failed', reason: 'action_failed', createdAt: '2026-10-19T08:00:00Z' },
  revision: 3, createdAt: '2026-10-19T07:00:00Z', modifiedAt: '2026-10-19T07:30:00Z',
};

const SCHEDULED = {
  ...AUTOMATION, id: 6, name: 'Напоминание о согласовании',
  trigger: { kind: 'scheduled', schedule: { frequency: 'weekly', time: '09:00', weekday: 1 } },
  nextRunAt: '2026-10-26T04:00:00Z', lastRun: undefined, active: false,
};

const RUNS = [
  { id: 11, kind: 'event', trigger: 'transition', recordId: 42, depth: 1, status: 'done', attempts: 1,
    createdAt: '2026-10-19T08:00:00Z', finishedAt: '2026-10-19T08:00:01Z',
    results: [{ index: 0, type: 'notify', status: 'done', recipients: [{ id: 2, name: 'Менеджер', channels: ['in_app'] }],
      message: { title: 'Заказ ЗК-1', body: 'Сумма 15' } }] },
  { id: 12, kind: 'event', trigger: 'saved', recordId: 43, depth: 1, status: 'failed', reason: 'action_failed', attempts: 1,
    createdAt: '2026-10-19T08:05:00Z',
    error: { key: 'error.common.record_fields_invalid', params: {}, fields: [] },
    results: [{ index: 0, type: 'set_field', status: 'failed', reason: 'refused', values: { review: 'true' },
      error: { key: 'error.common.record_fields_invalid', params: {}, fields: [] } }] },
];

function field(key: string, type: string, extra: Record<string, unknown> = {}) {
  return { key, labelKey: '', label: key, type, required: false, ...extra };
}

const ORDER_META = {
  code: 'studio.orders', listCode: 'studio.orders', layout: [], actions: ['create', 'update'], capabilities: [],
  fields: [
    field('discount', 'number'), field('total', 'number', { computed: true }),
    field('manager', 'ref', { ref: { path: '/entities/md.users', labelField: 'name', keyField: 'id', paged: true } }),
    field('review', 'boolean'), field('docNumber', 'text', { readonly: true }),
  ],
  workflow: {
    field: 'docStatus',
    states: [
      { code: 'draft', labelKey: 'common.status', initial: true, terminal: false, locks: [] },
      { code: 'pending', labelKey: 'common.status', initial: false, terminal: false, locks: [] },
    ],
    transitions: [{ code: 'approve', from: ['pending'], to: 'approved', permission: 'approve' }],
  },
};

const TASK_META = { ...ORDER_META, code: 'studio.tasks', fields: [field('ordernum', 'text', { required: true })], workflow: null };

async function routes(page: Page) {
  await page.route('**/api/v1/modules/active', async route => {
    const modules = await (await route.fetch()).json();
    await route.fulfill({ json: [...modules, { code: 'studio', name: 'Созданные формы', status: 'ACTIVE', isActive: true, isSystem: false }] });
  });
  await page.route(url => url.pathname === '/api/v1/studio/forms', route => route.fulfill({
    json: { items: [{ entityCode: 'studio.orders', title: labels('Заказы') }, { entityCode: 'studio.tasks', title: labels('Задачи') }], hasMore: false },
  }));
  await page.route(url => url.pathname === '/api/v1/form-meta/studio.orders', route => route.fulfill({ json: ORDER_META }));
  await page.route(url => url.pathname === '/api/v1/form-meta/studio.tasks', route => route.fulfill({ json: TASK_META }));
  await page.route(url => url.pathname === '/api/v1/automations/options', route => route.fulfill({
    json: { channels: ['in_app', 'email'], maxPerForm: 20, maxActions: 10, maxDepth: 3, maxRunsPerRecordHour: 20 },
  }));
  await page.route(url => url.pathname === '/api/v1/automations', route => route.fulfill({ json: { items: [AUTOMATION, SCHEDULED], hasMore: false } }));
  await page.route(url => url.pathname === '/api/v1/automations/5', route => route.fulfill({ json: AUTOMATION }));
  await page.route(url => url.pathname === '/api/v1/automations/5/runs', route => route.fulfill({ json: { items: RUNS, hasMore: false } }));
  await page.route(url => url.pathname === '/api/v1/automations/formulas/check', route => route.fulfill({
    json: { valid: false, reason: 'unknown_field', position: 1, name: 'x', messageKey: 'error.formula.unknown_field' },
  }));
}

for (const theme of ['light', 'dark'] as const) {
  test.describe(`automations ${theme}`, () => {
    let errors: string[];
    test.beforeEach(async ({ page }) => {
      errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
      await routes(page);
    });
    test.afterEach(() => expect(errors).toEqual([]));

    test('the automations of a form on a phone and a wide screen', async ({ page }) => {
      await page.setViewportSize({ width: 360, height: 740 });
      await page.goto('/settings/form-studio/automations?form=studio.orders');
      await expect(page.getByTestId('automation').first()).toContainText('Уведомить менеджера');
      await audit(page, '[data-testid="automations"]');
      await page.setViewportSize({ width: 1440, height: 1000 });
      await audit(page, '[data-testid="automations"]');
    });

    test('the editor of an automation with every kind of action, by keyboard', async ({ page }) => {
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.goto('/settings/form-studio/automations');
      const edit = page.getByTestId('automation-edit').first();
      await edit.focus();
      await page.keyboard.press('Enter');
      await expect(page.getByTestId('automation-form')).toBeVisible();
      await expect(page.getByTestId('automation-action-3')).toBeVisible();
      await expect(page.getByTestId('automation-dry-run')).toBeVisible();
      await audit(page, '[data-testid="automation-editor"]');
      await page.setViewportSize({ width: 360, height: 740 });
      await audit(page, '[data-testid="automation-editor"]');
    });

    test('the run log tells each run and action in words', async ({ page }) => {
      await page.setViewportSize({ width: 360, height: 740 });
      await page.goto('/settings/form-studio/automations?form=studio.orders');
      await page.getByTestId('automation-runs').first().click();
      const log = page.getByTestId('automation-runs-dialog');
      const open = log.getByTestId('automation-run-open').last();
      await open.focus();
      await page.keyboard.press('Enter');
      await expect(log.getByTestId('automation-run-results')).toBeVisible();
      await audit(page, '[role="dialog"]');
    });
  });
}
