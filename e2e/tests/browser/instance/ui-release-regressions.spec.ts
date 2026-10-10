import { expect, test, type Locator, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';

const MOBILE_VIEWPORT = { width: 390, height: 844 } as const;

async function expectInsideViewport(locator: Locator, viewportWidth: number): Promise<void> {
  await expect(locator).toBeVisible();
  const box = await locator.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(viewportWidth);
}

async function expectNoPageOverflow(page: Page): Promise<void> {
  await expect.poll(() => page.evaluate(() => ({
    documentFits: document.documentElement.scrollWidth <= window.innerWidth,
    contentFits: Array.from(document.querySelectorAll<HTMLElement>('.page-content'))
      .every(element => element.scrollWidth <= element.clientWidth)
  }))).toEqual({ documentFits: true, contentFits: true });
}

test('login brand keeps its product name outside the fixed mark', async ({ page }) => {
  await page.setViewportSize(MOBILE_VIEWPORT);
  await page.goto('/login');

  const lockup = page.getByLabel('SmartupCMS');
  await expect(lockup.locator('.brand-mark')).toHaveText('S');
  await expect(lockup.locator('.brand-name')).toHaveText('SmartupCMS');
  await expectInsideViewport(lockup, MOBILE_VIEWPORT.width);
});

test('administrator global search uses the server contract and leaves loading state', async ({ page }) => {
  await loginToInstance(page);
  await page.keyboard.press('ControlOrMeta+k');

  const searchResponse = page.waitForResponse(response =>
    response.url().includes('/api/v1/search?') && response.url().includes('entity=ALL'));
  await page.getByRole('combobox', { name: 'Поиск задач, проектов и пользователей' }).fill('admin');

  expect((await searchResponse).ok()).toBe(true);
  await expect(page.locator('.palette-loading')).toBeHidden();
  await expect(page.locator('.palette-error')).toHaveCount(0);
});

test('critical pages and create forms fit a mobile viewport', async ({ page }) => {
  await page.setViewportSize(MOBILE_VIEWPORT);
  await loginToInstance(page);

  const routes = [
    { path: '/tasks', heading: 'Задачи', action: 'Новая задача' },
    { path: '/analytics', heading: 'Аналитика и дашборды', action: 'Обновить' },
    { path: '/e/md.users', heading: 'Пользователи' },
    { path: '/iam/profile', heading: 'Мой профиль' }
  ] as const;

  for (const route of routes) {
    await page.goto(route.path);
    await expect(page.getByRole('heading', { level: 1, name: route.heading })).toBeVisible();
    await expectNoPageOverflow(page);
    if ('action' in route) {
      await expectInsideViewport(page.getByRole('button', { name: route.action, exact: true }), MOBILE_VIEWPORT.width);
    }
  }

  await page.goto('/tasks');
  await page.locator('.view-header').getByRole('button', { name: 'Новая задача', exact: true }).click();
  // Dialogs are smt-dialog: the scrolling body is .smt-dialog__body.
  await page.waitForSelector('.smt-dialog__body');
  await expect.poll(() => page.locator('.smt-dialog__body').evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);

  // The users are the general entity screen (ADR-0032 8): its create link and form fit the viewport too.
  await page.goto('/e/md.users');
  await expectInsideViewport(page.getByRole('link', { name: 'Создать', exact: true }), MOBILE_VIEWPORT.width);
  await page.goto('/e/md.users/new');
  await expect(page.getByRole('textbox', { name: 'Логин' })).toBeVisible();
  await expectNoPageOverflow(page);
});

test('compact administration actions preserve a 28px minimum hit target', async ({ page }) => {
  await loginToInstance(page);
  await page.goto('/iam/roles');
  await expect(page.getByRole('heading', { level: 1, name: 'Роли и матрица прав' })).toBeVisible();

  // The module pills are chips of an smt-radio-group now (roadmap item 38).
  for (const selector of ['.mini-btn', '.text-link', '.module-filter [role="radio"]', '.batch-btn']) {
    const control = page.locator(selector).first();
    await expect(control).toBeVisible();
    const box = await control.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.width).toBeGreaterThanOrEqual(24);
    expect(box!.height).toBeGreaterThanOrEqual(28);
  }
});

test('the permission matrix names every area in words, never by its code', async ({ page }) => {
  await loginToInstance(page);
  await page.goto('/iam/roles');
  const chips = page.locator('.module-filter [role="radio"]');
  await expect(chips.first()).toBeVisible();
  const labels = await chips.allInnerTexts();
  expect(labels.length).toBeGreaterThan(3);
  for (const label of labels) {
    // "Модуль: UPL" is the fallback of an area without a name; "(IAM)" the old code suffix.
    expect(label).not.toMatch(/Модуль:|\([A-Z]{2,}\)|\b[A-Z]{3,}\b/u);
  }
  await expect(page.locator('.admin-notice')).not.toContainText(/I-P\d|инвариант/u);
});

test('a new record names its entity, not its code', async ({ page }) => {
  await loginToInstance(page);
  await page.goto('/e/ms.tasks/new');
  const header = page.locator('ui-page-header');
  await expect(header.locator('.view-header__eyebrow')).toHaveText('Задачи');
  await expect(header).not.toContainText(/ms\.tasks/iu);
});

for (const height of [700, 600]) {
  test(`the side menu scrolls instead of clipping its groups at 1366x${height}`, async ({ page }) => {
    await page.setViewportSize({ width: 1366, height });
    await loginToInstance(page);
    await expect(page.locator('.nav-section-content').first()).toBeVisible();
    // A group that shrank inside the scrolling menu hides its last items behind overflow: hidden.
    await expect.poll(() => page.evaluate(() =>
      Array.from(document.querySelectorAll<HTMLElement>('.nav-section-content:not(.collapsed)'))
        .filter(group => group.scrollHeight > group.clientHeight + 1)
        .map(group => group.id)
    )).toEqual([]);
    const last = page.locator('.sidebar-nav a.nav-item').last();
    await last.scrollIntoViewIfNeeded();
    await expect(last).toBeInViewport({ ratio: 1 });
  });
}
