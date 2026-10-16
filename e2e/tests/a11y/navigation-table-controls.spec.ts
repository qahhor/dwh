import { expect, test } from '@playwright/test';

const metaField = (key: string, labelKey: string, type: string) => ({
  key, labelKey, type, ops: ['eq'], sortable: false, nullable: false, defaultVisible: true, searchable: false,
});

test('file actions fit the column and retain mobile targets including preview', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.route('**/api/v1/modules/active', route => route.fulfill({ json: [{ code: 'files', isActive: true, status: 'ACTIVE' }] }));
  await page.route('**/api/v1/query-meta/mf.files', route => route.fulfill({ json: {
    code: 'mf.files', defaultSort: '-createdAt', defaultLimit: 50, maxLimit: 200,
    fields: [metaField('originalName', 'files.list.file_name', 'text'), metaField('sizeBytes', 'files.list.size', 'number'), metaField('createdAt', 'files.list.upload_date', 'instant')],
  } }));
  await page.route('**/api/v1/files?*', route => route.fulfill({ json: {
    items: [{ id: 'image-1', originalName: 'image.png', mimeType: 'image/png', sizeBytes: 1000, createdBy: 1, createdAt: '2026-10-14T10:00:00Z' }], nextCursor: null, totalEstimated: 1,
  } }));
  await page.route('**/api/v1/files/storage/stats', route => route.fulfill({ json: {
    companyQuotaBytes: 100000, companyUsedBytes: 1000, companyAvailableBytes: 99000, userQuotaBytes: 100000, userUsedBytes: 1000, userAvailableBytes: 99000, totalFilesCount: 1, userFilesCount: 1,
  } }));
  await page.goto('/files');
  const actions = page.locator('app-files-table .row-actions').first();
  await expect(actions.locator('button')).toHaveCount(3);
  const bounds = (await actions.boundingBox())!;
  for (const button of await actions.locator('button').all()) {
    const box = (await button.boundingBox())!;
    expect(box.width).toBeGreaterThanOrEqual(44);
    expect(box.height).toBeGreaterThanOrEqual(44);
    expect(box.x).toBeGreaterThanOrEqual(bounds.x - 1);
    expect(box.x + box.width).toBeLessThanOrEqual(bounds.x + bounds.width + 1);
  }
});

test('collapsed navigation remains inside a short viewport and keeps keyboard access', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 450 });
  await page.route('**/api/v1/entities/menu', route => route.fulfill({ json: Array.from({ length: 20 }, (_, i) => ({
    code: `notes.sample${i}`, form: 'notes', route: `/e/notes.sample${i}`, labelKey: 'nav.notes', icon: 'description', section: 'workspace', order: i, module: 'notes',
  })) }));
  await page.goto('/tasks');
  await page.getByRole('button', { name: 'Свернуть навигацию', exact: true }).click();
  const opener = page.getByRole('button', { name: 'Рабочее пространство', exact: true });
  await opener.click();
  const menu = page.getByRole('menu', { name: 'Рабочее пространство', exact: true });
  await expect(menu).toBeVisible();
  const bounds = (await menu.boundingBox())!;
  expect(bounds.y + bounds.height).toBeLessThanOrEqual(450);
  await menu.getByRole('menuitem').first().focus();
  await page.keyboard.press('End');
  await expect(menu.getByRole('menuitem').last()).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(opener).toBeFocused();
});

test('small switches expose a 44px target without inflating the visible track', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/settings');
  await page.getByRole('tab', { name: 'Мои предпочтения', exact: true }).click();
  const control = page.getByRole('switch', { name: 'Звуковые оповещения' });
  await expect(control).toBeVisible();
  const box = (await control.boundingBox())!;
  expect(box.width).toBeGreaterThanOrEqual(44);
  expect(box.height).toBeGreaterThanOrEqual(44);
  const track = (await control.locator('.smt-switch__track').boundingBox())!;
  expect(track.height).toBeLessThanOrEqual(20);
  await page.getByRole('tab', { name: 'Навигация и меню', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1);
  await expect(page.getByRole('heading', { level: 2, name: 'Управление навигацией и меню' })).toBeVisible();
});

test('filter drawer starts in its content and restores the opener on Escape', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/tasks');
  const opener = page.getByRole('button', { name: 'Фильтр', exact: true });
  await opener.click();
  const dialog = page.getByRole('dialog', { name: 'Фильтр списка', exact: true });
  const addCondition = dialog.getByRole('button', { name: 'Добавить условие', exact: true });
  await expect(addCondition).toBeFocused();
  const close = dialog.getByRole('button', { name: 'Закрыть', exact: true });
  const box = (await close.boundingBox())!;
  expect(box.width).toBeGreaterThanOrEqual(44);
  expect(box.height).toBeGreaterThanOrEqual(44);
  await addCondition.click();
  const remove = dialog.locator('.filter-remove').first();
  const removeBox = (await remove.boundingBox())!;
  expect(removeBox.width).toBeGreaterThanOrEqual(44);
  expect(removeBox.height).toBeGreaterThanOrEqual(44);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(360);
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(opener).toBeFocused();
});
