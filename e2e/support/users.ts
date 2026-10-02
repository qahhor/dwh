import type { APIResponse, BrowserContext } from '@playwright/test';

import { Mailbox, resetToken } from './mailpit.js';

/**
 * Synthetic users of the e2e stack (ADR-0032 8): an account is created on the general entity runtime
 * `/api/v1/entities/md.users` without a password, the invitation arrives through the SMTP stub
 * (scripts/dev/e2e-mail.compose.yml) and its one-time link sets the password the test chose — the way a person accepts
 * an invitation. Nothing here prints a token, a password or a message body.
 */
export interface InvitedUser {
  id: number;
  name: string;
  login: string;
  email: string;
}

async function csrfHeaders(context: BrowserContext): Promise<Record<string, string>> {
  const csrf = (await context.cookies()).find(cookie => cookie.name === 'XSRF-TOKEN');
  if (!csrf?.value) throw new Error('The administrator context has no CSRF cookie');
  return { 'X-XSRF-TOKEN': csrf.value };
}

async function json<T>(response: APIResponse, expected: number, operation: string): Promise<T> {
  const status = response.status();
  try {
    if (status !== expected) throw new Error(`${operation} returned HTTP ${status}, expected ${expected}`);
    return (expected === 204 ? undefined : await response.json()) as T;
  } finally {
    await response.dispose();
  }
}

/**
 * Creates the user as the signed-in administrator of `admin`, gives it exactly `roleIds` when there are any (the
 * assignments of `md.assignments`, from the user's revision) and sets `password` through the mailed invitation.
 */
export async function inviteUser(
  admin: BrowserContext,
  user: { name: string; login: string; email: string },
  password: string,
  roleIds: readonly number[] = [],
): Promise<InvitedUser> {
  const mailbox = await Mailbox.open(user.email);
  try {
    const created = await json<{ id?: unknown; revision?: unknown }>(
      await admin.request.post('/api/v1/entities/md.users', {
        headers: await csrfHeaders(admin),
        data: { name: user.name, login: user.login, email: user.email, language: 'ru', timezone: 'Asia/Tashkent' },
        maxRedirects: 0,
        maxRetries: 0,
      }),
      201,
      'Synthetic user creation',
    );
    if (!Number.isInteger(created.id) || !Number.isInteger(created.revision)) {
      throw new Error('Synthetic user creation returned no numeric id and revision');
    }
    const id = Number(created.id);
    if (roleIds.length > 0) {
      await json<unknown>(
        await admin.request.put(`/api/v1/iam/users/${id}/roles`, {
          headers: { ...await csrfHeaders(admin), 'If-Match': `"${String(created.revision)}"` },
          data: { roleIds },
          maxRedirects: 0,
          maxRetries: 0,
        }),
        200,
        'Synthetic user roles',
      );
    }
    const token = resetToken(await mailbox.nextMessage());
    await json<void>(
      await admin.request.post('/api/v1/auth/password-reset/confirm', {
        headers: await csrfHeaders(admin),
        data: { token, newPassword: password },
        maxRedirects: 0,
        maxRetries: 0,
      }),
      204,
      'Synthetic user invitation',
    );
    return { id, name: user.name, login: user.login, email: user.email };
  } finally {
    await mailbox.close();
  }
}

/** The current revision of a user, for a change that names it (If-Match). */
export async function userRevision(admin: BrowserContext, id: number): Promise<number> {
  const record = await json<{ revision?: unknown }>(
    await admin.request.get(`/api/v1/entities/md.users/${id}`, { maxRedirects: 0, maxRetries: 0 }),
    200,
    'Synthetic user read',
  );
  return Number(record.revision);
}

/** Runs a record action of a user (block, anonymize, enable_2fa…) from its current revision. */
export async function runUserAction(admin: BrowserContext, id: number, action: string): Promise<void> {
  const revision = await userRevision(admin, id);
  await json<unknown>(
    await admin.request.post(`/api/v1/entities/md.users/${id}/actions/${action}`, {
      headers: { ...await csrfHeaders(admin), 'If-Match': `"${revision}"` },
      maxRedirects: 0,
      maxRetries: 0,
    }),
    200,
    `Synthetic user action ${action}`,
  );
}
