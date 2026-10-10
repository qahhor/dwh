import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { Task, TaskStatus } from '@core/models/task.models';
import { User } from '@core/models/auth.models';
import { TasksComponent } from './tasks.component';
import { Screen, inScreen, redraw } from '@testing/in-screen';
import { registryProviders, TASKS_META } from '@testing/registry-meta';

/*
 * The screen as a whole: how its children, the address and the requests work together. What a child
 * shows on its own is pinned in its spec, and the list, detail and form logic in the service specs.
 */

type Params = Record<string, unknown>;
type Read = (path: string, params: Params) => Observable<unknown> | undefined;

const EMPTY_PAGE = { items: [], nextCursor: null, hasMore: false, totalReturned: 0 };
/** The task list on the general runtime (ADR-0032 8) and one task's record. */
const LIST = '/entities/ms.tasks';
const record = (id: number) => `${LIST}/${id}`;
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
const page = <T>(items: T[], nextCursor: string | null = null) => ({
  items,
  nextCursor,
  hasMore: nextCursor !== null,
  totalReturned: items.length,
});
const user = (id: number, name: string): User => ({
  id,
  name,
  login: `user${id}`,
  email: `u${id}@example.com`,
  state: 'A',
  language: 'ru',
  timezone: 'Asia/Tashkent',
  attributes: {},
  is2faEnabled: false,
  forcePasswordChange: false,
  createdAt: '2026-09-05T00:00:00Z',
  modifiedAt: '2026-09-05T00:00:00Z',
});
const users = (...items: User[]) => ({ items, nextCursor: null, hasMore: false, totalReturned: items.length });
const STATUSES: TaskStatus[] = [
  { id: 1, code: 's1', name: 'Active', color: '#ff0000', terminal: false, sortOrder: 1 },
  { id: 2, code: 's2', name: 'Done', color: '#00ff00', terminal: true, sortOrder: 2 },
];

