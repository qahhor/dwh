import { expect, test, type Page } from '@playwright/test';
import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';

test.beforeEach(async ({ baseURL }) => {
  const configured = process.env.INSTANCE_BASE_URL;
  if (!configured || !baseURL || new URL(configured).origin !== new URL(baseURL).origin) {
    throw new Error('Search management E2E requires an explicit isolated INSTANCE_BASE_URL');
  }
  const target = new URL(baseURL);
  if (!['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname) || target.port === '4200') {
    throw new Error('Search management E2E requires a separate loopback QA origin, never port 4200');
  }
});

type FieldPolicy = { field: string; weight: number; numTypos: number; prefix: boolean };
type Policy = {
  globalLimit: number;
  requestsPerMinute: number;
  burst: number;
  schemaProfile: 'MIXED' | 'RU';
  fields: Record<'TASK' | 'PROJECT' | 'USER', FieldPolicy[]>;
};
type Settings = { version: number; policy: Policy };
type Job = { id: string; action: 'CHECK' | 'REBUILD' | 'ROLLBACK'; generationId: string; state: string };
type Status = {
  dependency: { enabled: boolean; healthy: boolean };
  initialized: boolean;
  activeProfile?: 'MIXED' | 'RU' | null;
  generations: Array<{ id: string; active: boolean }>;
};

async function csrfHeaders(page: Page): Promise<Record<string, string>> {
  const cookie = (await page.context().cookies(page.url())).find(value => value.name === 'XSRF-TOKEN');
  if (!cookie?.value) throw new Error('Authenticated search-management fixture has no CSRF cookie');
  return { 'X-XSRF-TOKEN': cookie.value };
}

async function api<T>(page: Page, method: 'GET' | 'PUT', path: string, expected: number, data?: unknown): Promise<T> {
  const response = await page.request.fetch(`/api/v1${path}`, {
    method,
    ...(method === 'GET' ? {} : { headers: await csrfHeaders(page), data })
  });
  try {
    if (response.status() !== expected) throw new Error(`${method} ${path} returned HTTP ${response.status()}, expected ${expected}`);
    return await response.json() as T;
  } finally {
    await response.dispose();
  }
}

async function terminalJob(page: Page, id: string): Promise<Job> {
  let latest: Job | undefined;
  await expect.poll(async () => {
    latest = await api<Job>(page, 'GET', `/search/jobs/${id}`, 200);
    return latest.state;
  }, { intervals: [500, 1000, 1500], timeout: 120_000 }).toMatch(/^(SUCCEEDED|FAILED|CANCELLED)$/u);
  if (!latest || latest.state !== 'SUCCEEDED') throw new Error(`Search job ${id} ended in ${latest?.state ?? 'UNKNOWN'}`);
  return latest;
}

async function openSearchSettings(page: Page): Promise<void> {
  await page.goto('/settings');
  await page.locator('#settings-search-tab').click();
  await expect(page.locator('#settings-search-panel app-search-settings')).toBeVisible();
}

test('real search management save/preview/check/rebuild exposes the current active generation', async ({ page }) => {
  test.setTimeout(240_000);
  await loginToInstance(page);
  const assertHealthy = collectPageErrors(page);
  await openSearchSettings(page);
  const original = await api<Settings>(page, 'GET', '/search/settings', 200);
  const initialStatus = await api<Status>(page, 'GET', '/search/status', 200);
  expect(initialStatus.generations.length).toBeLessThan(4);
  let currentVersion = original.version;

  try {
    const changedLimit = original.policy.globalLimit === 50 ? 49 : original.policy.globalLimit + 1;
    const limit = page.locator('#search-global-limit');
    await limit.fill(String(changedLimit));
    const savedResponse = page.waitForResponse(response => response.request().method() === 'PUT'
      && new URL(response.url()).pathname === '/api/v1/search/settings');
    await page.locator('button[data-action="save-search-settings"]').click();
    const saved = await savedResponse;
    expect(saved.status()).toBe(200);
    currentVersion = ((await saved.json()) as Settings).version;
    await expect(page.locator('[data-state="policy-clean"]')).toBeVisible();

    const unsavedPreviewLimit = changedLimit === 50 ? 49 : changedLimit + 1;
    await limit.fill(String(unsavedPreviewLimit));
    await page.locator('#search-preview-query').fill('search-management-preview-no-save');
    const previewRequest = page.waitForRequest(request => request.method() === 'POST'
      && new URL(request.url()).pathname === '/api/v1/search/preview');
    await page.locator('button[data-action="preview-search-settings"]').click();
    const previewBody = (await previewRequest).postDataJSON() as { q: string; policy: Policy };
    expect(previewBody.q).toBe('search-management-preview-no-save');
    expect(previewBody.policy.globalLimit).toBe(unsavedPreviewLimit);
    await expect(page.locator('[data-active-profile]')).toHaveAttribute('data-active-profile', initialStatus.activeProfile ?? 'MIXED');

    await limit.fill(String(original.policy.globalLimit));
    const restoredResponse = page.waitForResponse(response => response.request().method() === 'PUT'
      && new URL(response.url()).pathname === '/api/v1/search/settings');
    await page.locator('button[data-action="save-search-settings"]').click();
    const restored = await restoredResponse;
    expect(restored.status()).toBe(200);
    currentVersion = ((await restored.json()) as Settings).version;

    const checkResponse = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/search/jobs');
    await page.locator('button[data-action="start-check"]').click();
    const checkReceipt = await checkResponse;
    expect(checkReceipt.status()).toBe(202);
    await terminalJob(page, ((await checkReceipt.json()) as { id: string }).id);

    await expect(page.locator('button[data-action="start-rebuild"]')).toBeEnabled();
    await page.locator('button[data-action="start-rebuild"]').click();
    await expect(page.getByRole('dialog')).toBeVisible();
    const rebuildResponse = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/search/jobs');
    await page.locator('button[data-action="confirm-search-maintenance"]').click();
    const rebuildReceipt = await rebuildResponse;
    expect(rebuildReceipt.status()).toBe(202);
    const rebuilt = await terminalJob(page, ((await rebuildReceipt.json()) as { id: string }).id);
    const finalStatus = await api<Status>(page, 'GET', '/search/status', 200);
    expect(finalStatus.dependency).toMatchObject({ enabled: true, healthy: true });
    expect(finalStatus.initialized).toBe(true);
    expect(finalStatus.generations.find(generation => generation.active)?.id).toBe(rebuilt.generationId);
    assertHealthy();
  } finally {
    const current = await api<Settings>(page, 'GET', '/search/settings', 200);
    if (JSON.stringify(current.policy) !== JSON.stringify(original.policy)) {
      await api<Settings>(page, 'PUT', '/search/settings', 200, { version: current.version || currentVersion, policy: original.policy });
    }
  }
});

test('[controlled HTTP] 503, 409 and late status preserve local errors and the unsaved draft', async ({ page }) => {
  await loginToInstance(page);
  const expectedConsole = [
    /^Failed to load resource: the server responded with a status of 503/u,
    /^Failed to load resource: the server responded with a status of 409/u
  ];
  const assertHealthy = collectPageErrors(page, expectedConsole);
  let statusCalls = 0;
  await page.route('**/api/v1/search/status', async route => {
    statusCalls++;
    if (statusCalls === 1) return route.fulfill({ status: 503, json: { code: 'SERVICE_UNAVAILABLE', detail: 'Контролируемая недоступность статуса' } });
    return route.continue();
  });
  await openSearchSettings(page);
  await expect(page.getByRole('alert')).toContainText('Контролируемая недоступность статуса');
  await page.locator('button[data-action="refresh-search-status"]').click();
  await expect(page.locator('[data-status="healthy"]')).toBeVisible();
  await page.unroute('**/api/v1/search/status');

  const limit = page.locator('#search-global-limit');
  const before = Number(await limit.inputValue());
  const draftLimit = before === 50 ? 49 : before + 1;
  await limit.fill(String(draftLimit));
  await page.route('**/api/v1/search/settings', async route => {
    if (route.request().method() !== 'PUT') return route.continue();
    return route.fulfill({ status: 409, json: { code: 'CONFLICT', detail: 'Контролируемый конфликт версии' } });
  });
  await page.locator('button[data-action="save-search-settings"]').click();
  await expect(page.locator('[data-state="save-error"]')).toContainText('Контролируемый конфликт версии');
  await expect(limit).toHaveValue(String(draftLimit));
  await expect(page.locator('button[data-action="reload-search-settings"]')).toBeVisible();
  await expect(page.locator('.toast-container')).toHaveCount(0);
  await page.unroute('**/api/v1/search/settings');

  const realStatus = await api<Status>(page, 'GET', '/search/status', 200);
  let releaseLate!: () => void;
  const late = new Promise<void>(resolve => releaseLate = resolve);
  await page.route('**/api/v1/search/status', async route => {
    await late;
    await route.fulfill({ status: 200, json: realStatus });
  });
  const lateResponse = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/search/status');
  await page.locator('button[data-action="refresh-search-status"]').click();
  await limit.fill(String(draftLimit));
  releaseLate();
  expect((await lateResponse).status()).toBe(200);
  await expect(limit).toHaveValue(String(draftLimit));
  await page.unroute('**/api/v1/search/status');

  await page.route('**/api/v1/search/preview', route => route.fulfill({
    status: 503,
    json: { code: 'SERVICE_UNAVAILABLE', detail: 'Контролируемая недоступность preview' }
  }));
  await page.locator('#search-preview-query').fill('controlled-preview');
  await page.locator('button[data-action="preview-search-settings"]').click();
  await expect(page.locator('[data-state="preview-error"]')).toContainText('Контролируемая недоступность preview');
  await expect(page.locator('.toast-container')).toHaveCount(0);
  assertHealthy();
});
