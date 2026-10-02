import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Task } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { AuthService } from '@core/services/auth.service';
import { QueryMetaService } from '@core/services/query-meta.service';
import { TASKS_META, registryProviders } from '@testing/registry-meta';
import { TaskListStore } from './task-list.store';

const task = (id: number, title = `Task ${id}`): Task => ({
  id,
  title,
  typeCode: 'task',
  statusCode: 's1',
  priority: 'medium',
  attributes: {},
  createdAt: '2026-09-05T00:00:00Z',
});
const page = (items: Task[], nextCursor: string | null = null) => ({ items, nextCursor, hasMore: nextCursor !== null });
type Params = Record<string, unknown>;

/** The task list on the general runtime (ADR-0032 8). */
const LIST = '/entities/ms.tasks';
const ACTIVE = JSON.stringify([{ field: 'terminal', op: 'eq', value: false }]);

describe('TaskListStore', () => {
  let listReads: (params: Params) => Observable<unknown>;
  let api: { get: ReturnType<typeof vi.fn> };
  let store: TaskListStore;
  const listCalls = () => api.get.mock.calls.filter(([path]) => path === LIST).map(([, params]) => params as Params);

  function setup(meta: Observable<unknown> = of(TASKS_META)) {
    api = {
      get: vi.fn((path: string, params: Params) => (path === LIST ? listReads(params) : of([]))),
    };
    TestBed.configureTestingModule({
      providers: [
        TaskListStore,
        { provide: ApiService, useValue: api },
        { provide: AuthService, useValue: { currentUser: () => ({ id: 7 }) } },
        ...registryProviders(TASKS_META),
        { provide: QueryMetaService, useValue: { get: vi.fn(() => meta) } },
      ],
    });
    store = TestBed.inject(TaskListStore);
    TestBed.tick();
  }

  beforeEach(() => {
    listReads = () => of(page([task(1)]));
  });
  afterEach(() => vi.useRealTimers());

  it('reads the list metadata first, then the first page, and never the whole project list', () => {
    setup();
    store.loadTasks(true);

    expect(store.meta()?.code).toBe('ms.tasks');
    expect(store.tasks().map((t) => t.id)).toEqual([1]);
    expect(listCalls()[0]).toEqual(expect.objectContaining({ limit: 50, filter: ACTIVE }));
    // Plan 10/10, item 3.5: each row names its project; pickers search the paged list.
    expect(api.get.mock.calls.filter(([path]) => String(path).startsWith('/tasks/projects'))).toHaveLength(0);
  });

  it('asks for the metadata again after it failed', () => {
    const meta = new Subject<unknown>();
    setup(meta);
    store.loadTasks(true);
    meta.error({ status: 503 });
    expect(store.metaError()).toBe(true);
    expect(listCalls()).toHaveLength(0);
  });

  it('keeps the rows on screen when a reload fails, and a retry brings the new ones', () => {
    const failed = new Subject<unknown>();
    const reads = [of(page([task(1, 'Existing')])), failed, of(page([task(2, 'Recovered')]))];
    listReads = () => reads.shift()!;
    setup();
    store.loadTasks(true);

    store.loadTasks(true);
    failed.error({ detail: 'offline' });
    expect(store.tasks().map((t) => t.title)).toEqual(['Existing']);
    expect(store.listLoadError()).toBe(true);
    store.goToTaskPage(2);
    expect(listCalls()).toHaveLength(2);

    store.retryTaskList();
    expect(store.tasks().map((t) => t.title)).toEqual(['Recovered']);
    expect(store.listLoadError()).toBe(false);
  });

  it('starts over on a filter change and ignores the answer for the old page', () => {
    const [oldPage, filteredPage] = [new Subject<unknown>(), new Subject<unknown>()];
    const reads: Observable<unknown>[] = [of(page([task(1)], 'next')), oldPage, filteredPage];
    listReads = () => reads.shift()!;
    setup();
    store.loadTasks(true);

    store.goToTaskPage(2);
    store.setStatusFilterMode('all');
    filteredPage.next(page([task(700, 'Filtered first')]));
    oldPage.next(page([task(51, 'Old answer')]));

    expect(listCalls()[1]['cursor']).toBe('next');
    expect(listCalls()[2]).toEqual(expect.objectContaining({ cursor: undefined }));
    expect(listCalls()[2]['filter']).toBeUndefined();
    expect(store.taskPager.page()).toBe(1);
    expect(store.tasks().map((t) => t.id)).toEqual([700]);
  });

  it('sends the preset of a quick view: overdue, or the viewer as executor or observer', () => {
    setup();
    store.loadTasks(true);

    // The quick filters are conditions of the list's filter expression (ADR-0016).
    const conditions = () => JSON.parse(String(listCalls().at(-1)?.['filter'] ?? '[]'));
    const active = { field: 'terminal', op: 'eq', value: false };

    store.setPreset('overdue');
    expect(conditions()).toEqual([active, { field: 'overdue', op: 'eq', value: true }]);
    store.setPreset('executor');
    expect(conditions()).toEqual([active, { field: 'executorIds', op: 'in', value: [7] }]);
    store.setPreset('observer');
    expect(conditions()).toEqual([active, { field: 'observerIds', op: 'in', value: [7] }]);
    store.onProjectFilterChange(5);
    store.onPriorityFilterChange('high');
    expect(conditions()).toEqual(
      expect.arrayContaining([
        { field: 'priority', op: 'eq', value: 'high' },
        { field: 'projectId', op: 'eq', value: 5 },
      ]),
    );

    store.resetFilters();
    expect(conditions()).toEqual([active]);
  });

  it('searches 350 ms after typing, cancels the old page at once and blocks its retry meanwhile', async () => {
    vi.useFakeTimers();
    listReads = (params) =>
      params['cursor'] === 'c50' ? throwError(() => ({ status: 503 })) : of(page([task(1)], 'c50'));
    setup();
    store.loadTasks(true);
    store.goToTaskPage(2);
    expect(store.listLoadError()).toBe(true);
    const before = listCalls().length;

    store.onTaskSearchChange('pending');
    expect(store.isLoading()).toBe(true);
    store.retryTaskList();
    expect(listCalls()).toHaveLength(before);
    await vi.advanceTimersByTimeAsync(349);
    expect(listCalls()).toHaveLength(before);
    await vi.advanceTimersByTimeAsync(1);
    expect(listCalls().filter((params) => params['q'] === 'pending')).toHaveLength(1);

    store.onTaskSearchChange('beta');
    store.applyTaskSearchImmediately();
    await vi.advanceTimersByTimeAsync(350);
    expect(listCalls().filter((params) => params['q'] === 'beta')).toHaveLength(1);

    store.clearSearch();
    expect(store.filters.searchQuery).toBe('');
    expect(listCalls().at(-1)?.['q']).toBeUndefined();
  });

  it('sorts on the server from a header, and exports the quick filters without paging', () => {
    setup();
    store.onSort({ column: 'title', sortBy: 'ASC' as never });
    expect(listCalls()).toHaveLength(0);
    store.loadTasks(true);

    store.onSort({ column: 'title', sortBy: 'DESC' as never });
    expect(listCalls().at(-1)).toEqual(expect.objectContaining({ sort: '-title' }));

    const conditions = store.exportFilter();
    expect(conditions).toEqual([{ field: 'terminal', op: 'eq', value: false }]);
    expect(store.exportFilter()).toBe(conditions);
    store.setStatusFilterMode('status:review');
    expect(store.exportFilter()).toEqual([{ field: 'statusCode', op: 'eq', value: 'review' }]);
  });
});
