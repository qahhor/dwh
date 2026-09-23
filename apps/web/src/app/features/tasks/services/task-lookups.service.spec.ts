import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../../core/services/api.service';
import { User } from '../../../core/models/auth.models';
import { TaskLookupsService } from './task-lookups.service';

/** Characterisation of the four lookups: written against the behaviour the screens rely on. */
interface Call { path: string; params: Record<string, unknown>; answer: Subject<unknown> }

const user = (id: number, name = `User ${id}`): User => ({
  id, name, login: `u${id}`, email: '', state: 'A', language: 'ru', timezone: 'UTC', attributes: {},
  is2faEnabled: false, forcePasswordChange: false, createdAt: '', modifiedAt: '',
});
const task = (id: number) => ({ id, title: `Task ${id}` });

describe('TaskLookupsService', () => {
  let calls: Call[];
  let service: TaskLookupsService;

  beforeEach(() => {
    vi.useFakeTimers();
    calls = [];
    TestBed.configureTestingModule({
      providers: [{
        provide: ApiService,
        useValue: { get: (path: string, params: Record<string, unknown>) => { const answer = new Subject<unknown>(); calls.push({ path, params, answer }); return answer; } },
      }],
    });
    service = TestBed.inject(TaskLookupsService);
  });
  afterEach(() => { service.cleanup(); vi.useRealTimers(); });

  const answer = (call: Call, items: unknown[], nextCursor: string | null = null) => {
    call.answer.next({ items, nextCursor, hasMore: nextCursor !== null });
    call.answer.complete();
  };

  it('waits for typing to pause, then searches the first page', () => {
    service.onParentSearch('  deploy  ', () => null);
    expect(service.parentLookupLoading()).toBe(true);
    vi.advanceTimersByTime(299);
    expect(calls).toHaveLength(0);
    vi.advanceTimersByTime(1);
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({ path: '/tasks', params: { limit: 50, search: 'deploy', cursor: undefined } });
  });

  it('shows the answer to the latest search, not a slower earlier one', () => {
    service.onResponsibleSearch('an', () => null);
    vi.advanceTimersByTime(300);
    service.onResponsibleSearch('ann', () => null);
    vi.advanceTimersByTime(300);
    const [early, late] = calls;
    expect(late.params).toMatchObject({ search: 'ann', state: 'A' });
    answer(late, [user(2, 'Anna')]);
    answer(early, [user(1, 'Andrey')]);
    expect(service.responsibleUsers().map(item => item.id)).toEqual([2]);
    expect(service.responsibleLookupLoading()).toBe(false);
  });

  it('loads more with the returned cursor, appends, and stops at the last page', () => {
    service.onExecutorSearch('', () => []);
    vi.advanceTimersByTime(300);
    answer(calls[0], [user(1), user(2)], 'c2');
    expect(service.executorLookupHasMore()).toBe(true);

    service.loadMoreExecutors(() => []);
    expect(calls[1].params).toMatchObject({ cursor: 'c2' });
    // A second request while one is loading is ignored.
    service.loadMoreExecutors(() => []);
    expect(calls).toHaveLength(2);
    answer(calls[1], [user(3)]);
    expect(service.executorUsers().map(item => item.id)).toEqual([1, 2, 3]);
    expect(service.executorLookupHasMore()).toBe(false);
  });

  it('keeps the selected entries when a new search returns other results', () => {
    service.onObserverSearch('', () => []);
    vi.advanceTimersByTime(300);
    answer(calls[0], [user(1), user(2)]);
    service.onObserverSearch('zz', () => [2]);
    vi.advanceTimersByTime(300);
    answer(calls[1], [user(9)]);
    expect(service.observerUsers().map(item => item.id)).toEqual([9, 2]);

    service.onParentSearch('', () => null);
    vi.advanceTimersByTime(300);
    answer(calls[2], [task(5), task(6)]);
    service.onParentSearch('x', () => 6);
    vi.advanceTimersByTime(300);
    answer(calls[3], [task(8)]);
    expect(service.parentTaskOptions().map(option => option.id)).toEqual([6, 8]);
  });

  it('reports a failure and retries the same request', () => {
    service.onParentSearch('', () => null);
    vi.advanceTimersByTime(300);
    answer(calls[0], [task(1)], 'c2');
    service.loadMoreParents(() => null);
    calls[1].answer.error(new Error('offline'));
    expect([service.parentLookupError(), service.parentLookupLoading()]).toEqual([true, false]);
    expect(service.parentTaskOptions().map(option => option.id)).toEqual([1]);

    service.retryParentLookup(() => null);
    expect(calls[2].params).toMatchObject({ cursor: 'c2' });
    answer(calls[2], [task(2)]);
    expect(service.parentLookupError()).toBe(false);
    expect(service.parentTaskOptions().map(option => option.id)).toEqual([1, 2]);
  });

  it('drops answers still in flight after cleanup', () => {
    service.onResponsibleSearch('', () => null);
    vi.advanceTimersByTime(300);
    service.cleanup();
    answer(calls[0], [user(1)]);
    expect(service.responsibleUsers()).toEqual([]);
    // A pending search is cancelled too.
    service.onResponsibleSearch('late', () => null);
    service.cleanup();
    vi.advanceTimersByTime(300);
    expect(calls).toHaveLength(1);
  });
});
