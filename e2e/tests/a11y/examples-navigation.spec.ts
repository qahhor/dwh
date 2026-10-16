import { expect, test } from '@playwright/test';

const names = ['Заказы (эталон)', 'Товары (эталон)', 'Заявки (эталон)'];

for (const theme of ['light', 'dark'] as const) {
  test(`examples remain separate and reachable with keyboard in ${theme} navigation`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('smc_theme', value), theme);
    await page.goto('/notes');
    await expect(page.getByRole('heading', { name: 'Заметки', exact: true })).toBeVisible();
    const section = page.locator('#section-content-examples');
    await expect(section.getByRole('link')).toHaveCount(3);
    for (const name of names) {
      await expect(section.getByRole('link', { name, exact: true })).toBeVisible();
      await expect(page.locator('#section-content-workspace').getByRole('link', { name, exact: true })).toHaveCount(0);
    }
    await section.getByRole('link', { name: names[0], exact: true }).click();
    await expect(page).toHaveURL(/\/e\/example.orders$/);
    await expect(section.locator('[aria-current="page"]')).toHaveAttribute('href', '/e/example.orders');
    await page.getByRole('button', { name: 'Свернуть навигацию', exact: true }).click();
    const opener = page.getByRole('button', { name: 'Примеры', exact: true });
    await opener.press('Enter');
    const menu = page.getByRole('menu', { name: 'Примеры', exact: true });
    await expect(menu.getByRole('menuitem', { name: names[0], exact: true })).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(menu.getByRole('menuitem', { name: names[1], exact: true })).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(opener).toBeFocused();
  });
}

test('disabled examples disappear even when the menu still contains their metadata', async ({ page }) => {
  await page.route('**/api/v1/modules/active', async route => {
    const response = await route.fetch();
    const modules = await response.json() as { code: string }[];
    await route.fulfill({ json: modules.filter(module => module.code !== 'example') });
  });
  await page.goto('/notes');
  await expect(page.getByRole('heading', { name: 'Заметки', exact: true })).toBeVisible();
  await expect(page.locator('#section-content-examples')).toHaveCount(0);
  for (const name of names) {
    await expect(page.getByRole('link', { name, exact: true })).toHaveCount(0);
  }
  await page.getByRole('button', { name: 'Свернуть навигацию', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Примеры', exact: true })).toHaveCount(0);
});

test('no example links remain when the server removes entities without view rights', async ({ page }) => {
  await page.route('**/api/v1/auth/me', async route => {
    const response = await route.fetch();
    const me = await response.json();
    await route.fulfill({ json: { ...me, permissions: ['md.profile.view', 'notes.view'] } });
  });
  await page.route('**/api/v1/entities/menu', async route => {
    const response = await route.fetch();
    const items = await response.json() as { module: string | null }[];
    await route.fulfill({ json: items.filter(item => item.module !== 'example') });
  });
  await page.goto('/notes');
  await expect(page.getByRole('heading', { name: 'Заметки', exact: true })).toBeVisible();
  await expect(page.locator('#section-content-examples')).toHaveCount(0);
  await expect(page.locator('nav a[href^="/e/example."]')).toHaveCount(0);
  await page.getByRole('button', { name: 'Свернуть навигацию', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Примеры', exact: true })).toHaveCount(0);
});
