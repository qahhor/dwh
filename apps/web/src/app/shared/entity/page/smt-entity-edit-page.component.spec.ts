import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import type { FormMeta } from '@core/models/form-meta.models';
import { formField } from '@testing/form-meta';
import { entityRecord, problem, renderEntityScreen } from '@testing/entity-page';
import { translateTest } from '@testing/i18n-test.stub';

const CODE = 'test.orders';

const META: FormMeta = {
  code: CODE,
  listCode: CODE,
  fields: [formField('number', 'text', { label: 'Номер', labelKey: '', required: true })],
  layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['number'] }],
  actions: ['create', 'update'],
  capabilities: [],
};

const ORDER = entityRecord(4, { number: 'ЗК-4' });

function stateTitle(root: HTMLElement): string | undefined {
  return root.querySelector('[data-testid="entity-page-state"] h2')?.textContent?.trim();
}

/* Creating or changing a record of a declared entity (ADR-0032 7.1). */
describe('SMTEntityEditPageComponent', () => {
  it('titles a new record and a change by the record name', async () => {
    const created = await renderEntityScreen(`/e/${CODE}/new`, { meta: META });
    expect(created.root.querySelector('h1')?.textContent?.trim()).toBe(translateTest('ui.entity_page.create_title'));
    expect(created.root.querySelector('[data-testid="form-submit"]')?.textContent?.trim()).toBe('Создать');
    (created.root.querySelector('[data-testid="form-cancel"]') as HTMLButtonElement).click();
    await created.settle();
    expect(created.router.url).toBe('/e/test.orders');
    expect(created.confirm).not.toHaveBeenCalled();
  });

  it('titles a change by the record name and leads back to the record', async () => {
    const { root, router, settle } = await renderEntityScreen(`/e/${CODE}/4/edit`, { meta: META, records: [ORDER] });
    expect(root.querySelector('h1')?.textContent?.trim()).toBe(
      translateTest('ui.entity_page.edit_title', { name: 'ЗК-4' }),
    );
    (root.querySelector('[data-testid="form-cancel"]') as HTMLButtonElement).click();
    await settle();
    expect(router.url).toBe(`/e/${CODE}/4`);
  });

  it('refuses a new record to a viewer who may not create', async () => {
    const { root, api } = await renderEntityScreen(`/e/${CODE}/new`, { meta: { ...META, actions: ['update'] } });
    expect(stateTitle(root)).toBe(translateTest('ui.entity_page.denied'));
    expect(api.get.mock.calls.some(([path]) => String(path).startsWith(`/entities/${CODE}/`))).toBe(false);
  });

  it('says "not found" for an id that is not a number, without asking the server', async () => {
    const { root, api } = await renderEntityScreen(`/e/${CODE}/abc/edit`, { meta: META });
    expect(stateTitle(root)).toBe(translateTest('ui.entity_page.record_missing'));
    expect(api.get.mock.calls.some(([path]) => String(path).startsWith(`/entities/${CODE}/`))).toBe(false);
  });

  it('offers to retry a failed read of the record, then shows the form', async () => {
    let reads = 0;
    const { root, settle } = await renderEntityScreen(`/e/${CODE}/4/edit`, {
      meta: META,
      answers: { [`/entities/${CODE}/4`]: () => (reads++ === 0 ? throwError(() => problem(500)) : of(ORDER)) },
    });
    expect(stateTitle(root)).toBe(translateTest('ui.entity_page.load_failed'));

    (root.querySelector('[data-testid="entity-page-state"] button') as HTMLButtonElement).click();
    await settle();
    expect(reads).toBe(2);
    expect(root.querySelector('smt-entity-form')).not.toBeNull();
  });
});
