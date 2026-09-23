import { computed, DestroyRef, signal } from '@angular/core';
import { catchError, EMPTY, map, Observable, of, Subject, switchMap } from 'rxjs';

/** What a keyset endpoint returns. Only `items` and `nextCursor` are required to page. */
export interface KeysetResponse<T> {
  items?: readonly T[] | null;
  nextCursor?: string | null;
  hasMore?: boolean;
  totalEstimated?: number;
}

/** Fetches one page. `cursor` is null for the first page. */
export type KeysetFetch<T> = (cursor: string | null, pageSize: number) => Observable<KeysetResponse<T> | null | undefined>;

interface Attempt {
  readonly page: number;
  readonly cursor: string | null;
  /** Add to the rows on screen ("load more") instead of replacing them. */
  readonly append: boolean;
}

export type KeysetFailure = 'page' | 'more';

export interface KeysetPagerOptions<T = unknown> {
  pageSize?: number;
  destroyRef?: DestroyRef;
  /** Called when a request fails, after `failed` or `loadMoreFailed` is set. */
  onError?: (failure: KeysetFailure) => void;
  /** Called with each page that lands, for screens that merge rows into their own list. */
  onLoaded?: (rows: T[], append: boolean) => void;
}

/**
 * Page-by-page navigation over a keyset (cursor) API, with no UI of its own.
 *
 * A keyset API can only move one page at a time: the cursor for page N+1
 * arrives with page N. The pager keeps the cursor of every page visited, so
 * Back needs no extra request state, and it guarantees two things the
 * hand-written versions did not:
 *
 * - Only the latest request lands. A slower response to an earlier filter or
 *   page is discarded instead of overwriting newer results.
 * - The page number moves only when its page arrives. A failed "next" leaves
 *   the page, its rows and its cursors where they were, and `retry()`
 *   repeats exactly the request that failed.
 *
 * It serves both shapes the screens use: page-by-page (`next`, `previous`)
 * and a growing list (`loadMore`). A reset during a pending "load more"
 * cancels it, so rows for an old filter are never appended to a new one.
 */
export class KeysetPager<T> {
  /** Writable, so a screen can reflect a local edit without refetching. */
  readonly items = signal<T[]>([]);
  /** 1-based number of the page on screen. */
  readonly page = signal(1);
  readonly pageSize = signal(20);
  /** The server's estimate, 0 when it gives none. */
  readonly total = signal(0);
  readonly hasMore = signal(false);
  readonly loading = signal(false);
  readonly loadingMore = signal(false);
  /** The last page request failed; the previous page, if any, is still shown. */
  readonly failed = signal(false);
  /** The last "load more" failed; the rows already shown are kept. */
  readonly loadMoreFailed = signal(false);

  private readonly nextCursor = signal<string | null>(null);
  readonly canGoBack = computed(() => this.page() > 1);
  readonly canGoForward = computed(() => this.hasMore() && this.nextCursor() !== null);

  /** `cursors[i]` fetched page `i + 1`; the first page's cursor is null. */
  private cursors: (string | null)[] = [null];
  private lastAttempt: Attempt = { page: 1, cursor: null, append: false };
  /** `null` cancels whatever is in flight without asking for anything new. */
  private readonly requests = new Subject<Attempt | null>();

  constructor(fetch: KeysetFetch<T>, options: KeysetPagerOptions<T> = {}) {
    if (options.pageSize) this.pageSize.set(options.pageSize);
    const subscription = this.requests
      .pipe(
        switchMap(attempt => attempt === null ? EMPTY :
          fetch(attempt.cursor, this.pageSize()).pipe(
            map(response => ({ attempt, response: response ?? {}, ok: true as const })),
            catchError(() => of({ attempt, ok: false as const }))
          )
        )
      )
      .subscribe(result => {
        this.loading.set(false);
        this.loadingMore.set(false);
        if (!result.ok) {
          const failure: KeysetFailure = result.attempt.append ? 'more' : 'page';
          (failure === 'more' ? this.loadMoreFailed : this.failed).set(true);
          options.onError?.(failure);
          return;
        }
        const { attempt, response } = result;
        const rows = [...(response.items ?? [])];
        if (attempt.append) {
          this.items.update(current => [...current, ...rows]);
        } else {
          this.cursors = this.cursors.slice(0, attempt.page - 1);
          this.cursors[attempt.page - 1] = attempt.cursor;
          this.page.set(attempt.page);
          this.items.set(rows);
        }
        this.nextCursor.set(response.nextCursor ?? null);
        this.hasMore.set(Boolean(response.hasMore));
        this.total.set(response.totalEstimated ?? 0);
        options.onLoaded?.(rows, attempt.append);
      });
    options.destroyRef?.onDestroy(() => subscription.unsubscribe());
  }

  /** Drop the answer in flight and stop loading, as when the screen goes away. */
  cancel(): void {
    this.requests.next(null);
    this.loading.set(false);
    this.loadingMore.set(false);
  }

  /** Back to page one, as after a filter change. */
  first(): void {
    this.request({ page: 1, cursor: null, append: false });
  }

  /** The page on screen again, with the current filters. */
  reload(): void {
    const page = this.page();
    this.request({ page, cursor: this.cursors[page - 1] ?? null, append: false });
  }

  /** The next page added below the rows on screen. Ignored while anything is loading. */
  loadMore(): void {
    if (!this.canGoForward() || this.loading() || this.loadingMore()) return;
    this.request({ page: this.page(), cursor: this.nextCursor(), append: true });
  }

  /**
   * The results are about to change (the user is still typing a search):
   * drop the answer in flight, forget the old query's next cursor, and show
   * the list as busy until the next request is made.
   */
  invalidate(): void {
    this.requests.next(null);
    // The cursor belongs to the old query; continuing it would mix results.
    this.nextCursor.set(null);
    this.hasMore.set(false);
    this.loadingMore.set(false);
    this.failed.set(false);
    this.loading.set(true);
  }

  /** Exactly the last request, whichever it was. */
  retry(): void {
    this.request(this.lastAttempt);
  }

  next(): void {
    if (!this.canGoForward()) return;
    this.request({ page: this.page() + 1, cursor: this.nextCursor(), append: false });
  }

  previous(): void {
    const page = this.page();
    if (page <= 1) return;
    this.request({ page: page - 1, cursor: this.cursors[page - 2] ?? null, append: false });
  }

  /** For page controls that emit a page number. Keyset paging reaches only the neighbours. */
  goTo(page: number): void {
    if (page === this.page() + 1) this.next();
    else if (page === this.page() - 1) this.previous();
  }

  setPageSize(size: number): void {
    if (!Number.isFinite(size) || size < 1) return;
    this.pageSize.set(Math.floor(size));
    this.first();
  }

  private request(attempt: Attempt): void {
    this.lastAttempt = attempt;
    this.failed.set(false);
    this.loadMoreFailed.set(false);
    this.loading.set(!attempt.append);
    this.loadingMore.set(attempt.append);
    this.requests.next(attempt);
  }
}
