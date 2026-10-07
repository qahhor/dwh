import { expect, test, type BrowserContext } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { loadE2eEnv } from '../../../support/env.mjs';

const environment = loadE2eEnv();

/**
 * Plan 10/10, item 7.5 (ADR-0034): a session the server no longer accepts — past its absolute lifetime, idle too
 * long or ended elsewhere — answers 401, and the open tab treats that as an expired session: it forgets the
 * sign-in, says why and opens the sign-in page. The server side of expiry is KauthSessionExpiryHttpTest; here a
 * second browser ends the tab's session, which reaches the web the same way.
 */
async function csrfHeaders(context: BrowserContext): Promise<Record<string, string>> {
  const csrf = (await context.cookies()).find(cookie => cookie.name === 'XSRF-TOKEN');
  if (!csrf?.value) throw new Error('The signed-in context has no CSRF cookie');
  return { 'X-XSRF-TOKEN': csrf.value };
}

test('a session the server refuses sends the open tab to sign-in with the expiry notice', async ({ browser, page }) => {
  const elsewhere = await browser.newContext({ baseURL: environment.instance.baseURL });
  try {
    await loginToInstance(page);
    await loginToInstance(await elsewhere.newPage());

    // The tab's own session: its list marks it current.
    const listed = await page.request.get('/api/v1/iam/profile/sessions', { maxRedirects: 0, maxRetries: 0 });
    expect(listed.status()).toBe(200);
    const own = (await listed.json() as Array<{ id: number; current: boolean }>).find(session => session.current);
    await listed.dispose();
    expect(own, 'the tab sees its own session').toBeDefined();

    const ended = await elsewhere.request.delete(`/api/v1/iam/profile/sessions/${own!.id}`, {
      headers: await csrfHeaders(elsewhere),
      maxRedirects: 0,
      maxRetries: 0,
    });
    expect(ended.status()).toBe(204);
    await ended.dispose();

    // Moving inside the application sends API requests with the refused cookie.
    await page.locator('a[href="/notifications"]').first().click();

    await expect(page).toHaveURL(/\/login(?:\?.*)?$/u);
    await expect(page.locator('ui-toast-container')
      .getByText('Сеанс истёк или был завершён. Войдите снова — откроется та же страница.', { exact: true }))
      .toBeVisible();
  } finally {
    await elsewhere.close();
  }
});
