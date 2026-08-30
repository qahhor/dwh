import { expect, type Page } from '@playwright/test';

export function collectPageErrors(page: Page): () => void {
  const errors: string[] = [];

  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(error.message));

  return () => expect(errors, 'browser console/page errors').toEqual([]);
}

export function uniqueRunName(prefix: string): string {
  const random = Math.random().toString(36).slice(2, 8);
  return `${prefix}-${Date.now()}-${random}`;
}
