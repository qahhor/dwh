import { expect, test } from '@playwright/test';
import { mkdtemp, open, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';
import { expectNoSeriousAccessibilityViolations } from '../../../support/accessibility.js';

test('protected route redirects to the accessible login form', async ({ page }) => {
  await page.goto('/e/md.users');

  await expect(page).toHaveURL(/\/login$/u);
  await expect(page.getByRole('heading', { name: 'Корпоративный вход' })).toBeVisible();
  await expect(page.getByLabel('Логин или Email')).toBeVisible();
  await expect(page.getByLabel('Пароль', { exact: true })).toBeVisible();
});

test('login page publishes a reachable browser icon', async ({ page, request }) => {
  await page.goto('/login');

  const iconHref = await page.locator('link[rel="icon"]').getAttribute('href');
  expect(iconHref).toBeTruthy();

  const iconResponse = await request.get(iconHref!);
  expect(iconResponse.status()).toBe(200);
  expect((await iconResponse.body()).byteLength).toBeGreaterThan(0);
});

test('login applies the saved dark theme with accessible mobile controls', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('smc_theme', 'dark'));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Корпоративный вход' })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expectNoSeriousAccessibilityViolations(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('invalid credentials keep the user on login and show an alert', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Логин или Email').fill(`invalid-${Date.now()}`);
  await page.getByLabel('Пароль', { exact: true }).fill('Invalid-only-for-E2E-1');
  await page.getByLabel('Пароль', { exact: true }).dispatchEvent('keyup', { key: 'A', modifierCapsLock: true });
  await expect(page.getByRole('status')).toContainText('Caps Lock');
  const submit = page.getByRole('button', { name: 'Войти в систему' });
  const bounds = await submit.boundingBox();
  if (!bounds) throw new Error('Login submit button has no visible bounds');
  // Blurring the password must not move the lower edge away before pointerup.
  await submit.click({ position: { x: bounds.width / 2, y: bounds.height - 4 }, delay: 100 });

  await expect(page).toHaveURL(/\/login$/u);
  await expect(page.locator('#login-error')).toContainText(/Неверный|ошиб|заблокирован/u);
  await expect(page.getByRole('alert')).toHaveCount(1);
  await expect(page.getByLabel('Пароль', { exact: true })).toBeFocused();
});

test('admin can navigate principal areas without browser errors and can log out', async ({ page }) => {
  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page);

  const routes = [
    ['/tasks', 'Задачи'],
    ['/tasks/projects', 'Проекты'],
    ['/e/md.users', 'Пользователи'],
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
    response.request().method() === 'POST' && response.url().endsWith('/api/v1/iam/roles')
  );
  await createDialog.getByRole('button', { name: 'Создать', exact: true }).click();
  const createdRole = await createResponse;
  expect(createdRole.ok()).toBe(true);
  expect(new URL(createdRole.url()).origin).toBe(origin);
  await expect(page.getByRole('button', { name: `Выбрать роль ${roleName}` })).toBeVisible();

  await page.getByRole('button', { name: `Удалить роль ${roleName}` }).click();
  const deleteDialog = page.getByRole('dialog', { name: 'Удаление роли' });
  const deleteResponse = page.waitForResponse(response =>
    response.request().method() === 'DELETE' && /\/api\/v1\/iam\/roles\/\d+$/u.test(response.url())
  );
  await deleteDialog.getByRole('button', { name: 'Удалить', exact: true }).click();
  expect((await deleteResponse).ok()).toBe(true);
  await expect(page.getByRole('button', { name: `Выбрать роль ${roleName}` })).toHaveCount(0);
  assertNoPageErrors();
});

