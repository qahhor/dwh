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
  afterEach(() => vi.useRealTimers());

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
});
