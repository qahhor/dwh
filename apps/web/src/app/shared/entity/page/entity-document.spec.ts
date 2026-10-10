import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import type { FormMeta } from '@core/models/form-meta.models';
import { formField } from '@testing/form-meta';
import { entityRecord, metaField, problem, queryMetaFixture, renderEntityScreen } from '@testing/entity-page';
import { translateTest } from '@testing/i18n-test.stub';

/*
 * A document on the general entity screen (ADR-0032 9, plan 10/10, item 5.7) — the reference "document with lines and
 * statuses" no web code knows: its lines are edited under the form and saved with it, a line's problem shows under its
 * control, the state of its process locks fields and lines, and its card has the tabs its declaration gives, the
 * transitions as buttons and the history of its lines. Everything comes from these metadata fixtures.
 */
const CODE = 'test.documents';

const META: FormMeta = {
  code: CODE,
  listCode: CODE,
  fields: [
    formField('number', 'text', { label: 'Номер', labelKey: '', readonly: true }),
    formField('customer', 'text', { label: 'Клиент', labelKey: '', required: true }),
    formField('currency', 'select', {
      label: 'Валюта',
      labelKey: '',
      options: ['UZS', 'USD'],
      required: true,
      defaultValue: { kind: 'fixed', value: 'UZS' },
    }),
    formField('status', 'select', {
      label: 'Статус',
      labelKey: '',
      options: ['draft', 'posted', 'cancelled'],
      optionLabelPrefix: 'test.status.',
      readonly: true,
    }),
  ],
  layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['number', 'customer', 'currency', 'status'] }],
  actions: ['create', 'update', 'post', 'unpost', 'cancel'],
  capabilities: ['history'],
  collections: [
    {
      key: 'lines',
      labelKey: 'example.orders.lines',
      maxRows: 500,
      fields: [
        formField('product', 'text', { labelKey: 'example.orders.line.product', required: true }),
        formField('qty', 'number', { labelKey: 'example.orders.line.qty', required: true }),
        formField('price', 'money', {
          labelKey: 'example.orders.line.price',
          currencies: ['UZS', 'USD'],
          currencyFrom: 'currency',
        }),
        formField('amount', 'money', {
          labelKey: 'example.orders.line.amount',
          currencies: ['UZS', 'USD'],
          currencyFrom: 'currency',
          computed: true,
          readonly: true,
        }),
      ],
    },
  ],
  workflow: {
    field: 'status',
    states: [
      { code: 'draft', labelKey: 'example.orders.status.draft', initial: true, terminal: false, locks: [] },
      {
        code: 'posted',
        labelKey: 'example.orders.status.posted',
        initial: false,
        terminal: false,
        locks: ['customer', 'currency', 'lines'],
      },
      { code: 'cancelled', labelKey: 'example.orders.status.cancelled', initial: false, terminal: true, locks: [] },
    ],
    transitions: [
      { code: 'post', from: ['draft'], to: 'posted', permission: 'post', confirmKey: null },
      { code: 'unpost', from: ['posted'], to: 'draft', permission: 'unpost', confirmKey: null },
      {
        code: 'cancel',
        from: ['draft'],
        to: 'cancelled',
        permission: 'cancel',
        confirmKey: 'example.orders.cancel_confirm',
      },
    ],
  },
  tabs: [
    { key: 'main', labelKey: 'ui.entity_page.tab_fields', kind: 'sections', sections: ['main'] },
    { key: 'lines', labelKey: 'example.orders.lines', kind: 'collection', collection: 'lines' },
    { key: 'items', labelKey: 'nav.tasks', kind: 'related', entity: 'test.items', field: 'documentId' },
    { key: 'history', labelKey: 'ui.entity_page.tab_history', kind: 'history' },
  ],
};

const LINE = {
  id: 11,
  position: 1,
  product: 'Мука',
  qty: 3,
  price: { amount: '10.00', currency: 'UZS' },
  amount: { amount: '30.00', currency: 'UZS' },
};

function draft(values: Record<string, unknown> = {}) {
  return entityRecord(1, {
    number: 'ORD-000001',
    customer: 'Магазин',
    currency: 'UZS',
    status: 'draft',
    lines: [LINE],
    actions: ['create', 'update', 'post', 'cancel'],
    ...values,
  });
}

function lines(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>('smt-entity-lines .entity-line'));
}

