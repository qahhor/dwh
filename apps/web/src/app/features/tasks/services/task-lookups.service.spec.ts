import { TestBed } from '@angular/core/testing';
import { of, Subject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../../core/services/api.service';
import { TaskMember } from '../../../core/models/task.models';
import { TaskLookupsService } from './task-lookups.service';

const member = (userId: number, userName: string): TaskMember =>
  ({ taskId: 7, userId, userName, userLogin: `u${userId}`, involveKind: 'E' }) as TaskMember;

describe('TaskLookupsService', () => {
  let api: { get: ReturnType<typeof vi.fn> };
  let lookups: TaskLookupsService;

  beforeEach(() => {
    api = { get: vi.fn() };
    TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: api }] });
    lookups = TestBed.inject(TaskLookupsService);
  });

  it('searches active users and tasks through the shared sources', () => {
    api.get.mockReturnValue(of({ items: [], nextCursor: null, hasMore: false }));
    lookups.users.page('ann', null, 50).subscribe();
    lookups.tasks.page('Outside', 'c1', 50).subscribe();
    expect(api.get).toHaveBeenCalledWith('/iam/users', { state: 'A', limit: 50, cursor: undefined, search: 'ann' }, { notifyError: false });
    expect(api.get).toHaveBeenCalledWith('/tasks', { limit: 50, cursor: 'c1', search: 'Outside' }, { notifyError: false });
    expect(lookups.tasks.option({ id: 12, title: 'Report' })).toEqual({ label: '#12 Report', icon: 'task_alt' });
  });

  it('remembers a card\'s members and parent, a fresher card replacing an older name', () => {
    lookups.retainTaskMember(member(501, 'Old Name'));
    lookups.retainTaskMember(member(501, 'Fresh Name'));
    lookups.retainParentOption(999, 'Parent');
    expect(lookups.knownUserRows()).toEqual([{ id: 501, name: 'Fresh Name', login: 'u501' }]);
    expect(lookups.knownParentRows()).toEqual([{ id: 999, title: 'Parent' }]);
    expect(lookups.nameOf(501)).toBe('Fresh Name');
    expect(lookups.nameOf(502)).toBeNull();
  });

  it('asks once for the names it does not know and skips the ones it does', () => {
    lookups.retainTaskMember(member(501, 'Known'));
    const answer = new Subject<unknown>();
    api.get.mockReturnValue(answer);
    lookups.resolveUserNames([501, 502, 502, 0, -3]);
    lookups.resolveUserNames([502]);
    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.get).toHaveBeenCalledWith('/iam/users/502', undefined, { notifyError: false });
    answer.next({ id: 502, name: 'Resolved', login: 'u502' });
    answer.complete();
    expect(lookups.nameOf(502)).toBe('Resolved');
  });

  it('reads a chosen parent from its card', () => {
    api.get.mockReturnValue(of({ task: { id: 999, title: 'Remote Parent' }, members: [] }));
    let found: readonly unknown[] = [];
    lookups.tasks.resolve!([999]).subscribe(rows => (found = rows));
    expect(api.get).toHaveBeenCalledWith('/tasks/999', undefined, { notifyError: false });
    expect(found).toEqual([{ id: 999, title: 'Remote Parent' }]);
  });
});
