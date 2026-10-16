import { expect, test } from '@playwright/test';

const fields = [
  { key: 'code', label: 'Code', type: 'text', maxLength: 64 },
  { key: 'name', label: 'Name', type: 'text', maxLength: 255 },
  { key: 'unit', label: 'Unit', type: 'select', options: ['pc', 'kg'] },
  { key: 'price', label: 'Price', type: 'money', currencies: ['UZS', 'USD'], scale: 2 },
].map(field => ({ ...field, labelKey: '', required: true }));

for (const theme of ['light', 'dark']) {
  test(`metadata form keeps readable field widths and one focus ring in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
    await page.route('**/api/v1/form-meta/ms.notes', route => route.fulfill({ json: {
      code: 'ms.notes', listCode: 'ms.notes', fields,
      layout: [{ key: 'main', labelKey: 'entity.section.main', fields: fields.map(field => field.key) }],
      actions: ['create'], capabilities: [],
    } }));
    await page.goto('/e/ms.notes/new');
    const form = page.locator('smt-entity-form');
    const code = form.locator('[data-field="code"]');
    const name = form.locator('[data-field="name"]');
    await expect(name).toBeVisible();
    const codeBox = (await code.boundingBox())!;
    const nameBox = (await name.boundingBox())!;
    expect(nameBox.width).toBeGreaterThan(codeBox.width * 1.5);
    expect(Math.abs(codeBox.y - nameBox.y)).toBeLessThan(2);
    await page.setViewportSize({ width: 360, height: 800 });
    await expect.poll(async () => (await code.boundingBox())!.width).toBeGreaterThan(250);
    for (const field of fields) {
      const box = (await form.locator(`[data-field="${field.key}"]`).boundingBox())!;
      expect(box.width).toBeGreaterThan(250);
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(360);
    }
    const order = await form.locator('[data-field]').evaluateAll(elements => elements.map(element => element.getAttribute('data-field')));
    expect(order).toEqual(fields.map(field => field.key));

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/tasks');
    const search = page.locator('#task-search');
    const more = page.locator('#task-more-filters');
    await expect(more).toBeVisible();
    expect(Math.abs((await search.boundingBox())!.y - (await more.boundingBox())!.y)).toBeLessThan(8);
    await page.setViewportSize({ width: 360, height: 800 });
    await expect(page.locator('#app-sidebar')).toHaveAttribute('aria-hidden', 'true');
    const status = page.getByRole('combobox', { name: 'Фильтр по статусу' });
    await expect(status).toBeVisible();
    expect(await status.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
    await page.locator('#task-search').focus();
    const focus = await page.locator('#task-search').evaluate(control => ({
      inner: getComputedStyle(control).outlineStyle,
      outer: getComputedStyle(control.closest('.smt-input')!).outlineWidth,
    }));
    expect(focus.inner).toBe('none');
    expect(parseFloat(focus.outer)).toBeGreaterThanOrEqual(2);
    await page.locator('.task-page-actions button').click();
    const menu = page.getByRole('menu');
    await expect(menu).toBeVisible();
    const bounds = (await menu.boundingBox())!;
    expect(bounds.x).toBeGreaterThanOrEqual(0);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(360);
    await expect(menu.getByRole('menuitem')).toHaveCount(3);
    await page.keyboard.press('Escape');
    await page.locator('.mobile-menu-btn').click();
    const sidebar = page.locator('#app-sidebar');
    await expect(sidebar).toHaveAttribute('role', 'dialog');
    await sidebar.getByRole('searchbox', { name: 'Найти раздел' }).fill('Пользователи');
    await expect(sidebar.locator('.sidebar-nav').getByRole('link')).toHaveCount(1);
    await expect(sidebar.getByRole('link', { name: 'Пользователи', exact: true })).toBeVisible();
    await sidebar.getByRole('searchbox', { name: 'Найти раздел' }).fill('');
    await expect.poll(() => sidebar.locator('.sidebar-nav').getByRole('link').count()).toBeGreaterThan(1);
    await page.keyboard.press('Escape');
    await expect(page.locator('.mobile-menu-btn')).toBeFocused();
  });
}
