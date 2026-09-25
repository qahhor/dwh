/* Our code: where smt-data-select and smt-multi-data-select get their rows.
 * After the idea of the kit's data selects (smartup-ui-kit@6472beb,
 * components/forms/data-select), which spoke Biruni's query contract; ours
 * speaks keyset pages (`{ items, nextCursor, hasMore }`) and is written once
 * per reference list, not once per screen. See ADR-0015 rule 2. */
import { signal, type Signal } from '@angular/core';
import type { Observable } from 'rxjs';
import { LookupChannel } from '../../../../paging/lookup-channel';
import type { KeysetResponse } from '../../../../paging/keyset-pager';
import type { SMTSelectOption } from '../select/select.component';

/** A key a data select stores: a record id. Compared with `===`, so a primitive. */
export type SMTLookupKey = string | number;

/**
 * One reference list the data selects can search: how to fetch a page, how a
 * row is keyed and shown, and how to name a key that no page has brought yet
 * (a record opened for editing). Define it once, beside the API it reads.
 */
export interface SMTLookupSource<Row, K extends SMTLookupKey = number> {
  /** A page of rows matching the search text, after the cursor. */
  page(search: string, cursor: string | null, limit: number): Observable<KeysetResponse<Row> | null | undefined>;
  key(row: Row): K;
  /** How a row reads in the list; `id` is filled from `key`. */
  option(row: Row): Omit<SMTSelectOption<K>, 'id'>;
  /**
   * The rows for keys chosen before any page arrived. Emits the rows it
   * found; a key it leaves out is shown by its id. Without it such keys are
   * always shown by id.
   */
  resolve?(keys: readonly K[]): Observable<readonly Row[]>;
}

/**
 * The state behind a data select: the loaded rows, every row seen so far
 * (so a chosen value keeps its name whatever the search shows), lookups of
 * keys by id, and stable option objects so the list does not re-render and
 * lose focus on every check.
 */
export class LookupState<Row, K extends SMTLookupKey> {
  private readonly rows = signal<readonly Row[]>([]);

  private readonly known = signal<ReadonlyMap<K, Row>>(new Map());

  /** Keys the source could not name (deleted, or outside the viewer's scope). */
  private readonly unavailable = signal<ReadonlySet<K>>(new Set());

  private readonly requested = new Set<K>();

  private readonly cache = new Map<K, SMTSelectOption<K>>();

  private readonly channel: LookupChannel<Row, null>;

  private resolving: { unsubscribe(): void }[] = [];

  readonly loading: Signal<boolean>;

  readonly error: Signal<boolean>;

  readonly hasMore: Signal<boolean>;

  constructor(private readonly source: () => SMTLookupSource<Row, K>) {
    this.channel = new LookupChannel<Row, null>(
      (search, cursor, limit) => this.source().page(search, cursor, limit),
      (rows, append) => {
        this.remember(rows);
        this.rows.set(append ? [...this.rows(), ...rows] : rows);
      },
      null
    );
    this.loading = this.channel.loading;
    this.error = this.channel.error;
    this.hasMore = this.channel.hasMore;
  }

  /** The search text changed; empty (the list just opened) loads the first page at once. */
  search(text: string): void {
    if (text.trim() === '') this.channel.reset(() => null);
    else this.channel.search(text, () => null);
  }

  loadMore(): void {
    this.channel.load(false, () => null);
  }

  retry(): void {
    this.channel.retry(() => null);
  }

  /** The row behind a key, when it has been seen. */
  rowOf(key: K): Row | undefined {
    return this.known().get(key);
  }

  /** Rows of the current search, as options, without the excluded ones. */
  listed(exclude: (row: Row) => boolean): SMTSelectOption<K>[] {
    return this.rows().filter(row => !exclude(row)).map(row => this.optionOf(row));
  }

  /** The chosen keys as options: named when known, by id until then — never shown as "nothing chosen". */
  chosen(keys: readonly K[]): SMTSelectOption<K>[] {
    return keys.map(key => {
      const row = this.known().get(key);
      return row ? this.optionOf(row) : this.unnamed(key);
    });
  }

  /** Asks the source, once per key, for chosen keys no page has brought. */
  resolve(keys: readonly K[]): void {
    const resolve = this.source().resolve;
    const missing = keys.filter(key => !this.known().has(key) && !this.requested.has(key) && !this.unavailable().has(key));
    if (!resolve || missing.length === 0) return;
    missing.forEach(key => this.requested.add(key));
    const settle = (found: readonly Row[]) => {
      this.remember(found);
      const named = new Set(found.map(row => this.source().key(row)));
      const next = new Set(this.unavailable());
      for (const key of missing) {
        this.requested.delete(key);
        if (!named.has(key)) next.add(key);
      }
      this.unavailable.set(next);
    };
    this.resolving.push(resolve.call(this.source(), missing).subscribe({ next: settle, error: () => settle([]) }));
  }

  cancel(): void {
    this.channel.cancel();
    this.resolving.forEach(subscription => subscription.unsubscribe());
    this.resolving = [];
  }

  private remember(rows: readonly Row[]): void {
    if (rows.length === 0) return;
    const next = new Map(this.known());
    for (const row of rows) next.set(this.source().key(row), row);
    this.known.set(next);
  }

  private optionOf(row: Row): SMTSelectOption<K> {
    const key = this.source().key(row);
    const shown = this.source().option(row);
    const cached = this.cache.get(key);
    if (cached && sameOption(cached, shown)) return cached;
    const option = { ...shown, id: key };
    this.cache.set(key, option);
    return option;
  }

  private unnamed(key: K): SMTSelectOption<K> {
    const label = `ID: #${key}`;
    const cached = this.cache.get(key);
    if (cached && cached.label === label) return cached;
    const option = { id: key, label };
    this.cache.set(key, option);
    return option;
  }
}

function sameOption<K>(a: SMTSelectOption<K>, b: Omit<SMTSelectOption<K>, 'id'>): boolean {
  return a.label === b.label && a.subLabel === b.subLabel && a.icon === b.icon && a.color === b.color
    && a.disabled === b.disabled && (a.columns ?? []).join('\u0000') === (b.columns ?? []).join('\u0000');
}
