import { expect, test, type Page } from '@playwright/test';

const roles = [
  { id: 101, name: 'Первая роль', state: 'A', orderNo: 1, revision: 1 },
  { id: 102, name: 'Вторая роль', state: 'A', orderNo: 2, revision: 1 },
].map(role => ({ ...role, createdAt: '2026-10-01T09:00:00Z', modifiedAt: '2026-10-01T09:00:00Z' }));
const actions = [
  ['view', 'Просмотр'], ['create', 'Создание'], ['update', 'Изменение'],
  ['delete', 'Удаление'], ['export', 'Экспорт'],
];
const forms = Array.from({ length: 4 }, (_, index) => actions.map(([action, actionName]) => ({
  module: 'example', formCode: `example.review_${index}`, action, actionName,
  formName: `Заявки на согласование изменений условий поставки товаров в региональные подразделения ${index + 1}`,
}))).flat();

/** The same roles/form-action contracts as RolesApi; every write is intercepted and must remain absent. */
async function mockRoles(page: Page) {
  const writes: string[] = [];
  const permissionReads: string[] = [];
  await page.route('**/api/v1/**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(/^\/api\/v1/, '');
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
      writes.push(`${request.method()} ${path}`);
      return route.fulfill({ status: 405, json: { title: 'This scenario must not write data' } });
    }
    if (path === '/auth/me') {
      const response = await route.fetch();
      const viewer = await response.json();
      return route.fulfill({ json: { ...viewer, permissions: ['md.roles.view', 'md.roles.grant'] } });
    }
    if (path === '/iam/roles') return route.fulfill({ json: roles });
    if (path === '/iam/roles/user-counts') return route.fulfill({ json: { 101: 3, 102: 2 } });
    if (path === '/iam/forms') return route.fulfill({ json: forms });
    if (/^\/iam\/roles\/(101|102)\/permissions$/.test(path)) {
      permissionReads.push(path);
      return route.fulfill({ json: [] });
    }
    return route.continue();
  });
  return { writes, permissionReads };
}

for (const theme of ['light', 'dark'] as const) {
  test(`long permission rows keep checkbox icons inside a 360 px page in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
    const { writes } = await mockRoles(page);
    await page.goto('/iam/roles');
    const matrix = page.locator('app-role-permissions-matrix');
    await expect(matrix.getByRole('checkbox')).toHaveCount(20);
    const choice = matrix.getByRole('checkbox', { name: 'Просмотр', exact: true }).first();
    await expect(choice).toHaveAttribute('aria-disabled', 'false');
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(360);

    await choice.focus();
    await page.keyboard.press('Space');
    await expect(choice).toBeChecked();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(360);
    expect(writes).toEqual([]);
  });

  test(`switching a dirty role focuses continuing editing and Enter preserves its draft in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
    const { writes, permissionReads } = await mockRoles(page);
    await page.goto('/iam/roles');
    const choice = page.locator('app-role-permissions-matrix').getByRole('checkbox', { name: 'Просмотр', exact: true }).first();
    await expect(choice).toHaveAttribute('aria-disabled', 'false');
    await choice.focus();
    await page.keyboard.press('Space');
    await expect(choice).toBeChecked();
    await page.getByRole('button', { name: 'Выбрать роль Вторая роль', exact: true }).click();

    const dialog = page.getByRole('dialog', { name: 'Несохранённые изменения прав', exact: true });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Продолжить редактирование', exact: true })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(dialog).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Выбрать роль Первая роль', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(choice).toBeChecked();
    await expect(page.getByRole('button', { name: /Сохранить права.*\(1\)/ })).toBeVisible();
    expect(permissionReads).toEqual(['/iam/roles/101/permissions']);
    expect(writes).toEqual([]);
  });
}
