import { expect, test, type Page, type Response } from '@playwright/test';
import { AxeBuilder } from '@axe-core/playwright';
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

const controlledPolicy: Policy = {
  globalLimit: 10,
  requestsPerMinute: 120,
  burst: 20,
  schemaProfile: 'MIXED',
  fields: {
    TASK: [
      { field: 'title', weight: 10, numTypos: 2, prefix: true },
      { field: 'description_markdown', weight: 3, numTypos: 2, prefix: true },
      { field: 'status_name', weight: 2, numTypos: 2, prefix: true },
      { field: 'project_name', weight: 2, numTypos: 2, prefix: true }
    ],
    PROJECT: [
      { field: 'name', weight: 10, numTypos: 2, prefix: true },
      { field: 'description', weight: 3, numTypos: 2, prefix: true }
    ],
    USER: [
      { field: 'name', weight: 10, numTypos: 2, prefix: true },
      { field: 'login', weight: 8, numTypos: 0, prefix: true },
      { field: 'email', weight: 6, numTypos: 0, prefix: true },
      { field: 'phone', weight: 6, numTypos: 0, prefix: true }
    ]
  }
};

const controlledStatus = {
  dependency: {
    enabled: true, healthy: true, version: '27.1.0',
    installationDiskUsedBytes: 2048, installationDiskTotalBytes: 8192
  },
  initialized: true,
  activeProfile: 'MIXED',
  configuredProfile: 'MIXED',
  rebuildRequired: false,
  settingsDegraded: false,
  lastSuccessfulReconciliation: '2026-09-07T12:00:00Z',
  generations: [{
    id: 'controlled-generation', state: 'ACTIVE', active: true, registeredProfile: 'MIXED',
    documentCount: 14, entityDocumentCounts: { TASK: 8, PROJECT: 4, USER: 2 },
    storageBytes: 1024, schemaMatches: true, pendingDeliveries: 0, failedDeliveries: 0,
    queueLagSeconds: 0, createdAt: '2026-09-07T11:00:00Z'
  }],
  budgets: {
    connectTimeoutMs: 500, readTimeoutMs: 1500, fallbackTimeoutMs: 2000,
    searchRate: { user: { perMinute: 120, capacity: 20 }, api: { perMinute: 90, capacity: 20 } }
  },
  jobs: [],
  rollbackTargets: []
};

async function csrfHeaders(page: Page): Promise<Record<string, string>> {
  const cookie = (await page.context().cookies(page.url())).find(value => value.name === 'XSRF-TOKEN');
  if (!cookie?.value) throw new Error('Authenticated search-management fixture has no CSRF cookie');
  return { 'X-XSRF-TOKEN': cookie.value };
}

async function cleanupApi<T>(page: Page, method: 'GET' | 'PUT', path: string, data?: unknown): Promise<T> {
  for (let attempt = 0; attempt < 3; attempt++) {
    const response = await page.request.fetch(`/api/v1${path}`, {
      method,
      ...(method === 'GET' ? {} : { headers: await csrfHeaders(page), data })
    });
    try {
      if (response.status() === 429 && attempt < 2) {
        const retryAfter = Number(response.headers()['retry-after']);
        if (!Number.isInteger(retryAfter) || retryAfter < 1 || retryAfter > 30) {
          throw new Error(`Cleanup ${method} ${path} returned an invalid Retry-After`);
        }
        await page.waitForTimeout(retryAfter * 1000 + 100);
        continue;
      }
      if (response.status() !== 200) throw new Error(`Cleanup ${method} ${path} returned HTTP ${response.status()}, expected 200`);
      return await response.json() as T;
    } finally {
      await response.dispose();
    }
  }
  throw new Error(`Cleanup ${method} ${path} exhausted its bounded rate-limit retries`);
}

