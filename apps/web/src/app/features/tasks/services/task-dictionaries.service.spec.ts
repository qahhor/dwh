import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaskStatus, TaskType } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalConfirmConfig, SMTModalService } from '@shared/ui-kit/components/modal';
import { TaskDictionariesService } from './task-dictionaries.service';

const status = (id: number, name = `Status ${id}`): TaskStatus => ({ id, name, orderNo: id * 10, isTerminal: false });
const type = (id: number, name = `Type ${id}`): TaskType => ({
  id,
  code: `t${id}`,
  name,
  icon: 'task',
  color: '#000',
  orderNo: id * 10,
  isSystem: false,
});

describe('TaskDictionariesService', () => {
  let statuses: Observable<unknown>;
  let types: Observable<unknown>;
  let api: { get: ReturnType<typeof vi.fn>; post: ReturnType<typeof vi.fn>; delete: ReturnType<typeof vi.fn> };
  let toast: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  let confirmed: SMTModalConfirmConfig | undefined;
  let dictionaries: TaskDictionariesService;

  beforeEach(() => {
    statuses = of([status(1)]);
    types = of([type(1)]);
    confirmed = undefined;
    api = {
      get: vi.fn((path: string) => (path === '/tasks/statuses' ? statuses : types)),
      post: vi.fn(() => of({})),
      delete: vi.fn(() => of({})),
    };
    toast = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: toast },
        {
          provide: SMTModalService,
          useValue: {
            confirm: (options: SMTModalConfirmConfig) => {
              confirmed = options;
              return of(true);
            },
          },
        },
      ],
    });
    dictionaries = TestBed.inject(TaskDictionariesService);
    TestBed.tick();
  });

  const reads = (path: string) => api.get.mock.calls.filter(([url]) => url === path).length;

  it('reads the statuses and types once, and again on the next visit of the screen', () => {
    dictionaries.loadStatuses();
    dictionaries.loadTypes();
    TestBed.tick();
    expect(reads('/tasks/statuses')).toBe(2);
    expect(reads('/tasks/types')).toBe(2);
    expect(dictionaries.statuses()).toEqual([status(1)]);
    expect(dictionaries.taskTypes()).toEqual([type(1)]);
  });

  it('keeps the lists on screen when a later read fails', () => {
    statuses = throwError(() => ({ status: 503 }));
    types = of(null);
    dictionaries.loadStatuses();
    dictionaries.loadTypes();
    TestBed.tick();

    expect(dictionaries.statuses()).toEqual([status(1)]);
    expect(dictionaries.taskTypes()).toEqual([]);
  });

  it('adds a type and a status at the end of their order and reads the list again', () => {
    types = of([type(1), type(2)]);
    dictionaries.handleCreateType({ code: 'bug', name: 'Bug', icon: 'bug_report', color: '#f00' });
    TestBed.tick();
    expect(api.post).toHaveBeenCalledWith('/tasks/types', {
      code: 'bug',
      name: 'Bug',
      icon: 'bug_report',
      color: '#f00',
      orderNo: 20,
    });
    expect(dictionaries.taskTypes().map((t) => t.id)).toEqual([1, 2]);

    dictionaries.handleCreateStatus({ name: 'Done', color: '#0f0', isTerminal: true });
    expect(api.post).toHaveBeenCalledWith('/tasks/statuses', {
      name: 'Done',
      color: '#0f0',
      orderNo: 20,
      isTerminal: true,
    });
    expect(toast.success).toHaveBeenCalledTimes(2);
  });

  it('shows the server message when adding fails', () => {
    api.post.mockReturnValue(throwError(() => ({ error: { message: 'Code taken' } })));
    dictionaries.handleCreateType({ code: 't1', name: 'Twice', icon: 'task', color: '#000' });
    expect(toast.error).toHaveBeenCalledWith('Code taken');
  });

  it('shows a new order at once and saves it', () => {
    dictionaries.handleReorderStatuses([status(2), status(1)]);
    dictionaries.handleReorderTypes([type(3), type(1)]);

    expect(dictionaries.statuses().map((s) => s.id)).toEqual([2, 1]);
    expect(dictionaries.taskTypes().map((t) => t.id)).toEqual([3, 1]);
    expect(api.post).toHaveBeenCalledWith('/tasks/statuses/reorder', [2, 1]);
    expect(api.post).toHaveBeenCalledWith('/tasks/types/reorder', [3, 1]);
  });

  it('deletes from the confirmation, reads the list again and names a refusal there', () => {
    dictionaries.handleDeleteDictionaryItem({ kind: 'status', id: 4, name: 'Old' });
    expect(confirmed?.message).toContain('Old');
    const before = reads('/tasks/statuses');
    confirmed!.action!().subscribe();
    TestBed.tick();
    expect(api.delete).toHaveBeenCalledWith('/tasks/statuses/4', { notifyError: false });
    expect(reads('/tasks/statuses')).toBe(before + 1);
    expect(confirmed!.actionError!({ status: 409, detail: 'Status is used' })).toBe('Status is used');

    dictionaries.handleDeleteDictionaryItem({ kind: 'type', id: 5, name: 'Bug' });
    expect(confirmed!.actionError!({})).toBe('Ошибка удаления типа');
  });
});
