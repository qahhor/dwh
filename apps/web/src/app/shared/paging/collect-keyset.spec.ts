import { of, Subject, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { collectKeyset, CollectedRows } from './collect-keyset';
import { KeysetResponse } from './keyset-pager';

/** A server with `count` rows numbered from 1, paging by the last id. */
function server(count: number) {
  const calls: { cursor: string | null; limit: number }[] = [];
  const fetch = (cursor: string | null, limit: number) => {
    calls.push({ cursor, limit });
    const after = cursor === null ? 0 : Number(cursor);
    const rows = Array.from({ length: Math.max(0, Math.min(limit, count - after)) }, (_, i) => after + i + 1);
    const hasMore = after + rows.length < count;
    return of<KeysetResponse<number>>({ items: rows, hasMore, nextCursor: hasMore ? String(after + rows.length) : null });
  };
  return { calls, fetch };
}

function run<T>(source: ReturnType<typeof collectKeyset<T>>): CollectedRows<T> {
  let result: CollectedRows<T> | undefined;
  source.subscribe(value => result = value);
  if (!result) throw new Error('did not complete');
  return result;
}

describe('collectKeyset', () => {
  it('walks every page in order, each from the cursor the previous one returned', () => {
    const { calls, fetch } = server(5);
    const result = run(collectKeyset(fetch, 2, 100));
    expect(result).toEqual({ rows: [1, 2, 3, 4, 5], complete: true });
    expect(calls).toEqual([{ cursor: null, limit: 2 }, { cursor: '2', limit: 2 }, { cursor: '4', limit: 2 }]);
  });

  it('stops at the row limit and says the result is partial', () => {
    const { calls, fetch } = server(10);
    const result = run(collectKeyset(fetch, 3, 7));
    expect(result).toEqual({ rows: [1, 2, 3, 4, 5, 6, 7], complete: false });
    // The last request asks only for the rows still allowed.
    expect(calls.at(-1)).toEqual({ cursor: '6', limit: 1 });
  });

  it('is complete when the limit and the data end together', () => {
    const { fetch } = server(6);
    expect(run(collectKeyset(fetch, 3, 6))).toEqual({ rows: [1, 2, 3, 4, 5, 6], complete: true });
  });

  it('returns an empty, complete result for an empty list', () => {
    const { fetch } = server(0);
    expect(run(collectKeyset(fetch, 50, 100))).toEqual({ rows: [], complete: true });
  });

  it('does not loop on an empty page that still carries a cursor', () => {
    let calls = 0;
    const fetch = () => { calls++; return of({ items: [], hasMore: true, nextCursor: 'same' }); };
    expect(run(collectKeyset<number>(fetch, 50, 100)).rows).toEqual([]);
    expect(calls).toBe(1);
  });

  it('fails as a whole when a page fails', () => {
    let error: unknown;
    const fetch = (cursor: string | null) => cursor === null
      ? of({ items: [1], hasMore: true, nextCursor: '1' })
      : throwError(() => new Error('boom'));
    collectKeyset<number>(fetch, 1, 10).subscribe({ error: e => error = e });
    expect((error as Error).message).toBe('boom');
  });

  it('stops asking when unsubscribed', () => {
    const pending = new Subject<KeysetResponse<number>>();
    let calls = 0;
    const subscription = collectKeyset<number>(() => { calls++; return pending; }, 1, 10).subscribe();
    subscription.unsubscribe();
    pending.next({ items: [1], hasMore: true, nextCursor: '1' });
    expect(calls).toBe(1);
  });
});
