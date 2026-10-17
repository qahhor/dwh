import { randomBytes } from 'node:crypto';

import { expect, test, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';
import { openEntity } from '../../../support/entity-page.js';
import { fieldName } from '../../../support/fields.js';
import { chooseLiveFormDelivery } from '../../../support/live-form-studio.js';
import { clearSecret, fillSecret } from '../../../support/secret.js';
import { inviteUser } from '../../../support/users.js';
import { xlsxRows } from '../../../support/xlsx-read.js';

// ADR-0041, owner decision 4: the reference forms of stage 1 built by an administrator without a developer — a reference
// list kept on its page, a visit with a client, a date, a result and a comment seen by org unit, a client request with a
// type from the list and a responsible user. No intercepted API responses: the real server answers every step.

type Answer = { status: number; body: unknown; etag: string | null };

async function call(context: BrowserContext, method: string, path: string, data?: unknown, ifMatch?: string): Promise<Answer> {
  const csrf = (await context.cookies()).find(cookie => cookie.name === 'XSRF-TOKEN');
  if (!csrf) throw new Error('The signed-in context has no CSRF cookie');
  const headers: Record<string, string> = { 'X-XSRF-TOKEN': csrf.value };
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

/** A live form activated through the real package and activation API (ADR-0038). */
async function activate(context: BrowserContext, declaration: unknown): Promise<string> {
  const prepared = await expectCall(context, 'POST', '/api/v1/form-studio/new-forms/prepare', 200, declaration);
  const activated = await expectCall(context, 'POST', '/api/v1/studio/forms', 201, prepared.body);
  return (activated.body as { entityCode: string }).entityCode;
}

/** Chooses an option of an smt-select: its options live in the overlay listbox. */
async function choose(page: Page, trigger: Locator, option: string | RegExp): Promise<void> {
  await trigger.click();
  const listbox = page.locator('.cdk-overlay-container [role="listbox"]').last();
  await listbox.getByRole('option', { name: option }).first().click();
  await expect(listbox).toBeHidden();
}

/** Picks a row of a paged reference (smt-data-select) by typing a part of its name. */
async function pick(page: Page, field: Locator, search: string, option: string | RegExp): Promise<void> {
  await field.getByRole('combobox').first().click();
  await page.keyboard.type(search);
  const listbox = page.locator('.cdk-overlay-container [role="listbox"]').last();
  await listbox.getByRole('option', { name: option }).first().click();
}

/** A field box of the form editor by its number. */
function fieldBox(page: Page, index: number): Locator {
  return page.getByTestId(`new-field-${index}`);
}

async function addField(page: Page, index: number, id: string, type: string, name: string): Promise<Locator> {
  await page.getByTestId('new-form-add').click();
  const box = fieldBox(page, index);
  await box.getByRole('textbox', { name: fieldName('Идентификатор поля') }).fill(id);
  await choose(page, box.getByRole('combobox', { name: 'Тип поля' }), type);
  await box.getByRole('textbox', { name: fieldName('Русский') }).first().fill(name);
  await box.getByRole('textbox', { name: fieldName('Узбекский') }).first().fill(name);
  await box.getByRole('textbox', { name: fieldName('Английский') }).first().fill(name);
  return box;
}

async function setOption(box: Locator, index: number, code: string, name: string): Promise<void> {
  const option = box.getByTestId(new RegExp(`^field-option-\\d+-${index}$`, 'u'));
  await option.getByRole('textbox', { name: fieldName('Код варианта') }).fill(code);
  await option.getByRole('textbox', { name: fieldName('Русский') }).fill(name);
  await option.getByRole('textbox', { name: fieldName('Узбекский') }).fill(name);
  await option.getByRole('textbox', { name: fieldName('Английский') }).fill(name);
}

/** Creates a reference list with its values on its own page, as an administrator does. */
async function createList(page: Page, code: string, items: [string, string][]): Promise<void> {
  await page.goto('/settings/form-studio/lists');
  await page.getByTestId('studio-lists-new').click();
  const dialog = page.getByRole('dialog', { name: 'Новый справочник' });
  await dialog.getByRole('textbox', { name: fieldName('Код') }).fill(code);
  await dialog.getByRole('textbox', { name: fieldName('Русский') }).fill('Типы заявок');
  await dialog.getByRole('textbox', { name: fieldName('Узбекский') }).fill('Ariza turlari');
  await dialog.getByRole('textbox', { name: fieldName('Английский') }).fill('Request types');
  await dialog.getByTestId('form-submit').click();
  await expect(dialog).toBeHidden();
  const open = page.getByTestId('studio-list-open');
  await expect(open).toContainText('Типы заявок');
  for (const [item, name] of items) {
    await page.getByTestId('studio-list-add-item').click();
    const itemDialog = page.getByRole('dialog', { name: 'Добавить значение' });
    await itemDialog.getByRole('textbox', { name: fieldName('Код') }).fill(item);
    await itemDialog.getByRole('textbox', { name: fieldName('Русский') }).fill(name);
    await itemDialog.getByRole('textbox', { name: fieldName('Узбекский') }).fill(`${name} uz`);
    await itemDialog.getByRole('textbox', { name: fieldName('Английский') }).fill(`${name} en`);
    await itemDialog.getByTestId('form-submit').click();
    await expect(itemDialog).toBeHidden();
    await expect(open).toContainText(name);
  }
}

/** Builds the visit form in the editor: a client of another live form, a date, a result, a comment; seen by org unit. */
async function buildVisits(page: Page, name: string, clients: string): Promise<string> {
  await page.goto('/settings/form-studio/new');
  await chooseLiveFormDelivery(page);
  const entity = page.getByRole('group', { name: 'Новая форма', exact: true });
  await entity.getByTestId('new-entity-name').fill(name);
  await entity.getByRole('textbox', { name: fieldName('Русский') }).fill('Визиты торговых представителей');
  await entity.getByRole('textbox', { name: fieldName('Узбекский') }).fill('Tashriflar');
  await entity.getByRole('textbox', { name: fieldName('Английский') }).fill('Visits');
  await choose(page, page.getByTestId('new-form-scope').getByRole('combobox'), 'Подразделение и подчинённые');

  const client = await addField(page, 0, 'client', 'Ссылка', 'Клиент');
  await choose(page, client.getByTestId('field-target-kind-0').getByRole('combobox'), 'Другая форма');
  await choose(page, client.getByTestId('field-target-0').getByRole('combobox'), new RegExp(clients.replace('.', '\\.'), 'u'));
  await addField(page, 1, 'visitdate', 'Дата', 'Дата визита');
  const result = await addField(page, 2, 'result', 'Выбор из списка', 'Результат');
  await setOption(result, 0, 'done', 'Заказ получен');
  await result.getByTestId('field-add-option-2').click();
  await setOption(result, 1, 'later', 'Перенесён');
  await addField(page, 3, 'comment', 'Многострочный текст', 'Комментарий');

  const prepared = page.waitForResponse(response => response.url().endsWith('/api/v1/form-studio/new-forms/prepare'));
  await page.getByTestId('new-form-prepare').click();
  expect((await prepared).status()).toBe(200);
  await expect(page.locator('.new-form-checksum')).toHaveText(/^[a-f0-9]{64}$/u);
  const activation = page.waitForResponse(response => response.request().method() === 'POST'
    && response.url().endsWith('/api/v1/studio/forms'));
  await page.getByTestId('new-form-activate').click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Активировать форму', exact: true }).click();
  expect((await activation).status()).toBe(201);
  await expect(page.getByTestId('new-form-activated')).toContainText(`studio.${name}`);
  return `studio.${name}`;
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

/** A role that fills visits, an invited user of `unit` holding it and signed in in a context of its own. */
async function fieldUser(browser: Browser, admin: BrowserContext, unit: number, roleId: number, tag: string) {
  const login = `refs-${tag}-${randomBytes(4).toString('hex')}`;
  const password = `Qa!7${randomBytes(8).toString('hex')}`;
  const user = await inviteUser(admin, { name: `Торговый ${tag}`, login, email: `${login}@example.invalid` }, password, [roleId]);
  const assignments = (await expectCall(admin, 'GET', `/api/v1/iam/org-units/users/${user.id}`, 200)).body as { revision: number };
  await expectCall(admin, 'PUT', `/api/v1/iam/org-units/users/${user.id}`, 204, { orgUnitIds: [unit] }, `"${assignments.revision}"`);
  const record = await expectCall(admin, 'GET', `/api/v1/entities/md.users/${user.id}`, 200);
  await expectCall(admin, 'PATCH', `/api/v1/entities/md.users/${user.id}`, 200, { orgUnitId: unit }, record.etag ?? undefined);
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

test('an administrator builds the visit and the client request forms with references and reads names, not keys', async ({ page, browser }) => {
  test.setTimeout(240_000);
  await loginToInstance(page);
  const admin = page.context();
  const assertNoPageErrors = collectPageErrors(page);
  const tag = Date.now().toString(36);
  const modules = (await expectCall(admin, 'GET', '/api/v1/modules', 200)).body as { code: string; status: string }[];
  const wasEnabled = modules.find(module => module.code === 'studio')?.status === 'ACTIVE';
  await expectCall(admin, 'PUT', '/api/v1/modules/studio/enabled', 200, { enabled: true });
  const contexts: BrowserContext[] = [];
  try {
    const kinds = `kinds_${tag}`;
    await createList(page, kinds, [['repair', 'Ремонт'], ['delivery', 'Доставка']]);

    const clients = await activate(admin, {
      formatVersion: 3, kind: 'simple-form',
      module: { code: 'studio', title: labels('Созданные формы') },
      entity: { name: `clients_${tag}`, title: labels('Клиенты'), scope: 'all' },
      fields: [{ id: 'phone', type: 'phone', labels: labels('Телефон'), required: false, listed: true }],
    });
    const visits = await buildVisits(page, `visits_${tag}`, clients);
    const requests = await activate(admin, {
      formatVersion: 3, kind: 'simple-form',
      module: { code: 'studio', title: labels('Созданные формы') },
      entity: { name: `requests_${tag}`, title: labels('Заявки клиентов') },
      fields: [
        { id: 'client', type: 'ref', labels: labels('Клиент'), required: true, listed: true, target: { kind: 'form', code: clients } },
        { id: 'kind', type: 'ref', labels: labels('Тип заявки'), required: true, listed: true, target: { kind: 'list', code: kinds } },
        { id: 'responsible', type: 'user', labels: labels('Ответственный'), required: false, listed: true },
      ],
    });
    const client = (await expectCall(admin, 'POST', `/api/v1/entities/${clients}`, 201, { name: `Ромашка ${tag}`, code: 'romashka' })).body as { id: number };

    // A client request through the record form: the client and the responsible user are picked, the type chosen.
    const me = (await expectCall(admin, 'GET', '/api/v1/auth/me', 200)).body as { user?: { name?: string } };
    const entity = await openEntity(page, requests, 'Заявки клиентов');
    await page.getByRole('link', { name: 'Создать', exact: true }).click();
    await entity.fillField('name', 'Заявка на ремонт');
    await entity.fillField('code', 'r1');
    await pick(page, entity.form.locator('.entity-field[data-field="client"]'), 'Ромашка', new RegExp(`Ромашка ${tag}`, 'u'));
    await choose(page, entity.form.locator('.entity-field[data-field="kind"]').getByRole('combobox'), 'Ремонт');
    const myName = me.user?.name ?? '';
    if (myName) await pick(page, entity.form.locator('.entity-field[data-field="responsible"]'), myName.slice(0, 4), myName);
    expect((await entity.save()).status()).toBe(201);
    await expect(page.locator('main')).toContainText(`Ромашка ${tag}`);
    await expect(page.locator('main')).toContainText('Ремонт');
    await page.goto(`/e/${requests}`);
    const list = page.locator('main');
    await expect(list).toContainText(`Ромашка ${tag}`);
    await expect(list).toContainText('Ремонт');

    const rows = await exportRows(admin, requests, ['name', 'client', 'kind', 'responsible']);
    expect(rows[1].slice(0, 3)).toEqual(['Заявка на ремонт', `Ромашка ${tag}`, 'Ремонт']);
    if (myName) expect(rows[1][3]).toBe(myName);

    // A visit is seen only inside the org unit of its author.
    const units = (await expectCall(admin, 'GET', '/api/v1/iam/org-units', 200)).body as { id: number; parentId: number | null }[];
    const parent = units.find(unit => unit.parentId === null)?.id
      ?? ((await expectCall(admin, 'POST', '/api/v1/iam/org-units', 201, { parentId: null, code: `refs-root-${tag}`, name: `Компания ${tag}`, kind: 'department', orderNo: 1 })).body as { id: number }).id;
    const unitA = (await expectCall(admin, 'POST', '/api/v1/iam/org-units', 201, { parentId: parent, code: `refs-a-${tag}`, name: `Филиал А ${tag}`, kind: 'branch', orderNo: 1 })).body as { id: number };
    const unitB = (await expectCall(admin, 'POST', '/api/v1/iam/org-units', 201, { parentId: parent, code: `refs-b-${tag}`, name: `Филиал Б ${tag}`, kind: 'branch', orderNo: 2 })).body as { id: number };
    const role = (await expectCall(admin, 'POST', '/api/v1/iam/roles', 201, { name: `Торговые ${tag}`, orderNo: 9100 })).body as { id: number; revision?: number };
    const granted = await expectCall(admin, 'PUT', `/api/v1/iam/roles/${role.id}/permissions`, 204, [
      { formCode: 'md.profile', action: 'view' },
      { formCode: clients, action: 'view' },
      { formCode: visits, action: 'view' },
      { formCode: visits, action: 'create' },
    ], `"${role.revision ?? 1}"`);
    await expectCall(admin, 'PUT', `/api/v1/iam/org-units/roles/${role.id}/rule`, 204, { rule: 'UNITS' }, granted.etag ?? `"${(role.revision ?? 1) + 1}"`);
    const author = await fieldUser(browser, admin, unitA.id, role.id, `a${tag}`);
    const stranger = await fieldUser(browser, admin, unitB.id, role.id, `b${tag}`);
    contexts.push(author.context, stranger.context);
    const visit = (await expectCall(author.context, 'POST', `/api/v1/entities/${visits}`, 201, {
      name: 'Визит 1', code: 'v1', client: client.id, visitdate: '2026-10-17', result: 'done', comment: 'Заказ на неделю',
    })).body as { id: number; orgUnit: number };
    expect(visit.orgUnit).toBe(unitA.id);
    const seen = (await expectCall(author.context, 'GET', `/api/v1/entities/${visits}`, 200)).body as { items: { id: number; labels?: Record<string, string> }[] };
    expect(seen.items.map(item => item.id)).toEqual([visit.id]);
    expect(seen.items[0].labels?.client).toBe(`Ромашка ${tag}`);
    const hidden = (await expectCall(stranger.context, 'GET', `/api/v1/entities/${visits}`, 200)).body as { items: unknown[] };
    expect(hidden.items).toEqual([]);
    await expectCall(stranger.context, 'GET', `/api/v1/entities/${visits}/${visit.id}`, 404);
    await author.page.goto(`/e/${visits}`);
    await expect(author.page.locator('main')).toContainText('Заказ получен');
    await expect(author.page.locator('main')).toContainText(`Ромашка ${tag}`);
    assertNoPageErrors();
  } finally {
    for (const context of contexts) await context.close();
    await call(admin, 'PUT', '/api/v1/modules/studio/enabled', { enabled: wasEnabled });
  }
});
