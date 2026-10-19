import { randomBytes } from 'node:crypto';

import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';
import { fieldName } from '../../../support/fields.js';
import { Mailbox, oneTimeCode } from '../../../support/mailpit.js';
import { clearSecret, fillSecret } from '../../../support/secret.js';
import { chooseOption } from '../../../support/select.js';
import { inviteUser } from '../../../support/users.js';

// ADR-0046, stage 3, step 1 of the form and report builder: automations of the order with lines and approval of
// step 2.2. A discount above 20 on save marks the order «Требует проверки» (built through the editor); an order sent
// for approval tells its manager in the application and by email with its number and total; an approved order creates
// a «Задача на отгрузку» in another live form for its responsible person with the order's number; a scheduled reminder
// run now tells the responsible person of approved orders. The flag's own change starts nothing more (one run), a
// person without the right to keep automations gets 403, and an automation that would do what its author may not is
// refused with 403. Fixtures come through the API.

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

function field(id: string, type: string, title: string, extra: Record<string, unknown> = {}) {
  return { id, type, labels: labels(title), required: false, listed: true, ...extra };
}

/** A column of a table of lines: a field without the list flag. */
function column(id: string, type: string, title: string, extra: Record<string, unknown> = {}) {
  return { id, type, labels: labels(title), required: false, ...extra };
}

async function activate(admin: BrowserContext, declaration: unknown): Promise<string> {
  const prepared = await expectCall(admin, 'POST', '/api/v1/form-studio/new-forms/prepare', 200, declaration);
  return ((await expectCall(admin, 'POST', '/api/v1/studio/forms', 201, prepared.body)).body as { entityCode: string }).entityCode;
}

const module = { code: 'studio', title: labels('Созданные формы') };

/** The order of ADR-0043 with a manager, a responsible person and the review flag, in format 6. */
function orderDeclaration(name: string, clients: string, goods: string) {
  const state = (code: string, title: string, color: string, extra: Record<string, unknown> = {}) =>
    ({ code, labels: labels(title), color, ...extra });
  const move = (code: string, title: string, from: string, to: string, extra: Record<string, unknown> = {}) =>
    ({ code, labels: labels(title), from: [from], to, comment: 'optional', ...extra });
  return {
    formatVersion: 6, kind: 'simple-form', module,
    entity: { name, title: labels('Заказы автоматизаций'), scope: 'all', numbering: { pattern: 'АВ-{YYYY}-{NNNNNN}', reset: 'year' } },
    fields: [
      field('client', 'ref', 'Клиент', { required: true, target: { kind: 'form', code: clients } }),
      field('discount', 'number', 'Скидка', { scale: 2 }),
      field('total', 'number', 'Итого', { scale: 2, formula: 'sum(lines.items.amount)' }),
      field('manager', 'user', 'Менеджер'),
      field('responsible', 'user', 'Ответственный'),
      field('review', 'bool', 'Требует проверки'),
    ],
    lines: [{
      key: 'items', labels: labels('Строки заказа'), maxRows: 20,
      fields: [
        column('product', 'ref', 'Товар', { required: true, target: { kind: 'list', code: goods } }),
        column('qty', 'number', 'Количество', { required: true, scale: 3 }),
        column('price', 'number', 'Цена', { required: true, scale: 2 }),
        column('amount', 'number', 'Сумма', { scale: 2, formula: 'round(qty * price, 2)' }),
      ],
    }],
    workflow: {
      states: [
        state('draft', 'Черновик', 'gray'),
        state('pending', 'На согласовании', 'blue', { lockAll: true }),
        state('approved', 'Согласовано', 'green', { terminal: true }),
      ],
      transitions: [
        move('submit', 'На согласование', 'draft', 'pending', { condition: 'total > 0' }),
        move('approve', 'Согласовать', 'pending', 'approved', { notAuthor: true }),
      ],
    },
  };
}

interface Person { id: number; login: string; email: string; context: BrowserContext; page: Page }

