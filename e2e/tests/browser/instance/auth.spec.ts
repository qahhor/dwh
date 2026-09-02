import { expect, test } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';
import { clearSecret, fillSecret } from '../../../support/secret.js';

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
    ['/notifications', 'Центр уведомлений'],
    ['/files', 'Файловое хранилище'],
    ['/audit', 'Аудит и безопасность'],
    ['/settings', 'Настройки'],
    ['/system', 'Состояние системы'],
    ['/announcements', 'Объявления'],
  ] as const;

  for (const [path, heading] of routes) {
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible();
  }

  assertNoPageErrors();
  await page.getByRole('button', { name: 'Выйти из системы' }).click();
  await expect(page).toHaveURL(/\/login$/u);
});

test('administrator can create and remove a custom role', async ({ page }) => {
  const roleName = `E2E role ${Date.now()}${Math.random().toString(36).slice(2, 6)}`;

  await loginToInstance(page);
  const origin = new URL(page.url()).origin;
  const assertNoPageErrors = collectPageErrors(page);
  await page.goto('/iam/roles');
  await page.getByRole('button', { name: 'Новая роль' }).click();

  const createDialog = page.getByRole('dialog', { name: 'Создание новой роли' });
  await createDialog.getByLabel('Название роли').fill(roleName);
  const createResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && response.url().endsWith('/api/v1/rbac/roles')
  );
  await createDialog.getByRole('button', { name: 'Создать', exact: true }).click();
  const createdRole = await createResponse;
  expect(createdRole.ok()).toBe(true);
  expect(new URL(createdRole.url()).origin).toBe(origin);
  await expect(page.getByRole('button', { name: `Выбрать роль ${roleName}` })).toBeVisible();

  await page.getByRole('button', { name: `Удалить роль ${roleName}` }).click();
  const deleteDialog = page.getByRole('dialog', { name: 'Удаление роли' });
  const deleteResponse = page.waitForResponse(response =>
    response.request().method() === 'DELETE' && /\/api\/v1\/rbac\/roles\/\d+$/u.test(response.url())
  );
  await deleteDialog.getByRole('button', { name: 'Удалить', exact: true }).click();
  expect((await deleteResponse).ok()).toBe(true);
  await expect(page.getByRole('button', { name: `Выбрать роль ${roleName}` })).toHaveCount(0);
  assertNoPageErrors();
});

test('administrator can create and remove a user and upload and delete a file', async ({ page }) => {
  const suffix = `${Date.now()}${Math.random().toString(36).slice(2, 6)}`;
  const userName = `E2E User ${suffix}`;
  const login = `e2e${suffix}`.toLowerCase();
  const email = `${login}@example.test`;
  const temporaryPassword = `E2e!${suffix}Safe`;
  const fileName = `smartupcms-${suffix}.txt`;

  await loginToInstance(page);
  const origin = new URL(page.url()).origin;
  const assertNoPageErrors = collectPageErrors(page);
  await page.goto('/iam/users');
  await page.getByRole('button', { name: 'Новый пользователь' }).click();

  const createDialog = page.getByRole('dialog', { name: 'Создать пользователя' });
  await createDialog.getByLabel('ФИО').fill(userName);
  await createDialog.getByLabel('Логин').fill(login);
  await createDialog.getByLabel('Email').fill(email);
  const password = createDialog.getByLabel('Временный пароль');
  await fillSecret(password, temporaryPassword);
  const createResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && response.url().endsWith('/api/v1/iam/users')
  );
  try {
    await createDialog.getByRole('button', { name: 'Создать', exact: true }).click();
  } finally {
    await clearSecret(password);
  }
  const createdUser = await createResponse;
  expect(createdUser.ok()).toBe(true);
  expect(new URL(createdUser.url()).origin).toBe(origin);
  await expect(page.getByRole('button', { name: `Открыть профиль пользователя ${userName}` })).toBeVisible();

  await page.getByRole('button', { name: `Удалить пользователя ${userName}` }).click();
  const deleteDialog = page.getByRole('dialog', { name: 'Удаление пользователя' });
  const deleteUserResponse = page.waitForResponse(response =>
    response.request().method() === 'DELETE' && /\/api\/v1\/iam\/users\/\d+$/u.test(response.url())
  );
  await deleteDialog.getByRole('button', { name: 'Удалить', exact: true }).click();
  expect((await deleteUserResponse).ok()).toBe(true);
  await expect(page.getByRole('button', { name: `Открыть профиль пользователя ${userName}` })).toHaveCount(0);

  await page.goto('/files');
  await page.getByRole('button', { name: 'Загрузить файл' }).click();
  const uploadDialog = page.getByRole('dialog', { name: 'Загрузка файлов в хранилище' });
  const uploadResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && response.url().endsWith('/api/v1/files/upload')
  );
  await uploadDialog.locator('input[type="file"]').setInputFiles({
    name: fileName,
    mimeType: 'text/plain',
    buffer: Buffer.from(`SmartupCMS browser release verification ${suffix}.\n`, 'utf8'),
  });
  const uploadedFile = await uploadResponse;
  expect(uploadedFile.ok()).toBe(true);
  expect(new URL(uploadedFile.url()).origin).toBe(origin);
  await expect(uploadDialog.getByRole('button', { name: `Скачать ${fileName}` }).first()).toBeVisible();
  await uploadDialog.getByRole('button', { name: 'Закрыть', exact: true }).click();

  const fileTable = page.getByRole('region', { name: 'Таблица файлов' });
  await expect(fileTable.getByRole('button', { name: `Скачать файл ${fileName}` }).first()).toBeVisible();
  await fileTable.getByRole('button', { name: `Удалить файл ${fileName}` }).click();
  const deleteFileDialog = page.getByRole('dialog', { name: 'Подтверждение удаления' });
  const deleteFileResponse = page.waitForResponse(response =>
    response.request().method() === 'DELETE'
      && /\/api\/v1\/files\/[0-9a-f-]{36}$/u.test(response.url())
  );
  await deleteFileDialog.getByRole('button', { name: 'Удалить', exact: true }).click();
  expect((await deleteFileResponse).ok()).toBe(true);
  await expect(fileTable.getByRole('button', { name: `Скачать файл ${fileName}` }).first()).toHaveCount(0);
  assertNoPageErrors();
});