async function terminalJobFromUi(page: Page, id: string, action: Job['action']): Promise<Job> {
  let latest: Job | undefined;
  let responseError: unknown;
  const observe = (response: Response) => {
    if (response.request().method() !== 'GET'
      || new URL(response.url()).pathname !== `/api/v1/search/jobs/${id}`
      || response.status() !== 200) return;
    void response.json().then(value => latest = value as Job).catch(error => responseError = error);
  };
  page.on('response', observe);
  try {
    await expect.poll(() => {
      if (responseError) throw responseError;
      return latest?.state ?? 'NOT_OBSERVED';
    }, { message: 'terminal state observed from the real UI poll stream', intervals: [250], timeout: 120_000 })
      .toMatch(/^(SUCCEEDED|FAILED|CANCELLED)$/u);
  } finally {
    page.off('response', observe);
  }
  if (!latest || latest.state !== 'SUCCEEDED') throw new Error(`Search job ${id} ended in ${latest?.state ?? 'UNKNOWN'}`);
  await expect(page.locator('.active-job strong')).toHaveText(`${action} · SUCCEEDED`);
  return latest;
}

async function openSearchSettings(page: Page): Promise<{ settings: Response; status: Response }> {
  await page.goto('/settings');
  const settings = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/search/settings');
  const status = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/search/status');
  await page.locator('#settings-search-tab').click();
  await expect(page.locator('#settings-search-panel app-search-settings')).toBeVisible();
  return { settings: await settings, status: await status };
}

