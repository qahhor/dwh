import { randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';

import { expect, test, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';
import { fieldName } from '../../../support/fields.js';
import { clearSecret, fillSecret } from '../../../support/secret.js';
import { chooseOption } from '../../../support/select.js';
import { inviteUser } from '../../../support/users.js';
import { xlsxRows } from '../../../support/xlsx-read.js';

// ADR-0044, stage 2, step 3 of the form and report builder: the analytics of the order with lines of step 2.2 — a pivot
// «регион × месяц × сумма заказов» with totals and the share of the total, a drill-down from a cell to exactly its
// records and the XLSX of the matrix with names and totals; this month against the last one; the distinct clients of
// each region; a saved report with a required period and a region parameter opened from the catalog by two readers
// of different org units, each seeing only the orders of their unit. Fixtures come through the API, the reports
// through the screen.

test.use({ timezoneId: 'Asia/Tashkent' });

type Answer = { status: number; body: unknown; etag: string | null };

async function call(context: BrowserContext, method: string, path: string, data?: unknown, ifMatch?: string): Promise<Answer> {
  const csrf = (await context.cookies()).find(cookie => cookie.name === 'XSRF-TOKEN');
  if (!csrf) throw new Error('The signed-in context has no CSRF cookie');
  const headers: Record<string, string> = { 'X-XSRF-TOKEN': csrf.value, 'X-Time-Zone': 'Asia/Tashkent' };
  if (ifMatch) headers['If-Match'] = ifMatch;
  const response = await context.request.fetch(path, {
    method, headers, maxRedirects: 0, maxRetries: 0, ...(data === undefined ? {} : { data }),
  });
  try {
    const text = await response.text();
    return { status: response.status(), body: text ? JSON.parse(text) as unknown : undefined, etag: response.headers()['etag'] ?? null };
  } finally { await response.dispose(); }
}

async function expectCall(context: BrowserContext, method: string, path: string, expected: number, data?: unknown, ifMatch?: string) {
  const answer = await call(context, method, path, data, ifMatch);
  expect(answer.status, `${method} ${path}: ${JSON.stringify(answer.body)}`).toBe(expected);
  return answer;
}

const labels = (text: string) => ({ ru: text, uz: text, en: text });

async function list(admin: BrowserContext, code: string, title: string, items: [string, string][]): Promise<void> {
  await expectCall(admin, 'POST', '/api/v1/studio/lists', 201, { code, name: labels(title) });
  let order = 0;
  for (const [item, name] of items) {
    await expectCall(admin, 'POST', `/api/v1/studio/lists/${code}/items`, 201, { code: item, name: labels(name), sortOrder: ++order });
  }
}

async function activate(admin: BrowserContext, declaration: unknown): Promise<string> {
  const prepared = await expectCall(admin, 'POST', '/api/v1/form-studio/new-forms/prepare', 200, declaration);
  return ((await expectCall(admin, 'POST', '/api/v1/studio/forms', 201, prepared.body)).body as { entityCode: string }).entityCode;
}

function field(id: string, type: string, title: string, extra: Record<string, unknown> = {}) {
  return { id, type, labels: labels(title), required: false, listed: true, ...extra };
}

/** The order of step 2.2 with a region: a client, a date, a total of its lines; lines of a product, a quantity, a price. */
function order(name: string, clients: string, regions: string, goods: string) {
  return {
    formatVersion: 6,
    kind: 'simple-form',
    module: { code: 'studio', title: labels('Созданные формы') },
    entity: { name, title: labels('Заказы аналитики'), scope: 'org_unit' },
    fields: [
      field('region', 'ref', 'Регион', { required: true, target: { kind: 'list', code: regions } }),
      field('client', 'ref', 'Клиент', { required: true, target: { kind: 'form', code: clients } }),
      field('orderdate', 'date', 'Дата заказа', { required: true }),
      field('total', 'number', 'Сумма заказа', { scale: 2, formula: 'sum(lines.items.amount)' }),
    ],
    lines: [{
      key: 'items', labels: labels('Строки'), maxRows: 20,
      fields: [
        { id: 'product', type: 'ref', labels: labels('Товар'), required: true, target: { kind: 'list', code: goods } },
        { id: 'qty', type: 'number', labels: labels('Количество'), required: true, scale: 3 },
        { id: 'price', type: 'number', labels: labels('Цена'), required: true, scale: 2 },
        { id: 'amount', type: 'number', labels: labels('Сумма'), required: false, scale: 2, formula: 'qty * price' },
      ],
    }],
  };
}

async function fieldUser(browser: Browser, admin: BrowserContext, unit: number, roleId: number, who: string) {
  const login = `analytics-${who}-${randomBytes(4).toString('hex')}`;
  const password = `Qa!7${randomBytes(8).toString('hex')}`;
  const user = await inviteUser(admin, { name: `Продавец ${who}`, login, email: `${login}@example.invalid` }, password, [roleId]);
  const assignments = (await expectCall(admin, 'GET', `/api/v1/iam/org-units/users/${user.id}`, 200)).body as { revision: number };
  await expectCall(admin, 'PUT', `/api/v1/iam/org-units/users/${user.id}`, 204, { orgUnitIds: [unit] }, `"${assignments.revision}"`);
  const record = await expectCall(admin, 'GET', `/api/v1/entities/md.users/${user.id}`, 200);
  await expectCall(admin, 'PATCH', `/api/v1/entities/md.users/${user.id}`, 200, { orgUnitId: unit }, record.etag ?? undefined);
  const context = await browser.newContext({ timezoneId: 'Asia/Tashkent' });
  const page = await context.newPage();
  await page.goto('/login');
  await page.getByLabel('Логин или Email').fill(login);
  const secret = page.getByLabel(fieldName('Пароль'));
  try {
    await fillSecret(secret, password);
    await page.getByRole('button', { name: 'Войти в систему', exact: true }).click();
    await expect(page).not.toHaveURL(/\/login/u);
  } finally { await clearSecret(secret); }
  return { context, page };
}

/** Today and a day of the month before, in Tashkent. */
function days(): { today: string; before: string } {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Tashkent' }).format(new Date());
  const [year, month] = today.split('-').map(Number);
  const previous = month === 1 ? [year - 1, 12] : [year, month - 1];
  return { today, before: `${previous[0]}-${String(previous[1]).padStart(2, '0')}-10` };
}

async function openReport(page: Page, orders: string): Promise<void> {
  await page.goto(`/e/${orders}`);
  await page.getByTestId('entity-mode').getByRole('radio', { name: 'Отчёт' }).click();
  await expect(page.getByTestId('report-builder')).toBeVisible();
}

async function check(box: Locator): Promise<void> {
  const checkbox = box.getByRole('checkbox');
  await checkbox.click();
  await expect(checkbox).toBeChecked();
}

test('report analytics: a pivot with totals and shares, a drill-down, the XLSX, two periods, distinct clients and parameters by unit', async ({ page, browser }) => {
  test.setTimeout(360_000);
  await loginToInstance(page);
  const admin = page.context();
  const assertNoPageErrors = collectPageErrors(page);
  const tag = Date.now().toString(36);
  const modules = (await expectCall(admin, 'GET', '/api/v1/modules', 200)).body as { code: string; status: string }[];
  const wasEnabled = modules.find(module => module.code === 'studio')?.status === 'ACTIVE';
  await expectCall(admin, 'PUT', '/api/v1/modules/studio/enabled', 200, { enabled: true });
  const contexts: BrowserContext[] = [];
  try {
    const regions = `aregions_${tag}`;
    const goods = `agoods_${tag}`;
    await list(admin, regions, 'Регионы', [['north', 'Север'], ['south', 'Юг']]);
    await list(admin, goods, 'Товары', [['tea', 'Чай'], ['coffee', 'Кофе']]);
    const clients = await activate(admin, {
      formatVersion: 3, kind: 'simple-form', module: { code: 'studio', title: labels('Созданные формы') },
      entity: { name: `aclients_${tag}`, title: labels('Клиенты аналитики'), scope: 'all' },
      fields: [field('phone', 'text', 'Телефон', { maxLength: 30 })],
    });
    const orders = await activate(admin, order(`aorders_${tag}`, clients, regions, goods));
    const client = async (name: string, code: string) => ((await expectCall(admin, 'POST', `/api/v1/entities/${clients}`, 201, {
      name: `${name} ${tag}`, code: `${code}${tag}`,
    })).body as { id: number }).id;
    const [k1, k2, k3] = [await client('Альфа', 'a'), await client('Бета', 'b'), await client('Гамма', 'g')];

    const units = (await expectCall(admin, 'GET', '/api/v1/iam/org-units', 200)).body as { id: number; parentId: number | null }[];
    const parent = units.find(unit => unit.parentId == null)?.id
      ?? ((await expectCall(admin, 'POST', '/api/v1/iam/org-units', 201, { parentId: null, code: `an-root-${tag}`, name: `Компания ${tag}`, kind: 'department', orderNo: 1 })).body as { id: number }).id;
    const unitA = (await expectCall(admin, 'POST', '/api/v1/iam/org-units', 201, { parentId: parent, code: `an-a-${tag}`, name: `Филиал А ${tag}`, kind: 'branch', orderNo: 1 })).body as { id: number };
    const unitB = (await expectCall(admin, 'POST', '/api/v1/iam/org-units', 201, { parentId: parent, code: `an-b-${tag}`, name: `Филиал Б ${tag}`, kind: 'branch', orderNo: 2 })).body as { id: number };
    const role = (await expectCall(admin, 'POST', '/api/v1/iam/roles', 201, { name: `Продавцы аналитики ${tag}`, orderNo: 9300 })).body as { id: number; revision?: number };
    const granted = await expectCall(admin, 'PUT', `/api/v1/iam/roles/${role.id}/permissions`, 204, [
      { formCode: 'md.profile', action: 'view' },
      { formCode: clients, action: 'view' },
      { formCode: orders, action: 'view' },
      { formCode: orders, action: 'create' },
    ], `"${role.revision ?? 1}"`);
    await expectCall(admin, 'PUT', `/api/v1/iam/org-units/roles/${role.id}/rule`, 204, { rule: 'UNITS' }, granted.etag ?? `"${(role.revision ?? 1) + 1}"`);
    const sellerA = await fieldUser(browser, admin, unitA.id, role.id, `a${tag}`);
    const sellerB = await fieldUser(browser, admin, unitB.id, role.id, `b${tag}`);
    contexts.push(sellerA.context, sellerB.context);

    // Unit A this month: north 50 (Альфа), south 10 (Бета); the month before: north 30 (Альфа). Unit B: north 150.
    const { today, before } = days();
    const line = (product: string, qty: string, price: string) => ({ product, qty, price });
    const place = (context: BrowserContext, region: string, clientId: number, date: string, items: unknown[]) =>
      expectCall(context, 'POST', `/api/v1/entities/${orders}`, 201, { region, client: clientId, orderdate: date, items });
    await place(sellerA.context, 'north', k1, today, [line('tea', '2', '10'), line('coffee', '1', '30')]);
    await place(sellerA.context, 'south', k2, today, [line('tea', '1', '10')]);
    await place(sellerA.context, 'north', k1, before, [line('coffee', '1', '30')]);
    await place(sellerB.context, 'north', k3, today, [line('coffee', '5', '30')]);

    // The pivot «регион × месяц × сумма заказов» with the share of the total.
    await openReport(page, orders);
    await chooseOption(page.getByLabel('Группировать по'), 'region');
    await chooseOption(page.getByLabel('Затем по'), 'orderdate');
    await check(page.getByTestId('report-pivot-toggle'));
    await chooseOption(page.getByTestId('report-measure-0').getByLabel('Показатель'), 'sum');
    await chooseOption(page.getByTestId('report-measure-0').getByLabel('Поле'), 'total');
    await page.getByTestId('report-add-measure').click();
    await chooseOption(page.getByTestId('report-measure-1').getByLabel('Поле'), 'total');
    const pivoted = page.waitForResponse(response => /\/report\?/u.test(response.url())
      && decodeURIComponent(response.url()).includes('"share":"total"'));
    await chooseOption(page.getByTestId('report-measure-1').getByLabel('Доля'), 'total');
    expect((await pivoted).status()).toBe(200);
    const pivot = page.getByTestId('report-pivot');
    await expect(pivot.getByTestId('report-pivot-row')).toHaveCount(2);
    const north = pivot.getByTestId('report-pivot-row').filter({ hasText: 'Север' });
    await expect(north.getByTestId('report-pivot-row-total').first()).toContainText('230');
    await expect(north.getByTestId('report-pivot-row-total').last()).toContainText('95,83');
    await expect(pivot.getByTestId('report-pivot-grand').first()).toContainText('240');

    // The XLSX of the pivot: the matrix with the region names and the totals.
    const download = page.waitForEvent('download');
    await page.getByTestId('report-export').click();
    const sheet = xlsxRows(await readFile(await (await download).path()));
    expect(sheet.map(row => row[0])).toEqual(expect.arrayContaining(['Север', 'Юг', 'Итого']));
    expect(sheet.at(-1)?.[0]).toBe('Итого');
    expect(sheet.flat()).not.toContain('north');

    // A drill-down from the cell of the north this month opens exactly its two orders, under the reader's scope.
    await north.getByTestId('report-pivot-drill').last().click();
    await expect(page).toHaveURL(/filter=/u);
    const filter = new URL(page.url()).searchParams.get('filter') ?? '';
    const drilled = (await expectCall(admin, 'GET', `/api/v1/entities/${orders}?filter=${encodeURIComponent(filter)}`, 200)).body as { items: { total: number }[] };
    expect(drilled.items.map(item => Number(item.total)).sort((a, b) => a - b)).toEqual([50, 150]);

    // This month against the last one: 210 against 30.
    await openReport(page, orders);
    await chooseOption(page.getByTestId('report-measure-0').getByLabel('Показатель'), 'sum');
    await chooseOption(page.getByTestId('report-measure-0').getByLabel('Поле'), 'total');
    const compared = page.waitForResponse(response => /\/report\?/u.test(response.url())
      && decodeURIComponent(response.url()).includes('"field":"orderdate"'));
    await check(page.getByTestId('report-compare-toggle'));
    await chooseOption(page.getByTestId('report-compare').getByLabel('Поле даты'), 'orderdate');
    expect((await compared).status()).toBe(200);
    const kpi = page.getByTestId('report-kpis');
    await expect(kpi).toContainText('210');
    await expect(kpi.getByTestId('kpi-change')).toContainText('600');
    await expect(page.getByTestId('report-periods')).toBeVisible();

    // The distinct clients of each region and in all: north 2 (Альфа, Гамма), south 1, in all 3.
    await openReport(page, orders);
    await chooseOption(page.getByLabel('Группировать по'), 'region');
    await chooseOption(page.getByTestId('report-measure-0').getByLabel('Показатель'), 'count_distinct');
    await chooseOption(page.getByTestId('report-measure-0').getByLabel('Поле'), 'client');
    await check(page.getByTestId('report-totals-toggle'));
    const table = page.getByTestId('report-table');
    await expect(table.getByRole('row').filter({ hasText: 'Север' })).toContainText('2');
    await expect(table.getByTestId('report-total')).toContainText('3');

    // A saved report with a required period and a region parameter, shared with the sellers' role.
    await openReport(page, orders);
    await chooseOption(page.getByLabel('Группировать по'), 'region');
    await chooseOption(page.getByTestId('report-measure-0').getByLabel('Показатель'), 'sum');
    await chooseOption(page.getByTestId('report-measure-0').getByLabel('Поле'), 'total');
    await check(page.getByTestId('report-totals-toggle'));
    await page.getByTestId('report-parameter-add').click();
    await chooseOption(page.getByTestId('report-parameter-field-0').getByRole('combobox'), 'orderdate');
    await check(page.getByTestId('report-parameter-required-0'));
    await page.getByTestId('report-parameter-add').click();
    await chooseOption(page.getByTestId('report-parameter-field-1').getByRole('combobox'), 'region');
    await page.getByTestId('report-save-as').click();
    const dialog = page.getByRole('dialog');
    const reportName = `Заказы с параметрами ${tag}`;
    await dialog.getByTestId('report-name').fill(reportName);
    const saved = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === `/api/v1/list-views/${orders}`);
    await dialog.getByTestId('form-submit').click();
    expect((await saved).status()).toBe(201);
    await expect(dialog).toBeHidden();
    const catalog = (await expectCall(admin, 'GET', `/api/v1/report-views?q=${encodeURIComponent(tag)}&owner=mine`, 200)).body as {
      items: { id: number; name: string; lockVersion: number; state: { parameters?: unknown[] } }[];
    };
    const view = catalog.items.find(item => item.name === reportName)!;
    expect(view.state.parameters).toHaveLength(2);
    await expectCall(admin, 'PUT', `/api/v1/list-views/${orders}/${view.id}/sharing`, 200, { roleIds: [role.id] }, `"${view.lockVersion}"`);

    // Each seller opens it from the catalog, fills the period and sees only the orders of their unit.
    const readAs = async (seller: { page: Page }, region: string | null) => {
      await seller.page.goto('/reports');
      await seller.page.getByTestId('reports-search').fill(tag);
      await seller.page.getByTestId('reports-item').filter({ hasText: reportName }).getByTestId('reports-open').click();
      const parameters = seller.page.getByTestId('report-parameters');
      await expect(parameters.getByTestId('report-parameters-missing')).toBeVisible();
      await chooseOption(parameters.getByTestId('report-parameter-orderdate').getByRole('combobox'), 'this_month');
      if (region) {
        await parameters.getByTestId('report-parameter-region').getByRole('combobox').first().click();
        await seller.page.locator('.cdk-overlay-container [role="listbox"]').last().getByRole('option', { name: region }).click();
        await seller.page.keyboard.press('Escape');
      }
      const ran = seller.page.waitForResponse(response => new URL(response.url()).pathname === `/api/v1/entities/${orders}/reports/${view.id}`
        && response.url().includes('parameters='));
      await parameters.getByTestId('report-parameters-apply').click();
      expect((await ran).status()).toBe(200);
      return seller.page.getByTestId('report-table');
    };
    const tableA = await readAs(sellerA, null);
    await expect(tableA.getByRole('row').filter({ hasText: 'Север' })).toContainText('50');
    await expect(tableA.getByTestId('report-total')).toContainText('60');
    const tableB = await readAs(sellerB, null);
    await expect(tableB.locator('tbody').getByRole('rowheader')).toHaveText(['Север']);
    await expect(tableB.getByTestId('report-total')).toContainText('150');
    const onlyNorth = await readAs(sellerA, 'Север');
    await expect(onlyNorth.locator('tbody').getByRole('rowheader')).toHaveText(['Север']);
    await expect(onlyNorth.getByTestId('report-total')).toContainText('50');
    assertNoPageErrors();
  } finally {
    for (const context of contexts) await context.close();
    await call(admin, 'PUT', '/api/v1/modules/studio/enabled', { enabled: wasEnabled });
  }
});
