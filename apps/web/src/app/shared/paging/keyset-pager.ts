import { computed, DestroyRef, signal } from '@angular/core';
import { catchError, map, Observable, of, Subject, switchMap } from 'rxjs';

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
 */
export class KeysetPager<T> {
  readonly items = signal<readonly T[]>([]);
  /** 1-based number of the page on screen. */
  readonly page = signal(1);
  readonly pageSize = signal(20);
  /** The server's estimate, 0 when it gives none. */
  readonly total = signal(0);
  readonly hasMore = signal(false);
  readonly loading = signal(false);
  /** The last request failed; the previous page, if any, is still shown. */
  readonly failed = signal(false);

  private readonly nextCursor = signal<string | null>(null);
  readonly canGoBack = computed(() => this.page() > 1);
  readonly canGoForward = computed(() => this.hasMore() && this.nextCursor() !== null);

  /** `cursors[i]` fetched page `i + 1`; the first page's cursor is null. */
  private cursors: (string | null)[] = [null];
  private lastAttempt: Attempt = { page: 1, cursor: null };
  private readonly requests = new Subject<Attempt>();

  constructor(fetch: KeysetFetch<T>, options: { pageSize?: number; destroyRef?: DestroyRef } = {}) {
    if (options.pageSize) this.pageSize.set(options.pageSize);
    const subscription = this.requests
      .pipe(
        switchMap(attempt =>
          fetch(attempt.cursor, this.pageSize()).pipe(
            map(response => ({ attempt, response: response ?? {}, ok: true as const })),
            catchError(() => of({ attempt, ok: false as const }))
          )
        )
      )
      .subscribe(result => {
        this.loading.set(false);
        if (!result.ok) {
          this.failed.set(true);
          return;
        }
        const { attempt, response } = result;
        this.cursors = this.cursors.slice(0, attempt.page - 1);
        this.cursors[attempt.page - 1] = attempt.cursor;
        this.page.set(attempt.page);
        this.items.set(response.items ?? []);
        this.nextCursor.set(response.nextCursor ?? null);
        this.hasMore.set(Boolean(response.hasMore));
        this.total.set(response.totalEstimated ?? 0);
        this.failed.set(false);
      });
    options.destroyRef?.onDestroy(() => subscription.unsubscribe());
  }

  /** Back to page one, as after a filter change. */
  first(): void {
    this.request({ page: 1, cursor: null });
  }

  /** The page on screen again, with the current filters. */
  reload(): void {
    const page = this.page();
    this.request({ page, cursor: this.cursors[page - 1] ?? null });
  }

  /** Exactly the last request, whichever it was. */
  retry(): void {
    this.request(this.lastAttempt);
  }

  next(): void {
    if (!this.canGoForward()) return;
    this.request({ page: this.page() + 1, cursor: this.nextCursor() });
  }

  previous(): void {
    const page = this.page();
    if (page <= 1) return;
    this.request({ page: page - 1, cursor: this.cursors[page - 2] ?? null });
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
    this.loading.set(true);
    this.requests.next(attempt);
  }
}
