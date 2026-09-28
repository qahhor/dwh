import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

import { expect, type Page } from '@playwright/test';

import { loadE2eEnv } from './env.mjs';
import { clearSecret, fillSecret } from './secret.js';

const environment = loadE2eEnv();
// 20 characters: the longest password the policy accepts (8..20).
const rotatedInstancePassword = `E2e!${createHash('sha256')
  .update(environment.instance.password)
  .digest('base64url')
  .slice(0, 16)}`;
// Playwright restarts the worker after a failed test, which resets module state. Without a record of the
// rotation, every restart would first try the retired bootstrap password: one more failed sign-in each time,
// until the server locks the address (10 failures) and every later test fails at login. The record is a flag,
// never the password, in the output directory that each run starts empty.
const rotationRecord = path.join(process.cwd(), 'test-results', '.instance-password-rotated');
let activeInstancePassword = existsSync(rotationRecord) ? rotatedInstancePassword : environment.instance.password;

function recordRotation(): void {
  activeInstancePassword = rotatedInstancePassword;
  mkdirSync(path.dirname(rotationRecord), { recursive: true });
  writeFileSync(rotationRecord, '');
}

type LoginOutcome = 'tasks' | 'mandatory-change' | 'alert';

async function submitInstanceCredentials(page: Page, passwordValue: string): Promise<LoginOutcome> {
  await page.goto('/login');
  await page.getByLabel('Логин или Email').fill(environment.instance.login);
  const password = page.getByLabel('Пароль', { exact: true });
  try {
    await fillSecret(password, passwordValue);
    await page.getByRole('button', { name: 'Войти в систему' }).click();
    await Promise.race([
      page.waitForURL(/\/tasks(?:\?.*)?$/u),
      page.getByText('Смена временного пароля', { exact: true }).waitFor(),
      page.locator('#login-error').waitFor(),
    ]);
    // Editing/clearing a field intentionally removes stale inline errors.
    // Capture the outcome before the secret-cleanup input event runs.
    if (/\/tasks(?:\?.*)?$/u.test(page.url())) return 'tasks';
    if (await page.getByText('Смена временного пароля', { exact: true }).isVisible()) return 'mandatory-change';
    return 'alert';
  } finally {
    await clearSecret(password);
  }
}

async function completeMandatoryPasswordChange(page: Page): Promise<void> {
  const newPassword = page.getByLabel('Новый пароль', { exact: true });
  const confirmation = page.getByLabel('Повторите новый пароль', { exact: true });
  try {
    await fillSecret(newPassword, rotatedInstancePassword);
    await fillSecret(confirmation, rotatedInstancePassword);
    await page.getByRole('button', { name: 'Сменить пароль', exact: true }).click();
    // The toast shows it and the live announcer repeats it for screen readers: the toast is the visible one.
    await expect(page.locator('ui-toast-container').getByText('Пароль изменён. Войдите снова с новым паролем.', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Пароль', { exact: true })).toBeVisible();
  } finally {
    await Promise.all([clearSecret(newPassword), clearSecret(confirmation)]);
  }
  recordRotation();
  await submitInstanceCredentials(page, activeInstancePassword);
}

export async function loginToInstance(page: Page): Promise<void> {
  const outcome = await submitInstanceCredentials(page, activeInstancePassword);

  if (outcome === 'mandatory-change') {
    await completeMandatoryPasswordChange(page);
  } else if (outcome === 'alert'
    && activeInstancePassword !== rotatedInstancePassword) {
    recordRotation();
    await submitInstanceCredentials(page, activeInstancePassword);
  }

  await expect(page).toHaveURL(/\/tasks(?:\?.*)?$/u);
  const isMobile = (page.viewportSize()?.width ?? 1280) < 768;
  if (isMobile) {
    await expect(page.locator('.mobile-menu-btn')).toBeVisible();
  } else {
    await expect(page.locator('nav.sidebar-nav')).toBeVisible();
  }
}