test('[controlled HTTP/layout] 320px keyboard navigation keeps settings content in view and scrolls only the tab strip', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 900 });
  await loginToInstance(page);
  const assertHealthy = collectPageErrors(page);
  const controlledReads: Array<{ method: string; path: string }> = [];
  await page.route('**/api/v1/search/settings', route => {
    if (route.request().method() !== 'GET') return route.continue();
    controlledReads.push({ method: 'GET', path: new URL(route.request().url()).pathname });
    return route.fulfill({ status: 200, json: { version: 7, policy: controlledPolicy } });
  });
  await page.route('**/api/v1/search/status', route => {
    if (route.request().method() !== 'GET') return route.continue();
    controlledReads.push({ method: 'GET', path: new URL(route.request().url()).pathname });
    return route.fulfill({ status: 200, json: controlledStatus });
  });
  await page.route('**/api/v1/search/jobs?limit=20', route => {
    if (route.request().method() !== 'GET') return route.continue();
    controlledReads.push({ method: 'GET', path: new URL(route.request().url()).pathname });
    return route.fulfill({ status: 200, json: { items: [], hasMore: false } });
  });
  await page.goto('/settings');

  const tabs: Array<[string, string]> = [
    ['settings-general-tab', 'settings-general-panel'],
    ['settings-security-tab', 'settings-security-panel'],
    ['settings-storage-tab', 'settings-storage-panel'],
    ['settings-preferences-tab', 'settings-preferences-panel'],
    ['settings-languages-tab', 'settings-languages-panel'],
    ['settings-search-tab', 'settings-search-panel']
  ];
  const readLayout = (tabId: string, panelId: string) => page.evaluate(({ tabId, panelId }) => {
    const toolbar = document.querySelector<HTMLElement>('.settings-page > .toolbar');
    const pageContent = document.querySelector<HTMLElement>('.page-content');
    const header = document.querySelector<HTMLElement>('.settings-page > .view-header');
    const panel = document.querySelector<HTMLElement>(`#${panelId}`);
    const tab = document.querySelector<HTMLElement>(`#${tabId}`);
    if (!toolbar || !pageContent || !header || !panel || !tab) throw new Error('Settings layout fixture is incomplete');
    const toolbarRect = toolbar.getBoundingClientRect();
    const contentRect = pageContent.getBoundingClientRect();
    const panelRect = panel.getBoundingClientRect();
    const tabRect = tab.getBoundingClientRect();
    const tabStyle = getComputedStyle(tab);
    const outlineExpansion = Number.parseFloat(tabStyle.outlineWidth) + Number.parseFloat(tabStyle.outlineOffset);
    return {
      toolbarClientWidth: toolbar.clientWidth,
      toolbarScrollWidth: toolbar.scrollWidth,
      toolbarScrollLeft: toolbar.scrollLeft,
      pageContentScrollLeft: pageContent.scrollLeft,
      pageContentScrollTop: pageContent.scrollTop,
      documentClientWidth: document.documentElement.clientWidth,
      documentScrollWidth: document.documentElement.scrollWidth,
      panelWidth: panelRect.width,
      panelHeight: panelRect.height,
      panelLeft: panelRect.left,
      panelRight: panelRect.right,
      contentLeft: contentRect.left,
      contentRight: contentRect.right,
      tabLeft: tabRect.left,
      tabRight: tabRect.right,
      toolbarLeft: toolbarRect.left,
      toolbarRight: toolbarRect.right,
      toolbarTop: toolbarRect.top,
      headerTop: header.getBoundingClientRect().top,
      focusVisible: tab.matches(':focus-visible'),
      outlineWidth: tabStyle.outlineWidth,
      outlineOffset: tabStyle.outlineOffset,
      outlineExpansion
    };
  }, { tabId, panelId });
  await expect(page.locator(`#${tabs[0][1]}`)).toBeVisible();
  // Compare keyboard movement only after the fallback-to-web-font reflow settles.
  await page.evaluate(() => document.fonts.ready);
  const initial = await readLayout(...tabs[0]);
  const assertTab = async (tabId: string, panelId: string) => {
    const tab = page.locator(`#${tabId}`);
    await expect(tab).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.locator(`#${panelId}`)).toBeVisible();
    if (tabId === 'settings-search-tab') await expect(page.locator('#settings-search-panel .status-grid')).toBeVisible();
    await expect.poll(async () => {
      const current = await readLayout(tabId, panelId);
      return {
        focusVisible: current.focusVisible,
        outlineWidth: current.outlineWidth,
        outlineOffset: current.outlineOffset,
        contained: current.tabLeft - current.outlineExpansion >= current.toolbarLeft
          && current.tabRight + current.outlineExpansion <= current.toolbarRight
      };
    }, { message: `${tabId} settles with its complete authored focus ring inside the local toolbar` }).toEqual({
      focusVisible: true, outlineWidth: '2px', outlineOffset: '2px', contained: true
    });
    const current = await readLayout(tabId, panelId);
    expect(current.pageContentScrollLeft).toBe(0);
    expect(current.pageContentScrollTop).toBe(0);
    expect(current.documentScrollWidth).toBeLessThanOrEqual(current.documentClientWidth);
    expect(current.panelWidth).toBeGreaterThan(0);
    expect(current.panelHeight).toBeGreaterThan(0);
    expect(current.panelLeft).toBeGreaterThanOrEqual(current.contentLeft);
    expect(current.panelRight).toBeLessThanOrEqual(current.contentRight);
    expect(current.toolbarTop).toBe(initial.toolbarTop);
    expect(current.headerTop).toBe(initial.headerTop);
    return current;
  };

  await page.locator(`#${tabs[0][0]}`).focus();
  const forward = [];
  for (let index = 0; index < tabs.length; index++) {
    forward.push(await assertTab(...tabs[index]));
    if (index < tabs.length - 1) await page.keyboard.press('Tab');
  }
  expect(forward.at(-1)?.toolbarScrollWidth).toBeGreaterThan(forward.at(-1)?.toolbarClientWidth ?? Infinity);
  expect(forward.at(-1)?.toolbarScrollLeft).toBeGreaterThan(0);

  for (let index = tabs.length - 2; index >= 0; index--) {
    await page.keyboard.press('Shift+Tab');
    await assertTab(...tabs[index]);
  }
  expect((await readLayout(...tabs[0])).toolbarScrollLeft).toBe(0);
  expect(controlledReads.sort((left, right) => left.path.localeCompare(right.path))).toEqual([
    { method: 'GET', path: '/api/v1/search/jobs' },
    { method: 'GET', path: '/api/v1/search/settings' },
    { method: 'GET', path: '/api/v1/search/status' }
  ]);
  assertHealthy();
});

