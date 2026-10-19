import { AxeBuilder } from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

// ADR-0047: solution packages and templates — the gallery, the upload, the export with its contents and the history,
// and the import page with its outcomes, mapping and actions — meet WCAG 2.1 AA in both themes, on a phone and on a
// wide screen, and are reached by keyboard.

async function audit(page: Page, include: string) {
  await page.waitForFunction(() => document.getAnimations().every(animation => animation.playState !== 'running'));
  const result = await new AxeBuilder({ page }).include(include)
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(result.violations.map(violation => `${violation.id}: ${violation.nodes.map(node => node.target)}`)).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}

const labels = (ru: string, en = ru) => ({ ru, uz: ru, en });

const TEMPLATE = {
  code: 'order-approval', version: '1.0.0', title: labels('Заказ со строками и согласованием', 'Order with lines and approval'),
  description: labels('Заказ клиента со строками товаров, номером и согласованием.'),
  forms: 2, lists: 2, reports: 1, mailings: 1, automations: 1,
};

const IMPORT = {
  id: 9, code: 'order-approval', version: '1.0.0', title: TEMPLATE.title, description: TEMPLATE.description,
  checksum: 'a'.repeat(64), source: 'template', status: 'preview', producedBy: 'template',
  placeholders: [
    { key: 'approver', kind: 'role', labels: labels('Согласующий', 'Approver'), required: false },
    { key: 'mailing-recipients', kind: 'users', labels: labels('Получатели рассылки «Заказы по регионам»'), required: true },
  ],
  mapping: {},
  items: [
    { kind: 'list', key: 'regions', title: labels('Регионы'), outcome: 'create', problems: [], notes: [] },
    { kind: 'form', key: 'studio.orders', title: labels('Заказы'), outcome: 'upgrade', problems: [], notes: [] },
    { kind: 'grant', key: 'approver', title: labels('Согласующий'), outcome: 'skip', problems: [],
      notes: ['studio.packages.note.unmapped'] },
    { kind: 'report', key: 'orders-by-region', title: labels('Заказы по регионам'), outcome: 'refused',
      problems: [{ message: 'error.studio.solution_owner' }], notes: [] },
    { kind: 'automation', key: 'order-approval', title: labels('Согласование заказа'), outcome: 'create', problems: [],
      notes: ['studio.packages.note.paused'] },
  ],
  counts: { create: 2, upgrade: 1, unchanged: 0, skip: 1, refused: 1 },
  createdAt: '2026-10-19T08:00:00Z', createdBy: 1, checkedAt: '2026-10-19T08:00:00Z',
};

const PLAN = {
  included: [
    { kind: 'list', key: 'regions', title: labels('Регионы'), reason: 'dependency', of: 'form:studio.orders' },
    { kind: 'form', key: 'studio.orders', title: labels('Заказы'), reason: 'selected' },
  ],
  related: [{ kind: 'report', key: '12', title: labels('Заказы по регионам'), reason: 'related', of: 'form:studio.orders' }],
  placeholders: [{ key: 'role-1', kind: 'role', labels: labels('Согласующий'), required: false }],
};

async function routes(page: Page) {
  await page.route('**/api/v1/modules/active', async route => {
    const modules = await (await route.fetch()).json();
    await route.fulfill({ json: [...modules, { code: 'studio', name: 'Созданные формы', status: 'ACTIVE', isActive: true, isSystem: false }] });
  });
  await page.route(url => url.pathname === '/api/v1/studio/templates', route => route.fulfill({ json: [TEMPLATE] }));
  await page.route(url => url.pathname === '/api/v1/studio/packages/imports', route => route.fulfill({
    json: { items: [{ ...IMPORT, status: 'applied', appliedAt: '2026-10-19T09:00:00Z' }], hasMore: false, nextCursor: null },
  }));
  await page.route(url => url.pathname === '/api/v1/studio/packages/imports/9', route => route.fulfill({ json: IMPORT }));
  await page.route(url => url.pathname === '/api/v1/studio/packages/export/preview', route => route.fulfill({ json: PLAN }));
  await page.route(url => url.pathname === '/api/v1/studio/forms', route => route.fulfill({
    json: { items: [{ entityCode: 'studio.orders', title: labels('Заказы'), status: 'active' }], hasMore: false },
  }));
  await page.route(url => url.pathname === '/api/v1/iam/roles', route => route.fulfill({
    json: [{ id: 4, name: 'Согласующий', state: 'A' }],
  }));
}

for (const theme of ['light', 'dark'] as const) {
  test.describe(`packages ${theme}`, () => {
    let errors: string[];
    test.beforeEach(async ({ page }) => {
      errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
      await routes(page);
    });
    test.afterEach(() => expect(errors).toEqual([]));

    test('the template gallery, the upload and the history on a phone and a wide screen', async ({ page }) => {
      await page.setViewportSize({ width: 360, height: 740 });
      await page.goto('/settings/form-studio/packages');
      await expect(page.getByTestId('package-template').first()).toContainText('Заказ со строками и согласованием');
      await audit(page, '[data-testid="packages"]');
      const tab = page.getByRole('tab', { name: 'Загрузка пакета' });
      await tab.focus();
      await page.keyboard.press('Enter');
      await expect(page.getByTestId('package-upload')).toBeVisible();
      await audit(page, '[data-testid="packages"]');
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.getByRole('tab', { name: 'История' }).click();
      await expect(page.getByTestId('package-import').first()).toBeVisible();
      await audit(page, '[data-testid="packages"]');
    });

    test('the export with its contents, by keyboard', async ({ page }) => {
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.goto('/settings/form-studio/packages?tab=export');
      await page.getByTestId('package-export-code').locator('input').fill('orders');
      await page.getByTestId('package-export-ru').locator('input').fill('Заказы');
      await page.getByTestId('package-export-uz').locator('input').fill('Buyurtmalar');
      await page.getByTestId('package-export-en').locator('input').fill('Orders');
      await page.getByTestId('package-export-form-studio.orders').click();
      const show = page.getByTestId('package-export-show');
      await show.focus();
      await page.keyboard.press('Enter');
      await expect(page.getByTestId('package-export-plan')).toContainText('Регионы');
      await audit(page, '[data-testid="packages"]');
      await page.setViewportSize({ width: 360, height: 740 });
      await audit(page, '[data-testid="packages"]');
    });

    test('the import with its outcomes and mapping', async ({ page }) => {
      await page.setViewportSize({ width: 360, height: 740 });
      await page.goto('/settings/form-studio/packages/imports/9');
      await expect(page.getByTestId('package-import-items')).toContainText('Заказы по регионам');
      await expect(page.getByTestId('package-import-mapping')).toBeVisible();
      await audit(page, '[data-testid="package-import"]');
      await page.setViewportSize({ width: 1440, height: 1000 });
      await audit(page, '[data-testid="package-import"]');
    });
  });
}
