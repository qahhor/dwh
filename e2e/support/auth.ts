import { createHash } from 'node:crypto';

import { expect, type Page } from '@playwright/test';

import { loadE2eEnv } from './env.mjs';
import { clearSecret, fillSecret } from './secret.js';

const environment = loadE2eEnv();
const rotatedInstancePassword = `E2e!${createHash('sha256')
  .update(environment.instance.password)
  .digest('base64url')
  .slice(0, 24)}`;
let activeInstancePassword = environment.instance.password;

async function submitInstanceCredentials(page: Page, passwordValue: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Логин или Email').fill(environment.instance.login);
  const password = page.getByLabel('Пароль', { exact: true });
  await fillSecret(password, passwordValue);
  try {
    await page.getByRole('button', { name: 'Войти в систему' }).click();
    await Promise.race([
      page.waitForURL(/\/tasks(?:\?.*)?$/u),
      page.getByText('Смена временного пароля', { exact: true }).waitFor(),
      page.getByRole('alert').waitFor(),
    ]);
  } finally {
    await clearSecret(password);
  }
}

async function completeMandatoryPasswordChange(page: Page): Promise<void> {
  const newPassword = page.getByLabel('Новый пароль', { exact: true });
  const confirmation = page.getByLabel('Повторите новый пароль', { exact: true });
  await fillSecret(newPassword, rotatedInstancePassword);
  await fillSecret(confirmation, rotatedInstancePassword);
  try {
    await page.getByRole('button', { name: 'Сменить пароль и войти' }).click();
    await expect(page).toHaveURL(/\/tasks(?:\?.*)?$/u);
  } finally {
    await clearSecret(newPassword);
    await clearSecret(confirmation);
  }
  activeInstancePassword = rotatedInstancePassword;
}

export async function loginToInstance(page: Page): Promise<void> {
  await submitInstanceCredentials(page, activeInstancePassword);

  if (await page.getByText('Смена временного пароля', { exact: true }).isVisible().catch(() => false)) {
    await completeMandatoryPasswordChange(page);
  } else if (await page.getByRole('alert').isVisible().catch(() => false)
    && activeInstancePassword !== rotatedInstancePassword) {
    activeInstancePassword = rotatedInstancePassword;
    await submitInstanceCredentials(page, activeInstancePassword);
  }

  await expect(page).toHaveURL(/\/tasks(?:\?.*)?$/u);
  await expect(page.getByRole('navigation', { name: 'Основная навигация' })).toBeVisible();
}

export async function loginToControlPlane(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Логин').fill(environment.controlPlane.login);
  const password = page.getByLabel('Пароль', { exact: true });
  await fillSecret(password, environment.controlPlane.password);
  try {
    await page.getByRole('button', { name: 'Войти' }).click();
    await expect(page).toHaveURL(/\/fleet$/u);
    await expect(page.getByRole('navigation', { name: 'Основная навигация' })).toBeVisible();
  } finally {
    await clearSecret(password);
  }
}
