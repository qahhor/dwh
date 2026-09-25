import { firstValueFrom, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { TableColumnStateStore } from '../ui-kit/services/table-column-state.store';
import { ListViewState, ListViewsApi, SavedListView, samePayload } from './list-views';

const monthly: SavedListView = {
  id: 1,
  name: 'Monthly',
  state: { columns: { order: ['name', 'code'], hidden: [], widths: {} }, sort: '-name', filter: [{ field: 'periodicity', op: 'in', value: ['month'] }] },
  isDefault: false,
  lockVersion: 0,
  modifiedAt: '2026-09-25T00:00:00Z'
};
const all: SavedListView = { ...monthly, id: 2, name: 'All', state: { columns: { order: [], hidden: [], widths: {} }, sort: null, filter: [] }, isDefault: true };

function setup(views = [monthly, all], stored: object | null = null) {
  const api = {
    list: vi.fn(() => of(views)),
    create: vi.fn((_code: string, body: { name: string; state: SavedListView['state']; isDefault: boolean }) =>
      of({ ...body, id: 9, lockVersion: 0, modifiedAt: 'now' })),
    update: vi.fn((_code: string, id: number, body: { name: string; state: SavedListView['state']; isDefault: boolean; lockVersion: number }) =>
      of({ ...body, id, lockVersion: body.lockVersion + 1, modifiedAt: 'now' })),
    remove: vi.fn(() => of(undefined))
  };
  const store = { load: vi.fn(() => stored), save: vi.fn(), clear: vi.fn() };
  const onApply = vi.fn();
  const state = new ListViewState('upl.sources', api as unknown as ListViewsApi, {
    defaultSort: () => ({ field: 'code', descending: false }),
    onApply,
    columnsStore: store as unknown as TableColumnStateStore
  });
  return { state, api, store, onApply };
}

describe('ListViewState', () => {
  it('opens with the default view', async () => {
    const { state } = setup();

    expect(await firstValueFrom(state.load())).toBe(true);
    expect(state.active()?.name).toBe('All');
    expect(state.sort()).toEqual({ field: 'code', descending: false });
    expect(state.changed()).toBe(false);
  });

  it('without a default view opens the standard one with the columns left last time', async () => {
    const { state, store } = setup([monthly], { order: ['code'], hidden: ['name'], widths: {} });

    await firstValueFrom(state.load());
    expect(state.activeId()).toBeNull();
    expect(state.columns().hidden).toEqual(['name']);
    expect(store.load).toHaveBeenCalledWith('upl.sources');
  });

  it('keeps the standard view when the views cannot be loaded', async () => {
    const { state, api } = setup();
    api.list.mockReturnValue(throwError(() => ({ status: 503 })));

    expect(await firstValueFrom(state.load())).toBe(false);
    expect(state.activeId()).toBeNull();
    expect(state.sort()).toEqual({ field: 'code', descending: false });
  });

  it('applies a view: its columns, sort and filter, then reloads the list', async () => {
    const { state, onApply } = setup();
    await firstValueFrom(state.load());

    state.apply(monthly);

    expect(state.columns().order).toEqual(['name', 'code']);
    expect(state.sort()).toEqual({ field: 'name', descending: true });
    expect(state.filter()).toEqual(monthly.state.filter);
    expect(onApply).toHaveBeenCalledTimes(1);
  });

  it('notices changes to the active view and saves them with its lock version', async () => {
    const { state, api } = setup();
    await firstValueFrom(state.load());
    state.apply(monthly);

    state.setSort({ field: 'code', descending: true });
    expect(state.changed()).toBe(true);

    await firstValueFrom(state.saveActive());
    expect(api.update).toHaveBeenCalledWith('upl.sources', 1, expect.objectContaining({ lockVersion: 0, name: 'Monthly' }));
    expect(api.update.mock.calls[0][2].state.sort).toBe('-code');
    expect(state.active()?.lockVersion).toBe(1);
    expect(state.changed()).toBe(false);
  });

  it('saves the list default order as null, so a view follows the list', async () => {
    const { state, api } = setup([]);
    await firstValueFrom(state.load());

    await firstValueFrom(state.saveAs('Mine', true));

    expect(api.create.mock.calls[0][1].state.sort).toBeNull();
    expect(state.active()?.name).toBe('Mine');
  });

  it('keeps one default view and forgets a removed active view', async () => {
    const { state } = setup();
    await firstValueFrom(state.load());

    await firstValueFrom(state.setDefault(monthly, true));
    expect(state.views().filter(view => view.isDefault).map(view => view.name)).toEqual(['Monthly']);

    await firstValueFrom(state.remove(all));
    expect(state.activeId()).toBeNull();
    expect(state.views().map(view => view.name)).toEqual(['Monthly']);
  });

  it('remembers column changes of the standard view only', async () => {
    const { state, store } = setup([]);
    await firstValueFrom(state.load());

    state.setColumns({ order: ['name'], hidden: [], widths: {} });
    expect(store.save).toHaveBeenCalledTimes(1);

    state.apply(monthly);
    state.setColumns({ order: ['code'], hidden: [], widths: {} });
    expect(store.save).toHaveBeenCalledTimes(1);
  });

  it('compares payloads whatever the key order of widths', () => {
    const a = { columns: { order: [], hidden: [], widths: { a: '1px', b: '2px' } }, sort: null, filter: [] };
    const b = { columns: { order: [], hidden: [], widths: { b: '2px', a: '1px' } }, sort: null, filter: [] };
    expect(samePayload(a, b)).toBe(true);
    expect(samePayload(a, { ...b, sort: 'code' })).toBe(false);
  });
});
