import { randomBytes } from 'node:crypto';
import { expect, test, type Page, type Response } from '@playwright/test';
import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors, uniqueRunName } from '../../../support/diagnostics.js';
import { expectNoSeriousAccessibilityViolations } from '../../../support/accessibility.js';

// These fixtures belong to the companion package's disposable, migrated QA
// runtime. Never fall back to the repository's default installation on 4200.
test.beforeEach(async ({ baseURL }) => {
  const configured = process.env.INSTANCE_BASE_URL;
  if (!configured || !baseURL || new URL(configured).origin !== new URL(baseURL).origin) {
    throw new Error('Search reliability E2E requires an explicit isolated INSTANCE_BASE_URL');
  }
  const target = new URL(baseURL);
  if (!['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname)
    || ['4200', '14200', '14203', '14204', '14205', '14206'].includes(target.port)) {
    throw new Error('Search reliability E2E requires a separate loopback QA origin');
  }
});

type Category = 'TASK' | 'PROJECT' | 'USER';
type SearchResponse = {
  source: 'TYPESENSE' | 'POSTGRES'; degraded: boolean;
  hits: Array<{ id: string; entityType: Category; title: string }>;
};

async function csrfHeaders(page: Page): Promise<Record<string, string>> {
  const cookie = (await page.context().cookies(page.url())).find(value => value.name === 'XSRF-TOKEN');
  if (!cookie?.value) throw new Error('Authenticated fixture has no CSRF cookie');
  return { 'X-XSRF-TOKEN': cookie.value };
}

async function api<T>(page: Page, method: 'GET' | 'POST' | 'PATCH', path: string, expected: number, data?: unknown): Promise<T> {
  const response = await page.request.fetch(`/api/v1${path}`, {
    method, ...(method === 'GET' ? {} : { headers: await csrfHeaders(page), data }),
  });
  try {
    if (response.status() !== expected) throw new Error(`Synthetic ${method} fixture returned HTTP ${response.status()}, expected ${expected}`);
    return expected === 204 ? undefined as T : await response.json() as T;
  } finally {
    await response.dispose();
  }
}

async function indexed(page: Page, query: string, category: Category, id: string, present: boolean): Promise<void> {
  await expect.poll(async () => {
    const response = await api<SearchResponse>(page, 'GET', `/search?q=${encodeURIComponent(query)}&entity=${category}`, 200);
    return {
      source: response.source, degraded: response.degraded,
      present: response.hits.some(hit => hit.entityType === category && hit.id === id),
    };
  }, { message: 'observable indexed-data delivery, never SQL-fallback acceptance', intervals: [1000], timeout: 30_000 })
    .toEqual({ source: 'TYPESENSE', degraded: false, present });
}

async function openPalette(page: Page, query: string, category: Category | 'ALL') {
  await page.keyboard.press('Control+k');
  const palette = page.getByRole('dialog', { name: 'Глобальный поиск', exact: true });
  await expect(palette).toBeVisible();
  await palette.getByLabel('Категория поиска').selectOption(category);
  await palette.locator('input[role="combobox"]').fill(query);
  return palette;
}

test('real indexed task/project/user hits open fresh exact records, survive reload/back, and exclude passive records', async ({ page }) => {
  test.setTimeout(120_000);
  await loginToInstance(page);
  const assertHealthy = collectPageErrors(page);
  const marker = uniqueRunName('E2Esearch');
  const project = await api<{ id: number }>(page, 'POST', '/tasks/projects', 201,
    { name: `${marker} project`, description: 'Synthetic search project', state: 'A', attributes: {} });
  const task = await api<{ id: number }>(page, 'POST', '/tasks', 201,
    { title: `${marker} task`, descriptionMarkdown: 'Synthetic search task', projectId: project.id, priority: 'medium', attributes: { task_type: 'task' } });
  const login = `search${randomBytes(8).toString('hex')}`;
  const user = await api<{ id: number }>(page, 'POST', '/iam/users', 201,
    { name: `${marker} user`, login, email: `${login}@example.invalid`, password: `Qa!7${randomBytes(20).toString('hex')}`,
      language: 'ru', timezone: 'Asia/Tashkent', is2faEnabled: false, forcePasswordChange: true, roleIds: [], attributes: {} });
  const records = [
    { category: 'TASK' as const, id: String(task.id), endpoint: `/tasks/${task.id}`, route: `/tasks/items/${task.id}`, name: 'task', field: 'title' },
    { category: 'PROJECT' as const, id: String(project.id), endpoint: `/tasks/projects/${project.id}`, route: `/tasks/projects/${project.id}`, name: 'project', field: 'name' },
    { category: 'USER' as const, id: String(user.id), endpoint: `/iam/users/${user.id}`, route: `/iam/users/${user.id}`, name: 'user', field: 'name' },
  ];

  for (const record of records) {
    await indexed(page, marker, record.category, record.id, true);
    await page.goto('/tasks');
    // Real category/ID lookup is intentionally PostgreSQL; indexing was proven
    // separately above with an ordinary query and explicit Typesense provenance.
    const palette = await openPalette(page, `#${record.id}`, record.category);
    const hit = palette.getByRole('option').filter({ hasText: `${marker} ${record.name}` });
    await expect(hit).toHaveCount(1);
    await expect(palette.locator('.palette-degraded')).toHaveCount(0);
    const freshName = `${marker} refreshed ${record.name}`;
    await api(page, 'PATCH', record.endpoint, 204, { [record.field]: freshName });
    const detailResponse = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === `/api/v1${record.endpoint}`);
    await hit.click();
    expect((await detailResponse).status()).toBe(200);
    await expect(page).toHaveURL(new RegExp(`${record.route}$`, 'u'));
    const detail = page.locator(`[data-record-id="${record.id}"]`);
    await expect(detail).toContainText(freshName);
    if (record.category === 'PROJECT') await expect(page.locator('.project-edit-form')).toHaveCount(0);
    await page.reload();
    await expect(page).toHaveURL(new RegExp(`${record.route}$`, 'u'));
    await expect(detail).toContainText(freshName);
    await page.goBack();
    await expect(page).toHaveURL(/\/tasks$/u);
    await expect(page.getByRole('dialog')).toHaveCount(0);
  }

  await api(page, 'PATCH', `/tasks/projects/${project.id}`, 204, { state: 'P' });
  await api(page, 'POST', `/iam/users/${user.id}/block`, 204, {});
  await indexed(page, marker, 'PROJECT', String(project.id), false);
  await indexed(page, marker, 'USER', String(user.id), false);
  // Passive search exclusion does not redefine the existing detail policy.
  expect((await api<{ state: string }>(page, 'GET', `/tasks/projects/${project.id}`, 200)).state).toBe('P');
  expect((await api<{ state: string }>(page, 'GET', `/iam/users/${user.id}`, 200)).state).toBe('P');
  assertHealthy();
  // No task/project delete endpoint exists. The final QA runtime/volumes are
  // disposable; passive synthetic project/user records remain for evidence.
});

