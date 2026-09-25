import { describe, expect, it } from 'vitest';
import {
  applyColumnState,
  EMPTY_COLUMN_STATE,
  isDefaultColumnState,
  moveColumn,
  normalizeColumnState,
  setColumnVisible,
  setColumnWidth,
} from './column-state';
import { TableConfig } from './table.types';

interface Row { id: number }

const KEYS = ['code', 'name', 'period', 'version'];

function config(): TableConfig<Row> {
  const column = (label: string) => ({
    header: { type: 'primitive' as const, value: label },
    content: { type: 'primitive' as const, value: (row: Row) => row.id },
    width: '1fr',
  });
  return {
    trackBy: (_index, row) => row.id,
    columns: { code: column('Code'), name: column('Name'), period: column('Period'), version: column('Version') },
    columnsOrder: [...KEYS],
  };
}

describe('column state', () => {
  it('keeps a stored order, appends new columns and forgets removed ones', () => {
    const state = normalizeColumnState({ order: ['name', 'gone', 'code', 'name'], hidden: ['gone'], widths: { gone: '90px' } }, KEYS);

    expect(state.order).toEqual(['name', 'code', 'period', 'version']);
    expect(state.hidden).toEqual([]);
    expect(state.widths).toEqual({});
  });

  it('never hides a locked column or every column, and drops malformed widths', () => {
    const state = normalizeColumnState(
      { order: [], hidden: ['code', 'name', 'period', 'version'], widths: { name: '120px', period: '50%', version: 'calc(1px)' } },
      KEYS,
      ['code'],
    );

    expect(state.hidden).toEqual(['name', 'period', 'version']);
    expect(state.widths).toEqual({ name: '120px' });
    expect(normalizeColumnState({ order: [], hidden: [...KEYS], widths: {} }, KEYS).hidden).toEqual(['name', 'period', 'version']);
  });

  it('applies order, visibility and widths to the table configuration', () => {
    let state = normalizeColumnState(EMPTY_COLUMN_STATE, KEYS);
    state = moveColumn(state, 'version', -3);
    state = setColumnVisible(state, 'period', false);
    state = setColumnWidth(state, 'name', 181.6);

    const applied = applyColumnState(config(), state);
    expect(applied.columnsOrder).toEqual(['version', 'code', 'name']);
    expect(applied.columns['name'].width).toBe('182px');
    expect(applied.columns['code'].width).toBe('1fr');
    expect(config().columnsOrder).toEqual(KEYS);
  });

  it('does not move past either end and tells a default state apart', () => {
    const state = normalizeColumnState(EMPTY_COLUMN_STATE, KEYS);

    expect(moveColumn(state, 'code', -1)).toBe(state);
    expect(moveColumn(state, 'version', 1)).toBe(state);
    expect(isDefaultColumnState(state, KEYS)).toBe(true);
    expect(isDefaultColumnState(moveColumn(state, 'code', 1), KEYS)).toBe(false);
    expect(isDefaultColumnState(setColumnVisible(state, 'name', false), KEYS)).toBe(false);
  });
});