test('administrator can create and anonymise a user and upload and delete a file', async ({ page }) => {
  const suffix = `${Date.now()}${Math.random().toString(36).slice(2, 6)}`;
  const userName = `E2E User ${suffix}`;
  const login = `e2e${suffix}`.toLowerCase();
  const email = `${login}@example.test`;
  const fileName = `smartupcms-${suffix}.txt`;

  await loginToInstance(page);
  const origin = new URL(page.url()).origin;
  const assertNoPageErrors = collectPageErrors(page);
  // The users are the general entity screen (ADR-0032 8): a new user is invited, so no password is typed here.
  await page.goto('/e/md.users');
  await expect(page.getByRole('heading', { level: 1, name: 'Пользователи' })).toBeVisible();
  await page.getByRole('link', { name: 'Создать' }).click();
  await expect(page).toHaveURL(/\/e\/md\.users\/new$/u);
  await page.getByRole('textbox', { name: 'Имя' }).fill(userName);
  await page.getByRole('textbox', { name: 'Логин' }).fill(login);
  await page.getByRole('textbox', { name: 'Email' }).fill(email);
  const createResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && /\/api\/v1\/entities\/md\.users$/u.test(response.url())
  );
  // A new record: the primary button of the general form says "Создать" (forms standard, section 6).
  await page.getByRole('button', { name: 'Создать', exact: true }).click();
  const createdUser = await createResponse;
  expect(createdUser.status()).toBe(201);
  expect(new URL(createdUser.url()).origin).toBe(origin);
  await expect(page).toHaveURL(/\/e\/md\.users\/\d+$/u);
  await expect(page.getByRole('heading', { level: 1, name: userName })).toBeVisible();

  // The list finds the new user by the search.
  await page.getByRole('link', { name: 'К списку' }).click();
  await page.getByRole('searchbox', { name: 'Поиск' }).fill(login);
  const link = page.getByRole('link', { name: userName, exact: true });
  await expect(link).toBeVisible();
  await link.click();

  // Anonymisation takes the place of a delete and asks first (ADR-0032 8).
  await page.getByRole('button', { name: 'Анонимизировать', exact: true }).click();
  const anonymiseDialog = page.getByRole('alertdialog');
  await expect(anonymiseDialog).toContainText(userName);
  const anonymised = page.waitForResponse(response =>
    response.request().method() === 'POST' && /\/api\/v1\/entities\/md\.users\/\d+\/actions\/anonymize$/u.test(response.url())
  );
  await anonymiseDialog.getByRole('button', { name: 'Анонимизировать', exact: true }).click();
  expect((await anonymised).status()).toBe(200);
  await expect(page.getByRole('heading', { level: 1, name: /^Deleted User \d+$/u })).toBeVisible();

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
  await expect(uploadDialog.getByRole('button', { name: `Скачать «${fileName}»` }).first()).toBeVisible();
  // The header has a "Закрыть" icon button too; the footer action is the last one.
  await uploadDialog.getByRole('button', { name: 'Закрыть', exact: true }).last().click();

  const fileTable = page.getByRole('region', { name: 'Таблица файлов' });
  await expect(fileTable.getByRole('button', { name: `Скачать файл ${fileName}` }).first()).toBeVisible();
  await fileTable.getByRole('button', { name: `Удалить файл ${fileName}` }).click();
  const deleteFileDialog = page.getByRole('alertdialog', { name: 'Подтверждение удаления' });
  const deleteFileResponse = page.waitForResponse(response =>
    response.request().method() === 'DELETE'
      && /\/api\/v1\/files\/[0-9a-f-]{36}$/u.test(response.url())
  );
  await deleteFileDialog.getByRole('button', { name: 'Удалить', exact: true }).click();
  expect((await deleteFileResponse).ok()).toBe(true);
  await expect(fileTable.getByRole('button', { name: `Скачать файл ${fileName}` }).first()).toHaveCount(0);
  assertNoPageErrors();
});

test('oversized file is rejected with the stable 413 contract and a visible error', async ({ page }) => {
  test.setTimeout(90_000);
  const oversizedFileName = `oversize-${Date.now()}.pdf`;
  const temporaryDirectory = await mkdtemp(join(tmpdir(), 'smartupcms-e2e-upload-'));
  const oversizedFilePath = join(temporaryDirectory, oversizedFileName);
  const oversizedFile = await open(oversizedFilePath, 'w');
  try {
    await oversizedFile.write(Buffer.from('%PDF-1.7', 'ascii'));
    await oversizedFile.truncate(50 * 1024 * 1024 + 1);
  } finally {
    await oversizedFile.close();
  }

  try {
    await loginToInstance(page);
    const assertNoPageErrors = collectPageErrors(page, [
      /^Failed to load resource: the server responded with a status of 413 \(\)$/u,
    ]);
    await page.goto('/files');
    await page.getByRole('button', { name: 'Загрузить файл' }).click();

    const uploadDialog = page.getByRole('dialog', { name: 'Загрузка файлов в хранилище' });
    const uploadResponse = page.waitForResponse(response =>
      response.request().method() === 'POST' && response.url().endsWith('/api/v1/files/upload')
    );
    await uploadDialog.locator('input[type="file"]').setInputFiles(oversizedFilePath);

    const response = await uploadResponse;
    expect(response.status()).toBe(413);
    expect(response.headers()['content-type']).toContain('application/problem+json');
    await expect(response.json()).resolves.toMatchObject({
      status: 413,
      code: 'file_size_exceeded',
    });
    await expect(page.getByRole('alert')).toContainText('Размер файла превышает допустимые 50 МБ');
    await expect(page.getByRole('region', { name: 'Таблица файлов' })
      .getByText(oversizedFileName, { exact: true })).toHaveCount(0);
    assertNoPageErrors();
  } finally {
    await rm(temporaryDirectory, { recursive: true, force: true });
  }
});
