import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../../../core/services/api.service';
import { User } from '../../../../core/models/auth.models';
import { getManagerName } from '../users.models';
import { UserDirectoryService } from './user-directory.service';

interface Call { path: string; params: Record<string, unknown> | undefined; answer: Subject<unknown> }

const user = (id: number, name = `User ${id}`, managerId?: number): User => ({
  id, name, login: `u${id}`, email: '', state: 'A', language: 'ru', timezone: 'UTC', attributes: {},
  is2faEnabled: false, forcePasswordChange: false, createdAt: '', modifiedAt: '', managerId,
});

describe('UserDirectoryService', () => {
  let calls: Call[];
  let service: UserDirectoryService;

  beforeEach(() => {
    vi.useFakeTimers();
    calls = [];
    TestBed.configureTestingModule({
      providers: [
        UserDirectoryService,
        {
          provide: ApiService,
          useValue: { get: (path: string, params?: Record<string, unknown>) => { const answer = new Subject<unknown>(); calls.push({ path, params, answer }); return answer; } },
        },
      ],
    });
    service = TestBed.inject(UserDirectoryService);
  });
  afterEach(() => { service.cancel(); vi.useRealTimers(); });

  const reply = (call: Call, body: unknown) => { call.answer.next(body); call.answer.complete(); };
  const fail = (call: Call) => call.answer.error({ status: 404 });

  it("names a manager who is not among the loaded rows, asking for them once", () => {
    const report = user(5, 'Report', 42);
    const nameOf = (id: number) => service.nameOf(id);

    service.remember([report]);
    service.resolve([42, 42]);
    service.resolve([42]);
    expect(calls.map(call => call.path)).toEqual(['/iam/users/42']);
    expect(getManagerName(report, nameOf)).toBe('ID: #42');

    reply(calls[0], user(42, 'Dilnoza'));
    expect(getManagerName(report, nameOf)).toBe('Dilnoza');
    service.resolve([42]);
    expect(calls).toHaveLength(1);
  });

  it('does not ask again for a manager the server would not return', () => {
    service.resolve([7]);
    fail(calls[0]);
    service.resolve([7]);
    expect(calls).toHaveLength(1);
    expect(getManagerName(user(1, 'A', 7), id => service.nameOf(id))).toBe('ID: #7');
  });

  it('asks nothing for users already on the page', () => {
    service.remember([user(1), user(2)]);
    service.resolve([1, 2, null, undefined]);
    expect(calls).toHaveLength(0);
  });

  it('searches active users on the server for the manager picker', () => {
    service.openManagerPicker(null);
    service.searchManagers('  dil ');
    vi.advanceTimersByTime(300);
    expect(calls[0]).toMatchObject({ path: '/iam/users', params: { search: 'dil', state: 'A', limit: 50 } });
    reply(calls[0], { items: [user(3, 'Dilnoza'), user(4, 'Dilshod')], nextCursor: 'c1', hasMore: true });

    expect(service.managerOptions(null, null).map(option => option.label)).toEqual(['Dilnoza', 'Dilshod']);
    expect(service.managerLookupHasMore()).toBe(true);

    service.loadMoreManagers();
    expect(calls[1].params).toMatchObject({ cursor: 'c1', search: 'dil' });
    reply(calls[1], { items: [user(8, 'Dilya')], nextCursor: null, hasMore: false });
    expect(service.managerOptions(null, null).map(option => option.id)).toEqual([3, 4, 8]);
  });

  it('keeps the current manager in the picker whatever the search returns, and leaves the user out', () => {
    service.remember([user(9, 'Current manager')]);
    service.openManagerPicker(9);
    expect(calls).toHaveLength(0);
    expect(service.managerOptions(5, 9).map(option => option.label)).toEqual(['Current manager']);

    service.searchManagers('x');
    vi.advanceTimersByTime(300);
    reply(calls[0], { items: [user(5, 'Self'), user(6, 'Other')], nextCursor: null });
    expect(service.managerOptions(5, 9).map(option => option.id)).toEqual([9, 6]);
  });

  it('shows the current manager once their record arrives, when the screen had not loaded them', () => {
    service.openManagerPicker(11);
    expect(calls[0].path).toBe('/iam/users/11');
    expect(service.managerOptions(5, 11)).toEqual([]);
    reply(calls[0], user(11, 'Far away manager'));
    expect(service.managerOptions(5, 11).map(option => option.label)).toEqual(['Far away manager']);
  });

  it('hands the picker the same option objects between checks, so its list keeps focus', () => {
    service.remember([user(9, 'Manager')]);
    const first = service.managerOptions(null, 9)[0];
    expect(service.managerOptions(null, 9)[0]).toBe(first);
    service.remember([user(9, 'Renamed')]);
    expect(service.managerOptions(null, 9)[0].label).toBe('Renamed');
  });

  it('drops a slower answer to an earlier manager search', () => {
    service.searchManagers('an');
    vi.advanceTimersByTime(300);
    service.searchManagers('ann');
    vi.advanceTimersByTime(300);
    const [early, late] = calls;
    reply(late, { items: [user(2, 'Anna')], nextCursor: null });
    reply(early, { items: [user(1, 'Andrey')], nextCursor: null });
    expect(service.managerOptions(null, null).map(option => option.id)).toEqual([2]);
  });
});
