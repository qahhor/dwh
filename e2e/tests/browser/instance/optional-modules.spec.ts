import { expect, test, type Page } from '@playwright/test';
import { loginToInstance } from '../../../support/auth.js';
import { collectPageErrors } from '../../../support/diagnostics.js';

const OPTIONAL = ['tasks', 'notes', 'upl'] as const;

async function setEnabled(page: Page, code: string, enabled: boolean): Promise<void> {
  const cookie = (await page.context().cookies(page.url())).find(value => value.name === 'XSRF-TOKEN');
  if (!cookie?.value) throw new Error('The signed-in page has no CSRF cookie');
  const response = await page.request.put(`/api/v1/modules/${code}/enabled`, {
    headers: { 'X-XSRF-TOKEN': cookie.value }, data: { enabled },
  });
  try { expect(response.status(), `switch ${code}`).toBe(200); }
  finally { await response.dispose(); }
}

// FR-MOD-01: disabling applications must leave a usable core and no redirect loop.
test('optional applications close routes and APIs while core administration stays usable', async ({ page }) => {
  await loginToInstance(page);
  const assertNoPageErrors = collectPageErrors(page);
  const response = await page.request.get('/api/v1/modules');
  expect(response.status()).toBe(200);
  const initial = await response.json() as { code: string; status: string }[];
  await response.dispose();
  try {
    await page.goto('/settings/modules');
    await expect(page.getByRole('navigation', { name: 'Основная навигация' })).toBeVisible();
    for (const code of OPTIONAL) {
      const row = page.getByRole('row').filter({ has: page.locator('code').filter({ hasText: new RegExp(`^${code}$`) }) });
      const toggle = row.getByRole('switch');
      await expect(toggle).toBeEnabled();
      if (await toggle.getAttribute('aria-checked') === 'true') {
        const changed = page.waitForResponse(res => res.url().endsWith(`/modules/${code}/enabled`) && res.request().method() === 'PUT');
        await toggle.click();
        expect((await changed).status()).toBe(200);
      }
      await expect(toggle).toHaveAttribute('aria-checked', 'false');
    }
    for (const href of ['/tasks', '/tasks/projects', '/notes', '/upl/sources', '/upl/packages']) {
      await expect(page.locator(`nav a[href="${href}"]`)).toHaveCount(0);
    }
    for (const path of ['/tasks', '/tasks/items/42', '/tasks/projects/42', '/notes', '/upl/sources']) {
      await page.goto(path);
      await expect(page).toHaveURL(/\/iam\/profile$/u);
      await expect(page.getByRole('main')).toBeVisible();
    }
    for (const path of ['/api/v1/entities/ms.tasks', '/api/v1/entities/ms.notes', '/api/v1/upl/sources', '/api/v1/analytics/summary']) {
      const denied = await page.request.get(path);
      try { expect(denied.status(), path).toBe(404); }
      finally { await denied.dispose(); }
    }
    for (const path of ['/api/v1/report-widgets', '/api/v1/files', '/api/v1/notifications/inbox']) {
      const core = await page.request.get(path);
      try { expect(core.status(), path).toBe(200); }
      finally { await core.dispose(); }
    }
    assertNoPageErrors();
  } finally {
    for (const code of OPTIONAL) await setEnabled(page, code, initial.find(value => value.code === code)?.status === 'ACTIVE');
  }
  await page.goto('/tasks');
  await expect(page).toHaveURL(/\/tasks$/u);
});
