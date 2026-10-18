import { randomBytes } from 'node:crypto';

import { expect, test, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';
import { fieldName } from '../../../support/fields.js';
import { chooseLiveFormDelivery } from '../../../support/live-form-studio.js';
import { clearSecret, fillSecret } from '../../../support/secret.js';
import { inviteUser } from '../../../support/users.js';
import { xlsxSheet } from '../../../support/xlsx-read.js';

// ADR-0043, the files of a live form (simple-form format 7): an administrator adds a field of several files to a form
// in the editor; a person uploads a file to a record through the files module — with its progress and its check — and
// sees it in the card; the file is downloaded through its record, and a person who may not read the record gets 404;
// the export writes the file's name. No intercepted API responses.

type Answer = { status: number; body: unknown };

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
    let body: unknown = text;
    try { body = text ? JSON.parse(text) as unknown : undefined; } catch { body = text; }
    return { status: response.status(), body };
  } finally { await response.dispose(); }
}

async function expectCall(context: BrowserContext, method: string, path: string, expected: number, data?: unknown, ifMatch?: string) {
  const answer = await call(context, method, path, data, ifMatch);
  expect(answer.status, `${method} ${path}: ${JSON.stringify(answer.body)}`).toBe(expected);
  return answer;
}

/** Chooses an option of an smt-select by its visible name. */
async function choose(page: Page, trigger: Locator, option: string): Promise<void> {
  await trigger.click();
  const listbox = page.locator('.cdk-overlay-container [role="listbox"]').last();
  await listbox.getByRole('option', { name: option, exact: true }).first().click();
  await expect(listbox).toBeHidden();
}

/** Fills the three labels of a group of the editor with one name. */
async function name(group: Locator, text: string): Promise<void> {
  await group.getByRole('textbox', { name: fieldName('Русский') }).first().fill(text);
  await group.getByRole('textbox', { name: fieldName('Узбекский') }).first().fill(text);
  await group.getByRole('textbox', { name: fieldName('Английский') }).first().fill(text);
}

/** A field box of the editor: its key, type and labels. */
async function describeField(page: Page, box: Locator, id: string, type: string, label: string): Promise<void> {
  await box.getByRole('textbox', { name: fieldName('Идентификатор поля') }).fill(id);
  await choose(page, box.getByRole('combobox', { name: 'Тип поля' }), type);
  await name(box, label);
}

/** The claim in the editor: a required subject and a field of up to two files; the code of the activated form. */
async function buildClaim(page: Page, formName: string): Promise<string> {
  await page.goto('/settings/form-studio/new');
  await chooseLiveFormDelivery(page);
  const entity = page.getByRole('group', { name: 'Новая форма', exact: true });
  await entity.getByTestId('new-entity-name').fill(formName);
  await name(entity, 'Претензия');

  await page.getByTestId('new-form-add').click();
  const subject = page.getByTestId('new-field-0').first();
  await describeField(page, subject, 'subject', 'Текст', 'Тема');
  await subject.getByText('Обязательное', { exact: true }).click();
  await page.getByTestId('new-form-add').click();
  const documents = page.getByTestId('new-field-1').first();
  await describeField(page, documents, 'documents', 'Файл', 'Документы');
  await documents.getByTestId('field-files-multiple-1').getByText('Несколько файлов', { exact: true }).click();
  await expect(documents.getByRole('checkbox', { name: 'Несколько файлов' })).toBeChecked();
  await documents.getByTestId('field-files-max-1').locator('input').fill('2');

  const prepared = page.waitForResponse(response => response.url().endsWith('/api/v1/form-studio/new-forms/prepare'));
  await page.getByTestId('new-form-prepare').click();
  const preparedAnswer = await prepared;
  expect(preparedAnswer.status()).toBe(200);
  await expect(page.locator('.new-form-checksum')).toHaveText(/^[a-f0-9]{64}$/u);
  const activation = page.waitForResponse(response => response.request().method() === 'POST'
    && response.url().endsWith('/api/v1/studio/forms'));
  await page.getByTestId('new-form-activate').click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Активировать форму', exact: true }).click();
  expect((await activation).status()).toBe(201);
  const stored = (await expectCall(page.context(), 'GET', `/api/v1/studio/forms/studio.${formName}`, 200)).body as {
    declaration: { formatVersion: number; fields: { id: string; files?: { multiple: boolean; maxFiles?: number } }[] };
  };
  expect(stored.declaration.formatVersion).toBe(7);
  expect(stored.declaration.fields.find(field => field.id === 'documents')?.files).toEqual({ multiple: true, maxFiles: 2 });
  return `studio.${formName}`;
}

/** A role holding `rights`. */
async function role(admin: BrowserContext, title: string, rights: { formCode: string; action: string }[]): Promise<number> {
  const created = (await expectCall(admin, 'POST', '/api/v1/iam/roles', 201, { name: title, orderNo: 9300 })).body as { id: number; revision?: number };
  await expectCall(admin, 'PUT', `/api/v1/iam/roles/${created.id}/permissions`, 204, [
    { formCode: 'md.profile', action: 'view' }, ...rights,
  ], `"${created.revision ?? 1}"`);
  return created.id;
}

