import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import type { FormMeta } from '@core/models/form-meta.models';
import { formField } from '@testing/form-meta';
import { EntityFormHarness } from '@testing/entity-form';
import { entityRecord, metaField, problem, queryMetaFixture, renderEntityScreen } from '@testing/entity-page';
import { translateTest } from '@testing/i18n-test.stub';
import { PermissionService } from '@core/services/permission.service';
import { provideEntityOverrides } from './entity-overrides';

/*
 * The general entity screen (ADR-0032 7.1, 7.3; plan 10/10, item 5.5) on an entity no web code knows: everything the
 * pages draw comes from these metadata fixtures, as it would for a new module's entity.
 */
const CODE = 'test.orders';

const META: FormMeta = {
  code: CODE,
  listCode: CODE,
  fields: [
    formField('number', 'text', { label: 'Номер', labelKey: '', required: true, maxLength: 32 }),
    formField('customer', 'text', { label: 'Клиент', labelKey: '' }),
    formField('status', 'select', { label: 'Статус', labelKey: '', options: ['draft', 'posted'] }),
    formField('comment', 'textarea', { label: 'Комментарий', labelKey: '' }),
  ],
  layout: [
    { key: 'main', labelKey: 'entity.section.main', fields: ['number', 'customer', 'status'] },
    { key: 'notes', labelKey: 'entity.section.settings', fields: ['comment'] },
  ],
  actions: ['create', 'update', 'archive', 'delete'],
  capabilities: ['archive', 'bulk', 'export', 'history', 'saved_views'],
};

const LIST = queryMetaFixture(CODE, [
  metaField('number', '', 'text', { label: 'Номер', sortable: true, searchable: true }),
  metaField('status', '', 'enum', { label: 'Статус', enumValues: ['draft', 'posted'] }),
  metaField('customer', '', 'text', { label: 'Клиент', defaultVisible: false }),
]);

const ORDERS = [
  entityRecord(1, { number: 'ЗК-1', customer: 'Магазин 1', status: 'draft' }),
  entityRecord(2, { number: 'ЗК-2', customer: 'Магазин 2', status: 'posted' }),
];

const MENU = [
  {
    code: CODE,
    form: 'orders',
    route: `/e/${CODE}`,
    labelKey: 'test.orders.title',
    icon: 'receipt',
    section: 'workspace',
    order: 1,
    module: null,
  },
];

function headers(root: HTMLElement): string[] {
  return Array.from(root.querySelectorAll('[role="columnheader"]'))
    .map((header) => header.textContent?.trim() ?? '')
    .filter(Boolean);
}

