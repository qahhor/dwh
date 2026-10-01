import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { QueryCondition, QueryListMeta } from '@core/models/query-meta.models';
import { filterDsl, readFilterDsl, toQueryParams } from '@core/services/query-meta.service';
import { ApiService } from '@core/services/api.service';
import { ListViewState, ListViewsApi, SavedListView } from './list-views';
import { describeCondition, fromCondition, toCondition } from './filter-conditions';
import { SMT_DRAWER_DATA, SMT_DRAWER_REF } from '../ui-kit/components/drawer';
import { UiFilterPanelComponent } from '../ui/ui-filter-panel.component';
import { refLookup } from '../lookups/ref-lookup';
import { metaField } from '@testing/registry-meta';
import { firstValueFrom } from 'rxjs';

/** Roadmap item 53: any-groups in the filter and fields that refer to another list. */
const META: QueryListMeta = {
  code: 'ms.tasks',
  defaultSort: 'id',
  defaultLimit: 50,
  maxLimit: 200,
  maxConditions: 20,
  maxInValues: 100,
  fields: [
    metaField('title', 'tasks.col.title', 'text', { ops: ['eq', 'contains'] }),
    metaField('statusId', 'tasks.col.status', 'number', {
      ops: ['eq', 'ne', 'in'],
      ref: { path: '/tasks/statuses', labelField: 'name', keyField: 'id', paged: false },
    }),
  ],
} as QueryListMeta;

const A: QueryCondition = { field: 'title', op: 'contains', value: 'road' };
const B: QueryCondition = { field: 'statusId', op: 'eq', value: 3, label: 'Done' };

describe('any-groups in the filter DSL', () => {
  it('sends the conditions as one any-group only when there are two or more, without screen labels', () => {
    expect(filterDsl([A, B], 'any')).toEqual([{ any: [A, { field: 'statusId', op: 'eq', value: 3 }] }]);
    expect(filterDsl([A], 'any')).toEqual([A]);
    expect(filterDsl([A, B], 'all')).toEqual([A, { field: 'statusId', op: 'eq', value: 3 }]);
    expect(filterDsl([B], 'all', true)).toEqual([B]);
    expect(toQueryParams({ conditions: [A, B], match: 'any' }).filter).toBe(
      JSON.stringify([{ any: [A, { field: 'statusId', op: 'eq', value: 3 }] }]),
    );
  });

  it('reads a saved filter back into its conditions and how they combine', () => {
    expect(readFilterDsl([{ any: [A, B] }])).toEqual({ conditions: [A, B], match: 'any' });
    expect(readFilterDsl([A, B])).toEqual({ conditions: [A, B], match: 'all' });
    expect(readFilterDsl(null)).toEqual({ conditions: [], match: 'all' });
  });

  it('keeps the match and the reference labels in a saved view, and restores them when the view is opened', () => {
    const view: SavedListView = {
      id: 1,
      name: 'Mine',
      isDefault: true,
      lockVersion: 1,
      modifiedAt: '2026-09-26T00:00:00Z',
      state: { columns: { order: [], hidden: [], widths: {} }, sort: null, filter: [{ any: [A, B] }] },
    };
    const api = {
      list: () => of([view]),
      create: vi.fn(),
      update: vi.fn(),
      remove: vi.fn(),
    } as unknown as ListViewsApi;
    const state = new ListViewState('ms.tasks', api, { defaultSort: () => null, onApply: () => {} });
    state.load().subscribe();

    expect(state.match()).toBe('any');
    expect(state.filter()).toEqual([A, B]);
    expect(state.current().filter).toEqual([{ any: [A, B] }]);
    expect(state.changed()).toBe(false);

    state.setFilter([A, B], 'all');
    expect(state.changed()).toBe(true);
  });
});

describe('reference fields', () => {
  it('keep the chosen row name on the condition, so the chip reads it', () => {
    const draft = { ...fromCondition({ field: 'statusId', op: 'eq', value: 3 }), label: 'Done' };
    const condition = toCondition(draft, META);
    expect(condition).toEqual(B);
    expect(
      describeCondition(condition, META, (key) => (({ 'ui.filter.op.eq': '=' }) as Record<string, string>)[key] ?? key),
    ).toBe('tasks.col.status: = Done');
    expect(fromCondition(B).label).toBe('Done');
  });

  it('search a whole short list on the screen and name chosen keys from it', async () => {
    const get = vi.fn(() =>
      of([
        { id: 1, name: 'New' },
        { id: 3, name: 'Done' },
      ]),
    );
    const source = refLookup({ get } as unknown as ApiService, META.fields[1].ref!);
    const page = await firstValueFrom(source.page('do', null, 20));
    expect(page?.items?.map((row) => source.key(row))).toEqual([3]);
    expect(await firstValueFrom(source.resolve!([1]))).toEqual([{ id: 1, name: 'New' }]);
    expect(get).toHaveBeenCalledTimes(1);
  });

  it('search a paged list on the server with q', async () => {
    const get = vi.fn(() => of({ items: [{ id: 7, name: 'Anna' }], nextCursor: null, hasMore: false }));
    const source = refLookup({ get } as unknown as ApiService, {
      path: '/iam/users',
      labelField: 'name',
      keyField: 'id',
      paged: true,
    });
    await firstValueFrom(source.page('an', null, 20));
    expect(get).toHaveBeenCalledWith('/iam/users', { limit: 20, cursor: undefined, q: 'an' }, { notifyError: false });
    expect(source.option({ id: 7, name: 'Anna' }).label).toBe('Anna');
  });

  it('names a chosen row of a list paged under /page from the read the reference names', async () => {
    const get = vi.fn(() => of({ id: 5, name: 'Warehouse' }));
    const source = refLookup({ get } as unknown as ApiService, {
      path: '/tasks/projects/page',
      labelField: 'name',
      keyField: 'id',
      paged: true,
      readPath: '/tasks/projects',
    });
    expect(await firstValueFrom(source.resolve!([5]))).toEqual([{ id: 5, name: 'Warehouse' }]);
    expect(get).toHaveBeenCalledWith('/tasks/projects/5', undefined, { notifyError: false });
  });
});

describe('ui-filter-panel with two rows', () => {
  it('offers "any condition" and closes with it; a reference field gets the lookup editor', async () => {
    const close = vi.fn();
    await TestBed.configureTestingModule({
      imports: [UiFilterPanelComponent],
      providers: [
        { provide: SMT_DRAWER_DATA, useValue: { meta: META, conditions: [A, B], match: 'all' } },
        { provide: SMT_DRAWER_REF, useValue: { close, afterClosed: vi.fn(), componentInstance: null } },
        { provide: ApiService, useValue: { get: () => of([{ id: 3, name: 'Done' }]) } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(UiFilterPanelComponent);
    fixture.detectChanges();
    const panel = fixture.componentInstance;

    expect(fixture.nativeElement.querySelector('[data-testid="filter-match"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="filter-ref"]')).not.toBeNull();
    expect(panel.editorOf(META.fields[1], 'in')).toBe('input');
    expect(panel.refValue(panel.rows()[1])).toBe(3);

    panel.match.set('any');
    panel.apply();
    expect(close).toHaveBeenCalledWith({ conditions: [A, B], match: 'any' });
  });
});
