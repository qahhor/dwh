import { Observable, of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { KeysetPager, KeysetResponse } from './keyset-pager';

const page = (items: number[], nextCursor: string | null, totalEstimated = 30): KeysetResponse<number> =>
  ({ items, nextCursor, hasMore: nextCursor !== null, totalEstimated });

/** Three pages: null -> [1,2] -> 'c2' -> [3,4] -> 'c3' -> [5]. */
function server() {
  const pages: Record<string, KeysetResponse<number>> = {
    first: page([1, 2], 'c2'), c2: page([3, 4], 'c3'), c3: page([5], null),
  };
  return vi.fn((cursor: string | null) => of(pages[cursor ?? 'first']));
}

describe('KeysetPager', () => {
  it('walks forward with the returned cursors and back with the remembered ones', () => {
    const fetch = server();
    const pager = new KeysetPager<number>(fetch, { pageSize: 2 });
    pager.first();
    expect([pager.page(), [...pager.items()], pager.canGoBack(), pager.canGoForward()]).toEqual([1, [1, 2], false, true]);

    pager.next();
    pager.next();
    expect([pager.page(), [...pager.items()], pager.canGoForward()]).toEqual([3, [5], false]);
    pager.next(); // nothing beyond the last page
    expect(fetch).toHaveBeenCalledTimes(3);

    pager.previous();
    expect([pager.page(), [...pager.items()]]).toEqual([2, [3, 4]]);
    expect(fetch).toHaveBeenLastCalledWith('c2', 2);
    pager.previous();
    expect(fetch).toHaveBeenLastCalledWith(null, 2);
    expect(pager.total()).toBe(30);
  });

  it('lets only the latest request land', () => {
    const slow = new Subject<KeysetResponse<number>>();
    const fast = new Subject<KeysetResponse<number>>();
    const responses = [slow, fast];
    const pager = new KeysetPager<number>(() => responses.shift()!.asObservable());

    pager.first(); // e.g. old filter
    pager.first(); // new filter, issued before the first answered
    fast.next(page([9], null));
    slow.next(page([1], null)); // the stale answer arrives last

    expect([...pager.items()]).toEqual([9]);
    expect(pager.loading()).toBe(false);
  });

  it('keeps the page, its rows and its cursors when a request fails, and retries exactly that request', () => {
    let failNext = false;
    const pages = server();
    const fetch = vi.fn((cursor: string | null): Observable<KeysetResponse<number>> =>
      failNext ? throwError(() => new Error('offline')) : pages(cursor));
    const pager = new KeysetPager<number>(fetch);
    pager.first();

    failNext = true;
    pager.next();
    expect([pager.page(), [...pager.items()], pager.failed(), pager.loading()]).toEqual([1, [1, 2], true, false]);

    failNext = false;
    pager.retry();
    expect(fetch).toHaveBeenLastCalledWith('c2', 20);
    expect([pager.page(), [...pager.items()], pager.failed()]).toEqual([2, [3, 4], false]);
  });

  it('closes both directions after a failed filter change, so no old cursor pages the new query', () => {
    let fail = false;
    const pages = server();
    const fetch = vi.fn((cursor: string | null): Observable<KeysetResponse<number>> =>
      fail ? throwError(() => new Error('offline')) : pages(cursor));
    const pager = new KeysetPager<number>(fetch, { pageSize: 2 });
    pager.first();
    pager.next();
    expect([pager.page(), pager.canGoBack(), pager.canGoForward()]).toEqual([2, true, true]);

    fail = true;
    pager.first(); // the new filter's first page fails
    expect([pager.page(), pager.failed(), pager.canGoBack(), pager.canGoForward()]).toEqual([2, true, false, false]);
    const calls = fetch.mock.calls.length;
    pager.next();
    pager.previous();
    expect(fetch).toHaveBeenCalledTimes(calls);

    fail = false;
    pager.retry();
    expect([pager.page(), [...pager.items()], pager.canGoForward()]).toEqual([1, [1, 2], true]);
  });

  it('steps back when the page on screen comes back empty', () => {
    let emptied = false;
    const pages = server();
    const fetch = vi.fn((cursor: string | null): Observable<KeysetResponse<number>> =>
      emptied && cursor === 'c3' ? of(page([], null)) : pages(cursor));
    const pager = new KeysetPager<number>(fetch, { pageSize: 2 });
    pager.first();
    pager.next();
    pager.next();
    expect([pager.page(), [...pager.items()]]).toEqual([3, [5]]);

    emptied = true; // row 5 was deleted
    pager.reload();
    expect([pager.page(), [...pager.items()]]).toEqual([2, [3, 4]]);
    expect(fetch).toHaveBeenLastCalledWith('c2', 2);
  });

  it('stays on a page moved to that arrives empty, leaving Back to the user', () => {
    const fetch = vi.fn((cursor: string | null) => of(cursor ? page([], null) : page([1, 2], 'c2')));
    const pager = new KeysetPager<number>(fetch, { pageSize: 2 });
    pager.first();
    pager.next();
    expect([pager.page(), [...pager.items()], pager.canGoBack()]).toEqual([2, [], true]);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('reloads the page on screen and restarts from page one on a new page size', () => {
    const fetch = server();
    const pager = new KeysetPager<number>(fetch);
    pager.first();
    pager.next();
    pager.reload();
    expect(fetch).toHaveBeenLastCalledWith('c2', 20);
    expect(pager.page()).toBe(2);

    pager.setPageSize(50);
    expect(fetch).toHaveBeenLastCalledWith(null, 50);
    expect(pager.page()).toBe(1);
    pager.setPageSize(0);
    expect(pager.pageSize()).toBe(50);
  });

  it('forgets forward cursors once an earlier page is fetched again', () => {
    const fetch = server();
    const pager = new KeysetPager<number>(fetch);
    pager.first();
    pager.next();
    pager.next();
    pager.first();
    pager.goTo(3); // only neighbours are reachable
    expect(pager.page()).toBe(1);
    pager.goTo(2);
    expect(fetch).toHaveBeenLastCalledWith('c2', 20);
  });

  it('treats an empty or missing body as an empty last page', () => {
    const pager = new KeysetPager<number>(() => of(null));
    pager.first();
    expect([[...pager.items()], pager.canGoForward(), pager.total(), pager.failed()]).toEqual([[], false, 0, false]);
  });

  it('grows the list with loadMore and stops at the last page', () => {
    const fetch = server();
    const pager = new KeysetPager<number>(fetch);
    pager.first();
    pager.loadMore();
    expect([[...pager.items()], pager.page(), pager.canGoForward()]).toEqual([[1, 2, 3, 4], 1, true]);
    pager.loadMore();
    pager.loadMore();
    expect([...pager.items()]).toEqual([1, 2, 3, 4, 5]);
    expect(fetch).toHaveBeenCalledTimes(3);
  });

  it('never appends rows for an old filter to a new one', () => {
    const answers: Subject<KeysetResponse<number>>[] = [];
    const pager = new KeysetPager<number>(() => { const answer = new Subject<KeysetResponse<number>>(); answers.push(answer); return answer; });
    pager.first();
    answers[0].next(page([1, 2], 'c2'));
    pager.loadMore();           // pending
    expect(pager.loadingMore()).toBe(true);
    pager.first();              // the filter changes meanwhile
    answers[2].next(page([7], null));
    answers[1].next(page([3, 4], null)); // the old "load more" answers late

    expect([...pager.items()]).toEqual([7]);
    expect([pager.loading(), pager.loadingMore()]).toEqual([false, false]);
  });

  it('keeps shown rows when load more fails, reports it apart from page failures, and retries it', () => {
    let fail = false;
    const pages = server();
    const onError = vi.fn();
    const pager = new KeysetPager<number>(cursor => (fail ? throwError(() => new Error('x')) : pages(cursor)), { onError });
    pager.first();
    fail = true;
    pager.loadMore();
    expect([[...pager.items()], pager.loadMoreFailed(), pager.failed()]).toEqual([[1, 2], true, false]);
    expect(onError).toHaveBeenCalledWith('more');
    fail = false;
    pager.retry();
    expect([[...pager.items()], pager.loadMoreFailed()]).toEqual([[1, 2, 3, 4], false]);
  });

  it('drops the answer in flight on invalidate and stays busy until the next request', () => {
    const pending = new Subject<KeysetResponse<number>>();
    const pager = new KeysetPager<number>(() => pending);
    pager.first();
    pager.invalidate();
    pending.next(page([1], null));
    expect([[...pager.items()], pager.loading()]).toEqual([[], true]);
  });

  it('forgets the old query\'s next page on invalidate', () => {
    const pager = new KeysetPager<number>(server());
    pager.first();
    expect(pager.canGoForward()).toBe(true);
    pager.invalidate();
    expect([pager.canGoForward(), pager.hasMore()]).toEqual([false, false]);
  });
});
