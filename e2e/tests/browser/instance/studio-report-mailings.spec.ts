import { randomBytes } from 'node:crypto';

import { expect, test, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';
import { fieldName } from '../../../support/fields.js';
import { Mailbox, oneTimeCode } from '../../../support/mailpit.js';
import { clearSecret, fillSecret } from '../../../support/secret.js';
import { chooseOption } from '../../../support/select.js';
import { inviteUser } from '../../../support/users.js';
import { xlsxRows } from '../../../support/xlsx-read.js';

// ADR-0045, stage 2, step 4 of the form and report builder: a mailing of the regions report of step 2.3 — orders seen by
// org unit, a required period parameter fixed to the previous month — by email and in-app with the XLSX attached, sent
// only when the report has rows. Sent now from the mailings screen, it runs the report as each recipient: the seller of
// unit A gets a letter whose XLSX holds only unit A's regions, the seller of unit B only theirs, the seller of unit C,
// who has no orders last month, gets nothing; the in-app notification opens the report with the mailing's period. A
// paused mailing sends nothing to all, and the run history tells each outcome. Fixtures come through the API, the
// mailing through the screen.

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

async function activate(admin: BrowserContext, declaration: unknown): Promise<string> {
  const prepared = await expectCall(admin, 'POST', '/api/v1/form-studio/new-forms/prepare', 200, declaration);
  return ((await expectCall(admin, 'POST', '/api/v1/studio/forms', 201, prepared.body)).body as { entityCode: string }).entityCode;
}

interface Seller { id: number; email: string; context: BrowserContext; page: Page }

/** A seller of one org unit, signed in, with a confirmed email channel: a mailing goes only to a confirmed address. */
async function seller(browser: Browser, admin: BrowserContext, unit: number, roleId: number, who: string): Promise<Seller> {
  const login = `mailing-${who}-${randomBytes(4).toString('hex')}`;
  const email = `${login}@e2e.test`;
  const password = `Qa!7${randomBytes(8).toString('hex')}`;
  const user = await inviteUser(admin, { name: `Продавец ${who}`, login, email }, password, [roleId]);
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
  const mailbox = await Mailbox.open(email);
  try {
    const bound = await expectCall(context, 'POST', '/api/v1/iam/profile/channels', 202, { channel: 'email', address: email });
    const code = oneTimeCode(await mailbox.nextMessage());
    await expectCall(context, 'POST', '/api/v1/iam/profile/channels/confirm', 204, {
      verifyToken: (bound.body as { verifyToken: string }).verifyToken, code,
    });
  } finally { await mailbox.close(); }
  return { id: user.id, email, context, page };
}

/** Picks a user in the recipients of the mailing dialog by typing a part of the login. */
async function addRecipient(page: Page, control: Locator, login: string): Promise<void> {
  const trigger = control.getByRole('combobox').first();
  await trigger.focus();
  await page.keyboard.press('Enter');
  const search = page.locator('.cdk-overlay-container input[role="combobox"][aria-autocomplete="list"]').last();
  await expect(search).toBeFocused();
  await search.fill(login);
  const listbox = page.locator('.cdk-overlay-container [role="listbox"]').last();
  await listbox.getByRole('option', { name: new RegExp(login, 'u') }).first().click();
  await search.press('Escape');
  await expect(listbox).toBeHidden();
}

/** The first and the last day of the month before the current one, in Tashkent. */
function lastMonth(): { day: string; today: string } {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Tashkent' }).format(new Date());
  const [year, month] = today.split('-').map(Number);
  const previous = month === 1 ? [year - 1, 12] : [year, month - 1];
  return { day: `${previous[0]}-${String(previous[1]).padStart(2, '0')}-10`, today };
}

test('report mailings: sent now as each recipient, an XLSX of their own unit, nothing without rows, pause and history', async ({ page, browser }) => {
  test.setTimeout(420_000);
  await loginToInstance(page);
  const admin = page.context();
  const assertNoPageErrors = collectPageErrors(page);
  const tag = Date.now().toString(36);
  const modules = (await expectCall(admin, 'GET', '/api/v1/modules', 200)).body as { code: string; status: string }[];
  const wasEnabled = modules.find(module => module.code === 'studio')?.status === 'ACTIVE';
  await expectCall(admin, 'PUT', '/api/v1/modules/studio/enabled', 200, { enabled: true });
  const contexts: BrowserContext[] = [];
  const mailboxes: Mailbox[] = [];
  try {
    const regions = `mregions_${tag}`;
    await expectCall(admin, 'POST', '/api/v1/studio/lists', 201, { code: regions, name: labels('Регионы') });
    for (const [order, [code, title]] of [['north', 'Север'], ['south', 'Юг']].entries()) {
      await expectCall(admin, 'POST', `/api/v1/studio/lists/${regions}/items`, 201, { code, name: labels(title), sortOrder: order + 1 });
    }
    const orders = await activate(admin, {
      formatVersion: 4, kind: 'simple-form', module: { code: 'studio', title: labels('Созданные формы') },
      entity: { name: `morders_${tag}`, title: labels('Заказы рассылки'), scope: 'org_unit' },
      fields: [
        field('region', 'ref', 'Регион', { required: true, target: { kind: 'list', code: regions } }),
        field('orderdate', 'date', 'Дата заказа', { required: true }),
        field('total', 'number', 'Сумма заказа', { scale: 2 }),
      ],
    });

    const units = (await expectCall(admin, 'GET', '/api/v1/iam/org-units', 200)).body as { id: number; parentId: number | null }[];
    const parent = units.find(unit => unit.parentId == null)?.id
      ?? ((await expectCall(admin, 'POST', '/api/v1/iam/org-units', 201, { parentId: null, code: `ml-root-${tag}`, name: `Компания ${tag}`, kind: 'department', orderNo: 1 })).body as { id: number }).id;
    const unit = async (key: string, order: number) => ((await expectCall(admin, 'POST', '/api/v1/iam/org-units', 201, {
      parentId: parent, code: `ml-${key}-${tag}`, name: `Филиал ${key.toUpperCase()} ${tag}`, kind: 'branch', orderNo: order,
    })).body as { id: number }).id;
    const [unitA, unitB, unitC] = [await unit('a', 1), await unit('b', 2), await unit('c', 3)];
    const role = (await expectCall(admin, 'POST', '/api/v1/iam/roles', 201, { name: `Продавцы рассылки ${tag}`, orderNo: 9400 })).body as { id: number; revision?: number };
    const granted = await expectCall(admin, 'PUT', `/api/v1/iam/roles/${role.id}/permissions`, 204, [
      { formCode: 'md.profile', action: 'view' },
      { formCode: 'md.profile', action: 'manage_channels' },
      { formCode: 'notify.inbox', action: 'view' },
      { formCode: orders, action: 'view' },
      { formCode: orders, action: 'create' },
    ], `"${role.revision ?? 1}"`);
    await expectCall(admin, 'PUT', `/api/v1/iam/org-units/roles/${role.id}/rule`, 204, { rule: 'UNITS' }, granted.etag ?? `"${(role.revision ?? 1) + 1}"`);
    const sellerA = await seller(browser, admin, unitA, role.id, `a${tag}`);
    const sellerB = await seller(browser, admin, unitB, role.id, `b${tag}`);
    const sellerC = await seller(browser, admin, unitC, role.id, `c${tag}`);
    contexts.push(sellerA.context, sellerB.context, sellerC.context);

    // Last month: unit A north 100 and south 50, unit B north 700, unit C nothing (only an order of this month).
    const { day, today } = lastMonth();
    const place = (who: Seller, region: string, date: string, total: string) =>
      expectCall(who.context, 'POST', `/api/v1/entities/${orders}`, 201, { region, orderdate: date, total });
    await place(sellerA, 'north', day, '100');
    await place(sellerA, 'south', day, '50');
    await place(sellerA, 'north', today, '999');
    await place(sellerB, 'north', day, '700');
    await place(sellerC, 'south', today, '5');

    // The regions report with a required period, saved by the administrator and shared with the sellers' role.
    const reportName = `Заказы по регионам ${tag}`;
    const saved = await expectCall(admin, 'POST', `/api/v1/list-views/${orders}`, 201, {
      name: reportName, kind: 'report', isDefault: false,
      state: {
        groupBy: [{ field: 'region' }], measures: [{ op: 'sum', field: 'total' }], chart: 'table', totals: true,
        parameters: [{ key: 'period', field: 'orderdate', required: true }],
      },
    });
    const view = saved.body as { id: number; lockVersion: number };
    await expectCall(admin, 'PUT', `/api/v1/list-views/${orders}/${view.id}/sharing`, 200, { roleIds: [role.id] }, `"${view.lockVersion}"`);

    // The mailing, through the screen: the catalog links the report's mailings.
    await page.goto('/reports');
    await page.getByTestId('reports-search').fill(tag);
    await page.getByTestId('reports-item').filter({ hasText: reportName }).getByTestId('reports-mailings').click();
    await expect(page.getByTestId('report-mailings-empty')).toBeVisible();
    await page.getByTestId('report-mailings-new').click();
    const dialog = page.getByRole('dialog');
    const mailingName = `Итоги прошлого месяца ${tag}`;
    await dialog.getByTestId('report-mailing-name').fill(mailingName);
    const recipients = dialog.getByTestId('report-mailing-recipients');
    for (const who of [sellerA, sellerB, sellerC]) await addRecipient(page, recipients, who.email.split('@')[0]);
    await expect(dialog.getByTestId('report-mailing-channel-email').getByRole('checkbox')).toBeChecked();
    await expect(dialog.getByTestId('report-mailing-channel-in_app').getByRole('checkbox')).toBeChecked();
    await expect(dialog.getByTestId('report-mailing-attach').getByRole('checkbox')).toBeChecked();
    await chooseOption(dialog.getByTestId('report-mailing-frequency').getByRole('combobox'), 'monthly');
    await chooseOption(dialog.getByTestId('report-parameter-period').getByRole('combobox'), 'last_month');
    await chooseOption(dialog.getByTestId('report-mailing-condition').getByRole('combobox'), 'has_rows');
    const created = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/report-mailings');
    await dialog.getByTestId('form-submit').click();
    expect((await created).status()).toBe(201);
    await expect(dialog).toBeHidden();
    const card = page.getByTestId('report-mailing').filter({ hasText: mailingName });
    await expect(card).toContainText('Asia/Tashkent');

    // Sent now: each recipient's report runs as that recipient.
    const mailboxA = await Mailbox.open(sellerA.email);
    const mailboxB = await Mailbox.open(sellerB.email);
    const mailboxC = await Mailbox.open(sellerC.email);
    mailboxes.push(mailboxA, mailboxB, mailboxC);
    await card.getByTestId('report-mailing-send').click();
    const mailings = async () => ((await expectCall(admin, 'GET', `/api/v1/report-mailings?viewId=${view.id}`, 200)).body as {
      items: { id: number; revision: number; lastRun?: { id: number; status: string } }[];
    }).items[0];
    await expect.poll(async () => (await mailings()).lastRun?.status, { timeout: 60_000, intervals: [1_000, 2_000] }).toBe('done');

    const letterA = await mailboxA.nextLetter();
    expect(letterA.subject).toContain(mailingName);
    expect(letterA.attachments).toHaveLength(1);
    const sheetA = xlsxRows(letterA.attachments[0].content);
    expect(sheetA.map(row => row[0])).toEqual(expect.arrayContaining(['Север', 'Юг', 'Итого']));
    expect(Number(sheetA.find(row => row[0] === 'Итого')?.[1])).toBe(150);
    expect(sheetA.flat()).not.toContain('999');
    const letterB = await mailboxB.nextLetter();
    const sheetB = xlsxRows(letterB.attachments[0].content);
    expect(sheetB.map(row => row[0])).toContain('Север');
    expect(sheetB.map(row => row[0])).not.toContain('Юг');
    expect(Number(sheetB.find(row => row[0] === 'Итого')?.[1])).toBe(700);
    expect(await mailboxC.hasUnseen()).toBe(false);

    // The in-app notification of seller A opens the report with the mailing's period: only unit A's orders.
    const inbox = (await expectCall(sellerA.context, 'GET', '/api/v1/notifications/inbox?limit=20', 200)).body as {
      items: { title: string; formLink?: string }[];
    };
    const notice = inbox.items.find(item => item.title.includes(mailingName));
    expect(notice?.formLink).toContain(`report=${view.id}`);
    await sellerA.page.goto(notice!.formLink!);
    const table = sellerA.page.getByTestId('report-table');
    await expect(table.getByTestId('report-total')).toContainText('150');
    await expect(table.getByRole('row').filter({ hasText: 'Север' })).toContainText('100');
    expect((await expectCall(sellerC.context, 'GET', '/api/v1/notifications/inbox?limit=20', 200)).body)
      .not.toEqual(expect.objectContaining({ items: expect.arrayContaining([expect.objectContaining({ title: expect.stringContaining(mailingName) })]) }));

    // The run history tells who got what and why the seller of unit C got nothing.
    await card.getByTestId('report-mailing-history').click();
    const history = page.getByTestId('report-mailing-runs');
    await expect(history.getByTestId('report-mailing-run').first()).toContainText('Выполнена');
    await history.getByTestId('report-mailing-run-open').first().click();
    const deliveries = history.getByTestId('report-mailing-delivery');
    await expect(deliveries.filter({ hasText: `Продавец c${tag}` }).first()).toContainText('условие не выполнено');
    await expect(deliveries.filter({ hasText: `Продавец a${tag}` }).first()).toContainText('Передано на отправку');
    await page.keyboard.press('Escape');
    await expect(history).toBeHidden();

    // Paused, it sends nothing to all: the button is off and the server refuses.
    await card.getByTestId('report-mailing-toggle').click();
    await expect(card).toContainText('Приостановлена');
    await expect(card.getByTestId('report-mailing-send')).toBeDisabled();
    const paused = await mailings();
    await expectCall(admin, 'POST', `/api/v1/report-mailings/${paused.id}/runs`, 409, { scope: 'all' });
    expect(await mailboxA.hasUnseen()).toBe(false);
    assertNoPageErrors();
  } finally {
    for (const mailbox of mailboxes) await mailbox.close();
    for (const context of contexts) await context.close();
    await call(admin, 'PUT', '/api/v1/modules/studio/enabled', { enabled: wasEnabled });
  }
});