describe('the general entity list /e/:code', () => {
  it('draws the columns, rows and buttons from the metadata alone', async () => {
    const { root, api } = await renderEntityScreen(`/e/${CODE}`, {
      meta: META,
      list: LIST,
      records: ORDERS,
      menu: MENU,
    });

    expect(root.querySelector('ui-page-header')?.textContent).toContain('test.orders.title');
    // The first header also holds the select-all box and the sort mark; a hidden field has no column.
    expect(headers(root)).toEqual([expect.stringContaining('Номер'), 'Статус']);
    const links = Array.from(root.querySelectorAll<HTMLAnchorElement>('a.entity-open'));
    expect(links.map((link) => link.textContent?.trim())).toEqual(['ЗК-1', 'ЗК-2']);
    expect(links[0].getAttribute('href')).toBe(`/e/${CODE}/1`);
    expect(root.querySelector('[data-testid="entity-create"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="entity-archive-toggle"]')).not.toBeNull();
    expect(root.querySelector('ui-export-button')).not.toBeNull();
    expect(root.querySelector('ui-list-views')).not.toBeNull();
    expect(root.querySelector('input[type="checkbox"]')).not.toBeNull();
    expect(api.get).toHaveBeenCalledWith(`/entities/${CODE}`, expect.objectContaining({ limit: 25 }), {
      notifyError: false,
    });
  });

  it('offers only what the viewer may do and what the entity declares', async () => {
    const meta: FormMeta = { ...META, actions: [], capabilities: [] };
    const { root } = await renderEntityScreen(`/e/${CODE}`, { meta, list: LIST, records: ORDERS });

    expect(root.querySelector('[data-testid="entity-create"]')).toBeNull();
    expect(root.querySelector('[data-testid="entity-archive-toggle"]')).toBeNull();
    expect(root.querySelector('ui-export-button')).toBeNull();
    expect(root.querySelector('ui-list-views')).toBeNull();
    expect(root.querySelector('input[type="checkbox"]')).toBeNull();
    // Without a menu item the entity is named by its code.
    expect(root.querySelector('ui-page-header')?.textContent).toContain(CODE);
  });

  it('starts with the filter a link gives, leaving out conditions on fields the list does not have', async () => {
    const filter = JSON.stringify([
      { field: 'status', op: 'eq', value: 'posted' },
      { field: 'unknown', op: 'eq', value: 1 },
    ]);
    const { api } = await renderEntityScreen(`/e/${CODE}?filter=${encodeURIComponent(filter)}`, {
      meta: META,
      list: LIST,
      records: ORDERS,
    });

    const last = api.get.mock.calls.filter(([path]) => path === `/entities/${CODE}`).at(-1)!;
    const sent = String((last[1] as { filter?: string }).filter);
    expect(sent).toContain('"status"');
    expect(sent).not.toContain('"unknown"');
  });

  it('shows the archive when the switch is pressed', async () => {
    const { root, api, settle } = await renderEntityScreen(`/e/${CODE}`, { meta: META, list: LIST, records: ORDERS });

    root.querySelector<HTMLButtonElement>('[data-testid="entity-archive-toggle"]')!.click();
    await settle();

    const last = api.get.mock.calls.filter(([path]) => path === `/entities/${CODE}`).at(-1)!;
    expect(String((last[1] as { filter?: string }).filter)).toContain('"archived"');
  });

  it('says "not found" for an entity the server does not give this viewer', async () => {
    const { root } = await renderEntityScreen('/e/test.secret', { meta: { ...META, code: 'test.other' } });

    expect(root.querySelector('[data-testid="entity-page-state"]')?.textContent).toContain(
      translateTest('ui.entity_page.entity_missing'),
    );
  });

  it('keeps a switched-off module closed, by its menu item', async () => {
    const menu = [{ ...MENU[0], module: 'orders' }];
    const { router } = await renderEntityScreen(`/e/${CODE}`, {
      meta: META,
      menu,
      answers: { '/modules/active': [{ code: 'notes', isActive: true, status: 'ACTIVE' }] },
    });

    expect(router.url).toBe('/tasks');
  });

  it('draws a cell of the entity own from provideEntityOverrides', async () => {
    const { root } = await renderEntityScreen(`/e/${CODE}`, {
      meta: META,
      list: LIST,
      records: ORDERS,
      providers: [provideEntityOverrides(CODE, { cells: { status: StatusCellComponent } })],
    });

    expect(Array.from(root.querySelectorAll('.status-cell')).map((cell) => cell.textContent?.trim())).toEqual([
      'draft!',
      'posted!',
    ]);
  });
});

