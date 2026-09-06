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
      <button type="button" onclick="document.body.innerHTML = \`
        <h1>Смена временного пароля</h1>
        <label>Новый пароль <input type='password' aria-label='Новый пароль'></label>
        <label>Повторите новый пароль <textarea aria-label='Повторите новый пароль'></textarea></label>
        <button type='button'>Сменить пароль</button>
      \`">Войти в систему</button>
    `,
  }));

  const firstPassword = page.getByLabel('Новый пароль', { exact: true });
  let partialFillFailure: unknown;
  try {
    await loginToInstance(page);
  } catch (error) {
    partialFillFailure = error;
  }
  expect(partialFillFailure).toBeInstanceOf(Error);
  expect((partialFillFailure as Error).message).toContain('Secret target must be an input element');
  expect(await firstPassword.evaluate(element => (element as HTMLInputElement).value.length === 0)).toBe(true);

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
