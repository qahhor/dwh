import { expect, test, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors, uniqueRunName } from '../../../support/diagnostics.js';
import { openEntity } from '../../../support/entity-page.js';
import { fillSimpleForm } from '../../../support/new-form-studio.js';
import { chooseLiveFormDelivery } from '../../../support/live-form-studio.js';

async function moduleState(page: Page, enabled: boolean): Promise<void> {
  await call(page, 'PUT', '/api/v1/modules/studio/enabled', 200, { enabled });
}

/** One API call of the signed-in page, with its CSRF token; answers the JSON body, if any. */
async function call(page: Page, method: string, path: string, expected: number, data?: unknown): Promise<unknown> {
  const csrf = (await page.context().cookies(page.url())).find(cookie => cookie.name === 'XSRF-TOKEN');
  if (!csrf) throw new Error('The signed-in page has no CSRF cookie');
  const response = await page.request.fetch(path, {
    method, headers: { 'X-XSRF-TOKEN': csrf.value }, ...(data === undefined ? {} : { data }),
  });
  try {
    expect(response.status(), `${method} ${path}`).toBe(expected);
    const text = await response.text();
    return text ? JSON.parse(text) as unknown : undefined;
  } finally { await response.dispose(); }
}

/** A live form activated through the real package and activation API (ADR-0038). */
async function activateLiveForm(page: Page, name: string): Promise<string> {
  const labels = (text: string) => ({ ru: text, uz: text, en: text });
  const declaration = {
    formatVersion: 1, kind: 'simple-form',
    module: { code: 'studio', title: labels('Созданные формы') },
    entity: { name, title: labels(`Карантин ${name}`) },
    fields: [{ id: 'note', type: 'text', labels: labels('Заметка'), required: false, listed: true, maxLength: 200 }],
  };
  const prepared = await call(page, 'POST', '/api/v1/form-studio/new-forms/prepare', 200, declaration);
  await call(page, 'POST', '/api/v1/studio/forms', 201, prepared);
  return `studio.${name}`;
}

