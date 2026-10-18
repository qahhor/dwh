import { readFile } from 'node:fs/promises';

import { expect, test, type BrowserContext, type Locator, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';
import { fieldName } from '../../../support/fields.js';
import { chooseLiveFormDelivery } from '../../../support/live-form-studio.js';
import { chooseOption } from '../../../support/select.js';
import { xlsxRows } from '../../../support/xlsx-read.js';

// ADR-0041, owner decision 4, the reference report "by regions" of stage 1: an administrator keeps a reference list of
// regions, builds a client form named by its own field and laid out on two tabs, reports the clients by region with a
// count and a sum ordered by the count and cut to a top, opens the saved report from the reports catalog, drills into
// the records of one region and exports the report with the region names. The real server answers every step.

type Answer = { status: number; body: unknown };

async function call(context: BrowserContext, method: string, path: string, data?: unknown): Promise<Answer> {
  const csrf = (await context.cookies()).find(cookie => cookie.name === 'XSRF-TOKEN');
  if (!csrf) throw new Error('The signed-in context has no CSRF cookie');
  const response = await context.request.fetch(path, {
    method, headers: { 'X-XSRF-TOKEN': csrf.value }, maxRedirects: 0, maxRetries: 0, ...(data === undefined ? {} : { data }),
  });
  try {
    const text = await response.text();
    return { status: response.status(), body: text ? JSON.parse(text) as unknown : undefined };
  } finally { await response.dispose(); }
}

async function expectCall(context: BrowserContext, method: string, path: string, expected: number, data?: unknown) {
  const answer = await call(context, method, path, data);
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

/** The reference list of regions, kept on its page as an administrator keeps it. */
async function createRegions(page: Page, code: string, items: [string, string][]): Promise<void> {
  await page.goto('/settings/form-studio/lists');
  await page.getByTestId('studio-lists-new').click();
  const dialog = page.getByRole('dialog', { name: 'Новый справочник' });
  await dialog.getByRole('textbox', { name: fieldName('Код') }).fill(code);
  await name(dialog, 'Регионы');
  await dialog.getByTestId('form-submit').click();
  await expect(dialog).toBeHidden();
  const open = page.getByTestId('studio-list-open');
  await expect(open).toContainText('Регионы');
  for (const [item, label] of items) {
    await page.getByTestId('studio-list-add-item').click();
    const itemDialog = page.getByRole('dialog', { name: 'Добавить значение' });
    await itemDialog.getByRole('textbox', { name: fieldName('Код') }).fill(item);
    await itemDialog.getByRole('textbox', { name: fieldName('Русский') }).fill(label);
    await itemDialog.getByRole('textbox', { name: fieldName('Узбекский') }).fill(`${label} uz`);
    await itemDialog.getByRole('textbox', { name: fieldName('Английский') }).fill(`${label} en`);
    await itemDialog.getByTestId('form-submit').click();
    await expect(itemDialog).toBeHidden();
    await expect(open).toContainText(label);
  }
}

/**
 * The client form in the editor: a title, a region of the list and an amount; named by its title, the title and the
 * region on the first tab, the amount on the second.
 */
async function buildClients(page: Page, formName: string, regions: string): Promise<string> {
  await page.goto('/settings/form-studio/new');
  await chooseLiveFormDelivery(page);
  const entity = page.getByRole('group', { name: 'Новая форма', exact: true });
  await entity.getByTestId('new-entity-name').fill(formName);
  await name(entity, 'Клиенты по регионам');

  const title = await addField(page, 0, 'title', 'Текст', 'Название клиента');
  await title.getByText('Обязательное', { exact: true }).click();
  const region = await addField(page, 1, 'region', 'Ссылка на справочник или форму', 'Регион');
  await choose(page, region.getByTestId('field-target-1').getByRole('combobox'), new RegExp(regions, 'u'));
  await region.getByText('Обязательное', { exact: true }).click();
  await addField(page, 2, 'amount', 'Число', 'Сумма заказов');

  const layout = page.getByTestId('form-layout');
  await chooseOption(layout.getByTestId('form-title-field').getByRole('combobox'), 'title');
  await layout.getByTestId('form-layout-toggle').getByRole('checkbox').click();
  await expect(layout.getByTestId('form-section-0')).toBeVisible();
  await layout.getByTestId('form-section-add').click();
  await layout.getByTestId('form-tabs-toggle').getByRole('checkbox').click();
  await expect(layout.getByTestId('form-tab-1')).toBeVisible();
  await chooseOption(layout.getByTestId('form-place-amount').getByRole('combobox'), 'section1');
  await chooseOption(layout.getByTestId('form-section-tab-1').getByRole('combobox'), 'tab2');
  await name(layout.getByTestId('form-section-1'), 'Финансы');
  await name(layout.getByTestId('form-tab-1'), 'Деньги');

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
    declaration: { formatVersion: number; entity: { titleField?: string }; layout?: { tabs?: unknown[] } };
  };
  expect(stored.declaration.formatVersion).toBe(4);
  expect(stored.declaration.entity.titleField).toBe('title');
  expect(stored.declaration.layout?.tabs).toHaveLength(2);
  return `studio.${formName}`;
}

test('the reference report by regions: a list, a laid-out form, an ordered top report, the catalog, a drill-down and the XLSX', async ({ page }) => {
  test.setTimeout(300_000);
  await loginToInstance(page);
  const admin = page.context();
  const assertNoPageErrors = collectPageErrors(page);
  const tag = Date.now().toString(36);
  const modules = (await expectCall(admin, 'GET', '/api/v1/modules', 200)).body as { code: string; status: string }[];
  const wasEnabled = modules.find(module => module.code === 'studio')?.status === 'ACTIVE';
  await expectCall(admin, 'PUT', '/api/v1/modules/studio/enabled', 200, { enabled: true });
  try {
    const regions = `regions_${tag}`;
    const names: [string, string][] = [
      ['north', 'Север'], ['south', 'Юг'], ['east', 'Восток'], ['west', 'Запад'], ['center', 'Центр'], ['hills', 'Горы'],
    ];
    await createRegions(page, regions, names);
    const clients = await buildClients(page, `clients_${tag}`, regions);

    // One client through the record form: the title and the region on the first tab, the amount on the second.
    await page.goto(`/e/${clients}/new`);
    const form = page.locator('smt-entity-form');
    const tabs = form.locator('.entity-form-tabs');
    await expect(tabs.getByRole('tab')).toHaveText(['Вкладка 1', 'Деньги']);
    await form.locator('.entity-field[data-field="title"] input').fill(`Альфа ${tag}`);
    await choose(page, form.locator('.entity-field[data-field="region"]').getByRole('combobox'), 'Север');
    await expect(form.locator('.entity-field[data-field="amount"]')).toBeHidden();
    await tabs.getByRole('tab', { name: 'Деньги' }).click();
    await form.locator('.entity-field[data-field="amount"] input').fill('100');
    const created = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === `/api/v1/entities/${clients}`);
    await page.locator('smt-entity-edit-page').getByTestId('form-submit').click();
    expect((await created).status()).toBe(201);
    await expect(page.getByRole('heading', { level: 1, name: `Альфа ${tag}` })).toBeVisible();

    // The other clients through the API: north 3, south 2, the four others one each.
    const rows: [string, string, number][] = [
      ['Бета', 'north', 50], ['Гамма', 'north', 25], ['Дельта', 'south', 10], ['Эпсилон', 'south', 15],
      ['Зета', 'east', 1], ['Эта', 'west', 2], ['Тета', 'center', 3], ['Йота', 'hills', 4],
    ];
    for (const [title, region, amount] of rows) {
      await expectCall(admin, 'POST', `/api/v1/entities/${clients}`, 201, { title: `${title} ${tag}`, region, amount });
    }

    // The report by regions: a count and the sum of the amounts, by the count descending, the top five.
    await page.goto(`/e/${clients}`);
    await page.getByTestId('entity-mode').getByRole('radio', { name: 'Отчёт' }).click();
    await chooseOption(page.getByLabel('Группировать по'), 'region');
    await page.getByTestId('report-add-measure').click();
    await chooseOption(page.getByLabel('Порядок групп'), 'measure:0:desc');
    const ordered = page.waitForResponse(response => /\/api\/v1\/entities\/studio\.[^/]+\/report\?/u.test(response.url())
      && decodeURIComponent(response.url()).includes('"direction":"desc"')
      && new URL(response.url()).searchParams.get('limit') === '5');
    await chooseOption(page.getByLabel('Сколько групп показать'), '5');
    expect((await ordered).status()).toBe(200);
    const table = page.getByTestId('report-table');
    await expect(table.getByRole('rowheader')).toHaveCount(5);
    await expect(table.getByRole('rowheader').nth(0)).toHaveText('Север');
    await expect(table.getByRole('rowheader').nth(1)).toHaveText('Юг');
    await expect(table.getByRole('row').filter({ hasText: 'Север' })).toContainText('175');

    await page.getByTestId('report-save-as').click();
    const dialog = page.getByRole('dialog');
    const reportName = `По регионам ${tag}`;
    await dialog.getByTestId('report-name').fill(reportName);
    const saved = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === `/api/v1/list-views/${clients}`);
    await dialog.getByTestId('form-submit').click();
    expect((await saved).status()).toBe(201);
    await expect(dialog).toBeHidden();

    // The catalog finds it by a part of its name, as the person's own, and opens it on the list's report tab.
    await page.goto('/reports');
    await page.getByTestId('reports-search').fill(tag);
    const item = page.getByTestId('reports-item').filter({ hasText: reportName });
    await expect(item).toBeVisible();
    await expect(item).toContainText('Мой');
    await item.getByTestId('reports-open').click();
    await expect(page).toHaveURL(new RegExp(`/e/${clients.replace('.', '\\.')}\\?mode=report&report=\\d+`, 'u'));
    await expect(page.getByTestId('report-source')).toContainText(reportName);
    await expect(page.getByTestId('report-table').getByRole('rowheader').first()).toHaveText('Север');

    // The XLSX of the saved report writes the region names, in the report's order and top.
    const download = page.waitForEvent('download');
    await page.getByTestId('report-export').click();
    const file = await readFile(await (await download).path());
    const sheet = xlsxRows(file);
    expect(sheet).toHaveLength(6);
    expect(sheet.slice(1, 3).map(row => row[0])).toEqual(['Север', 'Юг']);
    expect(sheet.flat()).not.toContain('north');

    // A drill-down from the north row opens the list of the north clients only, under the reader's scope.
    await page.getByTestId('report-table').getByRole('row').filter({ hasText: 'Север' }).getByTestId('report-drill').click();
    await expect(page).toHaveURL(/filter=/u);
    const list = page.locator('main');
    await expect(list).toContainText(`Бета ${tag}`);
    await expect(list).toContainText(`Альфа ${tag}`);
    await expect(list).not.toContainText(`Дельта ${tag}`);
    await expect(list).not.toContainText(`Зета ${tag}`);
    assertNoPageErrors();
  } finally {
    await call(admin, 'PUT', '/api/v1/modules/studio/enabled', { enabled: wasEnabled });
  }
});
