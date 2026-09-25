import { describe, expect, it } from 'vitest';
import { OrderBy } from '../ui-kit/components/table/table.types';
import { sortRows } from './local-sort';

interface Row { id: number; name: string; size: number | null; at?: Date }

const ROWS: Row[] = [
  { id: 1, name: 'item 10', size: 5 },
  { id: 2, name: 'Item 2', size: null },
  { id: 3, name: 'альфа', size: 1 },
  { id: 4, name: 'item 2', size: 5 }
];
const values = { name: (row: Row) => row.name, size: (row: Row) => row.size };

describe('sortRows', () => {
  it('keeps the given order without a sort or for a column it cannot read', () => {
    expect(sortRows(ROWS, undefined, values, 'ru').map(row => row.id)).toEqual([1, 2, 3, 4]);
    expect(sortRows(ROWS, { column: 'nope', sortBy: OrderBy.Asc }, values, 'ru').map(row => row.id)).toEqual([1, 2, 3, 4]);
  });

  it('compares text by language with natural numbers and keeps ties in place', () => {
    // Russian collation puts Cyrillic before Latin; "Item 2" and "item 2" tie and keep their order.
    expect(sortRows(ROWS, { column: 'name', sortBy: OrderBy.Asc }, values, 'ru').map(row => row.id)).toEqual([3, 2, 4, 1]);
    expect(sortRows(ROWS, { column: 'name', sortBy: OrderBy.Asc }, values, 'en').map(row => row.id)).toEqual([2, 4, 1, 3]);
  });

  it('puts empty values last in both directions', () => {
    expect(sortRows(ROWS, { column: 'size', sortBy: OrderBy.Asc }, values, 'ru').map(row => row.id)).toEqual([3, 1, 4, 2]);
    expect(sortRows(ROWS, { column: 'size', sortBy: OrderBy.Desc }, values, 'ru').map(row => row.id)).toEqual([1, 4, 3, 2]);
  });

  it('orders dates and does not change the input', () => {
    const dated = [{ id: 1, name: '', size: 0, at: new Date('2026-02-01') }, { id: 2, name: '', size: 0, at: new Date('2026-01-01') }];
    expect(sortRows(dated, { column: 'at', sortBy: OrderBy.Asc }, { at: row => row.at }, 'en').map(row => row.id)).toEqual([2, 1]);
    expect(dated.map(row => row.id)).toEqual([1, 2]);
  });
});