test('[controlled visual] dark Search settings keeps text, notices and enabled actions readable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await loginToInstance(page);
  const expectedResponses: Array<{ method: string; path: string; status: number }> = [];
  page.on('response', response => {
    if ([409, 503].includes(response.status())) expectedResponses.push({
      method: response.request().method(), path: new URL(response.url()).pathname, status: response.status()
    });
  });
  await page.route('**/api/v1/search/settings', route => route.request().method() === 'GET'
    ? route.fulfill({ status: 200, json: { version: 7, policy: controlledPolicy } })
    : route.fulfill({ status: 409, json: { status: 409, code: 'VERSION_CONFLICT', detail: 'Controlled settings conflict' } }));
  await page.route('**/api/v1/search/status', route => route.fulfill({ status: 200, json: controlledStatus }));
  await page.route('**/api/v1/search/jobs*', route => route.fulfill({ status: 200, json: { items: [], hasMore: false } }));
  await page.route('**/api/v1/search/preview', route => route.fulfill({
    status: 503, json: { status: 503, code: 'SEARCH_UNAVAILABLE', detail: 'Controlled preview outage' }
  }));

  await page.goto('/settings');
  const theme = page.getByRole('button', { name: 'Переключить тему' });
  if (await theme.getAttribute('aria-pressed') !== 'true') await theme.click();
  await page.waitForFunction(() => document.documentElement.dataset['theme'] === 'dark');
  await page.locator('#settings-search-tab').click();
  const panel = page.locator('#settings-search-panel');
  await expect(panel.locator('.status-grid')).toBeVisible();

  const contrast = async (foregroundSelector: string, backgroundSelector = foregroundSelector) => page.evaluate(({ foregroundSelector, backgroundSelector }) => {
    const foreground = document.querySelector<HTMLElement>(foregroundSelector);
    const background = document.querySelector<HTMLElement>(backgroundSelector);
    if (!foreground || !background) throw new Error(`Contrast fixture is missing ${foregroundSelector} or ${backgroundSelector}`);
    const channel = (value: number) => {
      const normalized = value / 255;
      return normalized <= 0.04045 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4;
    };
    const luminance = (value: string) => {
      const channels = value.match(/\d+(?:\.\d+)?/gu)?.slice(0, 3).map(Number);
      if (!channels || channels.length !== 3) throw new Error(`Unsupported computed color for ${foregroundSelector}`);
      return 0.2126 * channel(channels[0]) + 0.7152 * channel(channels[1]) + 0.0722 * channel(channels[2]);
    };
    const foregroundLuminance = luminance(getComputedStyle(foreground).color);
    const backgroundLuminance = luminance(getComputedStyle(background).backgroundColor);
    return (Math.max(foregroundLuminance, backgroundLuminance) + 0.05)
      / (Math.min(foregroundLuminance, backgroundLuminance) + 0.05);
  }, { foregroundSelector, backgroundSelector });
  const actionContrast = async (selector: string, state: 'default' | 'hover' | 'focus') => {
    const action = panel.locator(selector);
    if (state === 'hover') await action.hover();
    if (state === 'focus') {
      await page.mouse.move(0, 0);
      await page.keyboard.press('Tab');
      await action.focus();
      await expect(action).toBeFocused();
      await expect.poll(() => action.evaluate(element => element.matches(':focus-visible'))).toBe(true);
    }
    return contrast(selector);
  };

  const ratios: Record<string, number> = {};
  ratios.warning = await contrast('#settings-search-panel .notice-warning');
  const limit = panel.locator('#search-global-limit');
  const currentLimit = Number(await limit.inputValue());
  await limit.fill(String(currentLimit === 1 ? 2 : currentLimit - 1));
  const save = panel.locator('button[data-action="save-search-settings"]');
  await expect(save).toBeEnabled();
  ratios.saveDefault = await actionContrast('button[data-action="save-search-settings"]', 'default');
  ratios.saveHover = await actionContrast('button[data-action="save-search-settings"]', 'hover');
  ratios.saveFocus = await actionContrast('button[data-action="save-search-settings"]', 'focus');
  await save.click();
  await expect(panel.locator('[data-state="save-error"]')).toBeVisible();
  ratios.saveError = await contrast('#settings-search-panel [data-state="save-error"] span', '#settings-search-panel [data-state="save-error"]');
  await panel.locator('#search-preview-query').fill('dark-state');
  await panel.locator('button[data-action="preview-search-settings"]').click();
  await expect(panel.locator('[data-state="preview-error"]')).toBeVisible();
  ratios.previewError = await contrast('#settings-search-panel [data-state="preview-error"]');

  const rebuild = panel.locator('button[data-action="start-rebuild"]');
  await expect(rebuild).toBeEnabled();
  ratios.rebuildDefault = await actionContrast('button[data-action="start-rebuild"]', 'default');
  ratios.rebuildHover = await actionContrast('button[data-action="start-rebuild"]', 'hover');
  ratios.rebuildFocus = await actionContrast('button[data-action="start-rebuild"]', 'focus');
  await rebuild.click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  const confirmSelector = '[role="dialog"] button[data-action="confirm-search-maintenance"]';
  const confirm = page.locator(confirmSelector);
  ratios.confirmDefault = await contrast(confirmSelector);
  await confirm.hover(); ratios.confirmHover = await contrast(confirmSelector);
  await page.mouse.move(0, 0); await page.keyboard.press('Tab'); await confirm.focus();
  await expect.poll(() => confirm.evaluate(element => element.matches(':focus-visible'))).toBe(true);
  ratios.confirmFocus = await contrast(confirmSelector);

  const violations = [];
  for (const selector of ['#settings-search-panel', '[role="dialog"]']) {
    const result = await new AxeBuilder({ page }).include(selector).analyze();
    violations.push(...result.violations.filter(violation => violation.impact === 'critical' || violation.impact === 'serious')
      .map(violation => ({ id: violation.id, targets: violation.nodes.flatMap(node => node.target) })));
  }
  expect(expectedResponses).toEqual([
    { method: 'PUT', path: '/api/v1/search/settings', status: 409 },
    { method: 'POST', path: '/api/v1/search/preview', status: 503 }
  ]);
  expect(Object.fromEntries(Object.entries(ratios).map(([name, value]) => [name, value >= 4.5]))).toEqual(
    Object.fromEntries(Object.keys(ratios).map(name => [name, true]))
  );
  expect(violations, 'Search-owned critical/serious dark accessibility violations').toEqual([]);
});

