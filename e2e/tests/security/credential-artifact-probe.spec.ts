import { expect, test } from '@playwright/test';

import { loginToInstance } from '../../support/auth.js';
import { dismissSensitiveStatus } from '../../support/sensitive.js';

test('production failure surface retains neither credentials nor generated tokens', async ({ page }) => {
  const sentinelToken = process.env.E2E_ARTIFACT_TOKEN_SENTINEL;
  if (!sentinelToken) throw new Error('E2E_ARTIFACT_TOKEN_SENTINEL is required');

  await page.route('http://artifact-security.invalid/login', (route) => route.fulfill({
    contentType: 'text/html; charset=utf-8',
    body: `
      <label>Логин или Email <input aria-label="Логин или Email"></label>
      <label>Пароль <input type="password" aria-label="Пароль"></label>
      <button type="button" onclick="location.assign('/tasks')">Войти в систему</button>
    `,
  }));
  await page.route('http://artifact-security.invalid/tasks', (route) => route.fulfill({
    contentType: 'text/html; charset=utf-8',
    body: '<nav aria-label="Основная навигация">Synthetic authenticated shell</nav>',
  }));

  await loginToInstance(page);

  await page.setContent(`
    <div role="status">
      <span id="token-label">Heartbeat-токен экземпляра: </span>
      <button type="button" aria-label="Скрыть токен">Скрыть токен</button>
    </div>
  `);
  await page.locator('#token-label').evaluate((element, token) => {
    element.append(document.createTextNode(token));
  }, sentinelToken);
  const tokenStatus = page.getByRole('status');
  await dismissSensitiveStatus(
    tokenStatus,
    tokenStatus.getByRole('button', { name: 'Скрыть токен' }),
  );

  expect('probe failure').toBe('ARTIFACT_SECURITY_EXPECTED_FAILURE');
});
