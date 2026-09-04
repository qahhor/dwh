import { expect, test, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';

const stats = {
  totalAuditLogs: 41,
  totalSecurityEvents: 0,
  securityEventsLast24h: 0,
  failedLoginsLast24h: 0,
};

const firstPage = {
  items: [
    {
      id: 103,
      tableName: 'md_users',
      rowPk: '42',
      event: 'U',
      changedBy: 7,
      isApi: false,
      changedAt: '2026-09-04T10:03:00Z',
      changedColumns: ['password_hash'],
      oldRow: { password_hash: '[REDACTED]' },
      newRow: { password_hash: '[REDACTED]' },
      changedByName: 'Администратор',
      changedByLogin: 'admin',
    },
    {
      id: 102,
      tableName: 'ms_tasks',
      rowPk: '17',
      event: 'I',
      isApi: true,
      changedAt: '2026-09-04T10:02:00Z',
      changedColumns: ['name'],
      oldRow: null,
      newRow: { name: 'Проверка аудита' },
    },
  ],
  nextCursor: 'audit-next',
  hasMore: true,
  totalEstimated: 41,
};

const secondPage = {
  items: [
    {
      id: 101,
      tableName: 'ms_projects',
      rowPk: '9',
      event: 'D',
      isApi: false,
      changedAt: '2026-09-04T10:01:00Z',
      changedColumns: [],
      oldRow: { name: 'Архивный проект' },
      newRow: null,
    },
  ],
  nextCursor: null,
  hasMore: false,
  totalEstimated: 41,
};

const emptySecurityPage = {
  items: [],
  nextCursor: null,
  hasMore: false,
  totalEstimated: 0,
};

async function mockAuditShell(page: Page): Promise<void> {
  await page.route('**/api/v1/audit/stats', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(stats),
  }));
  await page.route('**/api/v1/audit/security-events*', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(emptySecurityPage),
  }));
}

