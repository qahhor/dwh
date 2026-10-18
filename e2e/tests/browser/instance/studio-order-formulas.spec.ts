import { expect, test, type BrowserContext, type Locator, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';
import { fieldName } from '../../../support/fields.js';
import { chooseLiveFormDelivery } from '../../../support/live-form-studio.js';
import { chooseOption } from '../../../support/select.js';
import { xlsxRows } from '../../../support/xlsx-read.js';

// ADR-0042, stage 2, step 1 of the form builder: the order header «Заказ (шапка)» built by an administrator without a
// programmer — a total computed from the quantity, the price and the discount, today's date by default, a discount
// reason shown and required only above 10 %, a rule that keeps the discount at 30 % or less. The record form shows it
// live; the list and the XLSX hold the stored total; a write that bypasses the browser is checked the same way.

type Answer = { status: number; body: unknown };

const TOTAL = 'round(qty * price * (1 - coalesce(discount, 0) / 100), 2)';

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
    return { status: response.status(), body: text ? JSON.parse(text) as unknown : undefined };
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

/** Fills the three labels of a group of the editor with one name. */
async function name(group: Locator, text: string): Promise<void> {
  await group.getByRole('textbox', { name: fieldName('Русский') }).first().fill(text);
  await group.getByRole('textbox', { name: fieldName('Узбекский') }).first().fill(text);
  await group.getByRole('textbox', { name: fieldName('Английский') }).first().fill(text);
}

async function addField(page: Page, index: number, id: string, type: string, label: string): Promise<Locator> {
  await page.getByTestId('new-form-add').click();
  const box = page.getByTestId(`new-field-${index}`);
  await box.getByRole('textbox', { name: fieldName('Идентификатор поля') }).fill(id);
  await choose(page, box.getByRole('combobox', { name: 'Тип поля' }), type);
  await name(box, label);
  return box;
}

/** Types a formula into a formula input of the editor, found by its test id. */
async function formula(scope: Locator, testId: string, text: string): Promise<void> {
  await scope.getByTestId(testId).locator('textarea').fill(text);
}

/** Waits until the queued export is written and answers the rows of its file. */
async function exportRows(context: BrowserContext, list: string, columns: string[]): Promise<string[][]> {
  const queued = await expectCall(context, 'POST', '/api/v1/exports', 202, { list, columns, lang: 'ru' });
  const id = (queued.body as { id: string }).id;
  await expect.poll(async () => {
    const journal = (await expectCall(context, 'GET', '/api/v1/exports', 200)).body as { id: string; state: string }[];
    return journal.find(item => item.id === id)?.state;
  }, { timeout: 60_000 }).toBe('done');
  const file = await context.request.get(`/api/v1/exports/${id}/file`);
  try {
    expect(file.status()).toBe(200);
    return xlsxRows(await file.body());
  } finally { await file.dispose(); }
}

/** The order header in the editor, its formulas checked inline by the server; the code of the activated form. */
async function buildOrder(page: Page, formName: string): Promise<string> {
  await page.goto('/settings/form-studio/new');
  await chooseLiveFormDelivery(page);
  const entity = page.getByRole('group', { name: 'Новая форма', exact: true });
  await entity.getByTestId('new-entity-name').fill(formName);
  await name(entity, 'Заказ (шапка)');

  const qty = await addField(page, 0, 'qty', 'Число', 'Количество');
  await qty.getByText('Обязательное', { exact: true }).click();
  const price = await addField(page, 1, 'price', 'Число', 'Цена');
  await price.getByText('Обязательное', { exact: true }).click();
  await addField(page, 2, 'discount', 'Число', 'Скидка, %');

  const total = await addField(page, 3, 'total', 'Число', 'Сумма');
  await choose(page, total.getByTestId('field-mode-3').getByRole('combobox'), 'Вычисляется по формуле');
  // A formula with an error shows the server's reason and position under it; then the right one is typed.
  await formula(total, 'field-formula-3', 'round(qty * price * (1 - discount / 100), 2');
  await expect(total).toContainText('Ошибка в записи формулы (позиция 44)', { timeout: 15_000 });
  await formula(total, 'field-formula-3', TOTAL);
  await expect(total).not.toContainText('Ошибка в записи формулы', { timeout: 15_000 });

  const date = await addField(page, 4, 'orderdate', 'Дата', 'Дата заказа');
  await formula(date, 'field-default-formula-4', 'today()');
  const reason = await addField(page, 5, 'reason', 'Текст', 'Причина скидки');
  await formula(reason, 'field-visible-5', 'discount > 10');
  await formula(reason, 'field-required-5', 'discount > 10');

  const rules = page.getByTestId('form-rules');
  await rules.getByTestId('rule-add').click();
  const rule = rules.getByTestId('rule-0');
  await rule.getByTestId('rule-key-0').locator('input').fill('maxdiscount');
  await formula(rule, 'rule-formula-0', 'coalesce(discount, 0) <= 30');
  await rule.getByTestId('rule-message-ru-0').locator('input').fill('Скидка не больше 30%');
  await rule.getByTestId('rule-message-uz-0').locator('input').fill('Chegirma 30% dan oshmaydi');
  await rule.getByTestId('rule-message-en-0').locator('input').fill('The discount is at most 30%');
  await choose(page, rule.getByTestId('rule-field-0').getByRole('combobox'), /\(discount\)/u);

  // A document is named by its number: no system name and code to fill.
  await chooseOption(page.getByTestId('form-layout').getByTestId('form-title-field').getByRole('combobox'), 'recordNumber');

  const prepared = page.waitForResponse(response => response.url().endsWith('/api/v1/form-studio/new-forms/prepare'));
  await page.getByTestId('new-form-prepare').click();
  expect((await prepared).status()).toBe(200);
  await expect(page.locator('.new-form-checksum')).toHaveText(/^[a-f0-9]{64}$/u);
  const activation = page.waitForResponse(response => response.request().method() === 'POST'
    && response.url().endsWith('/api/v1/studio/forms'));
  await page.getByTestId('new-form-activate').click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Активировать форму', exact: true }).click();
  expect((await activation).status()).toBe(201);
  await expect(page.getByTestId('new-form-activated')).toContainText(`studio.${formName}`);
  const stored = (await expectCall(page.context(), 'GET', `/api/v1/studio/forms/studio.${formName}`, 200)).body as {
    declaration: { formatVersion: number; rules?: unknown[]; fields: { id: string; formula?: string }[] };
  };
  expect(stored.declaration.formatVersion).toBe(5);
  expect(stored.declaration.rules).toHaveLength(1);
  expect(stored.declaration.fields.find(field => field.id === 'total')?.formula).toBe(TOTAL);
  return `studio.${formName}`;
}

test('the order header: a computed total, a default date, a conditional reason and a rule, enforced by the server', async ({ page }) => {
  test.setTimeout(300_000);
  await loginToInstance(page);
  const admin = page.context();
  const assertNoPageErrors = collectPageErrors(page);
  const tag = Date.now().toString(36);
  const modules = (await expectCall(admin, 'GET', '/api/v1/modules', 200)).body as { code: string; status: string }[];
  const wasEnabled = modules.find(module => module.code === 'studio')?.status === 'ACTIVE';
  await expectCall(admin, 'PUT', '/api/v1/modules/studio/enabled', 200, { enabled: true });
  try {
    const orders = await buildOrder(page, `orders_${tag}`);

    // The record form: today's date by default, the total live, the reason only above 10 % and then required.
    await page.goto(`/e/${orders}/new`);
    const form = page.locator('smt-entity-form');
    const field = (key: string) => form.locator(`.entity-field[data-field="${key}"]`);
    const today = await page.evaluate(() => {
      const now = new Date();
      const pad = (n: number) => String(n).padStart(2, '0');
      return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
    });
    await field('qty').locator('input').fill('3');
    await field('price').locator('input').fill('19.99');
    await field('discount').locator('input').fill('5');
    await expect(field('total').locator('input')).toHaveValue('56.97');
    await expect(field('reason')).toHaveCount(0);
    await field('discount').locator('input').fill('15');
    await expect(field('total').locator('input')).toHaveValue('50.97');
    await expect(field('reason')).toBeVisible();
    const submit = page.locator('smt-entity-edit-page').getByTestId('form-submit');
    await submit.click();
    await expect(field('reason')).toContainText('Заполните поле');
    await field('reason').locator('input').fill('Оптовый клиент');
    await field('discount').locator('input').fill('40');
    await submit.click();
    await expect(field('discount')).toContainText('Скидка не больше 30%');
    await field('discount').locator('input').fill('15');
    const created = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === `/api/v1/entities/${orders}`);
    await submit.click();
    const answer = await created;
    expect(answer.status()).toBe(201);
    const record = await answer.json() as { id: number; total: number | string; orderdate: string; reason: string };
    expect(Number(record.total)).toBe(50.97);
    expect(record.orderdate).toBe(today);
    expect(record.reason).toBe('Оптовый клиент');

    // The list and the XLSX show the stored total.
    await page.goto(`/e/${orders}`);
    await expect(page.locator('main')).toContainText(/50[.,]97/u);
    const rows = await exportRows(admin, orders, ['qty', 'price', 'discount', 'total']);
    expect(rows.flat().some(cell => /^50[.,]97$/u.test(String(cell)))).toBe(true);

    // A write that bypasses the browser: the rule holds, the total is recomputed and never taken from the body.
    const refused = await expectCall(admin, 'POST', `/api/v1/entities/${orders}`, 422, {
      qty: 1, price: '10', discount: '40', reason: 'x',
    });
    const errors = (refused.body as { errors: { field: string; code: string; message: string }[] }).errors;
    expect(errors).toContainEqual(expect.objectContaining({ field: 'discount', code: 'rule_failed' }));
    const direct = (await expectCall(admin, 'POST', `/api/v1/entities/${orders}`, 201, {
      qty: 3, price: '19.99', discount: '0', total: '999',
    })).body as { id: number; total: number; orderdate: string; revision: number };
    expect(Number(direct.total)).toBe(59.97);
    expect(direct.orderdate).toMatch(/^\d{4}-\d{2}-\d{2}$/u);
    const missing = await expectCall(admin, 'POST', `/api/v1/entities/${orders}`, 422, { qty: 1, price: '10', discount: '20' });
    expect((missing.body as { errors: { field: string; code: string }[] }).errors)
      .toContainEqual(expect.objectContaining({ field: 'reason', code: 'required' }));
    const changed = (await expectCall(admin, 'PATCH', `/api/v1/entities/${orders}/${direct.id}`, 200,
      { price: '20', total: '1' }, `"${direct.revision}"`)).body as { total: number };
    expect(Number(changed.total)).toBe(60);
    assertNoPageErrors();
  } finally {
    await call(admin, 'PUT', '/api/v1/modules/studio/enabled', { enabled: wasEnabled });
  }
});