function type(input: HTMLInputElement | null, value: string): void {
  input!.value = value;
  input!.dispatchEvent(new Event('input'));
}

function click(root: HTMLElement, selector: string): void {
  root.querySelector<HTMLButtonElement>(selector)!.click();
}

describe('a document on the general form', () => {
  it('adds, fills, moves and removes lines and saves them with the record', async () => {
    const created = draft({ id: 7 });
    const { root, api, router, settle } = await renderEntityScreen(`/e/${CODE}/new`, {
      meta: META,
      changes: { [`POST /entities/${CODE}`]: () => of(created) },
      answers: { [`/entities/${CODE}/7`]: created },
    });

    type(root.querySelector('smt-entity-form [data-field="customer"] input'), 'Склад');
    for (let count = 0; count < 3; count += 1) {
      click(root, '[data-testid="entity-line-add"]');
      await settle();
    }
    expect(lines(root).length).toBe(3);
    ['Мука', 'Сахар', 'Соль'].forEach((product, index) => {
      type(lines(root)[index].querySelector('[data-field="product"] input'), product);
      type(lines(root)[index].querySelector('[data-field="qty"] input'), String(index + 1));
      type(lines(root)[index].querySelector('[data-field="price"] input'), '2.50');
    });
    await settle();
    lines(root)[2].querySelector<HTMLButtonElement>('[data-testid="entity-line-up"]')!.click();
    await settle();
    lines(root)[0].querySelector<HTMLButtonElement>('[data-testid="entity-line-remove"]')!.click();
    await settle();

    click(root, '[data-testid="form-submit"]');
    await settle();

    expect(api.post).toHaveBeenCalledWith(
      `/entities/${CODE}`,
      expect.objectContaining({
        customer: 'Склад',
        lines: [
          { product: 'Соль', qty: 3, price: { amount: '2.50', currency: 'UZS' } },
          { product: 'Сахар', qty: 2, price: { amount: '2.50', currency: 'UZS' } },
        ],
      }),
      { notifyError: false },
    );
    expect(router.url).toBe(`/e/${CODE}/7`);
  });

  it('shows a line problem of the server under its control and the problem of the lines under them', async () => {
    const { root, settle } = await renderEntityScreen(`/e/${CODE}/1/edit`, {
      meta: META,
      records: [draft()],
      changes: {
        [`PATCH /entities/${CODE}/1`]: () =>
          throwError(() =>
            problem(422, {
              errors: [
                { field: 'lines[0].qty', code: 'out_of_range', message: '' },
                { field: 'lines', code: 'required', message: '' },
              ],
            }),
          ),
      },
    });

    click(root, '[data-testid="form-submit"]');
    await settle();

    expect(lines(root)[0].querySelector('[data-field="qty"]')?.textContent).toContain(
      translateTest('ui.entity_form.out_of_range'),
    );
    expect(root.querySelector('[data-testid="entity-lines-problem"]')?.textContent).toContain(
      translateTest('ui.entity_lines.required'),
    );
  });

  it('keeps what the state locks: a posted document changes its other fields only, without its lines', async () => {
    const posted = draft({ status: 'posted', actions: ['update', 'unpost'] });
    const { root, api, settle } = await renderEntityScreen(`/e/${CODE}/1/edit`, {
      meta: { ...META, fields: [...META.fields, formField('comment', 'textarea', { label: 'Комм.', labelKey: '' })] },
      records: [posted],
    });

    expect(root.querySelector<HTMLInputElement>('smt-entity-form [data-field="customer"] input')!.disabled).toBe(true);
    expect(root.querySelector('[data-testid="entity-line-add"]')).toBeNull();
    click(root, '[data-testid="form-submit"]');
    await settle();

    const body = api.patch.mock.calls[0][1] as Record<string, unknown>;
    expect('lines' in body).toBe(false);
  });

  it('leaves an untouched document with lines and money without asking, and asks once a line changes', async () => {
    const { root, router, confirm, settle } = await renderEntityScreen(`/e/${CODE}/1/edit`, {
      meta: META,
      records: [draft()],
    });
    await router.navigateByUrl(`/e/${CODE}`);
    await settle();
    expect(confirm).not.toHaveBeenCalled();
    expect(router.url).toBe(`/e/${CODE}`);

    await router.navigateByUrl(`/e/${CODE}/1/edit`);
    await settle();
    click(root, '[data-testid="entity-line-add"]');
    await settle();
    await router.navigateByUrl(`/e/${CODE}`);
    await settle();
    expect(confirm).toHaveBeenCalledTimes(1);
  });
});