test('audit pagination is stable, secrets stay redacted, and filter reset drops the cursor', async ({ page }) => {
  await loginToInstance(page);
  await mockAuditShell(page);

  const requestedLogUrls: URL[] = [];
  await page.route('**/api/v1/audit/logs*', route => {
    const url = new URL(route.request().url());
    requestedLogUrls.push(url);
    const body = url.searchParams.get('row_pk')
      ? { ...firstPage, items: [firstPage.items[0]], nextCursor: null, hasMore: false, totalEstimated: 1 }
      : url.searchParams.get('cursor') === 'audit-next'
        ? secondPage
        : firstPage;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(body),
    });
  });

  await page.goto('/audit');
  await expect(page.getByRole('heading', { level: 1, name: 'Аудит и безопасность' })).toBeVisible();

  const firstIds = await page.locator('tbody tr td:first-child').allTextContents();
  expect(firstIds).toEqual(['#103', '#102']);
  expect(new Set(firstIds).size).toBe(firstIds.length);

  await page.getByRole('button', { name: 'Просмотреть изменение #103' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('password_hash');
  await expect(dialog).toContainText('[REDACTED]');
  await dialog.getByRole('button', { name: 'Закрыть', exact: true }).click();

  await page.getByRole('button', { name: 'Следующая страница' }).click();
  await expect(page.getByRole('button', { name: 'Просмотреть изменение #101' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Просмотреть изменение #103' })).toHaveCount(0);
  expect(requestedLogUrls.at(-1)?.searchParams.get('cursor')).toBe('audit-next');

  await page.getByRole('button', { name: 'Предыдущая страница' }).click();
  await expect(page.getByRole('button', { name: 'Просмотреть изменение #103' })).toBeVisible();

  await page.getByLabel('Ключ записи (PK)').fill('42');
  await page.getByLabel('ID пользователя').fill('7');
  await page.getByLabel('Дата с (UTC)').fill('2026-09-01');
  await page.getByLabel('Дата по (UTC)').fill('2026-09-04');
  await page.getByLabel('Фильтр журнала по действию').selectOption('U');
  await page.getByRole('button', { name: 'Применить' }).click();

  const filteredRequest = requestedLogUrls.at(-1);
  expect(filteredRequest?.searchParams.get('row_pk')).toBe('42');
  expect(filteredRequest?.searchParams.get('user_id')).toBe('7');
  expect(filteredRequest?.searchParams.get('event')).toBe('U');
  expect(filteredRequest?.searchParams.get('from')).toBe('2026-09-01T00:00:00.000Z');
  expect(filteredRequest?.searchParams.get('to')).toBe('2026-09-04T23:59:59.999Z');
  expect(filteredRequest?.searchParams.has('cursor')).toBe(false);

  await page.getByRole('button', { name: 'Сбросить фильтры' }).click();
  await expect(page.getByLabel('Ключ записи (PK)')).toHaveValue('');
  await expect(page.getByLabel('ID пользователя')).toHaveValue('');
  await expect(page.getByLabel('Дата с (UTC)')).toHaveValue('');
  await expect(page.getByLabel('Дата по (UTC)')).toHaveValue('');
  await expect(page.getByLabel('Фильтр журнала по действию')).toHaveValue('');

  const resetRequest = requestedLogUrls.at(-1);
  for (const parameter of ['row_pk', 'user_id', 'event', 'from', 'to', 'cursor']) {
    expect(resetRequest?.searchParams.has(parameter)).toBe(false);
  }
});

test('audit log error is visible and retry restores data without a reload', async ({ page }) => {
  await loginToInstance(page);
  await mockAuditShell(page);

  let attempts = 0;
  await page.route('**/api/v1/audit/logs*', route => {
    attempts += 1;
    if (attempts === 1) {
      return route.fulfill({
        status: 503,
        contentType: 'application/problem+json',
        body: JSON.stringify({ code: 'service_unavailable', detail: 'temporary failure' }),
      });
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(firstPage),
    });
  });

  await page.goto('/audit');
  const alert = page.getByRole('alert').filter({ hasText: 'Не удалось загрузить журнал изменений.' });
  await expect(alert).toBeVisible();
  await alert.getByRole('button', { name: 'Повторить' }).click();
  await expect(alert).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Просмотреть изменение #103' })).toBeVisible();
  expect(attempts).toBe(2);
});

test('forbidden audit response is shown inline instead of an empty success state', async ({ page }) => {
  await loginToInstance(page);
  await mockAuditShell(page);
  await page.route('**/api/v1/audit/logs*', route => route.fulfill({
    status: 403,
    contentType: 'application/problem+json',
    body: JSON.stringify({ code: 'permission_denied', detail: 'Недостаточно прав' }),
  }));

  await page.goto('/audit');

  await expect(page.getByRole('alert').filter({ hasText: 'Не удалось загрузить журнал изменений.' })).toBeVisible();
  await expect(page.getByText('Записей аудита не найдено')).toHaveCount(0);
});

test('live audit API preserves the page contract and rejects a malformed cursor', async ({ page }) => {
  await loginToInstance(page);

  const result = await page.evaluate(async () => {
    const readJson = async (path: string) => {
      const response = await fetch(path, { credentials: 'include' });
      return { status: response.status, body: await response.json() };
    };
    return {
      stats: await readJson('/api/v1/audit/stats'),
      logs: await readJson('/api/v1/audit/logs?limit=2'),
      security: await readJson('/api/v1/audit/security-events?limit=2'),
      malformed: await readJson('/api/v1/audit/logs?limit=2&cursor=malformed'),
    };
  });

  expect(result.stats.status).toBe(200);
  expect(result.stats.body).toEqual(expect.objectContaining({
    totalAuditLogs: expect.any(Number),
    totalSecurityEvents: expect.any(Number),
    securityEventsLast24h: expect.any(Number),
    failedLoginsLast24h: expect.any(Number),
  }));

  for (const response of [result.logs, result.security]) {
    expect(response.status).toBe(200);
    expect(response.body).toEqual(expect.objectContaining({
      items: expect.any(Array),
      hasMore: expect.any(Boolean),
      totalEstimated: expect.any(Number),
    }));
    expect(response.body).toHaveProperty('nextCursor');
  }

  expect(result.malformed.status).toBe(400);
  expect(result.malformed.body).toEqual(expect.objectContaining({ code: 'bad_request' }));
});
