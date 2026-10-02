import { randomBytes } from 'node:crypto';

import { devices, expect, test, type APIResponse, type Browser, type BrowserContext, type Page } from '@playwright/test';

import { loginToInstance } from '../../../support/auth.js';
import { loadE2eEnv } from '../../../support/env.mjs';
import { Mailbox, oneTimeCode, resetToken } from '../../../support/mailpit.js';
import { clearSecret, fillSecret } from '../../../support/secret.js';
import { runUserAction } from '../../../support/users.js';

// Plan 10/10, item 0.8: delivery by real SMTP. The stack sends to Mailpit (scripts/dev/e2e-mail.compose.yml); every
// code and link below is read from a mailed message and typed into the UI the way a person would, never printed.

const environment = loadE2eEnv();

/** A disposable password made at run time: at most 20 characters, as the policy requires. */
function disposablePassword(label: string): string {
  return `${label}!${randomBytes(9).toString('base64url')}`;
}

async function newDesktopContext(browser: Browser): Promise<BrowserContext> {
  return browser.newContext({
    ...devices['Desktop Chrome'],
    baseURL: environment.instance.baseURL,
    locale: 'ru-RU',
    timezoneId: 'Asia/Tashkent',
    reducedMotion: 'reduce',
    viewport: { width: 1366, height: 900 },
  });
}

async function csrfHeaders(context: BrowserContext): Promise<Record<string, string>> {
  const cookies = await context.cookies(environment.instance.baseURL);
  const csrf = cookies.find(cookie => cookie.name === 'XSRF-TOKEN');
  if (!csrf?.value) throw new Error('Authenticated browser context has no CSRF cookie');
  return { 'X-XSRF-TOKEN': csrf.value };
}

async function readJson<T>(response: APIResponse, expected: number, operation: string): Promise<T> {
  const status = response.status();
  if (status !== expected) {
    await response.dispose();
    throw new Error(`${operation} returned HTTP ${status}, expected ${expected}`);
  }
  const body = expected === 204 ? undefined : await response.json();
  await response.dispose();
  return body as T;
}

/** Signs in with login and password; resolves to where the sign-in landed. */
async function submitPassword(page: Page, login: string, password: string): Promise<'tasks' | 'otp'> {
  await page.goto('/login');
  await page.getByLabel('Логин или Email').fill(login);
  const field = page.getByLabel('Пароль', { exact: true });
  try {
    await fillSecret(field, password);
    await page.getByRole('button', { name: 'Войти в систему' }).click();
    const otpHeading = page.getByText('Двухфакторная аутентификация', { exact: true });
    await expect.poll(async () => {
      if (/\/tasks(?:\?.*)?$/u.test(page.url())) return 'tasks';
      if (await otpHeading.isVisible().catch(() => false)) return 'otp';
      return 'pending';
    }, { message: 'the sign-in leaves the password step' }).not.toBe('pending');
    return /\/tasks(?:\?.*)?$/u.test(page.url()) ? 'tasks' : 'otp';
  } finally {
    await clearSecret(field);
  }
}

/** Completes the second step with the code the login just mailed. */
async function enterMailedLoginCode(page: Page, mailbox: Mailbox): Promise<void> {
  const code = oneTimeCode(await mailbox.nextMessage());
  const field = page.getByLabel('Код подтверждения (OTP)');
  try {
    await fillSecret(field, code);
    await page.getByRole('button', { name: 'Подтвердить вход', exact: true }).click();
    await expect(page).toHaveURL(/\/tasks(?:\?.*)?$/u, { timeout: 15_000 });
  } finally {
    await clearSecret(field);
  }
  await expect(page.getByRole('navigation', { name: 'Основная навигация' })).toBeVisible();
}

