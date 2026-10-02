import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaskStatus, TaskType } from '@core/models/task.models';
import { ToastService } from '@core/services/toast.service';
import { EntitiesApi } from '@shared/entity/entities.api';
import { SMTModalConfirmConfig, SMTModalService } from '@shared/ui-kit/components/modal';
import { TASK_STATUSES, TASK_TYPES, TaskDictionariesService, movedItem } from './task-dictionaries.service';

const status = (id: number, name = `Status ${id}`): TaskStatus => ({
  id,
  code: `s${id}`,
  name,
  sortOrder: id * 10,
  terminal: false,
  revision: id,
});
const type = (id: number, name = `Type ${id}`): TaskType => ({
  id,
  code: `t${id}`,
  name,
  icon: 'task',
  color: '#000',
  sortOrder: id * 10,
  system: false,
  revision: id,
});

describe('TaskDictionariesService', () => {
  let statuses: Observable<unknown>;
  let types: Observable<unknown>;
  let entities: {
    all: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
    action: ReturnType<typeof vi.fn>;
  };
  let toast: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  let confirmed: SMTModalConfirmConfig | undefined;
  let dictionaries: TaskDictionariesService;

  beforeEach(() => {
    statuses = of([status(1)]);
    types = of([type(1)]);
    confirmed = undefined;
    entities = {
      all: vi.fn((code: string) => (code === TASK_STATUSES ? statuses : types)),
      create: vi.fn(() => of({})),
      remove: vi.fn(() => of(undefined)),
      action: vi.fn(() => of({})),
    };
    toast = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: EntitiesApi, useValue: entities },
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

  const reads = (code: string) => entities.all.mock.calls.filter(([read]) => read === code).length;

  it('reads the statuses and types of the runtime once, and again on the next visit of the screen', () => {
    dictionaries.loadStatuses();
    dictionaries.loadTypes();
    TestBed.tick();
    expect(reads(TASK_STATUSES)).toBe(2);
    expect(reads(TASK_TYPES)).toBe(2);
    expect(dictionaries.statuses()).toEqual([status(1)]);
    expect(dictionaries.taskTypes()).toEqual([type(1)]);
  });

  it('keeps the lists on screen when a later read fails', () => {
    statuses = throwError(() => ({ status: 503 }));
    dictionaries.loadStatuses();
    TestBed.tick();

    expect(dictionaries.statuses()).toEqual([status(1)]);
  });

  it('adds a type and a status; the server puts them last and makes the status code', () => {
    types = of([type(1), type(2)]);
    dictionaries.handleCreateType({ code: 'bug', name: 'Bug', icon: 'bug_report', color: '#f00' });
    TestBed.tick();
    expect(entities.create).toHaveBeenCalledWith(TASK_TYPES, {
      code: 'bug',
      name: 'Bug',
      icon: 'bug_report',
      color: '#f00',
    });
    expect(dictionaries.taskTypes().map((t) => t.id)).toEqual([1, 2]);

    dictionaries.handleCreateStatus({ name: 'Done', color: '#0f0', terminal: true });
    expect(entities.create).toHaveBeenCalledWith(TASK_STATUSES, { name: 'Done', color: '#0f0', terminal: true });
    expect(toast.success).toHaveBeenCalledTimes(2);
  });

  it('shows the server message when adding fails', () => {
    entities.create.mockReturnValue(throwError(() => ({ status: 422, error: { detail: 'Code taken' } })));
    dictionaries.handleCreateType({ code: 't1', name: 'Twice', icon: 'task', color: '#000' });
    expect(toast.error).toHaveBeenCalled();
  });

  it('shows a new order at once and moves the one item that moved, from its revision', () => {
    statuses = of([status(1), status(2), status(3)]);
    dictionaries.loadStatuses();
    TestBed.tick();

    dictionaries.handleReorderStatuses([status(3), status(1), status(2)]);

    expect(dictionaries.statuses().map((s) => s.id)).toEqual([3, 1, 2]);
    expect(entities.action).toHaveBeenCalledWith(TASK_STATUSES, 3, 'move', 3, { position: 1 });
  });

  it('finds the moved item of a drag down and a drag up', () => {
    const before = [status(1), status(2), status(3), status(4)];
    expect(movedItem(before, [status(2), status(3), status(1), status(4)])?.id).toBe(1);
    expect(movedItem(before, [status(1), status(4), status(2), status(3)])?.id).toBe(4);
    expect(movedItem(before, before)).toBeNull();
  });

  it('deletes from the confirmation, reads the list again and names a refusal there', () => {
    dictionaries.handleDeleteDictionaryItem({ kind: 'status', id: 4, name: 'Old' });
    expect(confirmed?.message).toContain('Old');
    const before = reads(TASK_STATUSES);
    confirmed!.action!().subscribe();
    TestBed.tick();
    expect(entities.remove).toHaveBeenCalledWith(TASK_STATUSES, 4);
    expect(reads(TASK_STATUSES)).toBe(before + 1);
    expect(confirmed!.actionError!({ status: 409, detail: 'Status is used' })).toBe('Status is used');

    dictionaries.handleDeleteDictionaryItem({ kind: 'type', id: 5, name: 'Bug' });
    expect(confirmed!.actionError!({})).toBe('Ошибка удаления типа');
  });
});