describe('a document card', () => {
  it('has the tabs of its declaration, its state and the transitions its record allows', async () => {
    const { root, settle } = await renderEntityScreen(`/e/${CODE}/1`, {
      meta: META,
      records: [draft()],
      answers: {
        '/query-meta/test.items': queryMetaFixture('test.items', [metaField('title', '', 'text', { label: 'Тема' })]),
        '/entities/test.items': { items: [entityRecord(5, { title: 'Связанная' })], nextCursor: null, hasMore: false },
      },
    });

    const tabs = Array.from(root.querySelectorAll<HTMLButtonElement>('[role="tab"]'));
    expect(tabs.map((tab) => tab.textContent?.trim())).toEqual([
      translateTest('ui.entity_page.tab_fields'),
      translateTest('example.orders.lines'),
      translateTest('nav.tasks'),
      translateTest('ui.entity_page.tab_history'),
    ]);
    expect(root.querySelector('[data-testid="entity-state"]')?.textContent).toContain(
      translateTest('example.orders.status.draft'),
    );
    expect(root.querySelector('[data-action="post"]')?.textContent).toContain(translateTest('entity.action.post'));
    expect(root.querySelector('[data-action="unpost"]')).toBeNull();

    tabs[1].click();
    await settle();
    const cells = Array.from(root.querySelectorAll('smt-entity-rows tbody td')).map((cell) => cell.textContent?.trim());
    expect(cells[1]).toBe('Мука');
    expect(cells.join('|')).toContain('30');

    tabs[2].click();
    await settle();
    const related = root.querySelector('smt-entity-related');
    expect(related?.querySelector('a')?.getAttribute('href')).toBe('/e/test.items/5');
    expect(related?.textContent).toContain('Связанная');
  });

  it('asks the question of a transition first and takes it from the revision on screen', async () => {
    const order = draft({ revision: 3 });
    const { root, api, confirm, settle } = await renderEntityScreen(`/e/${CODE}/1`, {
      meta: META,
      records: [order],
      changes: {
        [`POST /entities/${CODE}/1/actions/cancel`]: () =>
          of({ ...order, status: 'cancelled', revision: 4, actions: ['create'] }),
      },
    });

    click(root, '[data-action="cancel"]');
    await settle();

    expect(confirm).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining('ORD-000001') }));
    expect(api.post).toHaveBeenCalledWith(`/entities/${CODE}/1/actions/cancel`, {}, { notifyError: false, ifMatch: 3 });
    expect(root.querySelector('[data-testid="entity-state"]')?.textContent).toContain(
      translateTest('example.orders.status.cancelled'),
    );
    expect(root.querySelector('[data-testid="entity-edit"]')).toBeNull();
  });

  it('shows the change of the lines and the transition in the history', async () => {
    const entry = {
      id: 9,
      event: 'U',
      changedAt: '2026-10-02T09:00:00Z',
      changedByName: 'Иван Петров',
      isApi: false,
      changes: [
        { field: 'status', label: 'Статус', oldValue: 'draft', newValue: 'posted' },
        { field: 'action', oldValue: null, newValue: 'post' },
        {
          field: 'lines',
          labelKey: 'example.orders.lines',
          oldValue: { count: 1 },
          newValue: { count: 3, added: [{}, {}], changed: [{}] },
        },
      ],
    };
    const { root, settle } = await renderEntityScreen(`/e/${CODE}/1`, {
      meta: META,
      records: [draft()],
      answers: { [`/history/${CODE}/1`]: { items: [entry], nextCursor: null, hasMore: false } },
    });

    root.querySelectorAll<HTMLButtonElement>('[role="tab"]')[3].click();
    await settle();

    const text = root.querySelector('[data-testid="record-history-list"]')?.textContent ?? '';
    expect(text).toContain(translateTest('ui.history.rows_changed', { count: 3, added: 2, changed: 1, removed: 0 }));
    expect(text).toContain(translateTest('ui.history.rows_count', { count: 1 }));
    expect(text).toContain(translateTest('ui.history.action'));
    expect(text).toContain(translateTest('entity.action.post'));
  });
});
