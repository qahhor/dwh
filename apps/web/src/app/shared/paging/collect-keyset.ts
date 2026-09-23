import { EMPTY, expand, map, Observable, reduce } from 'rxjs';
import { KeysetFetch } from './keyset-pager';

export interface CollectedRows<T> {
  rows: T[];
  /** False when `maxRows` stopped the walk before the server ran out of rows. */
  complete: boolean;
}

/**
 * Every row a keyset endpoint returns, page after page, for work that needs
 * the whole result rather than the page on screen (an export). Pages are
 * requested one after another, since each cursor arrives with the page before
 * it, and the walk stops at `maxRows` so a broad filter cannot pull an
 * unbounded result into the browser. Unsubscribing stops it.
 */
export function collectKeyset<T>(fetch: KeysetFetch<T>, pageSize: number, maxRows: number): Observable<CollectedRows<T>> {
  interface Step { rows: T[]; next: string | null; collected: number }
  const step = (cursor: string | null, collected: number): Observable<Step> =>
    fetch(cursor, Math.min(pageSize, maxRows - collected)).pipe(
      map(response => {
        const rows = [...(response?.items ?? [])];
        const next = response?.hasMore && response.nextCursor ? response.nextCursor : null;
        return { rows, next, collected: collected + rows.length };
      })
    );
  return step(null, 0).pipe(
    // An empty page with a cursor would otherwise loop for ever.
    expand(page => page.next !== null && page.rows.length > 0 && page.collected < maxRows ? step(page.next, page.collected) : EMPTY),
    reduce<Step, CollectedRows<T>>((result, page) => ({
      rows: [...result.rows, ...page.rows],
      complete: page.next === null || page.rows.length === 0,
    }), { rows: [], complete: true })
  );
}
