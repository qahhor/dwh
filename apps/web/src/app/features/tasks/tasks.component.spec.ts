import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { Observable, Subject, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { Task } from '../../core/models/task.models';
import { TasksComponent } from './tasks.component';

describe('TasksComponent UI contracts', () => {
  async function createFixture() {
    const api = {
      get: vi.fn((path: string) => of(path === '/tasks'
        ? { items: [], nextCursor: null, hasMore: false }
        : [])),
      post: vi.fn(() => of({})),
      patch: vi.fn(() => of({})),
      delete: vi.fn(() => of({}))
    };
    await TestBed.configureTestingModule({
      imports: [TasksComponent],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: PermissionService, useValue: { canCreate: () => true, canUpdate: () => true, canDelete: () => true, hasPermission: () => true } },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: ActivatedRoute, useValue: { queryParams: of({}) } }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(TasksComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('labels filters, view controls and the task table interaction', async () => {
    const fixture = await createFixture();
    const task: Task = {
      id: 42,
      title: 'Проверить отчёт',
      statusId: 1,
      priority: 'high',
      attributes: {},
      createdAt: '2026-08-30T00:00:00Z'
    };
    fixture.componentInstance.tasks.set([task]);
    fixture.detectChanges();

    const search = fixture.nativeElement.querySelector('#task-search') as HTMLInputElement;
    const region = fixture.nativeElement.querySelector('.table-wrapper[role="region"]') as HTMLElement;
    const row = fixture.nativeElement.querySelector('tr.task-row') as HTMLTableRowElement;

    expect(fixture.nativeElement.querySelector(`label[for="${search.id}"]`)).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[role="group"][aria-label="Режим отображения задач"]')).not.toBeNull();
    expect(region.tabIndex).toBe(0);
    expect(region.querySelector('table')?.getAttribute('aria-label')).toBe('Список задач');
    expect(row.getAttribute('role')).toBe('button');
    expect(row.tabIndex).toBe(0);
    expect(fixture.nativeElement.querySelector('button[aria-label="Редактировать задачу #42"]')).not.toBeNull();
  });

  it('connects create-task labels, required state and shared field names', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.openCreateTaskModal();
    fixture.componentInstance.isCreateSubmitted = true;
    fixture.detectChanges();

    const title = fixture.nativeElement.querySelector('#task-create-title') as HTMLInputElement;
    const error = fixture.nativeElement.querySelector('#task-create-title-error') as HTMLElement;

    expect(fixture.nativeElement.querySelector(`label[for="${title.id}"]`)).not.toBeNull();
    expect(title.required).toBe(true);
    expect(title.getAttribute('aria-invalid')).toBe('true');
    expect(title.getAttribute('aria-describedby')).toBe(error.id);
    expect(fixture.nativeElement.querySelector('[role="group"][aria-label="Тип задачи"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('ui-searchable-select button[aria-label="Родительская задача"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('ui-user-multi-select button[aria-label="Наблюдатели"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('ui-markdown-editor textarea')?.getAttribute('id')).not.toBe('');
  });

  it('labels task dictionaries and confirms destructive actions in-app', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.taskTypes.set([{
      id: 5,
      code: 'review',
      name: 'Проверка',
      icon: 'fact_check',
      color: '#6366f1',
      orderNo: 10,
      isSystem: false
    }]);
    fixture.componentInstance.openSettingsModal();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="tablist"][aria-label="Справочники задач"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="task-type-code"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="task-type-name"]')).not.toBeNull();

    const remove = fixture.nativeElement.querySelector('button[aria-label="Удалить тип задачи Проверка"]') as HTMLButtonElement;
    remove.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Удалить тип задачи «Проверка»?');
  });

  it('renders each subtask action as a native named button', async () => {
    const fixture = await createFixture();
    const parent: Task = {
      id: 10,
      title: 'Родительская задача',
      statusId: 1,
      priority: 'medium',
      attributes: {},
      createdAt: '2026-08-30T00:00:00Z'
    };
    const subtask: Task = {
      ...parent,
      id: 11,
      title: 'Проверить подзадачу'
    };
    fixture.componentInstance.selectedTask.set(parent);
    fixture.componentInstance.taskSubtasks.set([subtask]);
    fixture.detectChanges();

    const action = fixture.nativeElement.querySelector('.subtask-row') as HTMLButtonElement;
    expect(action.tagName).toBe('BUTTON');
    expect(action.type).toBe('button');
    expect(action.getAttribute('aria-label')).toBe('Открыть подзадачу #11: Проверить подзадачу');
  });
});

describe('TasksComponent asynchronous detail and editing state', () => {
  interface ControlledApi {
    get: ReturnType<typeof vi.fn>;
    post: ReturnType<typeof vi.fn>;
    patch: ReturnType<typeof vi.fn>;
    delete: ReturnType<typeof vi.fn>;
  }

  const task = (id: number, title = `Task ${id}`): Task => ({
    id,
    title,
    statusId: 1,
    priority: 'medium',
    attributes: {},
    createdAt: '2026-09-05T00:00:00Z'
  });

  async function createControlledFixture(options: {
    get?: (path: string) => Observable<unknown>;
    post?: (path: string) => Observable<unknown>;
    patch?: (path: string) => Observable<unknown>;
    canComment?: boolean;
  } = {}) {
    const api: ControlledApi = {
      get: vi.fn((path: string) => options.get?.(path) ?? of(path === '/tasks'
        ? { items: [], nextCursor: null, hasMore: false }
        : path === '/iam/users'
          ? { items: [], nextCursor: null, hasMore: false }
          : [])),
      post: vi.fn((path: string) => options.post?.(path) ?? of({})),
      patch: vi.fn((path: string) => options.patch?.(path) ?? of({})),
      delete: vi.fn(() => of({}))
    };
    const permissions = {
      canCreate: vi.fn((form: string) => form === 'tasks.comments' ? options.canComment !== false : true),
      canUpdate: vi.fn(() => true),
      canDelete: vi.fn(() => true),
      hasPermission: vi.fn((form: string, action: string) => form === 'tasks.comments' && action === 'create'
        ? options.canComment !== false
        : true)
    };

    await TestBed.configureTestingModule({
      imports: [TasksComponent],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: PermissionService, useValue: permissions },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } },
        { provide: ActivatedRoute, useValue: { queryParams: of({}) } }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(TasksComponent);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, api };
  }

  it('keeps a failed edit detail request out of the save-ready state and supports retry', async () => {
    const first = new Subject<unknown>();
    const retry = new Subject<unknown>();
    const pending = [first, retry];
    const { fixture, component } = await createControlledFixture({
      get: path => path === '/tasks/7' ? pending.shift()! : of(path === '/tasks'
        ? { items: [], nextCursor: null, hasMore: false }
        : path === '/iam/users' ? { items: [], nextCursor: null, hasMore: false } : [])
    });

    component.openEditModal(task(7, 'Stale row'));
    first.error({ detail: 'offline' });
    fixture.detectChanges();

    expect(component.editingTask).toBeNull();
    expect(component.editLoadError()).toBe(true);
    expect(fixture.nativeElement.querySelector('#task-edit-title')).toBeNull();

    component.retryEditLoad();
    retry.next({ task: task(7, 'Fresh task'), members: [] });
    retry.complete();
    fixture.detectChanges();

    expect(component.editingTask?.title).toBe('Fresh task');
    expect(component.editForm.title).toBe('Fresh task');
  });

  it('builds the edit form from the fresh task and member response instead of the stale row', async () => {
    const detail = new Subject<unknown>();
    const { component } = await createControlledFixture({
      get: path => path === '/tasks/9' ? detail : of(path === '/tasks'
        ? { items: [], nextCursor: null, hasMore: false }
        : path === '/iam/users' ? { items: [], nextCursor: null, hasMore: false } : [])
    });
    const stale = { ...task(9, 'Stale title'), endTime: '2026-09-05T12:00:00Z' };
    const fresh = {
      ...task(9, 'Fresh title'),
      descriptionMarkdown: 'fresh body',
      endTime: '2026-09-05T12:00:37.123Z'
    };

    component.openEditModal(stale);
    detail.next({
      task: fresh,
      members: [
        { taskId: 9, userId: 31, involveKind: 'R', userName: 'Owner', userLogin: 'owner' },
        { taskId: 9, userId: 44, involveKind: 'O', userName: 'Observer', userLogin: 'observer' }
      ]
    });

    expect(component.editingTask).toEqual(fresh);
    expect(component.editForm.title).toBe('Fresh title');
    expect(component.editForm.descriptionMarkdown).toBe('fresh body');
    expect(component.editForm.responsibleUserId).toBe(31);
    expect(component.editForm.observerUserIds).toEqual([44]);
  });

  it('ignores out-of-order detail and comment responses for a previously selected task', async () => {
    const detail1 = new Subject<unknown>();
    const detail2 = new Subject<unknown>();
    const comments1 = new Subject<unknown>();
    const comments2 = new Subject<unknown>();
    const { component } = await createControlledFixture({
      get: path => ({
        '/tasks/1': detail1,
        '/tasks/2': detail2,
        '/tasks/1/comments': comments1,
        '/tasks/2/comments': comments2
      }[path] as Observable<unknown>) ?? of(path === '/tasks'
        ? { items: [], nextCursor: null, hasMore: false }
        : path === '/iam/users' ? { items: [], nextCursor: null, hasMore: false } : [])
    });

    component.openTaskDetails(task(1));
    component.openTaskDetails(task(2));
    detail2.next({ task: task(2, 'Fresh 2'), members: [], subtasks: [], ancestors: [], files: [] });
    comments2.next([{ id: 20, taskId: 2, userId: 1, userName: null, userLogin: null, textMarkdown: 'two', createdAt: '2026-09-05T00:00:00Z' }]);
    detail1.next({ task: task(1, 'Late 1'), members: [], subtasks: [task(11)], ancestors: [], files: [] });
    comments1.next([{ id: 10, taskId: 1, userId: 1, userName: null, userLogin: null, textMarkdown: 'one', createdAt: '2026-09-05T00:00:00Z' }]);

    expect(component.selectedTask()?.id).toBe(2);
    expect(component.selectedTask()?.title).toBe('Fresh 2');
    expect(component.taskSubtasks()).toEqual([]);
    expect(component.comments().map(comment => comment.taskId)).toEqual([2]);
  });

  it('ignores detail and comment responses after the detail modal closes', async () => {
    const detail = new Subject<unknown>();
    const comments = new Subject<unknown>();
    const { component } = await createControlledFixture({
      get: path => path === '/tasks/3' ? detail : path === '/tasks/3/comments' ? comments : of(path === '/tasks'
        ? { items: [], nextCursor: null, hasMore: false }
        : path === '/iam/users' ? { items: [], nextCursor: null, hasMore: false } : [])
    });

    component.openTaskDetails(task(3));
    component.closeTaskDetails();
    detail.next({ task: task(3, 'Late'), members: [], subtasks: [task(30)], ancestors: [], files: [] });
    comments.next([{ id: 30, taskId: 3, userId: 1, userName: null, userLogin: null, textMarkdown: 'late', createdAt: '2026-09-05T00:00:00Z' }]);

    expect(component.selectedTask()).toBeNull();
    expect(component.taskSubtasks()).toEqual([]);
    expect(component.comments()).toEqual([]);
  });

  it('does not attach a late file response to a newly selected task', async () => {
    const attach = new Subject<unknown>();
    const { component } = await createControlledFixture({
      post: path => path === '/tasks/3/files' ? attach : of({})
    });
    const file = { fileId: 'file-1', fileName: 'one.txt', sizeBytes: 3, mimeType: 'text/plain', createdAt: '2026-09-05T00:00:00Z' };

    component.openTaskDetails(task(3));
    component.onTaskFileAttached(file);
    component.openTaskDetails(task(4));
    attach.next({});

    expect(component.taskFiles()).toEqual([]);
  });

  it('keeps comment drafts scoped to their task', async () => {
    const { component } = await createControlledFixture();

    component.openTaskDetails(task(1));
    component.commentDraft = 'draft one';
    component.openTaskDetails(task(2));
    component.commentDraft = 'draft two';
    component.openTaskDetails(task(1));

    expect(component.commentDraft).toBe('draft one');
    component.openTaskDetails(task(2));
    expect(component.commentDraft).toBe('draft two');
  });

  it('prevents duplicate comment posts while the first request is in flight', async () => {
    const post = new Subject<unknown>();
    const { component, api } = await createControlledFixture({
      post: path => path === '/tasks/4/comments' ? post : of({})
    });
    component.openTaskDetails(task(4));
    component.commentDraft = 'hello';

    component.submitComment();
    component.submitComment();

    expect(api.post.mock.calls.filter(([path]) => path === '/tasks/4/comments')).toHaveLength(1);
    expect(component.isCommentSubmitting()).toBe(true);
    post.next({});
    post.complete();
    expect(component.commentDraft).toBe('');
    expect(component.isCommentSubmitting()).toBe(false);
  });

  it('does not post comments without the tasks.comments create permission', async () => {
    const { component, api } = await createControlledFixture({ canComment: false });
    component.openTaskDetails(task(5));
    component.commentDraft = 'not allowed';

    component.submitComment();

    expect(api.post.mock.calls.filter(([path]) => path === '/tasks/5/comments')).toHaveLength(0);
  });

  it('renders a stable fallback when a comment author identity was removed', async () => {
    const { fixture, component } = await createControlledFixture({
      get: path => path === '/tasks/14'
        ? of({ task: task(14), members: [], subtasks: [], ancestors: [], files: [] })
        : path === '/tasks/14/comments'
          ? of([{ id: 14, taskId: 14, userId: 99, userName: null, userLogin: null, textMarkdown: 'kept comment', createdAt: '2026-09-05T00:00:00Z' }])
          : of(path === '/tasks'
            ? { items: [], nextCursor: null, hasMore: false }
            : path === '/iam/users' ? { items: [], nextCursor: null, hasMore: false } : [])
    });

    component.openTaskDetails(task(14));
    fixture.detectChanges();

    const author = fixture.nativeElement.querySelector('.comment-author') as HTMLElement;
    expect(author.textContent).toContain('Удалённый пользователь');
    expect(author.textContent).not.toContain('@null');
  });

  it('asks before discarding a dirty edit but closes an unchanged edit directly', async () => {
    const detailResponses = [
      of({ task: task(6, 'Original'), members: [] }),
      of({ task: task(6, 'Original'), members: [] })
    ];
    const { component } = await createControlledFixture({
      get: path => path === '/tasks/6' ? detailResponses.shift()! : of(path === '/tasks'
        ? { items: [], nextCursor: null, hasMore: false }
        : path === '/iam/users' ? { items: [], nextCursor: null, hasMore: false } : [])
    });

    component.openEditModal(task(6));
    component.requestCloseEdit();
    expect(component.isEditModalOpen()).toBe(false);

    component.openEditModal(task(6));
    component.editForm.title = 'Changed';
    component.requestCloseEdit();
    expect(component.isEditModalOpen()).toBe(true);
    expect(component.isEditDiscardConfirmationOpen()).toBe(true);

    component.confirmDiscardEdit();
    expect(component.isEditModalOpen()).toBe(false);
  });

  it('uses one detail or edit modal at a time and returns to details after cancel', async () => {
    const { component } = await createControlledFixture({
      get: path => path === '/tasks/16'
        ? of({ task: task(16, 'Fresh'), members: [], subtasks: [], ancestors: [], files: [] })
        : path === '/tasks/16/comments'
          ? of([])
          : of(path === '/tasks'
            ? { items: [], nextCursor: null, hasMore: false }
            : path === '/iam/users' ? { items: [], nextCursor: null, hasMore: false } : [])
    });

    component.openTaskDetails(task(16));
    component.openEditModal(task(16));
    expect(component.selectedTask()).toBeNull();
    expect(component.isEditModalOpen()).toBe(true);

    component.requestCloseEdit();
    expect(component.isEditModalOpen()).toBe(false);
    expect(component.selectedTask()?.id).toBe(16);
  });

  it('keeps a busy edit open and sends only one PATCH request', async () => {
    const patch = new Subject<unknown>();
    const { component, api } = await createControlledFixture({
      get: path => path === '/tasks/8' ? of({ task: task(8), members: [] }) : of(path === '/tasks'
        ? { items: [], nextCursor: null, hasMore: false }
        : path === '/iam/users' ? { items: [], nextCursor: null, hasMore: false } : []),
      patch: path => path === '/tasks/8' ? patch : of({})
    });
    component.openEditModal(task(8));

    component.submitEditTask();
    component.submitEditTask();
    component.requestCloseEdit();

    expect(api.patch.mock.calls.filter(([path]) => path === '/tasks/8')).toHaveLength(1);
    expect(component.isEditModalOpen()).toBe(true);
    expect(component.isEditDiscardConfirmationOpen()).toBe(false);
  });

  it('makes every edit control inert while its PATCH snapshot is pending', async () => {
    const patch = new Subject<unknown>();
    const { fixture, component } = await createControlledFixture({
      get: path => path === '/tasks/19' ? of({ task: task(19), members: [] }) : of(path === '/tasks'
        ? { items: [], nextCursor: null, hasMore: false }
        : path === '/iam/users' ? { items: [], nextCursor: null, hasMore: false } : []),
      patch: path => path === '/tasks/19' ? patch : of({})
    });
    component.taskTypes.set([{ id: 1, code: 'task', name: 'Task', icon: 'task', color: '#000', orderNo: 1, isSystem: true }]);
    component.openEditModal(task(19));
    component.editForm.title = 'Submitted snapshot';
    component.submitEditTask();
    fixture.detectChanges();

    const form = fixture.nativeElement.querySelector('fieldset.task-edit-form') as HTMLFieldSetElement | null;
    expect(form).not.toBeNull();
    expect(form?.disabled).toBe(true);
    const controls = Array.from(form?.querySelectorAll('input, select, textarea, button') || []) as HTMLElement[];
    expect(controls.length).toBeGreaterThan(5);
    expect(controls.every(control => control.matches(':disabled'))).toBe(true);
  });

  it('locks create controls, dismissal and duplicate POSTs while creation is pending', async () => {
    const post = new Subject<unknown>();
    const { fixture, component, api } = await createControlledFixture({
      post: path => path === '/tasks' ? post : of({})
    });
    component.taskTypes.set([{ id: 1, code: 'task', name: 'Task', icon: 'task', color: '#000', orderNo: 1, isSystem: true }]);
    component.openCreateTaskModal();
    component.createForm.title = 'New task';
    component.submitCreateTask();
    component.submitCreateTask();
    component.requestCloseCreate();
    fixture.detectChanges();

    expect(api.post.mock.calls.filter(([path]) => path === '/tasks')).toHaveLength(1);
    expect(component.isCreateModalOpen()).toBe(true);
    const form = fixture.nativeElement.querySelector('fieldset.task-create-form') as HTMLFieldSetElement | null;
    expect(form).not.toBeNull();
    expect(form?.disabled).toBe(true);
    const controls = Array.from(form?.querySelectorAll('input, select, textarea, button') || []) as HTMLElement[];
    expect(controls.length).toBeGreaterThan(5);
    expect(controls.every(control => control.matches(':disabled'))).toBe(true);
  });

  it('preserves stale list rows and exposes retry after a replacement load fails', async () => {
    const failedLoad = new Subject<unknown>();
    const listLoads = [
      of({ items: [task(1, 'Existing')], nextCursor: null, hasMore: false }),
      failedLoad,
      of({ items: [task(2, 'Recovered')], nextCursor: null, hasMore: false })
    ];
    const { component } = await createControlledFixture({
      get: path => path === '/tasks' ? listLoads.shift()! : of(path === '/iam/users'
        ? { items: [], nextCursor: null, hasMore: false }
        : [])
    });

    component.loadTasks(true);
    failedLoad.error({ detail: 'offline' });

    expect(component.tasks().map(item => item.title)).toEqual(['Existing']);
    expect(component.listLoadError()).toBe(true);

    component.retryTaskList();
    expect(component.tasks().map(item => item.title)).toEqual(['Recovered']);
    expect(component.listLoadError()).toBe(false);
  });

  it('ignores all outstanding task responses after component destruction', async () => {
    const list = new Subject<unknown>();
    const detail = new Subject<unknown>();
    const comments = new Subject<unknown>();
    const { fixture, component } = await createControlledFixture({
      get: path => path === '/tasks' ? list : path === '/tasks/12' ? detail : path === '/tasks/12/comments' ? comments : of(path === '/iam/users'
        ? { items: [], nextCursor: null, hasMore: false }
        : [])
    });
    component.openTaskDetails(task(12));
    fixture.destroy();

    list.next({ items: [task(90)], nextCursor: null, hasMore: false });
    detail.next({ task: task(12, 'Late'), members: [], subtasks: [task(13)], ancestors: [], files: [] });
    comments.next([{ id: 1, taskId: 12, userId: 1, userName: null, userLogin: null, textMarkdown: 'late', createdAt: '2026-09-05T00:00:00Z' }]);

    expect(component.tasks()).toEqual([]);
    expect(component.taskSubtasks()).toEqual([]);
    expect(component.comments()).toEqual([]);
  });

  it('ignores an edit detail response after component destruction', async () => {
    const edit = new Subject<unknown>();
    const { fixture, component } = await createControlledFixture({
      get: path => path === '/tasks/18' ? edit : of(path === '/tasks'
        ? { items: [], nextCursor: null, hasMore: false }
        : path === '/iam/users' ? { items: [], nextCursor: null, hasMore: false } : [])
    });
    component.openEditModal(task(18));
    fixture.destroy();

    edit.next({ task: task(18, 'Late edit'), members: [] });

    expect(component.editingTask).toBeNull();
  });
});
