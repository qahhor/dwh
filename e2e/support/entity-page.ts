import { expect, type Locator, type Page, type Response } from '@playwright/test';

/**
 * Helpers for the general entity screen /e/:code (ADR-0032 11.5): a spec names fields, lines and actions by the keys of
 * form-meta, as the platform draws them (`[data-field]`, `[data-line]`, `[data-action]`), never by layout or text.
 */
export interface EntityPage {
  readonly page: Page;
  readonly code: string;
  /** The main form of a new or edited record. */
  readonly form: Locator;
  /** Types into the text control of a field of the form. */
  fillField(key: string, value: string): Promise<void>;
  /** Adds a line to a collection of the form (`lines` unless named) and fills its text controls by field key. */
  addLine(values: Record<string, string>, collection?: string): Promise<Locator>;
  /** Saves the form and answers the server's response to the create (POST) or change (PATCH). */
  save(): Promise<Response>;
  /** Runs an action of the record from its header by code and answers the server's response. */
  runAction(action: string): Promise<Response>;
  /** Expects the problem shown under a field of the form. */
  expectFieldError(key: string, text: string | RegExp): Promise<void>;
}

function escaped(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
}

/** Opens the list of entity `code`, waits for its title, and returns the helpers of its pages. */
export async function openEntity(page: Page, code: string, title?: string | RegExp): Promise<EntityPage> {
  await page.goto(`/e/${code}`);
  await expect(page.getByRole('heading', { level: 1, ...(title ? { name: title } : {}) })).toBeVisible();
  return entityPage(page, code);
}

/** The helpers of the pages of entity `code` on the page as it is now. */
export function entityPage(page: Page, code: string): EntityPage {
  const form = page.locator('smt-entity-form');
  const entities = new RegExp(`/api/v1/entities/${escaped(code)}`, 'u');

  return {
    page,
    code,
    form,
    async fillField(key, value) {
      await form.locator(`.entity-field[data-field="${key}"]`).locator('input, textarea').first().fill(value);
    },
    async addLine(values, collection = 'lines') {
      const lines = page.locator(`smt-entity-lines[data-collection="${collection}"]`);
      const index = await lines.locator('li[data-line]').count();
      await lines.getByTestId('entity-line-add').click();
      const line = lines.locator(`li[data-line="${index}"]`);
      await expect(line).toBeVisible();
      for (const [key, value] of Object.entries(values)) {
        await line.locator(`[data-field="${key}"] input`).first().fill(value);
      }
      return line;
    },
    async save() {
      const answered = page.waitForResponse(response => {
        const method = response.request().method();
        const path = new URL(response.url()).pathname;
        return (method === 'POST' || method === 'PATCH') && entities.test(path) && !path.includes('/actions/');
      });
      await page.getByRole('button', { name: 'Сохранить' }).click();
      return answered;
    },
    async runAction(action) {
      const answered = page.waitForResponse(response =>
        response.request().method() === 'POST'
        && new RegExp(`${entities.source}/\\d+/actions/${escaped(action)}$`, 'u').test(new URL(response.url()).pathname)
      );
      await page.locator(`[data-action="${action}"]`).click();
      return answered;
    },
    async expectFieldError(key, text) {
      await expect(form.locator(`.entity-field[data-field="${key}"] .smt-control__error`)).toHaveText(text);
    },
  };
}
