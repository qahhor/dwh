import { expect, test, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';
import { workbook, XLSX_TYPE } from '../../../support/xlsx.js';

/*
 * The import of entity records (ADR-0032 10.1; plan 10/10, item 5.8) on the general list /e/ms.task_types, without web
 * code of the task types: the template is downloaded, a filled file is uploaded, a dry run shows the refused row and
 * writes nothing, the load creates the valid row through the runtime — its hooks give it its place — and the report of
 * the refused row can be downloaded. The type the test creates is deleted at the end.
 */

async function csrfHeaders(page: Page): Promise<Record<string, string>> {
  const cookie = (await page.context().cookies(page.url())).find(value => value.name === 'XSRF-TOKEN');
  if (!cookie?.value) throw new Error('The signed-in page has no CSRF cookie');
  return { 'X-XSRF-TOKEN': cookie.value };
}

interface TaskTypeRecord {
  id: number;
  code: string;
  name: string;
  sortOrder: number | null;
}

async function typesNamed(page: Page, name: string): Promise<TaskTypeRecord[]> {
  const filter = JSON.stringify([{ field: 'name', op: 'eq', value: name }]);
  const response = await page.request.get(`/api/v1/entities/ms.task_types?filter=${encodeURIComponent(filter)}`);
  try {
    expect(response.status(), 'the task types list').toBe(200);
    return ((await response.json()) as { items: TaskTypeRecord[] }).items;
  } finally {
    await response.dispose();
  }
}

test('task types are imported from a file: template, dry run, load, report', async ({ page }) => {
  const stamp = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const code = `e2e_${stamp}`;
  const name = `E2E imported type ${stamp}`;

  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page);
  await page.goto('/e/ms.task_types');
  await page.getByTestId('entity-import').click();
  const dialog = page.getByRole('dialog', { name: 'Импорт из файла' });
  await expect(dialog).toBeVisible();

  // The template: an xlsx built from the declaration in the viewer's language.
  const href = await dialog.getByTestId('entity-import-template').getAttribute('href');
  expect(href).toMatch(/^\/api\/v1\/entities\/ms\.task_types\/import-template\?lang=/u);
  const template = await page.request.get(href ?? '');
  try {
    expect(template.status()).toBe(200);
    expect(template.headers()['content-type']).toContain(XLSX_TYPE);
  } finally {
    await template.dispose();
  }

  // A filled file: a valid row and a row whose colour the declaration refuses.
  const file = workbook([
    ['Код', 'Название', 'Цвет'],
    ['code', 'name', 'color'],
    [code, name, ''],
    [`${code}_bad`, `${name} bad`, 'red'],
  ]);
  const uploaded = page.waitForResponse(response =>
    response.request().method() === 'POST' && /\/api\/v1\/files\/upload$/u.test(response.url())
  );
  await dialog.locator('input[type="file"]').setInputFiles({ name: 'types.xlsx', mimeType: XLSX_TYPE, buffer: file });
  expect((await uploaded).ok()).toBe(true);
  await expect(dialog.getByTestId('entity-import-file')).toContainText('types.xlsx');

  // A dry run reports the refused row and writes nothing.
  await dialog.getByTestId('entity-import-dry-run').click();
  await expect(dialog.getByTestId('entity-import-summary')).toContainText('будет создано 1', { timeout: 30_000 });
  const errors = dialog.getByTestId('entity-import-errors');
  await expect(errors).toContainText('color');
  await expect(errors.locator('tbody tr')).toHaveCount(1);
  expect(await typesNamed(page, name)).toHaveLength(0);

  // The load creates the valid row; the report of the refused one can be downloaded.
  await dialog.getByTestId('form-submit').click();
  await expect(dialog.getByTestId('entity-import-summary')).toContainText('Загружено: создано 1', {
    timeout: 30_000,
  });
  const reportHref = await dialog.getByTestId('entity-import-report').getAttribute('href');
  const report = await page.request.get(reportHref ?? '');
  try {
    expect(report.status()).toBe(200);
  } finally {
    await report.dispose();
  }
  const created = await typesNamed(page, name);
  expect(created).toHaveLength(1);
  expect(created[0].code).toBe(code);
  expect(created[0].sortOrder, 'the place the create hook gives').not.toBeNull();

  await dialog.getByRole('button', { name: 'Закрыть' }).last().click();
  await expect(dialog).toBeHidden();
  assertNoPageErrors();

  const removed = await page.request.delete(`/api/v1/entities/ms.task_types/${created[0].id}`, {
    headers: await csrfHeaders(page),
  });
  try {
    expect(removed.status(), 'deleting the imported type').toBe(204);
  } finally {
    await removed.dispose();
  }
});