describe('the general entity form /e/:code/new and /e/:code/:id/edit', () => {
  it('creates a record from the form and opens it', async () => {
    const created = entityRecord(7, { number: 'ЗК-7' });
    const { root, api, router, settle } = await renderEntityScreen(`/e/${CODE}/new`, {
      meta: META,
      changes: { [`POST /entities/${CODE}`]: () => of(created) },
      answers: { [`/entities/${CODE}/7`]: created },
    });
    const form = new EntityFormHarness(
      root,
      () => META,
      () => undefined,
    );

    expect(form.keys()).toEqual(['number', 'customer', 'status', 'comment']);
    form.fill('number', 'ЗК-7');
    await settle();
    root.querySelector<HTMLButtonElement>('[data-testid="entity-save"]')!.click();
    await settle();

    expect(api.post).toHaveBeenCalledWith(
      `/entities/${CODE}`,
      expect.objectContaining({ number: 'ЗК-7', attributes: {} }),
      { notifyError: false },
    );
    expect(router.url).toBe(`/e/${CODE}/7`);
  });

  it('checks the declared rules before asking the server', async () => {
    const { root, api, settle } = await renderEntityScreen(`/e/${CODE}/new`, { meta: META });
    const form = new EntityFormHarness(
      root,
      () => META,
      () => undefined,
    );

    root.querySelector<HTMLButtonElement>('[data-testid="entity-save"]')!.click();
    await settle();

    expect(form.problem('number')).toBe(translateTest('ui.entity_form.required'));
    expect(api.post).not.toHaveBeenCalled();
  });

  it('puts the 422 of the server under its fields', async () => {
    const { root, settle } = await renderEntityScreen(`/e/${CODE}/new`, {
      meta: META,
      changes: {
        [`POST /entities/${CODE}`]: () =>
          throwError(() => problem(422, { errors: [{ field: 'number', code: 'too_long', message: '' }] })),
      },
    });
    const form = new EntityFormHarness(
      root,
      () => META,
      () => undefined,
    );
    form.fill('number', 'ЗК-1');
    await settle();

    root.querySelector<HTMLButtonElement>('[data-testid="entity-save"]')!.click();
    await settle();

    expect(form.problem('number')).toBe(translateTest('ui.entity_form.too_long', { n: 32 }));
  });

  it('changes a record from the revision it read, and offers to read it again after a conflict', async () => {
    const order = entityRecord(1, { number: 'ЗК-1', revision: 4 });
    const { root, api, toast, settle } = await renderEntityScreen(`/e/${CODE}/1/edit`, {
      meta: META,
      records: [order],
      changes: { [`PATCH /entities/${CODE}/1`]: () => throwError(() => problem(409)) },
    });
    const form = new EntityFormHarness(
      root,
      () => META,
      () => undefined,
    );

    expect(root.querySelector<HTMLInputElement>('[data-field="number"] input')!.value).toBe('ЗК-1');
    form.fill('customer', 'Магазин 9');
    await settle();
    root.querySelector<HTMLButtonElement>('[data-testid="entity-save"]')!.click();
    await settle();

    expect(api.patch).toHaveBeenCalledWith(
      `/entities/${CODE}/1`,
      expect.objectContaining({ number: 'ЗК-1', customer: 'Магазин 9' }),
      { notifyError: false, ifMatch: 4 },
    );
    const [, , , , action] = toast.show.mock.calls[0];
    expect(action.label).toBe(translateTest('common.refresh'));
    action.run();
    await settle();
    expect(api.get.mock.calls.filter(([path]) => path === `/entities/${CODE}/1`).length).toBe(2);
  });

  it('refuses to edit a record the viewer may not change', async () => {
    const { root } = await renderEntityScreen(`/e/${CODE}/1/edit`, {
      meta: META,
      records: [entityRecord(1, { number: 'ЗК-1', actions: [] })],
    });

    expect(root.querySelector('smt-entity-form')).toBeNull();
    expect(root.querySelector('[data-testid="entity-page-state"]')?.textContent).toContain(
      translateTest('ui.entity_page.denied'),
    );
  });

  it('draws a field control of the entity own from provideEntityOverrides', async () => {
    const { root } = await renderEntityScreen(`/e/${CODE}/new`, {
      meta: META,
      providers: [provideEntityOverrides(CODE, { fields: { status: StatusControlComponent } })],
    });

    const control = root.querySelector<HTMLButtonElement>('[data-field="status"] .status-control')!;
    expect(control.textContent?.trim()).toBe('—');
  });
});