/** The screen over an API that answers a path through `get`, and the list, users and the rest empty. */
async function setup(
  options: {
    get?: Read;
    post?: (path: string, body?: unknown) => Observable<unknown> | undefined;
    patch?: (path: string, body?: unknown) => Observable<unknown> | undefined;
    canComment?: boolean;
    route?: Partial<ActivatedRoute>;
  } = {},
) {
  const api = {
    get: vi.fn(
      (path: string, params: Params = {}) =>
        options.get?.(path, params) ?? of(path.startsWith('/entities/') ? EMPTY_PAGE : []),
    ),
    post: vi.fn((path: string, body?: unknown) => options.post?.(path, body) ?? of({})),
    patch: vi.fn((path: string, body?: unknown) => options.patch?.(path, body) ?? of({})),
    delete: vi.fn(() => of({})),
  };
  const toast = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), show: vi.fn() };
  const canComment = (form: string) => form !== 'tasks.comments' || options.canComment !== false;
  await TestBed.configureTestingModule({
    imports: [TasksComponent],
    providers: [
      { provide: ApiService, useValue: api },
      ...registryProviders(TASKS_META),
      {
        provide: PermissionService,
        useValue: {
          canCreate: canComment,
          canUpdate: () => true,
          canDelete: () => true,
          hasPermission: (form: string) => canComment(form),
        },
      },
      { provide: ToastService, useValue: toast },
      { provide: ActivatedRoute, useValue: options.route ?? { queryParams: of({}) } },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(TasksComponent);
  redraw(fixture);
  return { fixture, component: fixture.componentInstance, api, toast, screen: inScreen(fixture.nativeElement) };
}

/** The reads of the list itself: a card's subtasks are read from the same list, by their parent. */
const listCalls = (api: { get: ReturnType<typeof vi.fn> }, match: (params: Params) => boolean = () => true) =>
  api.get.mock.calls.filter(
    ([path, params]) =>
      path === LIST &&
      !String((params as Params)?.['filter'] ?? '').includes('parentTaskId') &&
      match(params as Params),
  );
const button = (screen: Screen, label: string) =>
  screen.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement;
const radio = (screen: Screen, group: string, label: string) =>
  ([...screen.querySelectorAll(`${group} [role="radio"]`)] as HTMLElement[]).find((node) =>
    node.textContent?.trim().includes(label),
  )!;
const optionLabels = () =>
  ([...document.querySelectorAll('.smt-select__option')] as HTMLElement[]).map((option) => option.textContent ?? '');
const pagerStatus = (screen: Screen) =>
  (screen.querySelector('ui-pagination [role="status"]') as HTMLElement).textContent?.replace(/\s+/g, ' ').trim();

/** Types into the search of the open select and waits out its pause. */
async function searchSelect(fixture: ComponentFixture<TasksComponent>, text: string) {
  const input = document.querySelector('.smt-select__search-input') as HTMLInputElement;
  input.value = text;
  input.dispatchEvent(new Event('input'));
  await vi.advanceTimersByTimeAsync(300);
  redraw(fixture);
}

/** Opens a picker by its trigger, searches it and picks the option with the label. */
async function pick(fixture: ComponentFixture<TasksComponent>, trigger: HTMLElement, text: string, label: string) {
  trigger.click();
  redraw(fixture);
  await searchSelect(fixture, text);
  ([...document.querySelectorAll('.smt-select__option')] as HTMLElement[])
    .find((option) => option.textContent?.includes(label))!
    .click();
}

/** A server page of comments holding the whole thread (plan item 3.5). */
const thread = <T>(items: T[]) => ({ items, hasMore: false, totalEstimated: items.length, totalExact: true });

describe('TasksComponent', () => {
  afterEach(() => vi.useRealTimers());

  it('labels the filters, the view switch and the task table', async () => {
    const { fixture, component, screen } = await setup();
    component.tasks.set([task(42, 'Проверить отчёт')]);
    redraw(fixture);
    const search = screen.querySelector('#task-search') as HTMLInputElement;
    const region = screen.querySelector('.table-card[role="region"]') as HTMLElement;

    expect(search.getAttribute('aria-label')).toBe('Поиск задач');
    expect(screen.querySelector('[role="radiogroup"][aria-label="Режим отображения задач"]')).not.toBeNull();
    expect(region.getAttribute('aria-label')).toBe('Таблица задач');
    expect(region.querySelector('[role="table"]')?.getAttribute('aria-label')).toBe('Список задач');
    expect(button(screen, 'Открыть задачу #42: Проверить отчёт')).not.toBeNull();
    expect(button(screen, 'Редактировать задачу #42')).not.toBeNull();
  });

  it('opens a task only through the actions of its row and card, and edits it in one dialog', async () => {
    const { fixture, component, screen } = await setup();
    component.statuses.set(STATUSES);
    component.tasks.set([task(42, 'Проверить отчёт')]);
    redraw(fixture);
    const opened: number[] = [];
    const open = vi.spyOn(component, 'openTaskDetails').mockImplementation((t) => void opened.push(t.id));
    const row = screen.querySelector('[role="rowgroup"] > [role="row"]') as HTMLElement;

    (row.querySelector('.inline-status-select [role="combobox"]') as HTMLButtonElement).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }),
    );
    button(screen, 'Редактировать задачу #42').dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }),
    );
    expect(opened).toEqual([]);
    (row.querySelector('.task-title-open') as HTMLButtonElement).click();
    expect(opened).toEqual([42]);

    button(screen, 'Редактировать задачу #42').click();
    redraw(fixture);
    expect(screen.querySelectorAll('[role="dialog"]')).toHaveLength(1);
    expect(screen.querySelector('[role="dialog"]')?.textContent).toContain('Редактирование задачи');
    component.requestCloseEdit();

    radio(screen, '.view-header__title-row', 'Канбан').click();
    redraw(fixture);
    button(screen, 'Переместить задачу #42 вперёд').dispatchEvent(
      new KeyboardEvent('keydown', { key: ' ', bubbles: true }),
    );
    expect(opened).toEqual([42]);
    open.mockRestore();
  });

  it('explains that the export takes every accessible task and ignores the filters', async () => {
    const { fixture, component, screen } = await setup();
    component.filterService.showExportMenu = true;
    redraw(fixture);
    const menu = screen.querySelector('.export-popover') as HTMLElement;
    expect(menu.textContent).toContain('Экспорт всех доступных задач');
    expect(menu.textContent).toContain('Текущие фильтры не применяются');
  });

  it('opens the list on the project of the address, and a malformed record id as not found', async () => {
    const { component, api } = await setup({
      route: { queryParams: of({ project_id: '5' }), paramMap: of(convertToParamMap({ id: 'not-a-number' })) },
    });

    expect(component.filterService.selectedProjectId).toBe(5);
    expect(component.routeRecordId()).toBe('not-a-number');
    expect(component.detailsService.detailNotFound()).toBe(true);
    expect(api.get.mock.calls.some(([path]) => String(path).includes('not-a-number'))).toBe(false);
  });

  it('creates a task: asks for the title, locks the dialog while sending, then reloads the list', async () => {
    const post = new Subject<unknown>();
    const { fixture, component, api, screen } = await setup({ post: (path) => (path === LIST ? post : undefined) });
    component.openCreateTaskModal();
    redraw(fixture);
    TestBed.tick();
    const title = screen.querySelector('#task-create-title') as HTMLInputElement;
    const error = () => screen.querySelector('.task-create-form .smt-control__error')?.textContent ?? '';

    title.dispatchEvent(new Event('blur'));
    redraw(fixture);
    TestBed.tick();
    expect(error()).toContain('Обязательное поле');
    title.value = 'Новая задача';
    title.dispatchEvent(new Event('input'));
    redraw(fixture);
    TestBed.tick();
    expect(component.createForm.title).toBe('Новая задача');
    expect(error()).toBe('');

    Object.assign(component.createForm, { responsibleUserId: 10, executorUserIds: [20, 30], observerUserIds: [40] });
    component.submitCreateTask();
    component.submitCreateTask();
    component.requestCloseCreate();
    redraw(fixture);
    expect(api.post.mock.calls.filter(([path]) => path === LIST)).toHaveLength(1);
    expect(api.post.mock.calls[0][1]).toEqual(
      expect.objectContaining({ title: 'Новая задача', responsibleId: 10, executorIds: [20, 30], observerIds: [40] }),
    );
    expect((screen.querySelector('fieldset.task-create-form') as HTMLFieldSetElement).disabled).toBe(true);

    const listReads = listCalls(api).length;
    post.next({ id: 100 });
    expect(component.formsService.isCreateModalOpen()).toBe(false);
    expect(listCalls(api)).toHaveLength(listReads + 1);
  });

  it('asks in the dialog before deleting a dictionary item', async () => {
    const { fixture, component, screen } = await setup();
    component.taskTypes.set([
      { id: 5, code: 'review', name: 'Проверка', icon: 'fact_check', color: '#6366f1', sortOrder: 10, system: false },
    ]);
    component.dictService.openSettingsModal();
    redraw(fixture);

    expect(screen.querySelector('[role="tablist"][aria-label="Справочники задач"]')).not.toBeNull();
    button(screen, 'Удалить тип задачи Проверка').click();
    redraw(fixture);
    await fixture.whenStable();
    const dialog = document.querySelector('.smt-modal-confirm') as HTMLElement;
    expect(dialog.closest('[role="alertdialog"]')).not.toBeNull();
    expect(dialog.textContent).toContain('Удалить тип задачи «Проверка»?');
    document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove());
  });

  it('opens a card with its comments, naming a removed author and posting only with the right to', async () => {
    const removedAuthor = {
      id: 1,
      taskId: 14,
      userId: 99,
      userName: null,
      userLogin: null,
      textMarkdown: 'kept',
      createdAt: '2026-09-05T00:00:00Z',
    };
    const { fixture, component, api, screen } = await setup({
      canComment: false,
      get: (path) =>
        path === record(14) ? of(task(14)) : path === '/tasks/14/comments' ? of(thread([removedAuthor])) : undefined,
    });

    component.openTaskDetails(task(14));
    redraw(fixture);
    const author = screen.querySelector('.comment-author') as HTMLElement;
    expect(author.textContent).toContain('Удалённый пользователь');
    expect(author.textContent).not.toContain('@null');

    component.commentDraft = 'not allowed';
    component.submitComment();
    // The only change sent is the viewed mark of the opened card, never the comment.
    expect(api.post.mock.calls.map((call: unknown[]) => call[0])).not.toContain('/tasks/14/comments');
    expect(api.post.mock.calls.every((call: unknown[]) => String(call[0]).endsWith('/view'))).toBe(true);
  });

  it('uses one detail or edit dialog at a time and returns to the card after cancel', async () => {
    const { component } = await setup({
      get: (path) => (path === record(16) ? of(task(16, 'Fresh')) : undefined),
    });

    component.openTaskDetails(task(16));
    component.openEditModal(task(16));
    expect(component.selectedTask()).toBeNull();
    expect(component.isEditModalOpen()).toBe(true);

    component.requestCloseEdit();
    expect(component.isEditModalOpen()).toBe(false);
    expect(component.selectedTask()?.id).toBe(16);
  });

  it('closes an open selector on Escape without dismissing its editor', async () => {
    const { fixture, component, screen } = await setup({
      get: (path) => (path === record(61) ? of(task(61, 'Fresh')) : undefined),
    });
    component.openEditModal(task(61));
    redraw(fixture);
    const selector = screen.querySelector('smt-select button[id$="-responsible"]') as HTMLButtonElement;
    selector.click();
    redraw(fixture);
    expect(selector.getAttribute('aria-expanded')).toBe('true');

    (document.querySelector('.smt-select__search-input') as HTMLInputElement).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
    redraw(fixture);

    expect(selector.getAttribute('aria-expanded')).toBe('false');
    expect(component.isEditModalOpen()).toBe(true);
    expect(screen.querySelectorAll('[role="dialog"]')).toHaveLength(1);
  });

  it('offers recovery for filtered and first empty states, outside the table rows', async () => {
    const { fixture, component, screen } = await setup();
    const empty = () => screen.querySelector('.empty-state-cell') as HTMLElement;
    expect(empty().closest('[role="rowgroup"]')).toBeNull();
    expect(empty().querySelector('button')?.textContent).toContain('Новая задача');

    component.searchQuery = 'missing';
    redraw(fixture);
    expect(empty().querySelector('button')?.textContent).toContain('Сбросить все фильтры');
    radio(screen, '.view-header__title-row', 'Канбан').click();
    redraw(fixture);
    expect(screen.querySelector('.kanban-board')?.innerHTML).toContain('Сбросить все фильтры');

    component.list.resetFilters();
    component.filterService.viewMode = 'table';
    redraw(fixture);
    expect(empty().textContent).toContain('Новая задача');
  });

  it('ignores every outstanding response once the screen is gone', async () => {
    const [list, detail, comments, edit] = [1, 2, 3, 4].map(() => new Subject<unknown>());
    const reads: Record<string, Subject<unknown>> = {
      [LIST]: list,
      [record(12)]: detail,
      '/tasks/12/comments': comments,
      [record(18)]: edit,
    };
    const { fixture, component } = await setup({ get: (path) => reads[path] });
    component.openTaskDetails(task(12));
    component.openEditModal(task(18));
    fixture.destroy();

    list.next(page([task(90)]));
    detail.next(task(12, 'Late'));
    comments.next([{ id: 1, taskId: 12, userId: 1, textMarkdown: 'late' }]);
    edit.next(task(18, 'Late edit'));

    expect(component.tasks()).toEqual([]);
    expect(component.taskSubtasks()).toEqual([]);
    expect(component.comments()).toEqual([]);
    expect(component.formsService.editingTask).toBeNull();
  });

  it('updates a priority on the server and in the row', async () => {
    const { component, api } = await setup();
    component.tasks.set([task(42)]);
    component.updatePriority(42, 'critical');
    expect(api.patch).toHaveBeenCalledWith(
      record(42),
      { priority: 'critical' },
      { notifyError: false, ifMatch: undefined },
    );
    expect(component.tasks()[0].priority).toBe('critical');
  });

  it('shows a refused priority change once and reads the tasks again from its button (plan item 3.6)', async () => {
    const conflict = { status: 409, code: 'revision_conflict', detail: 'Запись уже изменил другой пользователь' };
    const { component, api, toast } = await setup({ patch: () => throwError(() => conflict) });
    component.tasks.set([{ ...task(42), revision: 3 }]);

    component.updatePriority(42, 'critical');

    expect(api.patch).toHaveBeenCalledWith(record(42), { priority: 'critical' }, { notifyError: false, ifMatch: 3 });
    expect(toast.error).not.toHaveBeenCalled();
    expect(toast.show).toHaveBeenCalledTimes(1);
    expect(component.tasks()[0].priority).not.toBe('critical');
    const reads = listCalls(api).length;
    toast.show.mock.calls[0][4].run();
    expect(listCalls(api).length).toBe(reads + 1);
  });
});

