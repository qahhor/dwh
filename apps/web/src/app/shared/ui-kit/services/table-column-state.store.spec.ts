// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TableColumnStateStore } from './table-column-state.store';

describe('TableColumnStateStore', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('remembers a table state under its own id', () => {
    const store = new TableColumnStateStore();
    const state = { order: ['name', 'code'], hidden: ['code'], widths: { name: '200px' } };

    store.save('upl.sources', state);

    expect(store.load('upl.sources')).toEqual(state);
    expect(store.load('audit.logs')).toBeNull();
    store.clear('upl.sources');
    expect(store.load('upl.sources')).toBeNull();
  });

  it('treats anything malformed as nothing stored', () => {
    const store = new TableColumnStateStore();
    localStorage.setItem('dwh.table-columns.v1.a', '{not json');
    localStorage.setItem('dwh.table-columns.v1.b', JSON.stringify({ order: 'code', hidden: [], widths: {} }));
    localStorage.setItem('dwh.table-columns.v1.c', JSON.stringify({ order: [], hidden: [1], widths: {} }));

    expect(store.load('a')).toBeNull();
    expect(store.load('b')).toBeNull();
    expect(store.load('c')).toBeNull();
  });

  it('keeps working when the browser refuses storage', () => {
    const store = new TableColumnStateStore();
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('blocked'); });

    expect(() => store.save('t', { order: [], hidden: [], widths: {} })).not.toThrow();
    expect(store.load('t')).toBeNull();
    expect(() => store.clear('t')).not.toThrow();
  });
});