test('real missing canonical IDs show localized 404 and a working list action', async ({ page }) => {
  await loginToInstance(page);
  const failures: Array<{ method: string; path: string }> = [];
  page.on('response', response => {
    if (response.status() === 404) failures.push({ method: response.request().method(), path: new URL(response.url()).pathname });
  });
  const assertHealthy = collectPageErrors(page, [/^Failed to load resource: the server responded with a status of 404 \((?:Not Found)?\)$/u]);
  const absentId = '9223372036854775807';
  for (const record of [
    { route: '/tasks/items', endpoint: '/tasks' },
    { route: '/tasks/projects', endpoint: '/tasks/projects' },
    { route: '/iam/users', endpoint: '/iam/users' },
  ]) {
    // Verify this is an actually absent ID, not a passive or anonymized record.
    const preflight = await page.request.get(`/api/v1${record.endpoint}/${absentId}`);
    const status = preflight.status(); await preflight.dispose();
    expect(status).toBe(404);
    const missing = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === `/api/v1${record.endpoint}/${absentId}`);
    await page.goto(`${record.route}/${absentId}`);
    expect((await missing).status()).toBe(404);
    await expect(page.getByRole('alert')).toHaveText(/404 — Запись не найдена или недоступна/u);
    await page.getByRole('button', { name: 'Вернуться к списку', exact: true }).first().click();
    await expect(page).toHaveURL(new RegExp(`${record.route}$`, 'u'));
    await expect(page.getByRole('dialog')).toHaveCount(0);
  }
  expect(failures.filter(failure => !failure.path.endsWith('/comments'))).toEqual([
    { method: 'GET', path: `/api/v1/tasks/${absentId}` },
    { method: 'GET', path: `/api/v1/tasks/projects/${absentId}` },
    { method: 'GET', path: `/api/v1/iam/users/${absentId}` },
  ]);
  expect(failures.filter(failure => failure.path.endsWith('/comments'))
    .every(failure => failure.method === 'GET' && failure.path === `/api/v1/tasks/${absentId}/comments`)).toBe(true);
  assertHealthy();
});

