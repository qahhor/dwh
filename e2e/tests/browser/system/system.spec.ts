import { expect, test } from '@playwright/test';

import { expectNoSeriousAccessibilityViolations } from '../../../support/accessibility.js';
import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';

test('administrator can inspect and refresh local system status through the single origin', async ({ page }) => {
  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page);
  const origin = new URL(page.url()).origin;
  const initialResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/system/info'));

  await page.goto('/system');
  const systemResponse = await initialResponse;

  expect(systemResponse.ok()).toBe(true);
  expect(new URL(systemResponse.url()).origin).toBe(origin);
  const systemInfo = await systemResponse.json() as {
    organization: { code: string; name: string };
    schemaVersion: string;
  };
  expect(systemInfo.organization.name).not.toBe('');
  expect(systemInfo.organization.code).not.toBe('');
  await expect(page.getByRole('heading', { level: 1, name: 'Состояние системы' })).toBeVisible();
  const summary = page.getByLabel('Сводка установки');
  await expect(summary).toContainText(systemInfo.organization.name);
  await expect(summary).toContainText(systemInfo.organization.code);
  await expect(summary).toContainText(`Схема БД: ${systemInfo.schemaVersion}`);
  await expect(page.getByLabel('Состояние компонентов')).toContainText('PostgreSQL');
  await expect(page.getByTestId('backup-status')).toContainText('Резервное копирование');

  const refresh = page.getByRole('button', { name: 'Обновить состояние системы' });
  await refresh.focus();
  await expect(refresh).toBeFocused();
  const refreshResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/system/info'));
  await page.keyboard.press('Enter');
  expect((await refreshResponse).ok()).toBe(true);
  await expect(refresh).toBeEnabled();

  await expectNoSeriousAccessibilityViolations(page);
  assertNoPageErrors();
});

test('system status remains usable without horizontal overflow on a mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await loginToInstance(page);
  await page.goto('/system');

  await expect(page.getByRole('heading', { level: 1, name: 'Состояние системы' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Обновить состояние системы' })).toBeEnabled();
  const hasHorizontalOverflow = await page.evaluate(() =>
    document.documentElement.scrollWidth > window.innerWidth
  );
  expect(hasHorizontalOverflow).toBe(false);
  await expectNoSeriousAccessibilityViolations(page);
});
