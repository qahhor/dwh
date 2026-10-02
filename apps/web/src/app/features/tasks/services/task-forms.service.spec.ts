import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of } from 'rxjs';
import { Mock, beforeEach, describe, expect, it, vi } from 'vitest';
import { Task, TaskMember } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { TaskFormsService } from './task-forms.service';

const task = (id: number, title = `Task ${id}`, extra: Partial<Task> = {}): Task => ({
  id,
  title,
  typeCode: 'task',
  statusCode: 's1',
  priority: 'medium',
  attributes: {},
  createdAt: '2026-09-05T00:00:00Z',
  ...extra,
});
/** The task list on the general runtime (ADR-0032 8) and one task's record. */
const LIST = '/entities/ms.tasks';
const record = (id: number) => `${LIST}/${id}`;
const member = (userId: number, involveKind: string, userName = `User ${userId}`): TaskMember =>
  ({ taskId: 9, userId, involveKind, userName, userLogin: `u${userId}` }) as TaskMember;

describe('TaskFormsService', () => {
  let responses: Record<string, Observable<unknown> | Observable<unknown>[]>;
  let api: { get: ReturnType<typeof vi.fn>; post: ReturnType<typeof vi.fn>; patch: ReturnType<typeof vi.fn> };
  let toast: {
    success: ReturnType<typeof vi.fn>;
    error: ReturnType<typeof vi.fn>;
    warning: ReturnType<typeof vi.fn>;
    show: ReturnType<typeof vi.fn>;
  };
  let forms: TaskFormsService;
  let retainMember: Mock<(m: TaskMember) => void>;
  let retainParent: Mock<(id: number, title: string) => void>;
  const openEdit = (t: Task, returnTask: Task | null = null) =>
    forms.openEditModal(t, () => returnTask, retainMember, retainParent);
  /** A queue answers one request per entry; a single answer serves every request; a task has no participants. */
  const answer = (key: string) => {
    const value = responses[key];
    return Array.isArray(value) ? value.shift()! : (value ?? of(key.endsWith('/members') ? [] : {}));
  };

  beforeEach(() => {
    responses = {};
    retainMember = vi.fn();
    retainParent = vi.fn();
    api = {
      get: vi.fn((path: string) => answer(path)),
      post: vi.fn((path: string) => answer(`POST ${path}`)),
      patch: vi.fn((path: string) => answer(`PATCH ${path}`)),
    };
    toast = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), show: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: toast },
      ],
    });
    forms = TestBed.inject(TaskFormsService);
  });

  it('keeps a failed edit read out of the save-ready state and loads it again on retry', () => {
    const [first, retry] = [new Subject<unknown>(), new Subject<unknown>()];
    responses[record(7)] = [first, retry];

    openEdit(task(7, 'Stale row'));
    first.error({ detail: 'offline' });
    expect(forms.editingTask).toBeNull();
    expect(forms.editLoadError()).toBe(true);
    forms.submitEditTask(vi.fn());
    expect(api.patch).not.toHaveBeenCalled();

    forms.retryEditLoad(retainMember, retainParent);
    retry.next(task(7, 'Fresh task'));
    expect(forms.editingTask?.title).toBe('Fresh task');
    expect(forms.editForm.title).toBe('Fresh task');
    expect(forms.editLoadError()).toBe(false);
  });

  it('builds the edit form from the fresh task and its members, and remembers them for the pickers', () => {
    const fresh = task(9, 'Fresh title', {
      descriptionMarkdown: 'fresh body',
      parentTaskId: 999,
      endTime: '2026-09-05T12:00:37.123Z',
      responsibleId: 31,
      observerIds: [44],
      executorIds: [45],
    });
    responses[record(9)] = of(fresh);
    responses['/tasks/9/members'] = of([member(31, 'R', 'Owner'), member(44, 'O'), member(45, 'E')]);

    openEdit(task(9, 'Stale title'));

    expect(forms.editingTask).toEqual(fresh);
    expect(forms.editForm).toEqual(
      expect.objectContaining({
        title: 'Fresh title',
        descriptionMarkdown: 'fresh body',
        responsibleUserId: 31,
        observerUserIds: [44],
        executorUserIds: [45],
        parentTaskId: 999,
      }),
    );
    expect(retainMember).toHaveBeenCalledWith(expect.objectContaining({ userId: 31, userName: 'Owner' }));
  });

  it('treats an answer for another task or without members as a failed read', () => {
    responses[record(9)] = of(task(10));
    openEdit(task(9));
    expect(forms.editLoadError()).toBe(true);
    expect(forms.editingTask).toBeNull();
  });

  it('asks before discarding a dirty edit but closes an unchanged edit directly, back to the card', () => {
    responses[record(6)] = of(task(6, 'Original'));
    const openDetails = vi.fn();

    openEdit(task(6), task(6));
    forms.requestCloseEdit(openDetails);
    expect(forms.isEditModalOpen()).toBe(false);
    expect(openDetails).toHaveBeenCalledWith(task(6));

    openEdit(task(6));
    forms.editForm.title = 'Changed';
    forms.requestCloseEdit(openDetails);
    expect(forms.isEditModalOpen()).toBe(true);
    expect(forms.isEditDiscardConfirmationOpen()).toBe(true);
    forms.cancelDiscardEdit();
    expect(forms.isEditDiscardConfirmationOpen()).toBe(false);

    forms.requestCloseEdit(openDetails);
    forms.confirmDiscardEdit(openDetails);
    expect(forms.isEditModalOpen()).toBe(false);
  });

  it('keeps a busy edit open and sends only one PATCH, then closes and reports the saved task', () => {
    const patch = new Subject<unknown>();
    responses[record(8)] = of(task(8));
    responses[`PATCH ${record(8)}`] = patch;
    const saved = vi.fn();

    openEdit(task(8), task(8));
    forms.submitEditTask(saved);
    forms.submitEditTask(saved);
    forms.requestCloseEdit(vi.fn());

    expect(api.patch).toHaveBeenCalledTimes(1);
    expect(forms.isEditModalOpen()).toBe(true);
    expect(forms.isEditDiscardConfirmationOpen()).toBe(false);
    patch.next({});
    expect(forms.isEditModalOpen()).toBe(false);
    expect(saved).toHaveBeenCalledWith(task(8), 8);
    expect(toast.success).toHaveBeenCalled();
  });

  it('omits unchanged assignments from the edit PATCH while sending explicit clears', () => {
    responses[record(30)] = of(task(30, 'Task 30', { parentTaskId: 999, responsibleId: 501, observerIds: [502] }));

    openEdit(task(30));
    forms.editForm.title = 'Title only';
    forms.submitEditTask(vi.fn());
    const titleOnly = api.patch.mock.calls[0][1] as Record<string, unknown>;
    expect(titleOnly).toEqual(expect.objectContaining({ title: 'Title only' }));
    for (const key of ['parentTaskId', 'responsibleId', 'observerIds', 'executorIds']) {
      expect(titleOnly).not.toHaveProperty(key);
    }

    openEdit(task(30));
    Object.assign(forms.editForm, { parentTaskId: null, responsibleUserId: null, observerUserIds: [] });
    forms.submitEditTask(vi.fn());
    expect(api.patch.mock.calls[1][1]).toEqual(
      expect.objectContaining({ parentTaskId: null, responsibleId: null, observerIds: [] }),
    );
  });

  it('needs a title to save an edit, and keeps the form open when the PATCH fails', () => {
    responses[record(12)] = of(task(12));
    responses[`PATCH ${record(12)}`] = new Observable((subscriber) =>
      subscriber.error({ status: 503, detail: 'Busy' }),
    );
    openEdit(task(12));

    forms.editForm.title = '  ';
    forms.submitEditTask(vi.fn());
    expect(toast.warning).toHaveBeenCalled();
    expect(api.patch).not.toHaveBeenCalled();

    forms.editForm.title = 'Named';
    forms.submitEditTask(vi.fn());
    expect(toast.error).toHaveBeenCalledWith('Busy');
    expect(forms.isEditModalOpen()).toBe(true);
    expect(forms.isSubmitting()).toBe(false);
  });

  it('shows a save refused over a newer revision once and reads the task again from its button', () => {
    responses[record(14)] = [of(task(14, 'Old')), of(task(14, 'New'))];
    responses[`PATCH ${record(14)}`] = new Observable((subscriber) =>
      subscriber.error({ status: 409, code: 'revision_conflict', detail: 'Запись уже изменил другой пользователь' }),
    );
    openEdit(task(14));

    forms.submitEditTask(vi.fn());

    expect(api.patch.mock.calls[0][2]).toEqual(expect.objectContaining({ notifyError: false }));
    expect(toast.error).not.toHaveBeenCalled();
    expect(toast.show).toHaveBeenCalledTimes(1);
    expect(toast.show.mock.calls[0][1]).toBe('Запись уже изменил другой пользователь');
    toast.show.mock.calls[0][4].run();
    expect(api.get.mock.calls.filter(([path]) => path === record(14))).toHaveLength(2);
    expect(forms.editForm.title).toBe('New');
    expect(forms.isEditModalOpen()).toBe(true);
  });

  it('creates one task at a time with its co-executors and locks closing while it is sent', () => {
    const post = new Subject<unknown>();
    responses[`POST ${LIST}`] = post;
    const created = vi.fn();

    forms.openCreateTaskModal('task', 5);
    Object.assign(forms.createForm, {
      title: '  New task  ',
      responsibleUserId: 10,
      executorUserIds: [20, 30],
      observerUserIds: [40],
    });
    forms.submitCreateTask(created);
    forms.submitCreateTask(created);
    forms.requestCloseCreate();

    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post.mock.calls[0][1]).toEqual(
      expect.objectContaining({
        title: 'New task',
        projectId: 5,
        typeCode: 'task',
        responsibleId: 10,
        executorIds: [20, 30],
        observerIds: [40],
        attributes: {},
      }),
    );
    expect(forms.isCreateModalOpen()).toBe(true);
    post.next({ id: 100 });
    expect(forms.isCreateModalOpen()).toBe(false);
    expect(created).toHaveBeenCalledWith(null);
  });

  it('starts a subtask under its parent, remembering the parent for the picker', () => {
    forms.openAddSubtaskModal(task(40, 'Parent', { projectId: 3, priority: 'high' }), 'bug', retainParent);

    expect(forms.createForm).toEqual(
      expect.objectContaining({ parentTaskId: 40, projectId: 3, priority: 'high', taskType: 'bug' }),
    );
    expect(retainParent).toHaveBeenCalledWith(40, 'Parent');
    expect(forms.isCreateModalOpen()).toBe(true);
  });

  it('asks before leaving a page with a dirty create form, and lets a clean one go', () => {
    forms.openCreateTaskModal();
    expect(
      forms.canLeaveRecordPage(
        () => false,
        () => '',
        vi.fn(),
      ),
    ).toBe(true);
    expect(forms.isCreateModalOpen()).toBe(false);

    forms.openCreateTaskModal();
    forms.createForm.title = 'Draft';
    const decision = forms.canLeaveRecordPage(
      () => false,
      () => '',
      vi.fn(),
    );
    expect(decision).toBeInstanceOf(Observable);
    expect(
      forms.canLeaveRecordPage(
        () => true,
        () => '',
        vi.fn(),
      ),
    ).toBe(false);
  });
});
