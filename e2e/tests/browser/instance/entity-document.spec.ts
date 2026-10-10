import { expect, test, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors, uniqueRunName } from '../../../support/diagnostics.js';
import { openEntity } from '../../../support/entity-page.js';

/*
 * The reference "document with lines and statuses" (ADR-0032 9.4; plan 10/10, item 5.7) on the general entity screen
 * /e/example.orders: no web code of the orders takes part. An order is created with three lines, saved with them in one
 * request, posted — then its customer and lines are locked — and its history shows the creation, the lines and the
 * posting. The module ships switched off (ADR-0032 19, question 3): the test switches it on and off again.
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

test('an order is created with three lines, posted and its history shows it', async ({ page }) => {
  const customer = uniqueRunName('E2E order customer');

  await loginToInstance(page);
  await switchModule(page, true);
  try {
    const assertNoPageErrors = collectPageErrors(page);

    const orders = await openEntity(page, 'example.orders', 'Заказы (эталон)');

    // Create: the form and its lines come from form-meta alone.
    await page.getByRole('link', { name: 'Создать' }).click();
    await expect(page).toHaveURL(/\/e\/example\.orders\/new$/u);
    await orders.fillField('customer', customer);
    const lines = [
      { product: 'Мука пшеничная, 50 кг', qty: '3', price: '10.00' },
      { product: 'Сахар, 25 кг', qty: '1.5', price: '4.20' },
      { product: 'Соль, 1 кг', qty: '10', price: '0.55' },
    ];
    for (const [index, line] of lines.entries()) {
      await orders.addLine(line);
      await expect(page.getByRole('group', { name: `Строка ${index + 1}` })).toBeVisible();
    }
    const createResponse = await orders.save();
    expect(createResponse.request().method()).toBe('POST');
    expect(createResponse.status()).toBe(201);
    const order = await createResponse.json() as { number: string; lines: unknown[]; total: { amount: string } };
    expect(order.lines).toHaveLength(3);
    expect(order.total.amount).toBe('41.80');
    await expect(page).toHaveURL(/\/e\/example\.orders\/\d+$/u);
    await expect(page.getByRole('heading', { level: 1, name: order.number })).toBeVisible();
    await expect(page.getByTestId('entity-state')).toContainText('Черновик');

    // The lines tab shows the three lines with their amounts.
    await page.getByRole('tab', { name: 'Строки' }).click();
    await expect(page.locator('smt-entity-rows tbody tr')).toHaveCount(3);
    await expect(page.getByRole('cell', { name: 'Сахар, 25 кг' })).toBeVisible();

    // Post: a transition of the process from the revision on screen.
    await expect(page.getByRole('button', { name: 'Провести' })).toBeVisible();
    const postResponse = await orders.runAction('post');
    expect(postResponse.status()).toBe(200);
    expect(postResponse.request().headers()['if-match']).toMatch(/^"\d+"$/u);
    await expect(page.getByTestId('entity-state')).toContainText('Проведён');
    await expect(page.getByRole('button', { name: 'Провести' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Отменить проведение' })).toBeVisible();

    // A posted order keeps its customer and its lines: the form offers only what the state leaves open.
    await page.getByRole('link', { name: 'Редактировать' }).click();
    await expect(page).toHaveURL(/\/edit$/u);
    await expect(page.getByRole('textbox', { name: 'Клиент' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Добавить строку' })).toHaveCount(0);
    // Untouched, so going back asks nothing (forms standard, section 8).
    await page.getByRole('button', { name: 'Отмена', exact: true }).click();

    // History: the creation with its lines, then the posting.
    await page.getByRole('tab', { name: 'История' }).click();
    const history = page.getByTestId('record-history-list');
    await expect(history).toContainText('Создание');
    await expect(history).toContainText('строк: 3');
    await expect(history).toContainText('Проведён');
    await expect(history).toContainText('Провести');

    assertNoPageErrors();
  } finally {
    await switchModule(page, false);
  }
});