test('controlled search outage and 429: one local error, visible cooldown and manual retry on mobile', async ({ page }) => {
  await loginToInstance(page);
  await page.setViewportSize({ width: 390, height: 844 });
  const errors: Array<{ method: string; path: string; status: number }> = [];
  const onResponse = (response: Response) => {
    if ([429, 503].includes(response.status())) errors.push({ method: response.request().method(), path: new URL(response.url()).pathname, status: response.status() });
  };
  page.on('response', onResponse);
  const assertHealthy = collectPageErrors(page, [
    /^Failed to load resource: the server responded with a status of 503 \(Service Unavailable\)$/u,
    /^Failed to load resource: the server responded with a status of 429 \(Too Many Requests\)$/u,
  ]);
  let calls = 0;
  await page.route('**/api/v1/search?*', async route => {
    if (route.request().method() !== 'GET' || new URL(route.request().url()).pathname !== '/api/v1/search') return route.continue();
    calls++;
    if (calls === 1) return route.fulfill({ status: 503, json: { code: 'SEARCH_UNAVAILABLE', detail: 'Поиск временно недоступен' } });
    if (calls === 2) return route.fulfill({ status: 429, headers: { 'Retry-After': '2' }, json: { code: 'RATE_LIMITED', detail: 'rate limited' } });
    return route.fulfill({ status: 200, json: { query: 'new', totalHits: 0, hits: [], foundHits: null, hasMore: false, source: 'POSTGRES', degraded: true } });
  });
  const palette = await openPalette(page, 'outage', 'ALL');
  await expect(palette.getByRole('alert')).toContainText('Поиск временно недоступен');
  await expect(page.locator('.toast-container')).toHaveCount(0);
  await palette.getByRole('button', { name: 'Повторить', exact: true }).click();
  const retry = palette.getByRole('button', { name: 'Повторить', exact: true });
  await expect(retry).toBeDisabled();
  await expect(palette.getByRole('alert')).toContainText('Повторить можно через');
  await palette.locator('input[role="combobox"]').fill('new');
  await expect(retry).toBeEnabled();
  expect(calls).toBe(2); // Countdown expiration did not start a retry.
  await retry.click();
  await expect(palette.locator('.palette-empty')).toBeVisible();
  await expect(palette.locator('.palette-degraded')).toBeVisible();
  expect(calls).toBe(3);
  const box = await palette.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(390);
  await expectNoSeriousAccessibilityViolations(page, '.palette-dialog');
  await palette.getByRole('button', { name: 'Закрыть поиск', exact: true }).click();
  await expect(palette).toHaveCount(0);
  page.off('response', onResponse);
  expect(errors).toEqual([
    { method: 'GET', path: '/api/v1/search', status: 503 },
    { method: 'GET', path: '/api/v1/search', status: 429 },
  ]);
  assertHealthy();
});
