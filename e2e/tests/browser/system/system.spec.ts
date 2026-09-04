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
    backup: {
      status: string;
      freshness: string;
      ageSeconds: number | null;
      maxAgeSeconds: number | null;
    };
    checkedAt: string;
  };
  expect(systemInfo.organization.name).not.toBe('');
  expect(systemInfo.organization.code).not.toBe('');
  await expect(page.getByRole('heading', { level: 1, name: 'Состояние системы' })).toBeVisible();
  await expect(page.getByTestId('overall-status')).toHaveAttribute('data-status', /^(healthy|attention|unavailable)$/);
  await expect(page.getByTestId('overall-status')).toContainText('Проверено:');
  expect(Number.isNaN(Date.parse(systemInfo.checkedAt))).toBe(false);
  expect(['CURRENT', 'STALE', 'NOT_CONFIGURED', 'NOT_APPLICABLE', 'UNKNOWN'])
    .toContain(systemInfo.backup.freshness);
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
  const components = await page.getByLabel('Состояние компонентов').boundingBox();
  expect(components).not.toBeNull();
  expect(components!.y).toBeLessThan(844);
  const hasHorizontalOverflow = await page.evaluate(() =>
    document.documentElement.scrollWidth > window.innerWidth
  );
  expect(hasHorizontalOverflow).toBe(false);
  await expectNoSeriousAccessibilityViolations(page);
});

test('failed refresh keeps the last snapshot, marks it stale, and does not duplicate the error in a toast', async ({ page }) => {
  await loginToInstance(page);
  let systemInfoRequests = 0;
  await page.route('**/api/v1/system/info', async route => {
    systemInfoRequests += 1;
    if (systemInfoRequests === 1) {
      await route.continue();
      return;
    }
    await route.abort('failed');
  });

  await page.goto('/system');
  await expect(page.getByTestId('overall-status')).toBeVisible();
  await page.getByRole('button', { name: 'Обновить состояние системы' }).click();

  await expect(page.getByTestId('stale-status')).toContainText('Не удалось обновить состояние');
  await expect(page.getByTestId('stale-status')).toContainText('Показаны данные от');
  await expect(page.locator('.toast-item')).toHaveCount(0);
  await expect(page.getByTestId('overall-status')).toBeVisible();
});

test('stale successful backup is critical and prevents a healthy aggregate status', async ({ page }) => {
  await loginToInstance(page);
  await page.route('**/api/v1/system/info', async route => {
    const response = await route.fetch();
    const info = await response.json();
    await route.fulfill({
      response,
      json: {
        ...info,
        components: {
          database: { status: 'UP' },
          storage: { status: 'UP' },
          typesense: { status: 'DISABLED' }
        },
        backup: {
          status: 'SUCCESS',
          completedAt: '2026-09-02T09:00:00Z',
          failureCode: null,
          freshness: 'STALE',
          ageSeconds: 176_400,
          maxAgeSeconds: 86_400
        }
      }
    });
  });

  await page.goto('/system');

  await expect(page.getByTestId('backup-status')).toHaveAttribute('data-severity', 'critical');
  await expect(page.getByTestId('backup-status')).toContainText('Резервная копия устарела');
  await expect(page.getByTestId('backup-status')).toContainText('Допустимый возраст: 1 д.');
  await expect(page.getByTestId('overall-status')).toHaveAttribute('data-status', 'attention');
});