describe('TasksComponent list', () => {
  afterEach(() => vi.useRealTimers());

  it('searches 350 ms after typing and at once on Enter, without a delayed duplicate', async () => {
    vi.useFakeTimers();
    const { api, screen } = await setup();
    const initial = listCalls(api).length;
    const input = screen.querySelector('#task-search') as HTMLInputElement;
    input.value = 'alpha';
    input.dispatchEvent(new Event('input'));
    await vi.advanceTimersByTimeAsync(349);
    expect(listCalls(api)).toHaveLength(initial);
    await vi.advanceTimersByTimeAsync(1);
    expect(listCalls(api, (params) => params['q'] === 'alpha')).toHaveLength(1);

    input.value = 'beta';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(listCalls(api, (params) => params['q'] === 'beta')).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(350);
    expect(listCalls(api, (params) => params['q'] === 'beta')).toHaveLength(1);
  });

  it('drops the old list as soon as a search is typed, and the field clear loads the unfiltered page', async () => {
    vi.useFakeTimers();
    const initial = new Subject<unknown>();
    const { fixture, component, api, screen } = await setup({
      get: (path, params) => (path === LIST ? (params['q'] ? of(page([task(70, 'Filtered')])) : initial) : undefined),
    });
    const input = screen.querySelector('#task-search') as HTMLInputElement;
    input.value = 'current';
    input.dispatchEvent(new Event('input'));
    initial.next(page([task(69, 'Old answer')]));
    expect(component.tasks()).toEqual([]);

    await vi.advanceTimersByTimeAsync(350);
    redraw(fixture);
    // The field's own named clear button.
    const clear = input.closest('smt-input')?.querySelector('button.smt-input__action') as HTMLButtonElement;
    expect(clear.getAttribute('aria-label')).toBeTruthy();
    clear.click();
    expect(listCalls(api, (params) => params['q'] === undefined).length).toBeGreaterThan(1);
    expect(component.searchQuery).toBe('');
  });

  it('hides a failed page and blocks Next while a search is pending', async () => {
    vi.useFakeTimers();
    const { fixture, screen } = await setup({
      get: (path, params) =>
        path !== LIST
          ? undefined
          : params['cursor'] === 'c50'
            ? throwError(() => ({ status: 503 }))
            : of({ ...page([task(1)], 'c50'), totalEstimated: 60 }),
    });
    button(screen, 'Следующая страница').click();
    redraw(fixture);
    expect(screen.querySelector('#tasks-load-error')).not.toBeNull();

    const input = screen.querySelector('#task-search') as HTMLInputElement;
    input.value = 'pending';
    input.dispatchEvent(new Event('input'));
    redraw(fixture);
    expect(button(screen, 'Следующая страница').disabled).toBe(true);
    expect(screen.querySelector('#tasks-load-error')).toBeNull();
  });

  it('pages through all 125 server-paged tasks with the visible controls, without duplicates', async () => {
    const serverPage = (start: number, end: number, nextCursor: string | null) => ({
      ...page(
        Array.from({ length: end - start + 1 }, (_, index) => task(start + index)),
        nextCursor,
      ),
      // The registry list counts the whole list on its first page (ADR-0016).
      totalEstimated: 125,
    });
    const cursors: Array<unknown> = [];
    const { fixture, component, screen } = await setup({
      get: (path, params) => {
        if (path !== LIST) return undefined;
        cursors.push(params['cursor']);
        const cursor = params['cursor'];
        return of(
          cursor === 'c100'
            ? serverPage(101, 125, null)
            : cursor === 'c50'
              ? serverPage(51, 100, 'c100')
              : serverPage(1, 50, 'c50'),
        );
      },
    });

    const seen = component.tasks().map((item) => item.id);
    const ranges = [pagerStatus(screen)];
    for (const expectedPage of [2, 3]) {
      expect(button(screen, 'Следующая страница').disabled).toBe(false);
      button(screen, 'Следующая страница').click();
      redraw(fixture);
      seen.push(...component.tasks().map((item) => item.id));
      ranges.push(pagerStatus(screen));
      expect(component.list.taskPager.page()).toBe(expectedPage);
    }

    expect(cursors).toEqual([undefined, 'c50', 'c100']);
    expect(seen).toEqual(Array.from({ length: 125 }, (_, index) => index + 1));
    expect(screen.textContent).toContain('#125');
    expect(ranges).toEqual(['Показано 1–50 из 125', 'Показано 51–100 из 125', 'Показано 101–125 из 125']);
    // 125 rows through the DOM: slower than the default 5 s budget when coverage instruments the run.
  }, 20_000);

  it('keeps active and all filters visible and equivalent in table and kanban, including after a terminal move', async () => {
    const active = task(1, 'Active task');
    const done = task(2, 'Done task', { statusCode: 's2' });
    const { fixture, component, screen } = await setup({
      get: (path, params) =>
        path === '/entities/ms.task_statuses'
          ? of(page(STATUSES))
          : path === LIST
            ? // "Active" is the condition terminal = false of the list's filter; "all" names none.
              of(page(String(params['filter'] ?? '').includes('terminal') ? [active] : [active, done]))
            : undefined,
    });
    const statusRadio = (label: string) =>
      ([...screen.querySelectorAll('.toolbar .status-filter [role="radio"]')] as HTMLElement[]).find(
        (node) => node.textContent?.trim() === label,
      )!;

    expect(component.tasks().map((item) => item.id)).toEqual([1]);
    statusRadio('Все').click();
    redraw(fixture);
    expect(component.tasks().map((item) => item.id)).toEqual([1, 2]);

    component.filterService.viewMode = 'kanban';
    redraw(fixture);
    expect(screen.querySelector('.toolbar [role="radiogroup"][aria-label="Фильтр по статусу"]')).not.toBeNull();
    statusRadio('Активные').click();
    redraw(fixture);
    expect(component.tasks().map((item) => item.id)).toEqual([1]);

    component.updateStatus(1, 's2');
    expect(component.tasks()).toEqual([]);
  });

  it('keeps the page stable across rapid Next clicks and retries the failed cursor from the alert', async () => {
    const [firstNext, retryNext] = [new Subject<unknown>(), new Subject<unknown>()];
    const cursorReads = [firstNext, retryNext];
    const { fixture, component, api, screen } = await setup({
      get: (path, params) =>
        path !== LIST ? undefined : params['cursor'] ? cursorReads.shift() : of(page([task(1)], 'c50')),
    });

    button(screen, 'Следующая страница').click();
    redraw(fixture);
    expect(button(screen, 'Следующая страница').disabled).toBe(true);
    button(screen, 'Следующая страница').click();
    expect(listCalls(api, (params) => params['cursor'] === 'c50')).toHaveLength(1);
    expect(component.list.taskPager.page()).toBe(1);

    firstNext.error({ status: 503 });
    redraw(fixture);
    expect(component.list.taskPager.page()).toBe(1);
    expect(component.tasks().map((item) => item.id)).toEqual([1]);
    ([...screen.querySelectorAll('#tasks-load-error button')] as HTMLButtonElement[])
      .find((node) => node.textContent?.includes('Повторить'))!
      .click();
    retryNext.next(page([task(51)]));
    redraw(fixture);

    expect(listCalls(api, (params) => params['cursor'] === 'c50')).toHaveLength(2);
    expect(component.list.taskPager.page()).toBe(2);
    expect(component.tasks().map((item) => item.id)).toEqual([51]);
  });

  it('keeps Previous on a later page once its last active task becomes final', async () => {
    const { fixture, component, screen } = await setup({
      get: (path, params) =>
        path === '/entities/ms.task_statuses'
          ? of(page(STATUSES))
          : path === LIST
            ? of(params['cursor'] === 'c50' ? page([task(51)]) : page([task(1)], 'c50'))
            : undefined,
    });
    button(screen, 'Следующая страница').click();
    redraw(fixture);
    component.updateStatus(51, 's2');
    redraw(fixture);

    expect(component.list.taskPager.page()).toBe(2);
    expect(component.tasks()).toEqual([]);
    expect(button(screen, 'Предыдущая страница').disabled).toBe(false);
  });
});

