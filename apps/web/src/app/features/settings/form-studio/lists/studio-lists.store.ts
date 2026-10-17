import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Observable } from 'rxjs';
import type { components } from '@core/api/openapi';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { problemText } from '@shared/ui/problem-text';
import type { StudioLabels } from '../form-studio.models';

/** The answers of the server, whose properties are always present. */
export type StudioListSummary = Required<components['schemas']['StudioListSummary']>;
export type StudioListItem = Required<components['schemas']['StudioListItem']>;
export interface StudioListDetail {
  list: StudioListSummary;
  items: StudioListItem[];
}
interface ListPage {
  items?: StudioListSummary[];
  nextCursor?: string | null;
  hasMore?: boolean;
}

/** What the list and item dialogs send: a list takes a code and names, an item also an order and whether it is used. */
export interface StudioListEntry {
  code: string;
  name: StudioLabels;
  sortOrder: number;
  active: boolean;
}

/**
 * The reference lists an administrator keeps (ADR-0041): the catalog a page at a time, one open list with its items,
 * and the changes, each from the revision that was read. Nothing is deleted; an item is deactivated.
 */
@Injectable()
export class StudioListsStore {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(I18nService);
  private readonly permissions = inject(PermissionService);
  private readonly destroyRef = inject(DestroyRef);

  readonly lists = signal<StudioListSummary[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly loadingMore = signal(false);
  readonly error = signal('');
  readonly open = signal<StudioListDetail | null>(null);
  readonly openLoading = signal(false);
  readonly openError = signal('');
  readonly mayView = computed(() => this.permissions.hasPermission('studio.lists', 'view'));
  readonly mayManage = computed(() => this.permissions.hasPermission('studio.lists', 'manage'));

  load(): void {
    if (!this.mayView() || this.loading()) return;
    this.loading.set(true);
    this.error.set('');
    this.api
      .get<ListPage>('/studio/lists', { limit: 50 }, { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (page) => {
          this.lists.set(page.items ?? []);
          this.nextCursor.set(page.hasMore ? (page.nextCursor ?? null) : null);
        },
        error: (error) => this.error.set(problemText(error) || this.i18n.translate('studio.lists.load_failed')),
      });
  }

  loadMore(): void {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore()) return;
    this.loadingMore.set(true);
    this.api
      .get<ListPage>('/studio/lists', { limit: 50, cursor }, { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loadingMore.set(false)),
      )
      .subscribe({
        next: (page) => {
          this.lists.update((lists) => [...lists, ...(page.items ?? [])]);
          this.nextCursor.set(page.hasMore ? (page.nextCursor ?? null) : null);
        },
        error: (error) => this.error.set(problemText(error) || this.i18n.translate('studio.lists.load_failed')),
      });
  }

  /** Opens a list with its items. */
  select(code: string): void {
    this.openLoading.set(true);
    this.openError.set('');
    this.api
      .get<StudioListDetail>(`/studio/lists/${encodeURIComponent(code)}`, undefined, { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.openLoading.set(false)),
      )
      .subscribe({
        next: (detail) => this.open.set(detail),
        error: (error) => this.openError.set(problemText(error) || this.i18n.translate('studio.lists.open_failed')),
      });
  }

  createList(entry: StudioListEntry): Observable<StudioListDetail> {
    return this.api.post<StudioListDetail>(
      '/studio/lists',
      { code: entry.code, name: entry.name },
      { notifyError: false },
    );
  }

  renameList(list: StudioListSummary, entry: StudioListEntry): Observable<StudioListDetail> {
    return this.api.put<StudioListDetail>(
      `/studio/lists/${encodeURIComponent(list.code)}`,
      { name: entry.name },
      { notifyError: false, ifMatch: list.revision },
    );
  }

  addItem(list: StudioListSummary, entry: StudioListEntry): Observable<StudioListItem> {
    return this.api.post<StudioListItem>(
      `/studio/lists/${encodeURIComponent(list.code)}/items`,
      { code: entry.code, name: entry.name, sortOrder: entry.sortOrder },
      { notifyError: false },
    );
  }

  updateItem(list: StudioListSummary, item: StudioListItem, entry: StudioListEntry): Observable<StudioListItem> {
    return this.api.put<StudioListItem>(
      `/studio/lists/${encodeURIComponent(list.code)}/items/${encodeURIComponent(item.code)}`,
      { name: entry.name, sortOrder: entry.sortOrder, active: entry.active },
      { notifyError: false, ifMatch: item.revision },
    );
  }

  /** A list was created or renamed: the catalog shows it at once and it is the open one. */
  listSaved(detail: StudioListDetail): void {
    const summary = detail.list;
    this.lists.update((lists) =>
      lists.some((one) => one.code === summary.code)
        ? lists.map((one) => (one.code === summary.code ? summary : one))
        : [summary, ...lists],
    );
    this.open.set(detail);
  }

  /** An item changed: the open list is read again, so its order and counts are the server's. */
  itemSaved(): void {
    const open = this.open();
    if (!open) return;
    this.select(open.list.code);
    this.load();
  }

  /** A name in the language of the interface. */
  name(labels: StudioLabels): string {
    const language = this.i18n.currentLang();
    return labels[language === 'uz' || language === 'en' ? language : 'ru'];
  }
}