/** An invited user holding `roleId`, signed in in a browser context of its own. */
async function signedIn(browser: Browser, admin: BrowserContext, roleId: number, who: string) {
  const login = `claim-${who}-${randomBytes(4).toString('hex')}`;
  const password = `Qa!7${randomBytes(8).toString('hex')}`;
  await inviteUser(admin, { name: `Пользователь ${who}`, login, email: `${login}@example.invalid` }, password, [roleId]);
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
  return { context, page };
}

/** Waits until the queued export is written and answers the rows of its first sheet. */
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
    return xlsxSheet(await file.body(), 1);
  } finally { await file.dispose(); }
}

test('the files of a live form: uploaded to a record, read through it by its readers only, named in the export', async ({ page, browser }) => {
  test.setTimeout(300_000);
  await loginToInstance(page);
  const admin = page.context();
  const assertNoPageErrors = collectPageErrors(page);
  const tag = Date.now().toString(36);
  const modules = (await expectCall(admin, 'GET', '/api/v1/modules', 200)).body as { code: string; status: string }[];
  const wasEnabled = modules.find(module => module.code === 'studio')?.status === 'ACTIVE';
  await expectCall(admin, 'PUT', '/api/v1/modules/studio/enabled', 200, { enabled: true });
  const contexts: BrowserContext[] = [];
  try {
    const claims = await buildClaim(page, `claims_${tag}`);
    const writers = await role(admin, `Претензии ${tag}`, [
      { formCode: claims, action: 'view' },
      { formCode: claims, action: 'create' },
      { formCode: claims, action: 'update' },
      { formCode: 'mf.files', action: 'upload' },
    ]);
    const others = await role(admin, `Без претензий ${tag}`, [{ formCode: 'mf.files', action: 'upload' }]);
    const writer = await signedIn(browser, admin, writers, `w${tag}`);
    const other = await signedIn(browser, admin, others, `o${tag}`);
    contexts.push(writer.context, other.context);

    // The writer fills the claim and attaches a file: the field counts its files against the limit.
    await writer.page.goto(`/e/${claims}/new`);
    const form = writer.page.locator('smt-entity-form');
    // The editor names the records of a new form by the system name and code.
    await form.locator('.entity-field[data-field="name"] input').fill(`Претензия ${tag}`);
    await form.locator('.entity-field[data-field="code"] input').fill(`claim-${tag}`);
    await form.locator('.entity-field[data-field="subject"] input').fill(`Претензия ${tag}`);
    const documents = form.locator('[data-field="documents"]');
    await expect(documents).toContainText('Файлов: 0 из 2');
    await documents.locator('input[type="file"]').setInputFiles({
      name: 'act.txt', mimeType: 'text/plain', buffer: Buffer.from(`Акт ${tag}`, 'utf8'),
    });
    await expect(documents.locator('.file-chosen')).toContainText('act.txt');
    await expect(documents).toContainText('Файлов: 1 из 2');
    const created = writer.page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === `/api/v1/entities/${claims}`, { timeout: 30_000 });
    await writer.page.locator('smt-entity-edit-page').getByTestId('form-submit').click();
    const answer = await created;
    expect(answer.status(), await answer.text()).toBe(201);
    const claim = await answer.json() as { id: number; documents: { id: string; name: string }[] };
    expect(claim.documents.map(file => file.name)).toEqual(['act.txt']);

    // The card names the file and opens it through the record.
    await writer.page.goto(`/e/${claims}/${claim.id}`);
    const link = writer.page.locator('a.entity-card-file', { hasText: 'act.txt' });
    await expect(link).toBeVisible();
    const href = await link.getAttribute('href');
    expect(href).toBe(`/api/v1/entities/${claims}/${claim.id}/files/${claim.documents[0].id}`);
    const download = await writer.context.request.get(href ?? '');
    try {
      expect(download.status()).toBe(200);
      expect(await download.text()).toBe(`Акт ${tag}`);
    } finally { await download.dispose(); }

    // Nobody else reads it: neither through the record nor through the files module.
    await expectCall(other.context, 'GET', href ?? '', 404);
    await expectCall(other.context, 'GET', `/api/v1/entities/${claims}/${claim.id}`, 404);
    const direct = await call(other.context, 'GET', `/api/v1/files/${claim.documents[0].id}/download`);
    expect([403, 404]).toContain(direct.status);

    // The export writes the file's name, not a link.
    const rows = await exportRows(admin, claims, ['subject', 'documents']);
    expect(rows.some(row => row.includes(`Претензия ${tag}`) && row.includes('act.txt'))).toBe(true);
    assertNoPageErrors();
  } finally {
    for (const context of contexts) await context.close();
    await call(admin, 'PUT', '/api/v1/modules/studio/enabled', { enabled: wasEnabled });
  }
});