// ADR-0038: the real package, activation and generic runtime must agree; no intercepted API responses.
test('Studio prepares a real package and respects activation before creating and changing a record', async ({ page }) => {
  test.setTimeout(90_000);
  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page);
  const modulesResponse = await page.request.get('/api/v1/modules');
  expect(modulesResponse.status()).toBe(200);
  const modules = await modulesResponse.json() as { code: string; status: string }[];
  await modulesResponse.dispose();
  const wasEnabled = modules.find(module => module.code === 'studio')?.status === 'ACTIVE';
  await moduleState(page, true);
  try {
    await page.goto('/settings/form-studio/new');
    await fillSimpleForm(page);
    const entityName = `rc_${Date.now().toString(36)}`;
    await page.getByTestId('new-entity-name').fill(entityName);
    const availability = page.waitForResponse(response => response.url().endsWith('/api/v1/studio/activation'));
    await chooseLiveFormDelivery(page);
    const availabilityResponse = await availability;
    expect(availabilityResponse.status()).toBe(200);
    const { enabled } = await availabilityResponse.json() as { enabled: boolean };
    if (process.env.E2E_STUDIO_ACTIVATION_EXPECTED) {
      expect(String(enabled)).toBe(process.env.E2E_STUDIO_ACTIVATION_EXPECTED);
    }
    const prepared = page.waitForResponse(response => response.url().endsWith('/api/v1/form-studio/new-forms/prepare'));
    await page.getByTestId('new-form-prepare').click();
    const packageResponse = await prepared;
    expect(packageResponse.status()).toBe(200);
    const delivery = await packageResponse.json() as { checksum: string; declaration: { entity: { name: string } } };
    expect(delivery.checksum).toMatch(/^[a-f0-9]{64}$/u);
    expect(delivery.declaration.entity.name).toBe(entityName);
    await expect(page.getByTestId('new-form-download')).toBeEnabled();
    if (!enabled) {
      await expect(page.getByTestId('new-form-activate')).toBeDisabled();
      await expect(page.getByTestId('new-form-studio')).toContainText('Активация форм выключена');
      assertNoPageErrors();
      return;
    }
    await page.getByTestId('new-form-activate').click();
    const activation = page.waitForResponse(response => response.request().method() === 'POST'
      && response.url().endsWith('/api/v1/studio/forms'));
    await page.getByRole('alertdialog').getByRole('button', { name: 'Активировать форму', exact: true }).click();
    const activated = await activation;
    expect(activated.status()).toBe(201);
    expect(await activated.json()).toMatchObject({ entityCode: `studio.${entityName}`, checksum: delivery.checksum });
    await expect(page.getByTestId('new-form-activated')).toContainText(`studio.${entityName}`);

    const entity = await openEntity(page, `studio.${entityName}`, 'Места хранения');
    const name = uniqueRunName('Studio live record');
    await page.getByRole('link', { name: 'Создать', exact: true }).click();
    await entity.fillField('name', name);
    await entity.fillField('code', `rc_${Date.now().toString(36)}`);
    expect((await entity.save()).status()).toBe(201);
    await expect(page.getByRole('heading', { level: 1, name, exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { level: 1, name, exact: true })).toBeVisible();
    await page.getByRole('link', { name: 'Редактировать', exact: true }).click();
    await entity.fillField('name', `${name} changed`);
    const changed = await entity.save();
    expect(changed.status()).toBe(200);
    expect(changed.request().headers()['if-match']).toMatch(/^"\d+"$/u);
    await expect(page.getByRole('heading', { level: 1, name: `${name} changed`, exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Удалить', exact: true }).click();
    const deleted = page.waitForResponse(response => response.request().method() === 'DELETE'
      && response.url().includes(`/api/v1/entities/studio.${entityName}/`));
    await page.getByRole('alertdialog').getByRole('button', { name: 'Удалить', exact: true }).click();
    expect((await deleted).status()).toBe(204);
    await expect(page.getByTestId('entity-empty')).toBeVisible();
    assertNoPageErrors();
  } finally {
    await moduleState(page, wasEnabled);
  }
});

// ADR-0038: a quarantined live form answers as unknown while declared entities and other screens keep working.
test('A quarantined live form does not break declared entities and serves again once released', async ({ page }) => {
  test.setTimeout(90_000);
  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page, [/status of 404/u]);
  const modules = await call(page, 'GET', '/api/v1/modules', 200) as { code: string; status: string }[];
  const wasEnabled = modules.find(module => module.code === 'studio')?.status === 'ACTIVE';
  await moduleState(page, true);
  let code: string | undefined;
  let held = false;
  try {
    const { enabled } = await call(page, 'GET', '/api/v1/studio/activation', 200) as { enabled: boolean };
    if (process.env.E2E_STUDIO_ACTIVATION_EXPECTED) {
      expect(String(enabled)).toBe(process.env.E2E_STUDIO_ACTIVATION_EXPECTED);
    }
    test.skip(!enabled, 'Form activation is switched off on this stack');
    const name = `rq_${Date.now().toString(36)}`;
    code = await activateLiveForm(page, name);
    await openEntity(page, code, `Карантин ${name}`);

    await call(page, 'POST', '/api/v1/studio/quarantine', 201, { entityCode: code, reason: 'E2E quarantine' });
    held = true;
    await call(page, 'GET', `/api/v1/entities/${code}`, 404);
    const catalog = await call(page, 'GET', '/api/v1/studio/catalog', 200) as { quarantined: { entityCode: string }[] };
    expect(catalog.quarantined.map(form => form.entityCode)).toContain(code);
    await page.goto(`/e/${code}`);
    await expect(page.getByTestId('entity-page-state')).toBeVisible();

    await openEntity(page, 'ms.tasks');
    await expect(page.getByTestId('entity-mode')).toBeVisible();
    await call(page, 'GET', '/api/v1/entities/ms.tasks', 200);
    const menu = await call(page, 'GET', '/api/v1/entities/menu', 200) as { code: string }[];
    expect(menu.map(item => item.code)).not.toContain(code);

    await call(page, 'DELETE', `/api/v1/studio/quarantine/${code}`, 204);
    held = false;
    await openEntity(page, code, `Карантин ${name}`);
    assertNoPageErrors();
  } finally {
    if (held && code) await call(page, 'DELETE', `/api/v1/studio/quarantine/${code}`, 204);
    await moduleState(page, wasEnabled);
  }
});
