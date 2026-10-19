import { randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';

import { expect, test, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';
import { fieldName } from '../../../support/fields.js';
import { clearSecret, fillSecret } from '../../../support/secret.js';
import { chooseOption } from '../../../support/select.js';
import { inviteUser } from '../../../support/users.js';

// ADR-0047, stage 3, step 2 of the form and report builder: solution packages and templates. (a) A small solution
// built here — a list, two forms that refer to it and to each other, a report — is exported through the wizard and
// imported again under new codes: the package carries everything it needs. (b) «Заказ со строками и согласованием» is
// created from its template with the approver and seller roles and the mailing's recipient mapped; its automation stays
// paused, so a first order sent for approval gets no date, and once an administrator switches it on the next order
// gets it; the approver approves. (c) A changed package file is refused by its checksum. (d) A package whose form would
// change incompatibly an existing one is refused for that form, with the reason, and nothing is applied. (e) A person
// without the right gets 403.

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
const module = { code: 'studio', title: labels('Созданные формы') };

function field(id: string, type: string, title: string, extra: Record<string, unknown> = {}) {
  return { id, type, labels: labels(title), required: false, listed: true, ...extra };
}

async function activate(admin: BrowserContext, declaration: unknown): Promise<string> {
  const prepared = await expectCall(admin, 'POST', '/api/v1/form-studio/new-forms/prepare', 200, declaration);
  return ((await expectCall(admin, 'POST', '/api/v1/studio/forms', 201, prepared.body)).body as { entityCode: string }).entityCode;
}

interface Person { id: number; login: string; context: BrowserContext }

async function person(browser: Browser, admin: BrowserContext, roleId: number, who: string): Promise<Person> {
  const login = `pkg-${who}-${randomBytes(4).toString('hex')}`;
  const password = `Qa!7${randomBytes(8).toString('hex')}`;
  const user = await inviteUser(admin, { name: `Сотрудник ${who}`, login, email: `${login}@e2e.test` }, password, [roleId]);
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
  return { id: user.id, login, context };
}

async function role(admin: BrowserContext, name: string): Promise<number> {
  const created = (await expectCall(admin, 'POST', '/api/v1/iam/roles', 201, { name, orderNo: 9500 })).body as { id: number; revision?: number };
  const signedIn = [['md.profile', 'view'], ['notify.inbox', 'view']];
  await expectCall(admin, 'PUT', `/api/v1/iam/roles/${created.id}/permissions`, 204,
    signedIn.map(([formCode, action]) => ({ formCode, action })), `"${created.revision ?? 1}"`);
  return created.id;
}

async function addUser(page: Page, control: Locator, login: string): Promise<void> {
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

/** Applies the import shown on the page, through the confirmation, and waits for the server's answer. */
async function applyImport(page: Page): Promise<number> {
  const applied = page.waitForResponse(response => response.request().method() === 'POST'
    && /\/api\/v1\/studio\/packages\/imports\/\d+\/apply$/u.test(new URL(response.url()).pathname));
  await page.getByTestId('form-submit').click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Применить пакет', exact: true }).click();
  return (await applied).status();
}

/** Uploads a package file through the import tab and waits for the import page. */
async function upload(page: Page, file: { name: string; buffer: Buffer }, suffix: string): Promise<void> {
  await page.goto('/settings/form-studio/packages?tab=import');
  await page.getByTestId('package-file').setInputFiles({ name: file.name, mimeType: 'application/json', buffer: file.buffer });
  await expect(page.getByTestId('package-file-name')).toHaveText(file.name);
  await page.getByTestId('package-suffix').locator('input').fill(suffix);
  await page.getByTestId('form-submit').click();
}

test('packages: export and import under new codes, an order solution from its template, a changed file, an incompatible form, rights', async ({ page, browser }) => {
  test.setTimeout(480_000);
  await loginToInstance(page);
  const admin = page.context();
  const assertNoPageErrors = collectPageErrors(page);
  const tag = Date.now().toString(36);
  const modules = (await expectCall(admin, 'GET', '/api/v1/modules', 200)).body as { code: string; status: string }[];
  const wasEnabled = modules.find(entry => entry.code === 'studio')?.status === 'ACTIVE';
  await expectCall(admin, 'PUT', '/api/v1/modules/studio/enabled', 200, { enabled: true });
  const contexts: BrowserContext[] = [];
  try {
    // (a) A small solution: a list of regions, clients named by their title with a region, visits of a client.
    const regions = `pregions_${tag}`;
    await expectCall(admin, 'POST', '/api/v1/studio/lists', 201, { code: regions, name: labels('Регионы пакета') });
    await expectCall(admin, 'POST', `/api/v1/studio/lists/${regions}/items`, 201, { code: 'north', name: labels('Север'), sortOrder: 1 });
    const clients = await activate(admin, {
      formatVersion: 4, kind: 'simple-form', module,
      entity: { name: `pclients_${tag}`, title: labels('Клиенты пакета'), scope: 'all', titleField: 'title' },
      fields: [
        field('title', 'text', 'Название', { required: true, maxLength: 120 }),
        field('region', 'ref', 'Регион', { target: { kind: 'list', code: regions } }),
      ],
    });
    const visits = await activate(admin, {
      formatVersion: 4, kind: 'simple-form', module,
      entity: { name: `pvisits_${tag}`, title: labels('Визиты пакета'), scope: 'all' },
      fields: [
        field('client', 'ref', 'Клиент', { required: true, target: { kind: 'form', code: clients } }),
        field('result', 'select', 'Результат', {
          options: [{ code: 'ordered', labels: labels('Заказ') }, { code: 'none', labels: labels('Нет заказа') }],
        }),
      ],
    });
    const report = ((await expectCall(admin, 'POST', `/api/v1/list-views/${visits}`, 201, {
      name: `Визиты по результатам ${tag}`, isDefault: false, kind: 'report',
      state: { groupBy: [{ field: 'result' }], measures: [{ op: 'count' }], filter: [], chart: 'table' },
    })).body as { id: number }).id;

    // The export wizard: the visits form; its clients and regions come as dependencies, its report is offered.
    await page.goto('/settings/form-studio/packages?tab=export');
    await page.getByTestId('package-export-code').locator('input').fill(`visits-${tag}`);
    await page.getByTestId('package-export-ru').locator('input').fill('Визиты');
    await page.getByTestId('package-export-uz').locator('input').fill('Tashriflar');
    await page.getByTestId('package-export-en').locator('input').fill('Visits');
    await page.getByTestId(`package-export-form-${visits}`).click();
    await page.getByTestId('package-export-show').click();
    const plan = page.getByTestId('package-export-plan');
    await expect(plan).toContainText('Клиенты пакета');
    await expect(plan).toContainText('Регионы пакета');
    await page.getByTestId(`package-export-related-report-${report}`).click();
    const downloaded = page.waitForEvent('download');
    await page.getByTestId('form-submit').click();
    const download = await downloaded;
    const file = { name: download.suggestedFilename(), buffer: await readFile(await download.path()) };
    const pack = JSON.parse(file.buffer.toString('utf8')) as { checksum: string; reports: unknown[]; forms: unknown[] };
    expect(pack.checksum).toMatch(/^[0-9a-f]{64}$/u);
    expect(pack.forms).toHaveLength(2);
    expect(pack.reports).toHaveLength(1);

    // Imported again under new codes: everything is created, nothing else is touched.
    await upload(page, file, '_c');
    await expect(page).toHaveURL(/\/settings\/form-studio\/packages\/imports\/\d+$/u);
    await expect(page.getByTestId(`package-item-form-${visits}_c`)).toContainText('Создать');
    await expect(page.getByTestId(`package-item-list-${regions}_c`)).toContainText('Создать');
    expect(await applyImport(page)).toBe(200);
    await expect(page.getByTestId('package-import-applied')).toBeVisible();
    await expectCall(admin, 'GET', `/api/v1/studio/forms/${visits}_c`, 200);
    await expectCall(admin, 'GET', `/api/v1/studio/forms/${clients}_c`, 200);

    // (c) A changed file is refused by its checksum.
    const changed = file.buffer.toString('utf8').replace('Визиты пакета', 'Визиты чужого пакета');
    await upload(page, { name: 'changed.json', buffer: Buffer.from(changed, 'utf8') }, '_x');
    await expect(page.getByTestId('package-upload-error')).toContainText('Контрольная сумма не совпадает');

    // (d) A form of the package would change an existing one incompatibly: refused for that form, nothing applied.
    await activate(admin, {
      formatVersion: 4, kind: 'simple-form', module,
      entity: { name: `pclients_${tag}_d`, title: labels('Клиенты пакета'), scope: 'all', titleField: 'title' },
      fields: [field('title', 'text', 'Название', { required: true, maxLength: 120 }), field('region', 'text', 'Регион')],
    });
    await upload(page, file, '_d');
    await expect(page).toHaveURL(/\/settings\/form-studio\/packages\/imports\/\d+$/u);
    const refusedForm = page.getByTestId(`package-item-form-${clients}_d`);
    await expect(refusedForm).toContainText('Отказано');
    await expect(refusedForm).toContainText('несовместимо');
    await expect(page.getByTestId('package-import-refused')).toBeVisible();
    expect(await applyImport(page)).toBe(422);
    await expect(page.getByTestId('package-import-failure')).toContainText('Пакет не применён');
    await expectCall(admin, 'GET', `/api/v1/studio/forms/${visits}_d`, 404);

    // (b) «Заказ со строками и согласованием» from its template, the roles and the mailing's recipient mapped.
    const sellers = await role(admin, `Торговые представители ${tag}`);
    const approvers = await role(admin, `Согласующие ${tag}`);
    const seller = await person(browser, admin, sellers, `s${tag}`);
    const approver = await person(browser, admin, approvers, `a${tag}`);
    contexts.push(seller.context, approver.context);
    await page.goto('/settings/form-studio/packages');
    await page.getByTestId('package-template-create-order-approval').click();
    await expect(page).toHaveURL(/\/settings\/form-studio\/packages\/imports\/\d+$/u);
    await expect(page.getByTestId('package-item-automation-order-approval')).toContainText('приостановленным');
    await chooseOption(page.getByTestId('package-map-seller').getByRole('combobox'), String(sellers));
    await chooseOption(page.getByTestId('package-map-approver').getByRole('combobox'), String(approvers));
    await addUser(page, page.getByTestId('package-map-mailing-recipients'), approver.login);
    expect(await applyImport(page)).toBe(200);
    await expect(page.getByTestId('package-import-applied')).toBeVisible();
    const automation = ((await expectCall(admin, 'GET', '/api/v1/automations?form=studio.orders', 200)).body as {
      items: { id: number; active: boolean; name: string }[];
    }).items.find(item => item.name === 'Согласование заказа');
    expect(automation?.active).toBe(false);

    // The automation is paused: an order sent for approval gets no date.
    const client = ((await expectCall(seller.context, 'POST', '/api/v1/entities/studio.clients', 201, {
      title: `Клиент ${tag}`, region: 'tashkent',
    })).body as { id: number }).id;
    const order = async () => ((await expectCall(seller.context, 'POST', '/api/v1/entities/studio.orders', 201, {
      client, region: 'tashkent', items: [{ product: 'tea', qty: '2', price: '10' }],
    })).body as { id: number; revision: number });
    const read = async (id: number) => (await expectCall(seller.context, 'GET', `/api/v1/entities/studio.orders/${id}`, 200)).body as {
      submitted?: string | null; revision: number; docStatus: string;
    };
    const first = await order();
    await expectCall(seller.context, 'POST', `/api/v1/entities/studio.orders/${first.id}/actions/submit`, 200, {}, `"${(await read(first.id)).revision}"`);
    await page.waitForTimeout(6_000);
    expect((await read(first.id)).submitted ?? null).toBeNull();

    // Switched on by the administrator, it dates the next order sent for approval; the approver approves it.
    await page.goto('/settings/form-studio/automations?form=studio.orders');
    const card = page.getByTestId('automation').filter({ hasText: 'Согласование заказа' });
    const switched = page.waitForResponse(response => response.request().method() === 'PUT'
      && new URL(response.url()).pathname === `/api/v1/automations/${automation?.id}`);
    await card.getByTestId('automation-toggle').click();
    expect((await switched).status()).toBe(200);
    const second = await order();
    await expectCall(seller.context, 'POST', `/api/v1/entities/studio.orders/${second.id}/actions/submit`, 200, {}, `"${(await read(second.id)).revision}"`);
    await expect.poll(async () => (await read(second.id)).submitted ?? null, { timeout: 60_000, intervals: [1_000, 2_000] }).not.toBeNull();
    const pending = await read(second.id);
    await expectCall(approver.context, 'POST', `/api/v1/entities/studio.orders/${second.id}/actions/approve`, 200, {}, `"${pending.revision}"`);
    expect((await read(second.id)).docStatus).toBe('approved');

    // (e) Without the right: 403.
    await expectCall(seller.context, 'GET', '/api/v1/studio/templates', 403);
    await expectCall(seller.context, 'POST', '/api/v1/studio/packages/imports', 403, pack);
    assertNoPageErrors();
  } finally {
    for (const context of contexts) await context.close();
    await call(admin, 'PUT', '/api/v1/modules/studio/enabled', { enabled: wasEnabled });
  }
});
