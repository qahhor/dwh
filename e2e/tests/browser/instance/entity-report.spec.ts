import { expect, test, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors, uniqueRunName } from '../../../support/diagnostics.js';
import { chooseOption } from '../../../support/select.js';

/*
 * A report without code (ADR-0032 10.2; plan 10/10, item 5.8, acceptance "a report or a widget without Java code"):
 * on the general list of the reference orders a person groups the orders by status, sums their totals, sees the bars
 * and the table, saves the report as a widget and finds it on the analytics dashboard. Nothing of it is code of the
 * orders: the groupings and measures come from the list's metadata, the report is a saved view of the list. The module
 * ships switched off (ADR-0032 19, question 3): the test switches it on and off again.
 */

async function csrfHeaders(page: Page): Promise<Record<string, string>> {
  const cookie = (await page.context().cookies(page.url())).find(value => value.name === 'XSRF-TOKEN');
  if (!cookie?.value) throw new Error('The signed-in page has no CSRF cookie');
  return { 'X-XSRF-TOKEN': cookie.value };
}

async function switchModule(page: Page, enabled: boolean): Promise<void> {
  const response = await page.request.put('/api/v1/modules/example/enabled', {
    headers: await csrfHeaders(page),
    data: { enabled },
  });
  try {
    expect(response.status(), `switching the module example ${enabled ? 'on' : 'off'}`).toBe(200);
  } finally {
    await response.dispose();
  }
}

async function createOrder(page: Page, customer: string, price: string): Promise<void> {
  const response = await page.request.post('/api/v1/entities/example.orders', {
    headers: await csrfHeaders(page),
    data: { customer, lines: [{ product: 'Мука пшеничная, 50 кг', qty: '2', price }] },
  });
  try {
    expect(response.status(), 'creating an order').toBe(201);
  } finally {
    await response.dispose();
  }
}

test('a report of the orders is built, saved as a widget and shown on the dashboard without code', async ({ page }) => {
  const customer = uniqueRunName('E2E report customer');
  const name = uniqueRunName('E2E orders by status');
  let savedId: number | null = null;

  await loginToInstance(page);
  await switchModule(page, true);
  try {
    await createOrder(page, customer, '10.00');
    await createOrder(page, customer, '5.50');
    const assertNoPageErrors = collectPageErrors(page);

    await page.goto('/e/example.orders');
    await expect(page.getByRole('heading', { level: 1, name: 'Заказы (эталон)' })).toBeVisible();

    // The report tab: the whole list counted at once, as one figure.
    const counted = page.waitForResponse(response => /\/api\/v1\/entities\/example\.orders\/report\?/u.test(response.url()));
    await page.getByTestId('entity-mode').getByRole('radio', { name: 'Отчёт' }).click();
    expect((await counted).status()).toBe(200);
    await expect(page.getByTestId('report-kpis')).toBeVisible();

    // Group by status and sum the totals: the server builds the query from the list's declaration.
    const grouped = page.waitForResponse(response =>
      /\/api\/v1\/entities\/example\.orders\/report\?/u.test(response.url())
      && decodeURIComponent(response.url()).includes('"field":"status"')
      && decodeURIComponent(response.url()).includes('"op":"sum"'));
    await chooseOption(page.getByLabel('Группировать по'), 'status');
    await chooseOption(page.getByLabel('Показатель', { exact: true }), 'sum');
    expect((await grouped).status()).toBe(200);

    await expect(page.getByTestId('report-chart')).toBeVisible();
    const table = page.getByRole('table', { name: 'Отчёт: Заказы (эталон)', exact: true });
    await expect(table.getByRole('columnheader', { name: 'Статус' })).toBeVisible();
    await expect(table.getByRole('columnheader', { name: 'Валюта' })).toBeVisible();
    await expect(table.getByRole('rowheader', { name: 'Черновик' }).first()).toBeVisible();
    // Money is summed per currency: the platform adds the currency to the grouping.
    await expect(table.getByRole('row').filter({ hasText: 'Черновик' }).filter({ hasText: 'UZS' }).first()).toBeVisible();

    // Save it as a widget of the dashboard: a saved view of the list, of the kind "widget".
    await page.getByTestId('report-save-as').click();
    const dialog = page.getByRole('dialog');
    await dialog.getByTestId('report-name').fill(name);
    await dialog.getByTestId('report-widget').getByRole('checkbox').click();
    const saved = page.waitForResponse(response =>
      response.request().method() === 'POST' && /\/api\/v1\/list-views\/example\.orders$/u.test(response.url()));
    await dialog.getByTestId('form-submit').click();
    const savedResponse = await saved;
    expect(savedResponse.status()).toBe(201);
    const view = await savedResponse.json() as { id: number; kind: string; state: { chart: string } };
    savedId = view.id;
    expect(view.kind).toBe('widget');
    expect(view.state.chart).toBe('bar');

    // The dashboard shows the viewer's widget, run under the orders' own rights.
    await page.goto('/analytics');
    const widget = page.getByTestId('analytics-widget').filter({ hasText: name });
    await expect(widget.getByRole('heading', { name })).toBeVisible();
    await expect(widget.getByTestId('bar-chart-bar').first()).toBeVisible();

    assertNoPageErrors();
  } finally {
    if (savedId !== null) {
      const removed = await page.request.delete(`/api/v1/list-views/example.orders/${savedId}`, {
        headers: await csrfHeaders(page),
      });
      await removed.dispose();
    }
    await switchModule(page, false);
  }
});
