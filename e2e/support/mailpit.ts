import { expect, request, type APIRequestContext } from '@playwright/test';

import { loadE2eEnv } from './env.mjs';

/**
 * Reads the messages the server sent through the e2e stack's SMTP stub (scripts/dev/e2e-mail.compose.yml, plan
 * 10/10, item 0.8). A message carries a one-time code or a reset link: nothing here prints a body, a code or a
 * token, and every error names only the recipient's role and what was missing.
 */
export class Mailbox {
  private readonly seen = new Set<string>();

  private constructor(
    private readonly api: APIRequestContext,
    private readonly recipient: string,
  ) {}

  static async open(recipient: string): Promise<Mailbox> {
    const api = await request.newContext({ baseURL: loadE2eEnv().mailpit.baseURL });
    const ready = await api.get('/readyz');
    const status = ready.status();
    await ready.dispose();
    if (status !== 200) {
      await api.dispose();
      throw new Error(`Mailpit is not ready (HTTP ${status}): start the stack with scripts/dev/e2e-mail.compose.yml`);
    }
    return new Mailbox(api, recipient.toLowerCase());
  }

  /** Waits for the next message to the recipient that this mailbox has not returned yet; gives its text body. */
  async nextMessage(timeout = 20_000): Promise<string> {
    let id: string | undefined;
    await expect.poll(async () => {
      id = await this.newestUnseenId();
      return id !== undefined;
    }, { message: 'a new message reaches the mail stub', timeout, intervals: [250, 500, 1_000] }).toBe(true);
    if (!id) throw new Error('Mail stub returned no message id');
    this.seen.add(id);

    const response = await this.api.get(`/api/v1/message/${encodeURIComponent(id)}`);
    const status = response.status();
    if (status !== 200) {
      await response.dispose();
      throw new Error(`Mail stub message read returned HTTP ${status}`);
    }
    const body = await response.json() as { Text?: unknown };
    await response.dispose();
    if (typeof body.Text !== 'string') throw new Error('Mail stub message has no text body');
    return body.Text;
  }

  /** Removes what this mailbox read, so no code or link outlives the test in the stub. */
  async close(): Promise<void> {
    try {
      if (this.seen.size > 0) {
        const response = await this.api.delete('/api/v1/messages', { data: { IDs: [...this.seen] } });
        await response.dispose();
      }
    } finally {
      await this.api.dispose();
    }
  }

  private async newestUnseenId(): Promise<string | undefined> {
    const response = await this.api.get('/api/v1/messages', { params: { limit: '100' } });
    const status = response.status();
    if (status !== 200) {
      await response.dispose();
      throw new Error(`Mail stub message list returned HTTP ${status}`);
    }
    const body = await response.json() as {
      messages?: Array<{ ID?: unknown; To?: Array<{ Address?: unknown }> }>;
    };
    await response.dispose();
    // Newest first, as the stub lists them.
    return (body.messages ?? [])
      .filter(message => (message.To ?? []).some(to =>
        typeof to.Address === 'string' && to.Address.toLowerCase() === this.recipient))
      .map(message => String(message.ID))
      .find(id => !this.seen.has(id));
  }
}

/** The six-digit code of a login or channel confirmation message. */
export function oneTimeCode(text: string): string {
  const match = /(?<!\d)(\d{6})(?!\d)/u.exec(text);
  if (!match) throw new Error('The message carries no six-digit code');
  return match[1];
}

/** The token of a password reset link (`.../reset-password#token=...`). */
export function resetToken(text: string): string {
  const match = /\/reset-password#token=([A-Za-z0-9_-]{20,})/u.exec(text);
  if (!match) throw new Error('The message carries no password reset link');
  return match[1];
}
