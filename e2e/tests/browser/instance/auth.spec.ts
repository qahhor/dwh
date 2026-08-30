import { expect, test } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';

test('protected route redirects to the accessible login form', async ({ page }) => {
  await page.goto('/iam/users');

  await expect(page).toHaveURL(/\/login$/u);
  await expect(page.getByRole('heading', { name: 'Корпоративный вход' })).toBeVisible();
  await expect(page.getByLabel('Логин или Email')).toBeVisible();
  await expect(page.getByLabel('Пароль')).toBeVisible();
});

test('invalid credentials keep the user on login and show an alert', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Логин или Email').fill(`invalid-${Date.now()}`);
  await page.getByLabel('Пароль').fill('Invalid-only-for-E2E-1');
  await page.getByRole('button', { name: 'Войти в систему' }).click();

  await expect(page).toHaveURL(/\/login$/u);
  await expect(page.getByRole('alert')).toContainText(/Неверный|ошиб|заблокирован/u);
});

test('admin can navigate principal areas without browser errors and can log out', async ({ page }) => {
  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page);

  const routes = [
    ['/tasks', 'Задачи'],
    ['/tasks/projects', 'Проекты'],
    ['/iam/users', 'Пользователи'],
    ['/iam/roles', 'Роли и матрица прав'],
    ['/iam/custom-fields', 'Динамические атрибуты'],
    ['/files', 'Файловое хранилище'],
    ['/audit', 'Аудит и безопасность'],
    ['/settings', 'Настройки'],
  ] as const;

  for (const [path, heading] of routes) {
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible();
  }

  assertNoPageErrors();
  await page.getByRole('button', { name: 'Выйти из системы' }).click();
  await expect(page).toHaveURL(/\/login$/u);
});