describe('TasksComponent pickers', () => {
  afterEach(() => vi.useRealTimers());

  it('picks a searched user and a parent outside the current task page when creating', async () => {
    vi.useFakeTimers();
    const parent = task(999, 'Outside filtered page');
    const searched: Record<string, User> = { user501: user(501, 'Remote User'), user502: user(502, 'Remote Observer') };
    const { fixture, component, api, screen } = await setup({
      get: (path, params) => {
        const q = params['q'] as string | undefined;
        if (path === '/entities/md.users') return of(q && searched[q] ? users(searched[q]) : users());
        if (path === LIST) return of(page(q === 'Outside' ? [parent] : [task(1)]));
        return undefined;
      },
    });
    component.openCreateTaskModal();
    redraw(fixture);
    // Let the opened dialog settle before typing.
    await vi.advanceTimersByTimeAsync(0);
    redraw(fixture);
    const responsible = screen.querySelector('smt-select button[id$="-responsible"]') as HTMLButtonElement;
    const parentTrigger = screen.querySelector('smt-select button[id$="-parent"]') as HTMLElement;
    const observers = screen.querySelector('smt-multi-select button[aria-label="Наблюдатели"]') as HTMLElement;

    await pick(fixture, responsible, 'user501', 'Remote User');
    await pick(fixture, parentTrigger, 'Outside', 'Outside filtered page');
    await pick(fixture, observers, 'user502', 'Remote Observer');

    expect(component.createForm.responsibleUserId).toBe(501);
    expect(component.createForm.parentTaskId).toBe(999);
    expect(component.createForm.observerUserIds).toEqual([502]);
    // The parent search ignores the list's own filters.
    expect(listCalls(api, (p) => p['q'] === 'Outside' && p['filter'] === undefined)).toHaveLength(1);
    redraw(fixture);
    // The pickers name what was chosen, whatever their lists show now.
    expect(responsible.textContent).toContain('Remote User');
    expect(parentTrigger.textContent).toContain('Outside filtered page');
    expect(button(screen, 'Удалить Remote Observer')).not.toBeNull();
  });

  it('keeps the members of an edited task named when the user lookup is denied', async () => {
    vi.useFakeTimers();
    const members = [
      { taskId: 40, userId: 501, involveKind: 'R', userName: 'Scoped Owner', userLogin: 'owner501' },
      { taskId: 40, userId: 502, involveKind: 'O', userName: 'Scoped Observer', userLogin: 'observer502' },
    ];
    const { fixture, component, screen } = await setup({
      get: (path) =>
        path === record(40)
          ? of(task(40, 'Task 40', { responsibleId: 501, observerIds: [502] }))
          : path === '/tasks/40/members'
            ? of(members)
            : path === '/entities/md.users'
              ? throwError(() => ({ status: 403 }))
              : undefined,
    });
    component.openEditModal(task(40));
    redraw(fixture);
    const responsible = screen.querySelector('smt-select button[id$="-responsible"]') as HTMLButtonElement;
    responsible.click();
    await vi.advanceTimersByTimeAsync(300);
    redraw(fixture);

    expect(component.editForm.responsibleUserId).toBe(501);
    expect(component.editForm.observerUserIds).toEqual([502]);
    expect(responsible.textContent).toContain('Scoped Owner');
    expect(button(screen, 'Удалить Scoped Observer')).not.toBeNull();
    expect(document.querySelector('.smt-select__error[role="alert"]')).not.toBeNull();
  });

  it('lets a fresh card name a member instead of the name an older card left', async () => {
    const card = (userName: string) => of([{ taskId: 60, userId: 501, involveKind: 'R', userName, userLogin: 'u501' }]);
    const cards = [card('Old Name'), card('Fresh Name')];
    const { fixture, component, screen } = await setup({
      get: (path) =>
        path === record(60)
          ? of(task(60, 'Task 60', { responsibleId: 501 }))
          : path === '/tasks/60/members'
            ? cards.shift()
            : undefined,
    });
    component.openEditModal(task(60));
    component.requestCloseEdit();
    component.openEditModal(task(60));
    redraw(fixture);

    const responsible = screen.querySelector('smt-select button[id$="-responsible"]') as HTMLButtonElement;
    expect(responsible.textContent).toContain('Fresh Name');
    expect(responsible.textContent).not.toContain('Old Name');
  });

  it('drops an in-flight user search as soon as a new query is typed', async () => {
    vi.useFakeTimers();
    const [first, second] = [new Subject<unknown>(), new Subject<unknown>()];
    const { fixture, component, screen } = await setup({
      get: (path, params) => (path === '/entities/md.users' ? (params['q'] === 'new' ? second : first) : undefined),
    });
    component.openCreateTaskModal();
    redraw(fixture);
    (screen.querySelector('smt-select button[id$="-responsible"]') as HTMLButtonElement).click();
    await vi.advanceTimersByTimeAsync(300);
    redraw(fixture);

    const input = document.querySelector('.smt-select__search-input') as HTMLInputElement;
    input.value = 'new';
    input.dispatchEvent(new Event('input'));
    first.next(users(user(10, 'Stale user')));
    redraw(fixture);
    expect(optionLabels().some((label) => label.includes('Stale user'))).toBe(false);

    await vi.advanceTimersByTimeAsync(300);
    second.next(users(user(501, 'Current user')));
    redraw(fixture);
    expect(optionLabels().some((label) => label.includes('Current user'))).toBe(true);
  });

  it('stops paging the old query while a new one is pending and ignores its late page', async () => {
    vi.useFakeTimers();
    const [oldMore, newQuery] = [new Subject<unknown>(), new Subject<unknown>()];
    const { fixture, component, api, screen } = await setup({
      get: (path, params) => {
        if (path !== '/entities/md.users') return undefined;
        if (params['q'] === 'new') return newQuery;
        if (params['cursor'] === 'u50') return oldMore;
        return of({ ...users(user(1, 'Initial')), nextCursor: 'u50', hasMore: true });
      },
    });
    component.openCreateTaskModal();
    redraw(fixture);
    (screen.querySelector('smt-select button[id$="-responsible"]') as HTMLButtonElement).click();
    await vi.advanceTimersByTimeAsync(300);
    redraw(fixture);
    (document.querySelector('.smt-select__more') as HTMLButtonElement).click();

    const input = document.querySelector('.smt-select__search-input') as HTMLInputElement;
    input.value = 'new';
    input.dispatchEvent(new Event('input'));
    redraw(fixture);
    expect(document.querySelector('.smt-select__more')).toBeNull();
    expect(
      api.get.mock.calls.some(
        ([path, p]) => path === '/entities/md.users' && p?.['q'] === 'new' && p?.['cursor'] === 'u50',
      ),
    ).toBe(false);

    oldMore.next({ ...users(user(2, 'Old late')), nextCursor: 'u100', hasMore: true });
    redraw(fixture);
    expect(document.querySelector('.smt-select__more')).toBeNull();
    expect(optionLabels().some((label) => label.includes('Old late'))).toBe(false);
    await vi.advanceTimersByTimeAsync(300);
    newQuery.next(users(user(501, 'New result')));
    redraw(fixture);
    expect(optionLabels().some((label) => label.includes('New result'))).toBe(true);
  });
});
