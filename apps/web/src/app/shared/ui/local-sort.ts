import { OrderBy } from '../ui-kit/components/table/table.types';

export type LocalSortValue = string | number | boolean | Date | null | undefined;

/** A header click on a table whose rows are all on screen. */
export interface LocalSort {
  column: string;
  sortBy: OrderBy;
}

/**
 * Orders every row of a list that is fully loaded, so a header click sorts
 * the list and not only what is visible. Text compares by the reader's
 * language with numbers in their natural order; an empty value goes last
 * whichever way the column is sorted ("unknown" is not a low value). Ties keep
 * the previous order, so rows never jump between renders. Without a sort, or
 * for a column with no value reader, the rows keep their given order.
 */
export function sortRows<T>(
  rows: readonly T[],
  sort: LocalSort | null | undefined,
  values: Readonly<Record<string, (row: T) => LocalSortValue>>,
  locale: string,
): T[] {
  const read = sort ? values[sort.column] : undefined;
  if (!sort || !read) return [...rows];
  const direction = sort.sortBy === OrderBy.Desc ? -1 : 1;
  const collator = new Intl.Collator(locale, { numeric: true, sensitivity: 'base' });
  return rows
    .map((row, index) => ({ row, index, value: read(row) }))
    .sort((a, b) => {
      const aEmpty = a.value === null || a.value === undefined || a.value === '';
      const bEmpty = b.value === null || b.value === undefined || b.value === '';
      if (aEmpty !== bEmpty) return aEmpty ? 1 : -1;
      if (aEmpty) return a.index - b.index;
      return direction * compareValues(a.value!, b.value!, collator) || a.index - b.index;
    })
    .map(entry => entry.row);
}

function compareValues(a: Exclude<LocalSortValue, null | undefined>, b: Exclude<LocalSortValue, null | undefined>, collator: Intl.Collator): number {
  if (typeof a === 'string' && typeof b === 'string') return collator.compare(a, b);
  const toNumber = (value: typeof a) => (value instanceof Date ? value.getTime() : typeof value === 'boolean' ? Number(value) : Number(value));
  return toNumber(a) - toNumber(b);
}
