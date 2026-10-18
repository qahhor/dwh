import { randomBytes } from 'node:crypto';

import { expect, test, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';
import { fieldName } from '../../../support/fields.js';
import { chooseLiveFormDelivery } from '../../../support/live-form-studio.js';
import { clearSecret, fillSecret } from '../../../support/secret.js';
import { inviteUser } from '../../../support/users.js';
import { xlsxSheet } from '../../../support/xlsx-read.js';

// ADR-0043, stage 2, step 2 of the form builder: the document «Заказ со строками и согласованием» built by an
// administrator without a programmer — a client of another live form, a number ЗК-<year>-<6 digits>, lines of a
// product from a reference list with a quantity, a price and a computed sum, a header total of the line sums, and the
// statuses draft, pending, approved and rejected. A seller fills and submits the order, a manager is told and approves
// it with a comment, the author never approves, an approved order is only read, the history keeps the transitions with
// their comments, the list filters by status and the XLSX holds the lines. No intercepted API responses.

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

/** Chooses an option of an smt-select by its visible name. */
async function choose(page: Page, trigger: Locator, option: string | RegExp): Promise<void> {
  await trigger.click();
  const listbox = page.locator('.cdk-overlay-container [role="listbox"]').last();
  await listbox.getByRole('option', typeof option === 'string' ? { name: option, exact: true } : { name: option }).first().click();
  await expect(listbox).toBeHidden();
}

/** Picks a row of a paged reference (smt-data-select) by typing a part of its name. */
async function pick(page: Page, field: Locator, search: string, option: string | RegExp): Promise<void> {
  const trigger = field.getByRole('combobox').first();
  await trigger.focus();
  await page.keyboard.press('Enter');
  const input = page.locator('.cdk-overlay-container input[role="combobox"][aria-autocomplete="list"]').last();
  await expect(input).toBeFocused();
  await input.fill(search);
  const listbox = page.locator('.cdk-overlay-container [role="listbox"]').last();
  await listbox.getByRole('option', { name: option }).first().click();
  await expect(trigger).not.toContainText('Не выбрано');
}

/** Fills the three labels of a group of the editor with one name. */
async function name(group: Locator, text: string): Promise<void> {
  await group.getByRole('textbox', { name: fieldName('Русский') }).first().fill(text);
  await group.getByRole('textbox', { name: fieldName('Узбекский') }).first().fill(text);
  await group.getByRole('textbox', { name: fieldName('Английский') }).first().fill(text);
}

/** A field or a column box of the editor: its key, type and labels. */
async function describe(page: Page, box: Locator, id: string, type: string, label: string): Promise<void> {
  await box.getByRole('textbox', { name: fieldName('Идентификатор поля') }).fill(id);
  await choose(page, box.getByRole('combobox', { name: 'Тип поля' }), type);
  await name(box, label);
}

/** A live form of clients other forms refer to, activated through the API: not the subject of this test. */
async function activateClients(context: BrowserContext, formName: string): Promise<string> {
  const labels = (text: string) => ({ ru: text, uz: text, en: text });
  const prepared = await expectCall(context, 'POST', '/api/v1/form-studio/new-forms/prepare', 200, {
    formatVersion: 3,
    kind: 'simple-form',
    module: { code: 'studio', title: labels('Созданные формы') },
    entity: { name: formName, title: labels('Клиенты'), scope: 'all' },
    fields: [{ id: 'phone', type: 'text', labels: labels('Телефон'), required: false, listed: true, maxLength: 30 }],
  });
  const activated = await expectCall(context, 'POST', '/api/v1/studio/forms', 201, prepared.body);
  return (activated.body as { entityCode: string }).entityCode;
}

/** A reference list of goods kept by the administrator. */
async function createGoods(context: BrowserContext, code: string): Promise<void> {
  await expectCall(context, 'POST', '/api/v1/studio/lists', 201, { code, name: { ru: 'Товары', uz: 'Tovarlar', en: 'Goods' } });
  let order = 0;
  for (const [item, title] of [['tea', 'Чай'], ['coffee', 'Кофе']]) {
    await expectCall(context, 'POST', `/api/v1/studio/lists/${code}/items`, 201, {
      code: item, name: { ru: title, uz: title, en: title }, sortOrder: ++order,
    });
  }
}

/** The order in the editor: a client, a computed total, lines, a number and statuses; the code of the activated form. */
async function buildOrder(page: Page, formName: string, clients: string, goods: string): Promise<string> {
  await page.goto('/settings/form-studio/new');
  await chooseLiveFormDelivery(page);
  const entity = page.getByRole('group', { name: 'Новая форма', exact: true });
  await entity.getByTestId('new-entity-name').fill(formName);
  await name(entity, 'Заказ');

  await page.getByTestId('new-form-add').click();
  const client = page.getByTestId('new-field-0');
  await describe(page, client, 'client', 'Ссылка на справочник или форму', 'Клиент');
  await choose(page, client.getByTestId('field-target-kind-0').getByRole('combobox'), 'Другая форма');
  await choose(page, client.getByTestId('field-target-0').getByRole('combobox'), new RegExp(clients.replace('.', '\\.'), 'u'));
  await client.getByText('Обязательное', { exact: true }).click();
  await page.getByTestId('new-form-add').click();
  const total = page.getByTestId('new-field-1');
  await describe(page, total, 'total', 'Число', 'Итого');

  // The lines: a product from the reference list, a quantity, a price and a sum computed in the row.
  const lines = page.getByTestId('form-lines');
  await lines.getByTestId('lines-add').click();
  const table = lines.getByTestId('lines-table-0');
  await table.getByTestId('lines-key-0').locator('input').fill('items');
  await name(table, 'Строки заказа');
  const product = table.getByTestId('new-field-0');
  await describe(page, product, 'product', 'Ссылка на справочник или форму', 'Товар');
  await choose(page, product.getByTestId('field-target-0').getByRole('combobox'), new RegExp(`\\(${goods}\\)`, 'u'));
  for (const [index, id, label] of [[1, 'qty', 'Количество'], [2, 'price', 'Цена'], [3, 'amount', 'Сумма']] as const) {
    await table.getByTestId('lines-add-column-0').click();
    await describe(page, table.getByTestId(`new-field-${index}`), id, 'Число', label);
  }
  await table.getByTestId('new-field-1').getByText('Обязательное', { exact: true }).click();
  await table.getByTestId('new-field-2').getByText('Обязательное', { exact: true }).click();
  await table.getByTestId('line-computed-3').click();
  await table.getByTestId('line-formula-3').locator('textarea').fill('round(qty * price, 2)');
  await expect(table.getByTestId('new-field-3')).not.toContainText('Ошибка в записи формулы', { timeout: 15_000 });

  // The header total sums the lines; the editor checks the formula on the server.
  await choose(page, total.getByTestId('field-mode-1').getByRole('combobox'), 'Вычисляется по формуле');
  await total.getByTestId('field-formula-1').locator('textarea').fill('sum(lines.items.amount)');
  await expect(total).not.toContainText('Нет поля', { timeout: 15_000 });

  const numbering = page.getByTestId('form-numbering');
  await numbering.getByTestId('numbering-on').click();
  await numbering.getByTestId('numbering-pattern').locator('input').fill('ЗК-{YYYY}-{NNNNNN}');

  const workflow = page.getByTestId('form-workflow');
  await workflow.getByTestId('workflow-on').click();
  await expect(workflow.getByTestId('workflow-state-3')).toBeVisible();
  await workflow.getByTestId('workflow-transition-condition-0').locator('textarea').fill('total > 0');
  await expect(workflow.getByTestId('workflow-transition-0')).not.toContainText('позиция', { timeout: 15_000 });

  const prepared = page.waitForResponse(response => response.url().endsWith('/api/v1/form-studio/new-forms/prepare'));
  await page.getByTestId('new-form-prepare').click();
  const preparedAnswer = await prepared;
  expect(preparedAnswer.status(), await preparedAnswer.text()).toBe(200);
  await expect(page.locator('.new-form-checksum')).toHaveText(/^[a-f0-9]{64}$/u);
  const activation = page.waitForResponse(response => response.request().method() === 'POST'
    && response.url().endsWith('/api/v1/studio/forms'));
  await page.getByTestId('new-form-activate').click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Активировать форму', exact: true }).click();
  expect((await activation).status()).toBe(201);
  await expect(page.getByTestId('new-form-activated')).toContainText(`studio.${formName}`);
  const stored = (await expectCall(page.context(), 'GET', `/api/v1/studio/forms/studio.${formName}`, 200)).body as {
    declaration: { formatVersion: number; lines?: unknown[]; workflow?: { transitions: unknown[] } };
  };
  expect(stored.declaration.formatVersion).toBe(6);
  expect(stored.declaration.lines).toHaveLength(1);
  expect(stored.declaration.workflow?.transitions).toHaveLength(3);
  return `studio.${formName}`;
}

/** A role holding `rights` of the order and the clients. */
async function role(admin: BrowserContext, title: string, rights: { formCode: string; action: string }[]): Promise<number> {
  const created = (await expectCall(admin, 'POST', '/api/v1/iam/roles', 201, { name: title, orderNo: 9200 })).body as { id: number; revision?: number };
  await expectCall(admin, 'PUT', `/api/v1/iam/roles/${created.id}/permissions`, 204, [
    { formCode: 'md.profile', action: 'view' }, ...rights,
  ], `"${created.revision ?? 1}"`);
  return created.id;
}

/** An invited user holding `roleId`, signed in in a browser context of its own. */
async function signedIn(browser: Browser, admin: BrowserContext, roleId: number, who: string) {
  const login = `order-${who}-${randomBytes(4).toString('hex')}`;
  const password = `Qa!7${randomBytes(8).toString('hex')}`;
  const user = await inviteUser(admin, { name: `Пользователь ${who}`, login, email: `${login}@example.invalid` }, password, [roleId]);
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/login');
  await page.getByLabel('Логин или Email').fill(login);
  const secret = page.getByLabel(fieldName('Пароль'));
  try {
    await fillSecret(secret, password);
    await page.getByRole('button', { name: 'Войти в систему', exact: true }).click();
    await expect(page).not.toHaveURL(/\/login/u);
  } finally { await clearSecret(secret); }
  return { context, page, user };
}

/** Waits until the queued export is written and answers the rows of each of its sheets. */
async function exportSheets(context: BrowserContext, list: string, columns: string[]): Promise<string[][][]> {
  const queued = await expectCall(context, 'POST', '/api/v1/exports', 202, { list, columns, lang: 'ru' });
  const id = (queued.body as { id: string }).id;
  await expect.poll(async () => {
    const journal = (await expectCall(context, 'GET', '/api/v1/exports', 200)).body as { id: string; state: string }[];
    return journal.find(item => item.id === id)?.state;
  }, { timeout: 60_000 }).toBe('done');
  const file = await context.request.get(`/api/v1/exports/${id}/file`);
  try {
    expect(file.status()).toBe(200);
    const body = await file.body();
    return [xlsxSheet(body, 1), xlsxSheet(body, 2)];
  } finally { await file.dispose(); }
}

test('the order with lines and approval: numbered, computed, approved by a manager and then only read', async ({ page, browser }) => {
  test.setTimeout(420_000);
  await loginToInstance(page);
  const admin = page.context();
  const assertNoPageErrors = collectPageErrors(page);
  const tag = Date.now().toString(36);
  const modules = (await expectCall(admin, 'GET', '/api/v1/modules', 200)).body as { code: string; status: string }[];
  const wasEnabled = modules.find(module => module.code === 'studio')?.status === 'ACTIVE';
  await expectCall(admin, 'PUT', '/api/v1/modules/studio/enabled', 200, { enabled: true });
  const contexts: BrowserContext[] = [];
  try {
    const goods = `goods_${tag}`;
    await createGoods(admin, goods);
    const clients = await activateClients(admin, `buyers_${tag}`);
    const orders = await buildOrder(page, `orders_${tag}`, clients, goods);
    const buyer = (await expectCall(admin, 'POST', `/api/v1/entities/${clients}`, 201, { name: `Клиент ${tag}`, code: `c-${tag}` }))
      .body as { id: number };

    const sellers = await role(admin, `Продавцы ${tag}`, [
      { formCode: clients, action: 'view' },
      { formCode: orders, action: 'view' },
      { formCode: orders, action: 'create' },
      { formCode: orders, action: 'update' },
      { formCode: orders, action: 'submit' },
    ]);
    const managers = await role(admin, `Менеджеры ${tag}`, [
      { formCode: clients, action: 'view' },
      { formCode: orders, action: 'view' },
      { formCode: orders, action: 'approve' },
      { formCode: orders, action: 'reject' },
    ]);
    const seller = await signedIn(browser, admin, sellers, `s${tag}`);
    const manager = await signedIn(browser, admin, managers, `m${tag}`);
    contexts.push(seller.context, manager.context);

    // The seller fills the order: the sums of the lines and the total follow the typing.
    await seller.page.goto(`/e/${orders}/new`);
    const form = seller.page.locator('smt-entity-form');
    await pick(seller.page, form.locator('.entity-field[data-field="client"]'), tag, new RegExp(`Клиент ${tag}`, 'u'));
    const rows = seller.page.locator('smt-entity-lines[data-collection="items"]');
    for (const [productName, qty, price] of [['Чай', '2', '10.50'], ['Кофе', '1', '30']] as const) {
      await rows.getByTestId('entity-line-add').click();
      const row = rows.locator('[data-line]').last();
      await choose(seller.page, row.locator('[data-field="product"]').getByRole('combobox'), productName);
      await row.locator('[data-field="qty"] input').fill(qty);
      await row.locator('[data-field="price"] input').fill(price);
    }
    await expect(rows.locator('[data-line="0"] [data-field="amount"]')).toContainText('21.00');
    await expect(form.locator('.entity-field[data-field="total"] input')).toHaveValue('51.00');
    const created = seller.page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === `/api/v1/entities/${orders}`);
    await seller.page.locator('smt-entity-edit-page').getByTestId('form-submit').click();
    const answer = await created;
    expect(answer.status(), await answer.text()).toBe(201);
    const order = await answer.json() as { id: number; docNumber: string; total: number | string; revision: number };
    const year = new Date().getFullYear();
    expect(order.docNumber).toMatch(new RegExp(`^ЗК-${year}-\\d{6}$`, 'u'));
    expect(Number(order.total)).toBe(51);

    // The seller submits it; a transition the seller holds no right of is refused by the server.
    await seller.page.goto(`/e/${orders}/${order.id}`);
    await expect(seller.page.locator('h1')).toContainText(order.docNumber);
    await expect(seller.page.getByTestId('entity-state')).toContainText('Черновик');
    await seller.page.locator('[data-action="submit"]').click();
    await expect(seller.page.getByTestId('entity-state')).toContainText('На согласовании');
    await expect(seller.page.locator('[data-action="approve"]')).toHaveCount(0);
    await expectCall(seller.context, 'POST', `/api/v1/entities/${orders}/${order.id}/actions/approve`, 403, {}, '"2"');

    // The manager is told, approves with a comment and the order is only read.
    await expect.poll(async () => {
      const inbox = (await expectCall(manager.context, 'GET', '/api/v1/notifications/inbox?limit=20', 200)).body as {
        items: { title: string; formLink?: string }[];
      };
      return inbox.items.some(item => item.title.includes(order.docNumber) && item.formLink === `/e/${orders}/${order.id}`);
    }, { timeout: 30_000 }).toBe(true);
    await manager.page.goto(`/e/${orders}/${order.id}`);
    await manager.page.locator('[data-action="approve"]').click();
    const comment = manager.page.locator('[data-testid="entity-transition-form"] textarea');
    await comment.fill('Цены согласованы');
    await manager.page.getByTestId('entity-transition-actions').getByTestId('form-submit').click();
    await expect(manager.page.getByTestId('entity-state')).toContainText('Согласовано');
    await expect(manager.page.getByTestId('entity-edit')).toHaveCount(0);
    await manager.page.getByRole('tab', { name: 'История' }).click();
    const history = manager.page.locator('[data-testid="record-history-list"]');
    await expect(history).toContainText('Цены согласованы');
    await expect(history).toContainText('Согласовано');

    // An approved order is refused to every change, through the browser or not; a status filter finds it.
    await expectCall(manager.context, 'PATCH', `/api/v1/entities/${orders}/${order.id}`, 403, { total: '1' }, '"3"');
    const refused = await expectCall(admin, 'PATCH', `/api/v1/entities/${orders}/${order.id}`, 422, {
      items: [{ product: 'tea', qty: '9', price: '1' }],
    }, '"3"');
    expect(JSON.stringify(refused.body)).toContain('readonly');
    const filtered = (await expectCall(admin, 'GET',
      `/api/v1/entities/${orders}?filter=${encodeURIComponent('[{"field":"docStatus","op":"eq","value":"approved"}]')}`, 200)).body as {
      items: { id: number }[];
    };
    expect(filtered.items.map(item => item.id)).toContain(order.id);

    // The author never approves the own order, even holding the right.
    const own = (await expectCall(admin, 'POST', `/api/v1/entities/${orders}`, 201, {
      client: buyer.id, items: [{ product: 'tea', qty: '1', price: '5' }],
    })).body as { id: number };
    await expectCall(admin, 'POST', `/api/v1/entities/${orders}/${own.id}/actions/submit`, 200, {}, '"1"');
    const self = await expectCall(admin, 'POST', `/api/v1/entities/${orders}/${own.id}/actions/approve`, 422, {}, '"2"');
    expect(JSON.stringify(self.body)).toContain('transition_author');

    // The XLSX holds the order and, on a sheet of its own, its lines with the names of the goods.
    const [list, lineSheet] = await exportSheets(admin, orders, ['docNumber', 'docStatus', 'total']);
    expect(list.flat()).toContain(order.docNumber);
    expect(lineSheet.some(row => row.includes(order.docNumber) && row.includes('Чай'))).toBe(true);
    assertNoPageErrors();
  } finally {
    for (const context of contexts) await context.close();
    await call(admin, 'PUT', '/api/v1/modules/studio/enabled', { enabled: wasEnabled });
  }
});