/** A person with one role, signed in; with a confirmed email channel when `mail` is true. */
async function person(browser: Browser, admin: BrowserContext, roleId: number, who: string, mail = false): Promise<Person> {
  const login = `auto-${who}-${randomBytes(4).toString('hex')}`;
  const email = `${login}@e2e.test`;
  const password = `Qa!7${randomBytes(8).toString('hex')}`;
  const user = await inviteUser(admin, { name: `Сотрудник ${who}`, login, email }, password, [roleId]);
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
  if (mail) {
    const mailbox = await Mailbox.open(email);
    try {
      const bound = await expectCall(context, 'POST', '/api/v1/iam/profile/channels', 202, { channel: 'email', address: email });
      const code = oneTimeCode(await mailbox.nextMessage());
      await expectCall(context, 'POST', '/api/v1/iam/profile/channels/confirm', 204, {
        verifyToken: (bound.body as { verifyToken: string }).verifyToken, code,
      });
    } finally { await mailbox.close(); }
  }
  return { id: user.id, login, email, context, page };
}

async function role(admin: BrowserContext, name: string, rights: [string, string][]): Promise<number> {
  const created = (await expectCall(admin, 'POST', '/api/v1/iam/roles', 201, { name, orderNo: 9500 })).body as { id: number; revision?: number };
  const signedIn: [string, string][] = [['md.profile', 'view'], ['md.profile', 'manage_channels'], ['notify.inbox', 'view']];
  await expectCall(admin, 'PUT', `/api/v1/iam/roles/${created.id}/permissions`, 204,
    [...signedIn, ...rights].map(([formCode, action]) => ({ formCode, action })), `"${created.revision ?? 1}"`);
  return created.id;
}

type Run = { status: string; recordId: number; reason?: string };

async function runs(admin: BrowserContext, automation: number): Promise<Run[]> {
  return ((await expectCall(admin, 'GET', `/api/v1/automations/${automation}/runs`, 200)).body as { items: Run[] }).items;
}

