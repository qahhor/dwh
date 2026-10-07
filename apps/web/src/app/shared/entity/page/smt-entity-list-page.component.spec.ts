import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import type { FormMeta } from '@core/models/form-meta.models';
import { formField } from '@testing/form-meta';
import { entityRecord, metaField, problem, queryMetaFixture, renderEntityScreen } from '@testing/entity-page';
import { translateTest } from '@testing/i18n-test.stub';

const CODE = 'test.orders';

const META: FormMeta = {
  code: CODE,
  listCode: CODE,
  fields: [formField('number', 'text', { label: 'Номер', labelKey: '' })],
  layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['number'] }],
  actions: ['update'],
  capabilities: ['saved_views'],
};

const LIST = queryMetaFixture(CODE, [
  metaField('number', '', 'text', { label: 'Номер', sortable: true, searchable: true }),
  metaField('status', '', 'enum', { label: 'Статус', enumValues: ['draft', 'posted'] }),
]);

const ORDERS = [
  entityRecord(1, { number: 'ЗК-1', status: 'draft' }),
  entityRecord(2, { number: '', status: 'posted' }),
];

function pageReads(api: { get: { mock: { calls: unknown[][] } } }) {
  return api.get.mock.calls.filter(([path]) => path === `/entities/${CODE}`);
}

/* The list of a declared entity (ADR-0032 7.1): drawn from query-meta once it has come. */
describe('SMTEntityListPageComponent', () => {
  it('asks for the first page only after the list metadata and the default view', async () => {
    const { api } = await renderEntityScreen(`/e/${CODE}`, { meta: META, list: LIST, records: ORDERS });

    const order = api.get.mock.calls.map(([path]) => path);
    expect(order.indexOf(`/query-meta/${CODE}`)).toBeLessThan(order.indexOf(`/list-views/${CODE}`));
    expect(order.indexOf(`/list-views/${CODE}`)).toBeLessThan(order.indexOf(`/entities/${CODE}`));
    expect(pageReads(api)).toHaveLength(1);
  });

  it('offers to retry a failed read of the list metadata, then draws the list', async () => {
    let reads = 0;
    const { root, api, settle } = await renderEntityScreen(`/e/${CODE}`, {
      meta: META,
      records: ORDERS,
      answers: { [`/query-meta/${CODE}`]: () => (reads++ === 0 ? throwError(() => problem(500)) : of(LIST)) },
    });

    expect(root.querySelector('[data-testid="entity-page-state"] h2')?.textContent?.trim()).toBe(
      translateTest('ui.entity_page.load_failed'),
    );
    expect(pageReads(api)).toHaveLength(0);

    (root.querySelector('[data-testid="entity-page-state"] button') as HTMLButtonElement).click();
    await settle();

    expect(reads).toBe(2);
    expect(root.querySelector('[data-testid="entity-page-state"]')).toBeNull();
    expect(root.querySelector('ui-server-table')).not.toBeNull();
    expect(pageReads(api)).toHaveLength(1);
  });

  it('names a record link by its first column, or by its number when that is empty', async () => {
    const { root } = await renderEntityScreen(`/e/${CODE}`, { meta: META, list: LIST, records: ORDERS });

    const links = Array.from(root.querySelectorAll<HTMLAnchorElement>('a.entity-open'));
    expect(links.map((link) => link.textContent?.trim())).toEqual([
      'ЗК-1',
      translateTest('ui.entity_page.record', { id: 2 }),
    ]);
    expect(links[0].getAttribute('href')).toBe(`/e/${CODE}/1`);
  });

  it('searches the list once the person stops typing', async () => {
    const { root, api, settle } = await renderEntityScreen(`/e/${CODE}`, { meta: META, list: LIST, records: ORDERS });

    const box = root.querySelector('.entity-search input') as HTMLInputElement;
    box.value = 'ЗК';
    box.dispatchEvent(new Event('input'));
    await new Promise((resolve) => setTimeout(resolve, 350));
    await settle();

    const last = pageReads(api).at(-1)!;
    expect((last[1] as { q?: string }).q).toBe('ЗК');
  });

  it('switches to the report of the list and back', async () => {
    const { root, settle, harness } = await renderEntityScreen(`/e/${CODE}`, {
      meta: META,
      list: LIST,
      records: ORDERS,
      answers: {
        [`/entities/${CODE}/report`]: { groups: [], measures: [{ op: 'count' }], rows: [], truncated: false },
      },
    });

    const page = harness.fixture.debugElement.query(By.css('smt-entity-list-page')).componentInstance;
    page.mode.set('report');
    await settle();
    expect(root.querySelector('smt-entity-report-builder')).not.toBeNull();
    expect(root.querySelector('.entity-search')).toBeNull();

    page.mode.set('list');
    await settle();
    expect(root.querySelector('ui-server-table')).not.toBeNull();
  });

  it('offers no create button to a viewer who may not create', async () => {
    const { root } = await renderEntityScreen(`/e/${CODE}`, { meta: META, list: LIST, records: [] });
    expect(root.querySelector('[data-testid="entity-create"]')).toBeNull();
    expect(root.querySelector('[data-testid="entity-empty"] p')?.textContent?.trim()).toBe(
      translateTest('ui.entity_page.empty_hint_view'),
    );
  });
});
