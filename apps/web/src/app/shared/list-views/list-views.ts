import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { QueryCondition, QuerySort } from '../../core/models/query-meta.models';
import { ApiService } from '../../core/services/api.service';
import { formatSort, parseSort } from '../../core/services/query-meta.service';
import { EMPTY_COLUMN_STATE, TableColumnState } from '../ui-kit/components/table/column-state';
import { TableColumnStateStore } from '../ui-kit/services/table-column-state.store';

/** What a saved view holds (ADR-0016): the server stores it in this canonical shape. */
export interface ListViewPayload {
  columns: TableColumnState;
  /** Field key, minus for descending; `null` — the list's default order. */
  sort: string | null;
  filter: QueryCondition[];
}

export interface SavedListView {
  id: number;
  name: string;
  state: ListViewPayload;
  isDefault: boolean;
  lockVersion: number;
  modifiedAt: string;
}

export interface ListViewRequest {
  name: string;
  state: ListViewPayload;
  isDefault: boolean;
  lockVersion?: number;
}

/** `/api/v1/list-views/{list}`: the signed-in person's own views of a list. */
@Injectable({ providedIn: 'root' })
export class ListViewsApi {
  private readonly api = inject(ApiService);

  list(listCode: string): Observable<SavedListView[]> {
    return this.api.get<SavedListView[]>(this.path(listCode), undefined, { notifyError: false });
  }

  create(listCode: string, body: ListViewRequest): Observable<SavedListView> {
    return this.api.post<SavedListView>(this.path(listCode), body, { notifyError: false });
  }

  update(listCode: string, id: number, body: ListViewRequest): Observable<SavedListView> {
    return this.api.put<SavedListView>(`${this.path(listCode)}/${id}`, body, { notifyError: false });
  }

  remove(listCode: string, id: number): Observable<void> {
    return this.api.delete<void>(`${this.path(listCode)}/${id}`, { notifyError: false });
  }

  private path(listCode: string): string {
    return `/list-views/${encodeURIComponent(listCode)}`;
  }
}

export interface ListViewStateOptions {
  /** The list's own order, used by the standard view and when a view has no sort. */
  defaultSort: () => QuerySort | null;
  /** Called after a view is applied, so the screen reloads its first page. */
  onApply: () => void;
  /** Where the unsaved column choice of the standard view is kept between visits. */
  columnsStore?: TableColumnStateStore;
}

/**
 * The views of one list and what is on screen now, with no UI of its own
 * (like KeysetPager). The screen reads `sort` and `filter` for its requests,
 * the table reads and writes `columns`, and `ui-list-views` switches, saves and
 * removes views. Opening a list applies the person's default view; without one,
 * the standard view with the columns they left last time.
 */
export class ListViewState {
  readonly views = signal<SavedListView[]>([]);
  readonly activeId = signal<number | null>(null);
  readonly columns = signal<TableColumnState>(EMPTY_COLUMN_STATE);
  readonly sort = signal<QuerySort | null>(null);
  readonly filter = signal<QueryCondition[]>([]);
  readonly busy = signal(false);

  readonly active = computed(() => this.views().find(view => view.id === this.activeId()) ?? null);

  /** What would be saved now. */
  readonly current = computed<ListViewPayload>(() => ({
    columns: this.columns(),
    sort: this.sortText(this.sort()),
    filter: this.filter(),
  }));

  /** The active view differs from what is on screen. */
  readonly changed = computed(() => {
    const active = this.active();
    return active !== null && !samePayload(active.state, this.current());
  });

  constructor(
    readonly listCode: string,
    private readonly api: ListViewsApi,
    private readonly options: ListViewStateOptions,
  ) {}

  /** Loads the views and applies the default one; a failure leaves the standard view. */
  load(): Observable<boolean> {
    return this.api.list(this.listCode).pipe(
      map(views => {
        this.views.set(views);
        const preferred = views.find(view => view.isDefault) ?? null;
        this.show(preferred);
        return true;
      }),
      catchError(() => {
        this.show(null);
        return of(false);
      }),
    );
  }