test.describe.serial('mail delivery through the SMTP stub', () => {
  test('a mailed code confirms the email channel and signs in, a mailed link resets the password', async ({ browser, page }) => {
    test.slow();
    const suffix = `${Date.now()}-${randomBytes(3).toString('hex')}`;
    const subject = {
      name: `Mail subject ${suffix}`,
      login: `mail-${suffix}`,
      email: `mail-${suffix}@e2e.test`,
      password: disposablePassword('Start'),
    };
    const nextPassword = disposablePassword('Reset');
    const pageErrors: string[] = [];
    const contexts: BrowserContext[] = [];
    let userId: number | undefined;
    const mailbox = await Mailbox.open(subject.email);

    async function subjectPage(): Promise<Page> {
      const context = await newDesktopContext(browser);
      contexts.push(context);
      const created = await context.newPage();
      created.on('pageerror', error => pageErrors.push(error.message));
      return created;
    }

    await loginToInstance(page);
    const admin = page.context();

    try {
      const roles = await readJson<Array<{ id: number; pcode: string }>>(
        await admin.request.get('/api/v1/iam/roles'), 200, 'Role lookup');
      const manager = roles.find(role => role.pcode === 'manager');
      if (!manager) throw new Error('Built-in non-admin manager role is unavailable');

      // 0. The administrator creates the account without a password (ADR-0032 8); the invitation arrives by mail
      //    and its link sets the first password on the reset page, as a person accepting it would.
      const created = await readJson<{ id?: unknown; revision?: unknown }>(
        await admin.request.post('/api/v1/entities/md.users', {
          headers: await csrfHeaders(admin),
          data: {
            name: subject.name,
            login: subject.login,
            email: subject.email,
            language: 'ru',
            timezone: 'Asia/Tashkent',
          },
        }),
        201,
        'Synthetic user setup',
      );
      if (!Number.isInteger(created.id)) throw new Error('Synthetic user setup returned no numeric id');
      userId = Number(created.id);
      await readJson<unknown>(await admin.request.put(`/api/v1/iam/users/${userId}/roles`, {
        headers: { ...await csrfHeaders(admin), 'If-Match': `"${String(created.revision)}"` },
        data: { roleIds: [manager.id] },
      }), 200, 'Synthetic user roles');

      const invitation = await subjectPage();
      await invitation.goto('/login');
      const invitationToken = resetToken(await mailbox.nextMessage());
      await invitation.evaluate(value => window.location.assign(`/reset-password#token=${value}`), invitationToken);
      const firstPassword = invitation.locator('#reset-new-password');
      const firstConfirmation = invitation.locator('#reset-confirm-password');
      try {
        await expect(firstPassword).toBeVisible({ timeout: 15_000 });
        await fillSecret(firstPassword, subject.password);
        await fillSecret(firstConfirmation, subject.password);
        await invitation.getByRole('button', { name: 'Сохранить пароль', exact: true }).click();
        await expect(invitation.getByText('Пароль изменён. Войдите с новым паролем.', { exact: true })).toBeVisible();
      } finally {
        await Promise.all([clearSecret(firstPassword), clearSecret(firstConfirmation)]);
      }

      // 1. The person binds the email channel in the profile and confirms it with the mailed code.
      const profile = await subjectPage();
      expect(await submitPassword(profile, subject.login, subject.password)).toBe('tasks');
      await profile.goto('/iam/profile');
      await expect(profile.getByRole('heading', { name: 'Мой профиль' })).toBeVisible();
      await profile.getByRole('button', { name: 'Привязать канал', exact: true }).click();
      const bindDialog = profile.getByRole('dialog', { name: 'Привязка канала связи' });
      await expect(bindDialog).toBeVisible();
      await bindDialog.locator('#profile-channel-address').fill(subject.email);
      await bindDialog.getByRole('button', { name: 'Отправить код', exact: true }).click();

      const confirmDialog = profile.getByRole('dialog', { name: 'Подтверждение канала связи' });
      await expect(confirmDialog).toBeVisible();
      const channelCode = oneTimeCode(await mailbox.nextMessage());
      const codeField = confirmDialog.locator('#profile-channel-code');
      try {
        await fillSecret(codeField, channelCode);
        await confirmDialog.getByRole('button', { name: 'Подтвердить', exact: true }).click();
        await expect(confirmDialog).toBeHidden();
      } finally {
        await clearSecret(codeField);
      }
      await expect(profile.getByTestId('profile-channels-table').getByText('Подтверждён', { exact: true }))
        .toBeVisible();

      // 2. The administrator turns on two-factor sign-in, an action of the user (ADR-0032 8); the code now arrives
      //    by mail.
      await runUserAction(admin, userId, 'enable_2fa');

      const otpSignIn = await subjectPage();
      expect(await submitPassword(otpSignIn, subject.login, subject.password)).toBe('otp');
      await enterMailedLoginCode(otpSignIn, mailbox);

      // 3. Forgotten password: the link arrives by mail and opens the reset page.
      const reset = await subjectPage();
      await reset.goto('/login');
      await reset.getByRole('button', { name: 'Забыли пароль?', exact: true }).click();
      const resetDialog = reset.getByRole('dialog', { name: 'Восстановление пароля' });
      await resetDialog.locator('#reset-email').fill(subject.email);
      await resetDialog.getByRole('button', { name: 'Отправить ссылку', exact: true }).click();
      await expect(reset.locator('ui-toast-container').getByText(/ссылка уже отправлена/u)).toBeVisible();

      const token = resetToken(await mailbox.nextMessage());
      // Navigated from the page, not by page.goto: a navigation step would carry the token in its title.
      await reset.evaluate(value => window.location.assign(`/reset-password#token=${value}`), token);
      const newPassword = reset.locator('#reset-new-password');
      const confirmation = reset.locator('#reset-confirm-password');
      try {
        await expect(newPassword).toBeVisible({ timeout: 15_000 });
        await fillSecret(newPassword, nextPassword);
        await fillSecret(confirmation, nextPassword);
        await reset.getByRole('button', { name: 'Сохранить пароль', exact: true }).click();
        await expect(reset.getByText('Пароль изменён. Войдите с новым паролем.', { exact: true })).toBeVisible();
      } finally {
        await Promise.all([clearSecret(newPassword), clearSecret(confirmation)]);
      }

      // 4. The new password and a fresh mailed code sign in.
      const afterReset = await subjectPage();
      expect(await submitPassword(afterReset, subject.login, nextPassword)).toBe('otp');
      await enterMailedLoginCode(afterReset, mailbox);

      expect(pageErrors, 'uncaught page errors').toEqual([]);
    } finally {
      for (const context of contexts) await context.close();
      await mailbox.close();
      // Anonymisation takes the place of a delete (ADR-0032 8).
      if (userId !== undefined) await runUserAction(admin, userId, 'anonymize');
    }
  });
});