test('real search management save/preview/check/rebuild exposes the current active generation', async ({ page }) => {
  test.setTimeout(240_000);
  await loginToInstance(page);
  const assertHealthy = collectPageErrors(page);
  const opened = await openSearchSettings(page);
  expect(opened.settings.status()).toBe(200);
  expect(opened.status.status()).toBe(200);
  const original = await opened.settings.json() as Settings;
  const initialStatus = await opened.status.json() as Status;
  expect(initialStatus.generations.length).toBeLessThan(4);
  let currentVersion = original.version;
  let primaryFailure: unknown;

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
    const checkId = ((await checkReceipt.json()) as { id: string }).id;
    const checkRefresh = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === '/api/v1/search/status');
    await terminalJobFromUi(page, checkId, 'CHECK');
    expect((await checkRefresh).status()).toBe(200);

    await expect(page.locator('button[data-action="start-rebuild"]')).toBeEnabled();
    // The real CHECK consumed the shared management family. One full refill
    // interval leaves room for both the next mutation and its immediate poll.
    await page.waitForTimeout(6_100);
    await page.locator('button[data-action="start-rebuild"]').click();
    await expect(page.getByRole('dialog')).toBeVisible();
    const rebuildResponse = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/search/jobs');
    await page.locator('button[data-action="confirm-search-maintenance"]').click();
    const rebuildReceipt = await rebuildResponse;
    expect(rebuildReceipt.status()).toBe(202);
    const rebuildId = ((await rebuildReceipt.json()) as { id: string }).id;
    const finalStatusResponse = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === '/api/v1/search/status');
    const rebuilt = await terminalJobFromUi(page, rebuildId, 'REBUILD');
    const finalStatusHttp = await finalStatusResponse;
    expect(finalStatusHttp.status()).toBe(200);
    const finalStatus = await finalStatusHttp.json() as Status;
    expect(finalStatus.dependency).toMatchObject({ enabled: true, healthy: true });
    expect(finalStatus.initialized).toBe(true);
    expect(finalStatus.generations.find(generation => generation.active)?.id).toBe(rebuilt.generationId);
    assertHealthy();
  } catch (error) {
    primaryFailure = error;
    throw error;
  } finally {
    try {
      const current = await cleanupApi<Settings>(page, 'GET', '/search/settings');
      if (JSON.stringify(current.policy) !== JSON.stringify(original.policy)) {
        await cleanupApi<Settings>(page, 'PUT', '/search/settings', { version: current.version || currentVersion, policy: original.policy });
      }
    } catch (cleanupFailure) {
      if (primaryFailure) {
        throw new AggregateError([primaryFailure, cleanupFailure], 'Search management failed and settings cleanup also failed');
      }
      throw cleanupFailure;
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
  let delayStatus = false;
  let releaseLate!: () => void;
  const late = new Promise<void>(resolve => releaseLate = resolve);
  await page.route('**/api/v1/search/status', async route => {
    statusCalls++;
    if (statusCalls === 1) return route.fulfill({ status: 503, json: { code: 'SERVICE_UNAVAILABLE', detail: 'Контролируемая недоступность статуса' } });
    if (delayStatus) await late;
    return route.fulfill({ status: 200, json: controlledStatus });
  });
  await page.route('**/api/v1/search/jobs*', route => route.fulfill({
    status: 200, json: { items: [], hasMore: false }
  }));
  await page.route('**/api/v1/search/settings', async route => {
    if (route.request().method() === 'PUT') {
      return route.fulfill({ status: 409, json: { code: 'CONFLICT', detail: 'Контролируемый конфликт версии' } });
    }
    return route.fulfill({ status: 200, json: { version: 7, policy: controlledPolicy } });
  });
  await openSearchSettings(page);
  await expect(page.getByRole('alert')).toContainText('Контролируемая недоступность статуса');
  await page.locator('button[data-action="refresh-search-status"]').click();
  await expect(page.locator('[data-status="healthy"]')).toBeVisible();

  const limit = page.locator('#search-global-limit');
  const before = Number(await limit.inputValue());
  const draftLimit = before === 50 ? 49 : before + 1;
  await limit.fill(String(draftLimit));
  await page.locator('button[data-action="save-search-settings"]').click();
  await expect(page.locator('[data-state="save-error"]')).toContainText('Контролируемый конфликт версии');
  await expect(limit).toHaveValue(String(draftLimit));
  await expect(page.locator('button[data-action="reload-search-settings"]')).toBeVisible();
  await expect(page.locator('.toast-container')).toHaveCount(0);

  delayStatus = true;
  const lateResponse = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/search/status');
  await page.locator('button[data-action="refresh-search-status"]').click();
  await limit.fill(String(draftLimit));
  releaseLate();
  expect((await lateResponse).status()).toBe(200);
  await expect(limit).toHaveValue(String(draftLimit));

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