  /** Switches to a saved view, or to the standard one with `null`, and reloads the list. */
  apply(view: SavedListView | null): void {
    this.show(view);
    this.options.onApply();
  }

  setColumns(columns: TableColumnState): void {
    this.columns.set(columns);
    if (this.activeId() === null) this.options.columnsStore?.save(this.listCode, columns);
  }

  /** New filter conditions from the builder or a removed chip; the list reloads from its first page. */
  setFilter(conditions: QueryCondition[]): void {
    this.filter.set(conditions);
    this.options.onApply();
  }

  setSort(sort: QuerySort | null): void {
    this.sort.set(sort ?? this.options.defaultSort());
  }

  saveAs(name: string, isDefault: boolean): Observable<SavedListView> {
    this.busy.set(true);
    return this.api.create(this.listCode, { name, state: this.current(), isDefault }).pipe(
      tap({
        next: saved => {
          this.replace(saved);
          this.activeId.set(saved.id);
        },
        finalize: () => this.busy.set(false),
      }),
    );
  }

  /** Writes what is on screen into the active view. */
  saveActive(): Observable<SavedListView> {
    const active = this.active()!;
    return this.update(active, { name: active.name, state: this.current(), isDefault: active.isDefault });
  }

  setDefault(view: SavedListView, isDefault: boolean): Observable<SavedListView> {
    return this.update(view, { name: view.name, state: view.state, isDefault });
  }

  remove(view: SavedListView): Observable<void> {
    this.busy.set(true);
    return this.api.remove(this.listCode, view.id).pipe(
      tap({
        next: () => {
          this.views.update(views => views.filter(item => item.id !== view.id));
          if (this.activeId() === view.id) this.activeId.set(null);
        },
        finalize: () => this.busy.set(false),
      }),
    );
  }

  private update(view: SavedListView, body: ListViewRequest): Observable<SavedListView> {
    this.busy.set(true);
    return this.api.update(this.listCode, view.id, { ...body, lockVersion: view.lockVersion }).pipe(
      tap({
        next: saved => this.replace(saved),
        finalize: () => this.busy.set(false),
      }),
    );
  }

  /** The saved view replaces its old copy; only one view of a list can be the default. */
  private replace(saved: SavedListView): void {
    this.views.update(views => {
      const others = views
        .filter(view => view.id !== saved.id)
        .map(view => (saved.isDefault && view.isDefault ? { ...view, isDefault: false } : view));
      return [...others, saved].sort((a, b) => a.name.localeCompare(b.name) || a.id - b.id);
    });
  }

  private show(view: SavedListView | null): void {
    this.activeId.set(view?.id ?? null);
    if (view) {
      this.columns.set(view.state.columns ?? EMPTY_COLUMN_STATE);
      this.sort.set(view.state.sort ? parseSort(view.state.sort) : this.options.defaultSort());
      this.filter.set(view.state.filter ?? []);
    } else {
      this.columns.set(this.options.columnsStore?.load(this.listCode) ?? EMPTY_COLUMN_STATE);
      this.sort.set(this.options.defaultSort());
      this.filter.set([]);
    }
  }

  /** The list's default order is saved as `null`, so a view follows the list if its default changes. */
  private sortText(sort: QuerySort | null): string | null {
    if (!sort) return null;
    const fallback = this.options.defaultSort();
    return fallback && fallback.field === sort.field && fallback.descending === sort.descending ? null : formatSort(sort);
  }
}

/** Two payloads mean the same view: the same columns, sort and filter, whatever the key order. */
export function samePayload(a: ListViewPayload, b: ListViewPayload): boolean {
  return canonical(a) === canonical(b);
}

function canonical(payload: ListViewPayload): string {
  const columns = payload.columns ?? EMPTY_COLUMN_STATE;
  const widths = Object.keys(columns.widths ?? {}).sort().map(key => [key, columns.widths[key]]);
  return JSON.stringify([columns.order ?? [], columns.hidden ?? [], widths, payload.sort ?? null, payload.filter ?? []]);
}
