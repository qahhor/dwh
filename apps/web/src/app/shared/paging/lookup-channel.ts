import { computed, Signal } from '@angular/core';
import { Observable } from 'rxjs';
import { KeysetPager, KeysetResponse } from './keyset-pager';

export interface LookupChannelOptions {
  /** Rows per request. */
  pageSize?: number;
  /** Pause after the last keystroke before searching, in milliseconds. */
  searchDelayMs?: number;
}

/**
 * One searchable, growing lookup list over a keyset endpoint. The pager
 * cancels a superseded request and appends "load more" pages; the channel
 * adds the typing pause and remembers who is selected, so the owner can keep
 * selected entries in the list whatever the search returns.
 */
export class LookupChannel<T, S> {
  private query = '';
  private timer?: ReturnType<typeof setTimeout>;
  private selected: () => S;
  private readonly pager: KeysetPager<T>;
  private readonly searchDelayMs: number;

  readonly loading: Signal<boolean>;
  readonly error: Signal<boolean>;
  readonly hasMore: Signal<boolean>;

  constructor(
    fetch: (query: string, cursor: string | null, pageSize: number) => Observable<KeysetResponse<T> | null | undefined>,
    apply: (rows: T[], append: boolean, selected: S) => void,
    noSelection: S,
    options: LookupChannelOptions = {}
  ) {
    this.selected = () => noSelection;
    this.searchDelayMs = options.searchDelayMs ?? 300;
    this.pager = new KeysetPager<T>((cursor, pageSize) => fetch(this.query, cursor, pageSize), {
      pageSize: options.pageSize ?? 50,
      onLoaded: (rows, append) => apply(rows, append, this.selected()),
    });
    this.loading = computed(() => this.pager.loading() || this.pager.loadingMore());
    this.error = computed(() => this.pager.failed() || this.pager.loadMoreFailed());
    this.hasMore = this.pager.canGoForward;
  }

  search(query: string, selected: () => S): void {
    this.query = query.trim();
    this.selected = selected;
    clearTimeout(this.timer);
    this.pager.invalidate();
    this.timer = setTimeout(() => this.pager.first(), this.searchDelayMs);
  }

  load(reset: boolean, selected: () => S): void {
    this.selected = selected;
    if (reset) this.pager.first();
    else this.pager.loadMore();
  }

  retry(selected: () => S): void {
    this.selected = selected;
    this.pager.retry();
  }

  cancel(): void {
    clearTimeout(this.timer);
    this.pager.cancel();
  }
}
