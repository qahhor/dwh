import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import type { FormMeta } from '@core/models/form-meta.models';
import { formField } from '@testing/form-meta';
import { metaField, problem, queryMetaFixture, renderEntityScreen } from '@testing/entity-page';
import { translateTest } from '@testing/i18n-test.stub';

const CODE = 'test.orders';

const META: FormMeta = {
  code: CODE,
  listCode: CODE,
  fields: [formField('number', 'text', { label: 'Номер', labelKey: '' })],
  layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['number'] }],
  actions: ['create', 'update'],
  capabilities: [],
};

const LIST = queryMetaFixture(CODE, [metaField('number', '', 'text', { label: 'Номер' })]);

function formMetaReads(api: { get: { mock: { calls: unknown[][] } } }, code = CODE): number {
  return api.get.mock.calls.filter(([path]) => path === `/form-meta/${code}`).length;
}

/*
 * The shell of the general entity screen (ADR-0032 7.1): it reads the form once and draws the page of the route under
 * it, or what stands in for it.
 */
describe('SMTEntityPageComponent', () => {
  it('reads the form once and draws the page of the route under it', async () => {
    const { root, api } = await renderEntityScreen(`/e/${CODE}`, { meta: META, list: LIST });

    expect(formMetaReads(api)).toBe(1);
    expect(root.querySelector('smt-entity-list-page')).not.toBeNull();
    expect(root.querySelector('[data-testid="entity-page-state"]')).toBeNull();
  });

  it('says "not found" for a code that cannot be an entity, without asking the server', async () => {
    const { root, api } = await renderEntityScreen('/e/Not-A-Code', { meta: META, list: LIST });

    expect(api.get.mock.calls.some(([path]) => String(path).startsWith('/form-meta/'))).toBe(false);
    expect(root.querySelector('[data-testid="entity-page-state"] h2')?.textContent?.trim()).toBe(
      translateTest('ui.entity_page.entity_missing'),
    );
  });

  it('says "not found" when the server does not give the entity to this viewer', async () => {
    const { root } = await renderEntityScreen(`/e/${CODE}`, {
      meta: META,
      answers: { [`/form-meta/${CODE}`]: () => throwError(() => problem(404)) },
    });

    expect(root.querySelector('[data-testid="entity-page-state"] h2')?.textContent?.trim()).toBe(
      translateTest('ui.entity_page.entity_missing'),
    );
  });

  it('offers to retry a failed read of the form, and draws the page once it comes', async () => {
    let reads = 0;
    const { root, api, settle } = await renderEntityScreen(`/e/${CODE}`, {
      meta: META,
      list: LIST,
      answers: {
        [`/form-meta/${CODE}`]: () => (reads++ === 0 ? throwError(() => problem(500)) : of(META)),
      },
    });

    const state = root.querySelector('[data-testid="entity-page-state"]') as HTMLElement;
    expect(state.getAttribute('role')).toBe('alert');
    (state.querySelector('button') as HTMLButtonElement).click();
    await settle();

    expect(formMetaReads(api)).toBe(2);
    expect(root.querySelector('smt-entity-list-page')).not.toBeNull();
  });

  it('reads the form of another entity when the code changes', async () => {
    const other: FormMeta = { ...META, code: 'test.items', listCode: 'test.items' };
    const { root, api, navigate } = await renderEntityScreen(`/e/${CODE}`, {
      meta: META,
      list: LIST,
      answers: {
        '/form-meta/test.items': other,
        '/query-meta/test.items': queryMetaFixture('test.items', [metaField('number', '', 'text')]),
        '/list-views/test.items': [],
        '/entities/test.items': { items: [], nextCursor: null, hasMore: false, totalEstimated: 0 },
      },
    });

    await navigate('/e/test.items');
    expect(formMetaReads(api, 'test.items')).toBe(1);
    expect(root.querySelector('smt-entity-list-page')).not.toBeNull();
  });
});
