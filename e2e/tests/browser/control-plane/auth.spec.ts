import { expect, test } from '@playwright/test';

import { loginToControlPlane } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';

test('invalid credentials produce an accessible error', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Логин').fill(`invalid-${Date.now()}`);
  await page.getByLabel('Пароль').fill('Invalid-only-for-E2E-1');
  await page.getByRole('button', { name: 'Войти' }).click();

  await expect(page).toHaveURL(/\/login$/u);
  await expect(page.getByRole('alert')).toHaveText('Неверный логин или пароль');
});

test('admin can navigate operational pages and log out without browser errors', async ({ page }) => {
  await loginToControlPlane(page);
  const assertNoPageErrors = collectPageErrors(page);

  const routes = [
    ['/fleet', 'Флот экземпляров'],
    ['/clients', 'Клиенты и Организации'],
    ['/modules', 'Модерация пользовательских модулей'],
    ['/backups', 'Резервные копии'],
    ['/announcements', 'Системные объявления'],
  ] as const;

  for (const [path, heading] of routes) {
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible();
  }

  await page.getByRole('button', { name: 'Выйти из Control Panel' }).click();
  await expect(page).toHaveURL(/\/login$/u);
  assertNoPageErrors();
});
