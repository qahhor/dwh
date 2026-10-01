import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, map, of } from 'rxjs';
import { CustomField } from '@core/models/custom-field.models';
import { QueryListMeta } from '@core/models/query-meta.models';
import { Task } from '@core/models/task.models';
import { CustomFieldsApi } from '@core/services/custom-fields.api';
import { QueryMetaService, parseSort } from '@core/services/query-meta.service';
import { ListViewState, ListViewsApi } from '@shared/list-views/list-views';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { OrderBy } from '@shared/ui-kit/components/table/table.types';
import { TableColumnStateStore } from '@shared/ui-kit/services/table-column-state.store';
import { sortFromHeader } from '@shared/ui/registry-table-config';
import { TasksApi } from '../tasks.api';
import { TaskFilterService } from './task-filter.service';

/**
 * The task list of one screen: its metadata, saved views, pages, quick filters
 * and the custom fields of its rows; each row names its own project. Provided
 * by the screen, so the pager and its requests end with it.
 */
@Injectable()
export class TaskListStore {
  readonly filters = inject(TaskFilterService);
  private readonly tasksApi = inject(TasksApi);
  private readonly customFieldsApi = inject(CustomFieldsApi);
  private readonly queryMeta = inject(QueryMetaService);
  private readonly destroyRef = inject(DestroyRef);

  /** Field metadata of the list (`query-meta/ms.tasks`), roadmap item 49. */
  readonly meta = signal<QueryListMeta | null>(null);
  readonly metaError = signal(false);

  readonly customFields = computed(() => this.customFieldsResource.value() ?? []);

  private readonly customFieldsResource = rxResource({
    stream: () =>
      this.customFieldsApi.list('TASK').pipe(
        map((res) => res || []),
        catchError(() => of<CustomField[]>([])),
      ),
  });

  private exportFilters: Record<string, string> = {};

  /** Sort, filter and columns of the list; saved views keep them under a name. */
  readonly views = new ListViewState('ms.tasks', inject(ListViewsApi), {
    defaultSort: () => {
      const meta = this.meta();
      return meta ? parseSort(meta.defaultSort) : null;
    },
    onApply: () => this.taskPager.first(),
    columnsStore: inject(TableColumnStateStore),
  });

  /* Page-by-page over the keyset API. The pager cancels a superseded request,
     moves the page only when it arrives and retries exactly the failed one;
     the quick filters, the search, the sort and the filter are read when each request is made. */
  readonly taskPager = new KeysetPager<Task>(
    (cursor, limit) =>
      this.tasksApi.page(this.filters.buildListParams(cursor, limit), {
        sort: this.views.sort(),
        conditions: this.views.filter(),
        match: this.views.match(),
        search: this.filters.searchQuery,
      }),
    // One page size: the pager's, which is also the limit each request sends.
    { pageSize: this.filters.pageSize, destroyRef: this.destroyRef },
  );
  /** Writable: kanban and inline edits update rows in place. */
  readonly tasks = this.taskPager.items;

  readonly isLoading = this.taskPager.loading;
  readonly listLoadError = this.taskPager.failed;
  readonly hasMore = this.taskPager.canGoForward;

  /** The first page for the current filters; without `reset`, the page on screen again. The metadata comes first, once. */
  loadTasks(reset: boolean = false) {
    clearTimeout(this.filters.taskSearchTimer);
    if (!this.meta()) {
      this.metaError.set(false);
      this.queryMeta
        .get('ms.tasks')
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (meta) => {
            this.meta.set(meta);
            this.views.load().subscribe(() => this.taskPager.first());
          },
          error: () => this.metaError.set(true),
        });
      return;
    }
    if (reset) this.taskPager.first();
    else this.taskPager.reload();
  }

  /** A header click sorts the whole list on the server. */
  onSort(event: { column: string; sortBy: OrderBy } | undefined) {
    if (!this.meta()) return;
    this.views.setSort(sortFromHeader(event));
    this.taskPager.first();
  }

  /** The quick filters as export options; the same object while they stay, so the button is not re-rendered. */
  exportOptions(): Record<string, string> {
    const next: Record<string, string> = {};
    for (const [key, value] of Object.entries(this.filters.buildListParams(null))) {
      // Paging is not a filter: the export takes every row.
      if (key === 'limit' || key === 'cursor') continue;
      if (value !== undefined && value !== null && value !== '') next[key] = String(value);
    }
    const same =
      Object.keys(next).length === Object.keys(this.exportFilters).length &&
      Object.entries(next).every(([key, value]) => this.exportFilters[key] === value);
    if (!same) this.exportFilters = next;
    return this.exportFilters;
  }

  onTaskSearchChange(query: string) {
    this.filters.searchQuery = query;
    clearTimeout(this.filters.taskSearchTimer);
    this.cancelListRequestForFilterChange();
    this.filters.taskSearchTimer = setTimeout(() => this.loadTasks(true), 350);
  }

  applyTaskSearchImmediately() {
    clearTimeout(this.filters.taskSearchTimer);
    this.loadTasks(true);
  }

  retryTaskList() {
    if (this.isLoading()) return;
    this.taskPager.retry();
  }

  goToTaskPage(page: number) {
    if (this.isLoading() || this.listLoadError()) return;
    this.taskPager.goTo(page);
  }

  clearSearch() {
    this.cancelListRequestForFilterChange();
    this.filters.clearSearch(() => this.loadTasks(true));
  }
  setPreset(preset: 'all' | 'my' | 'executor' | 'observer' | 'reported' | 'overdue') {
    this.filters.setPreset(preset, () => this.loadTasks(true));
  }
  setStatusFilterMode(mode: 'active' | 'all' | number) {
    this.filters.setStatusFilterMode(mode, () => this.loadTasks(true));
  }
  onProjectFilterChange(projectId: number | null) {
    this.filters.onProjectFilterChange(projectId, () => this.loadTasks(true));
  }
  onPriorityFilterChange(priority: string) {
    this.filters.onPriorityFilterChange(priority, () => this.loadTasks(true));
  }
  resetFilters() {
    this.cancelListRequestForFilterChange();
    this.filters.resetFilters(() => this.loadTasks(true));
  }

  /** The answer in flight is for the old query: drop it and show the list as busy. */
  private cancelListRequestForFilterChange() {
    this.taskPager.invalidate();
  }
}
