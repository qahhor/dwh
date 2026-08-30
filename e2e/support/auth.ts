import { expect, type Page } from '@playwright/test';

import { loadE2eEnv } from './env.mjs';
import { clearSecret, fillSecret } from './secret.js';

const environment = loadE2eEnv();

export async function loginToInstance(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Логин или Email').fill(environment.instance.login);
  const password = page.getByLabel('Пароль');
  await fillSecret(password, environment.instance.password);
  try {
    await page.getByRole('button', { name: 'Войти в систему' }).click();
    await expect(page).toHaveURL(/\/tasks(?:\?.*)?$/u);
    await expect(page.getByRole('navigation', { name: 'Основная навигация' })).toBeVisible();
  } finally {
    await clearSecret(password);
  }
}

export async function loginToControlPlane(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Логин').fill(environment.controlPlane.login);
  const password = page.getByLabel('Пароль');
  await fillSecret(password, environment.controlPlane.password);
  try {
    await page.getByRole('button', { name: 'Войти' }).click();
    await expect(page).toHaveURL(/\/fleet$/u);
    await expect(page.getByRole('navigation', { name: 'Основная навигация' })).toBeVisible();
  } finally {
    await clearSecret(password);
  }
}