test('automations: a review flag on save, notice on approval, a shipping task on approve, a reminder, no loop, rights', async ({ page, browser }) => {
  test.setTimeout(480_000);
  await loginToInstance(page);
  const admin = page.context();
  const assertNoPageErrors = collectPageErrors(page);
  const tag = Date.now().toString(36);
  const modules = (await expectCall(admin, 'GET', '/api/v1/modules', 200)).body as { code: string; status: string }[];
  const wasEnabled = modules.find(entry => entry.code === 'studio')?.status === 'ACTIVE';
  await expectCall(admin, 'PUT', '/api/v1/modules/studio/enabled', 200, { enabled: true });
  const contexts: BrowserContext[] = [];
  const mailboxes: Mailbox[] = [];
  try {
    const goods = `agoods_${tag}`;
    await expectCall(admin, 'POST', '/api/v1/studio/lists', 201, { code: goods, name: labels('Товары') });
    await expectCall(admin, 'POST', `/api/v1/studio/lists/${goods}/items`, 201, { code: 'tea', name: labels('Чай'), sortOrder: 1 });
    const clients = await activate(admin, {
      formatVersion: 3, kind: 'simple-form', module, entity: { name: `aclients_${tag}`, title: labels('Клиенты'), scope: 'all' },
      fields: [field('phone', 'text', 'Телефон', { maxLength: 30 })],
    });
    const tasks = await activate(admin, {
      formatVersion: 4, kind: 'simple-form', module,
      entity: { name: `aships_${tag}`, title: labels('Задачи на отгрузку'), scope: 'all', titleField: 'ordernum' },
      fields: [
        field('ordernum', 'text', 'Номер заказа', { required: true, maxLength: 40 }),
        field('assignee', 'user', 'Исполнитель'),
        field('note', 'text', 'Что сделать', { maxLength: 200 }),
      ],
    });
    const orders = await activate(admin, orderDeclaration(`aorders_${tag}`, clients, goods));

    const sellers = await role(admin, `Продавцы автоматизаций ${tag}`, [
      [orders, 'view'], [orders, 'create'], [orders, 'update'], [orders, 'submit'], [clients, 'view'], [clients, 'create'], ['md.users', 'view'],
    ]);
    const managers = await role(admin, `Менеджеры автоматизаций ${tag}`, [[orders, 'view'], [orders, 'approve']]);
    const shippers = await role(admin, `Отгрузка автоматизаций ${tag}`, [[orders, 'view'], [tasks, 'view']]);
    const weak = await role(admin, `Без права изменения ${tag}`, [['studio.automations', 'view'], ['studio.automations', 'manage'], [orders, 'view']]);
    const seller = await person(browser, admin, sellers, `s${tag}`);
    const manager = await person(browser, admin, managers, `m${tag}`, true);
    const shipper = await person(browser, admin, shippers, `r${tag}`);
    const outsider = await person(browser, admin, weak, `w${tag}`);
    contexts.push(seller.context, manager.context, shipper.context, outsider.context);
    const client = ((await expectCall(seller.context, 'POST', `/api/v1/entities/${clients}`, 201, { name: 'Клиент', code: `c${tag}`, phone: '1' })).body as { id: number }).id;

    // (c) The review flag, built through the editor: on save, when the discount is above 20, set «Требует проверки».
    await page.goto(`/settings/form-studio/automations?form=${orders}`);
    await expect(page.getByTestId('automations-empty')).toBeVisible();
    await page.getByTestId('automations-new').click();
    await expect(page.getByTestId('automation-form')).toBeVisible();
    await page.getByTestId('automation-name').fill(`Скидка больше 20 ${tag}`);
    await page.getByTestId('automation-condition').locator('textarea').fill('discount > 20');
    await page.getByTestId('automation-action-remove-0').click();
    await chooseOption(page.getByTestId('automation-new-action').getByRole('combobox'), 'set_field');
    await page.getByTestId('automation-add-action').click();
    await chooseOption(page.getByTestId('automation-value-field-0-0').getByRole('combobox'), 'review');
    await page.getByTestId('automation-value-formula-0-0').locator('textarea').fill('true');
    const created = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/automations');
    await page.getByTestId('form-submit').click();
    const answer = await created;
    expect(answer.status(), `${answer.request().postData()} -> ${await answer.text()}`).toBe(201);
    const card = page.getByTestId('automation').filter({ hasText: `Скидка больше 20 ${tag}` });
    await expect(card).toBeVisible();
    const flag = ((await expectCall(admin, 'GET', `/api/v1/automations?form=${orders}`, 200)).body as { items: { id: number }[] }).items[0].id;

    // (a) and (b), through the API: notify the manager on approval; an approved order creates the shipping task.
    const notice = ((await expectCall(admin, 'POST', '/api/v1/automations', 201, {
      form: orders, name: `Уведомить менеджера ${tag}`, trigger: { kind: 'transition', to: ['pending'] },
      actions: [{
        type: 'notify', channels: ['in_app', 'email'], recipients: { fields: ['manager'] },
        title: { ru: 'Заказ {docNumber} ждёт согласования' }, body: { ru: 'Заказ {docNumber} на сумму {total} ждёт вашего решения.' },
      }],
    })).body as { id: number }).id;
    const shipping = ((await expectCall(admin, 'POST', '/api/v1/automations', 201, {
      form: orders, name: `Задача на отгрузку ${tag}`, trigger: { kind: 'transition', to: ['approved'] },
      actions: [{ type: 'create_record', form: tasks, values: [
        { field: 'ordernum', formula: 'docNumber' }, { field: 'assignee', formula: 'responsible' }, { field: 'note', formula: '"Отгрузить"' },
      ] }],
    })).body as { id: number }).id;

    // (f) Without the right to keep automations: 403; with it but without changing orders, a set_field is 403 too.
    const flagRequest = { form: orders, name: 'Чужая', trigger: { kind: 'saved' }, actions: [{ type: 'set_field', values: [{ field: 'review', formula: 'true' }] }] };
    await expectCall(seller.context, 'POST', '/api/v1/automations', 403, flagRequest);
    await expectCall(outsider.context, 'POST', '/api/v1/automations', 403, flagRequest);

    // The seller saves an order with a discount of 25: the automation marks it for review, once.
    const mailbox = await Mailbox.open(manager.email);
    mailboxes.push(mailbox);
    const order = (await expectCall(seller.context, 'POST', `/api/v1/entities/${orders}`, 201, {
      client, discount: '25', manager: manager.id, responsible: shipper.id,
      items: [{ product: 'tea', qty: '2', price: '10' }],
    })).body as { id: number; docNumber: string };
    const read = async () => (await expectCall(seller.context, 'GET', `/api/v1/entities/${orders}/${order.id}`, 200)).body as {
      review?: boolean; revision: number; docStatus: string; total: unknown;
    };
    await expect.poll(async () => (await read()).review, { timeout: 60_000, intervals: [1_000, 2_000] }).toBe(true);
    // (e) Its own change starts nothing more: one run for the record, and the record keeps its revision.
    const flagged = await read();
    await page.waitForTimeout(8_000);
    expect((await runs(admin, flag)).filter(run => run.recordId === order.id)).toEqual([expect.objectContaining({ status: 'done' })]);
    expect((await read()).revision).toBe(flagged.revision);
    const history = (await expectCall(seller.context, 'GET', `/api/v1/history/${orders}/${order.id}`, 200)).body;
    expect(JSON.stringify(history)).toContain(`Скидка больше 20 ${tag}`);

    // (a) Sent for approval: the manager is told in the application and by email, with the number and the total.
    await expectCall(seller.context, 'POST', `/api/v1/entities/${orders}/${order.id}/actions/submit`, 200, {}, `"${flagged.revision}"`);
    const letter = await mailbox.nextLetter(60_000);
    expect(letter.subject).toContain(order.docNumber);
    expect(letter.text).toContain('20');
    const inbox = async () => ((await expectCall(manager.context, 'GET', '/api/v1/notifications/inbox?limit=20', 200)).body as {
      items: { title: string; body?: string }[];
    }).items;
    await expect.poll(async () => (await inbox()).some(item => item.title.includes(`Заказ ${order.docNumber} ждёт`)), { timeout: 30_000 }).toBe(true);
    await expect.poll(async () => (await runs(admin, notice)).map(run => run.status), { timeout: 30_000 }).toEqual(['done']);

    // (b) Approved by the manager: a shipping task for the responsible person with the order's number.
    const pending = await read();
    await expectCall(manager.context, 'POST', `/api/v1/entities/${orders}/${order.id}/actions/approve`, 200, {}, `"${pending.revision}"`);
    const shipped = async () => ((await expectCall(shipper.context, 'GET', `/api/v1/entities/${tasks}`, 200)).body as {
      items: { ordernum: string; assignee: number; note: string }[];
    }).items;
    await expect.poll(async () => (await shipped()).length, { timeout: 60_000, intervals: [1_000, 2_000] }).toBe(1);
    expect((await shipped())[0]).toEqual(expect.objectContaining({ ordernum: order.docNumber, assignee: shipper.id, note: 'Отгрузить' }));
    expect((await runs(admin, shipping)).map(run => run.status)).toEqual(['done']);

    // (d) A scheduled reminder of approved orders, run now from the list: the responsible person is told.
    const reminder = ((await expectCall(admin, 'POST', '/api/v1/automations', 201, {
      form: orders, name: `Напоминание об отгрузке ${tag}`, timeZone: 'Asia/Tashkent',
      trigger: { kind: 'scheduled', schedule: { frequency: 'daily', time: '09:00' } },
      condition: 'docStatus = "approved" and daysBetween(dateOf(modifiedAt), today()) >= 0',
      actions: [{ type: 'notify', channels: ['in_app'], recipients: { fields: ['responsible'] },
        title: { ru: 'Отгрузите заказ {docNumber}' }, body: { ru: 'Заказ {docNumber} согласован.' } }],
    })).body as { id: number }).id;
    await page.goto(`/settings/form-studio/automations?form=${orders}`);
    const reminderCard = page.getByTestId('automation').filter({ hasText: `Напоминание об отгрузке ${tag}` });
    await reminderCard.getByTestId('automation-run-now').click();
    await expect.poll(async () => (await runs(admin, reminder)).map(run => run.status), { timeout: 60_000, intervals: [1_000, 2_000] }).toEqual(['done']);
    const shipperInbox = ((await expectCall(shipper.context, 'GET', '/api/v1/notifications/inbox?limit=20', 200)).body as {
      items: { title: string }[];
    }).items;
    expect(shipperInbox.some(item => item.title.includes(`Отгрузите заказ ${order.docNumber}`))).toBe(true);

    // The run log on the screen tells the shipping task's run in words.
    const shippingCard = page.getByTestId('automation').filter({ hasText: `Задача на отгрузку ${tag}` });
    await shippingCard.getByTestId('automation-runs').click();
    const log = page.getByTestId('automation-runs-dialog');
    await expect(log.getByTestId('automation-run').first()).toContainText('Выполнен');
    await page.keyboard.press('Escape');
    await expect(log).toBeHidden();
    assertNoPageErrors();
  } finally {
    for (const mailbox of mailboxes) await mailbox.close();
    for (const context of contexts) await context.close();
    await call(admin, 'PUT', '/api/v1/modules/studio/enabled', { enabled: wasEnabled });
  }
});
