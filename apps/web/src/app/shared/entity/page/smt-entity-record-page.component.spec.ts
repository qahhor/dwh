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
  fields: [formField('number', 'text', { label: 'Номер', labelKey: '' })],
  layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['number'] }],
  actions: ['update'],
  capabilities: ['history'],
};

function stateTitle(root: HTMLElement): string | undefined {
  return root.querySelector('[data-testid="entity-page-state"] h2')?.textContent?.trim();
}

function tabLabels(root: HTMLElement): string[] {
  return Array.from(root.querySelectorAll('[role="tab"]')).map((tab) => tab.textContent?.trim() ?? '');
}

/* The card of a record of a declared entity (ADR-0032 7.1). */
describe('SMTEntityRecordPageComponent', () => {
  it('names the record by its first field, or by its number', async () => {
    const named = await renderEntityScreen(`/e/${CODE}/4`, {
      meta: META,
      records: [entityRecord(4, { number: 'ЗК-4' })],
    });
    expect(named.root.querySelector('h1')?.textContent?.trim()).toBe('ЗК-4');
  });

  it('names a record without a name by its number', async () => {
    const { root } = await renderEntityScreen(`/e/${CODE}/5`, {
      meta: META,
      records: [entityRecord(5, { number: '' })],
    });
    expect(root.querySelector('h1')?.textContent?.trim()).toBe(translateTest('ui.entity_page.record', { id: 5 }));
  });

  it('offers the history tab only for an entity that keeps one', async () => {
    const kept = await renderEntityScreen(`/e/${CODE}/4`, { meta: META, records: [entityRecord(4)] });
    expect(tabLabels(kept.root)).toEqual([
      translateTest('ui.entity_page.tab_fields'),
      translateTest('ui.entity_page.tab_history'),
    ]);
  });

  it('draws no tab bar for an entity without history: the fields are the whole card', async () => {
    const { root } = await renderEntityScreen(`/e/${CODE}/4`, {
      meta: { ...META, capabilities: [] },
      records: [entityRecord(4)],
    });
    expect(tabLabels(root)).toEqual([]);
    expect(root.querySelector('#entity-record-panel')?.getAttribute('role')).toBeNull();
    expect(root.querySelector('smt-entity-form, [data-section="main"]')).not.toBeNull();
  });

  it('labels an action without a catalog text by its code', async () => {
    const { root } = await renderEntityScreen(`/e/${CODE}/4`, {
      meta: META,
      records: [entityRecord(4, { actions: ['update', 'recalculate_totals'] })],
    });
    expect(root.querySelector('[data-action="recalculate_totals"]')?.textContent?.trim()).toBe('recalculate_totals');
  });

  it('says "not found" for an id that is not a number, without asking the server', async () => {
    const { root, api } = await renderEntityScreen(`/e/${CODE}/new-ish`, { meta: META });
    expect(stateTitle(root)).toBe(translateTest('ui.entity_page.record_missing'));
    expect(api.get.mock.calls.some(([path]) => String(path).startsWith(`/entities/${CODE}/`))).toBe(false);
  });

  it('offers to retry a failed read of the record', async () => {
    let reads = 0;
    const { root, settle } = await renderEntityScreen(`/e/${CODE}/4`, {
      meta: META,
      answers: {
        [`/entities/${CODE}/4`]: () => (reads++ === 0 ? throwError(() => problem(500)) : of(entityRecord(4))),
      },
    });
    expect(stateTitle(root)).toBe(translateTest('ui.entity_page.load_failed'));

    (root.querySelector('[data-testid="entity-page-state"] button') as HTMLButtonElement).click();
    await settle();
    expect(reads).toBe(2);
    expect(root.querySelector('[data-testid="entity-page-state"]')).toBeNull();
    expect(root.querySelector('h1')).not.toBeNull();
  });
});