describe('the general entity record /e/:code/:id', () => {
  it('shows the record by its sections and the buttons its actions allow', async () => {
    const { root } = await renderEntityScreen(`/e/${CODE}/1`, { meta: META, records: ORDERS, menu: MENU });

    expect(root.querySelector('ui-page-header h1, ui-page-header [class*="title"]')?.textContent).toContain('ЗК-1');
    const lines = Array.from(root.querySelectorAll('.entity-card-line')).map((line) => line.textContent?.trim());
    expect(lines.join('|')).toContain('Магазин 1');
    expect(root.querySelector('[data-testid="entity-edit"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="entity-archive"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="entity-delete"]')).not.toBeNull();
    expect(root.querySelectorAll('[role="tab"]').length).toBe(2);
  });

  it('shows the change history on its tab, opened', async () => {
    const entry = {
      id: 5,
      event: 'U',
      changedAt: '2026-10-01T09:00:00Z',
      changedByName: 'Иван Петров',
      isApi: false,
      changes: [{ field: 'customer', label: 'Клиент', oldValue: 'Магазин', newValue: 'Магазин 1' }],
    };
    const { root, api, settle } = await renderEntityScreen(`/e/${CODE}/1`, {
      meta: META,
      records: ORDERS,
      answers: { [`/history/${CODE}/1`]: { items: [entry], nextCursor: null, hasMore: false } },
    });

    const historyTab = Array.from(root.querySelectorAll<HTMLButtonElement>('[role="tab"]')).find((tab) =>
      tab.textContent?.includes(translateTest('ui.entity_page.tab_history')),
    )!;
    historyTab.click();
    await settle();

    expect(api.get).toHaveBeenCalledWith(`/history/${CODE}/1`, expect.anything(), expect.anything());
    expect(root.querySelector('[data-testid="record-history-list"]')?.textContent).toContain('Магазин 1');
  });

  it('offers nothing the record does not allow this viewer', async () => {
    const { root } = await renderEntityScreen(`/e/${CODE}/1`, {
      meta: META,
      records: [entityRecord(1, { number: 'ЗК-1', actions: [] })],
    });

    expect(root.querySelector('[data-testid="entity-edit"]')).toBeNull();
    expect(root.querySelector('[data-testid="entity-archive"]')).toBeNull();
    expect(root.querySelector('[data-testid="entity-delete"]')).toBeNull();
  });

  it('archives from the revision on screen and shows the mark', async () => {
    const order = entityRecord(1, { number: 'ЗК-1', revision: 3 });
    const { root, api, settle } = await renderEntityScreen(`/e/${CODE}/1`, {
      meta: META,
      records: [order],
      changes: { [`PUT /entities/${CODE}/1/archived`]: () => of({ ...order, archived: true, revision: 4 }) },
    });

    root.querySelector<HTMLButtonElement>('[data-testid="entity-archive"]')!.click();
    await settle();

    expect(api.put).toHaveBeenCalledWith(
      `/entities/${CODE}/1/archived`,
      { archived: true },
      {
        notifyError: false,
        ifMatch: 3,
      },
    );
    expect(root.querySelector('[data-testid="entity-archived"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="entity-archive"]')?.textContent).toContain(
      translateTest('ui.entity_page.restore'),
    );
  });

  it('deletes after asking and goes back to the list', async () => {
    const { root, api, confirm, router, settle } = await renderEntityScreen(`/e/${CODE}/1`, {
      meta: META,
      list: LIST,
      records: ORDERS,
      changes: { [`DELETE /entities/${CODE}/1`]: () => of(undefined) },
    });

    root.querySelector<HTMLButtonElement>('[data-testid="entity-delete"]')!.click();
    await settle();

    expect(confirm).toHaveBeenCalled();
    expect(api.delete).toHaveBeenCalledWith(`/entities/${CODE}/1`, { notifyError: false });
    expect(router.url).toBe(`/e/${CODE}`);
  });

  it('runs an action of the record by its code from the revision on screen', async () => {
    const order = entityRecord(1, { number: 'ЗК-1', revision: 2, actions: ['post'] });
    const { root, api, settle } = await renderEntityScreen(`/e/${CODE}/1`, {
      meta: META,
      records: [order],
      changes: { [`POST /entities/${CODE}/1/actions/post`]: () => of({ ...order, revision: 3, actions: [] }) },
    });

    root.querySelector<HTMLButtonElement>('[data-action="post"]')!.click();
    await settle();

    expect(api.post).toHaveBeenCalledWith(`/entities/${CODE}/1/actions/post`, {}, { notifyError: false, ifMatch: 2 });
    expect(root.querySelector('[data-action="post"]')).toBeNull();
  });

  it('asks before an action with a confirmation text, naming the record, and runs it on yes', async () => {
    const order = entityRecord(1, { number: 'ЗК-1', revision: 4, actions: ['block'] });
    const { root, api, confirm, settle } = await renderEntityScreen(`/e/${CODE}/1`, {
      meta: META,
      records: [order],
      changes: { [`POST /entities/${CODE}/1/actions/block`]: () => of({ ...order, revision: 5, actions: [] }) },
    });

    root.querySelector<HTMLButtonElement>('[data-action="block"]')!.click();
    await settle();

    expect(confirm).toHaveBeenCalledWith(
      expect.objectContaining({
        message: translateTest('entity.action_confirm.block', { name: 'ЗК-1' }),
        destructive: true,
      }),
    );
    expect(api.post).toHaveBeenCalledWith(`/entities/${CODE}/1/actions/block`, {}, { notifyError: false, ifMatch: 4 });
  });

  it('offers a tab of the entity own only to a viewer who holds one of its rights', async () => {
    const tabs = [
      { key: 'open', labelKey: 'test.tab.open', component: TabComponent },
      {
        key: 'audit',
        labelKey: 'test.tab.audit',
        component: TabComponent,
        requires: [{ form: 'orders', action: 'audit' }],
      },
      {
        key: 'either',
        labelKey: 'test.tab.either',
        component: TabComponent,
        requires: [
          { form: 'orders', action: 'audit' },
          { form: 'orders', action: 'view' },
        ],
      },
    ];
    const { root } = await renderEntityScreen(`/e/${CODE}/1`, {
      meta: META,
      records: ORDERS,
      providers: [
        provideEntityOverrides(CODE, { tabs }),
        {
          provide: PermissionService,
          useValue: { hasPermission: (form: string, action: string) => form === 'orders' && action === 'view' },
        },
      ],
    });

    const labels = Array.from(root.querySelectorAll('[role="tab"]')).map((tab) => tab.textContent?.trim());
    expect(labels).toEqual([
      translateTest('ui.entity_page.tab_fields'),
      translateTest('ui.entity_page.tab_history'),
      'test.tab.open',
      'test.tab.either',
    ]);
  });

  it('says "not found" for a record out of reach', async () => {
    const { root } = await renderEntityScreen(`/e/${CODE}/99`, { meta: META, records: ORDERS });

    expect(root.querySelector('[data-testid="entity-page-state"]')?.textContent).toContain(
      translateTest('ui.entity_page.record_missing'),
    );
  });

  it('opens a file field through the record', async () => {
    const meta: FormMeta = {
      ...META,
      fields: [...META.fields, formField('scan', 'file', { label: 'Скан', labelKey: '' })],
      layout: [...META.layout, { key: 'files', labelKey: 'entity.section.custom', fields: ['scan'] }],
    };
    const order = entityRecord(1, { number: 'ЗК-1', scan: { id: 'f-1', name: 'scan.pdf', size: 10 } });
    const { root } = await renderEntityScreen(`/e/${CODE}/1`, { meta, records: [order] });

    const link = root.querySelector<HTMLAnchorElement>('[data-field="scan"] a')!;
    expect(link.textContent?.trim()).toBe('scan.pdf');
    expect(link.getAttribute('href')).toBe(`/api/v1/entities/${CODE}/1/files/f-1`);
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
  });
});

@Component({
  selector: 'smt-test-status-cell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="status-cell">{{ row()['status'] }}!</span>`,
})
class StatusCellComponent {
  readonly row = input.required<Record<string, unknown>>();
  readonly field = input<unknown>();
}

@Component({
  selector: 'smt-test-status-control',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<button type="button" class="status-control" (click)="set()('posted')">{{ value() ?? '—' }}</button>`,
})
class StatusControlComponent {
  readonly field = input<unknown>();
  readonly value = input<unknown>();
  readonly problem = input('');
  readonly disabled = input(false);
  readonly set = input.required<(value: unknown) => void>();
}

@Component({
  selector: 'smt-test-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p class="test-tab">{{ record()['number'] }}</p>`,
})
class TabComponent {
  readonly meta = input<unknown>();
  readonly record = input.required<Record<string, unknown>>();
}
