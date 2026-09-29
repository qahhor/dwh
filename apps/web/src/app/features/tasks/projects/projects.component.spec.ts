import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, ParamMap, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject, Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Project } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { ProjectsComponent } from './projects.component';
import { Screen, inScreen, redraw } from '@testing/in-screen';
import { PROJECTS_META, registryProviders } from '@testing/registry-meta';
import { QueryListMeta } from '@core/models/query-meta.models';
import { ProjectListItem } from './projects.models';

// The screen as a whole: list requests, the address and the dialogs' wiring. Their rules: the service specs.

type Params = Record<string, unknown>;

/** A page of the project list as the server answers it. */
const page = (items: ProjectListItem[]) => ({ items, nextCursor: null, hasMore: false, totalEstimated: items.length });
const project = (id: number, state: 'A' | 'P' = 'A', extra: Partial<Project> = {}): Project => ({
  id,
  name: `Project ${id}`,
  state,
  createdAt: '2026-09-06T00:00:00Z',
  ...extra,
});

/** Answers a url through `read`, the list with these projects and every other read empty. */
async function setup(
  options: {
    items?: ProjectListItem[];
    read?: (url: string) => Observable<unknown> | undefined;
    permissions?: string[];
    meta?: QueryListMeta;
    paramMap?: Observable<ParamMap>;
  } = {},
) {
  const api = {
    get: vi.fn(
      (url: string, _params?: unknown, _options?: unknown) =>
        options.read?.(url) ?? of(url === '/tasks/projects/page' && options.items ? page(options.items) : []),
    ),
    post: vi.fn((): Observable<unknown> => of({})),
    patch: vi.fn((): Observable<unknown> => of({})),
    delete: vi.fn(() => of({})),
  };
  const router = { navigate: vi.fn() };
  await TestBed.configureTestingModule({
    imports: [ProjectsComponent],
    providers: [
      { provide: ApiService, useValue: api },
      PermissionService,
      { provide: ToastService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } },
      { provide: Router, useValue: router },
      ...(options.paramMap ? [{ provide: ActivatedRoute, useValue: { paramMap: options.paramMap } }] : []),
      ...registryProviders(options.meta ?? PROJECTS_META),
    ],
  }).compileComponents();
  TestBed.inject(PermissionService).setPermissions(options.permissions ?? ['*.*']);
  const fixture = TestBed.createComponent(ProjectsComponent);
  redraw(fixture);
  /** Change detection, then the wait for the resources' answers. */
  const settle = async () => {
    redraw(fixture);
    await TestBed.inject(ApplicationRef).whenStable();
    redraw(fixture);
  };
  return {
    fixture,
    component: fixture.componentInstance,
    api,
    router,
    settle,
    screen: inScreen(fixture.nativeElement),
  };
}

const footerButtons = (screen: Screen) =>
  [...screen.querySelectorAll('[role="dialog"] [footer] button')] as HTMLButtonElement[];
const pills = (screen: Screen) =>
  ([...screen.querySelectorAll('.status-pill')] as Element[]).map((node) => node.textContent?.trim());
function type(field: HTMLInputElement | HTMLTextAreaElement, value: string) {
  field.value = value;
  field.dispatchEvent(new Event('input'));
}

describe('ProjectsComponent', () => {
  afterEach(() => vi.useRealTimers());

  it('labels the filters and keeps the projects table inside a named region, in list and cards', async () => {
    const { fixture, component, screen } = await setup({ items: [project(1), project(2, 'P')] });
    const search = screen.querySelector('#project-search') as HTMLInputElement;
    const region = screen.querySelector('.table-card[role="region"]') as HTMLElement;

    expect(screen.querySelector(`label[for="${search.id}"]`)).not.toBeNull();
    expect(screen.querySelector('[role="radiogroup"][aria-label="Режим отображения проектов"]')).not.toBeNull();
    expect(region.getAttribute('aria-label')).toBe('Таблица проектов');
    expect(region.querySelector('[role="table"]')?.getAttribute('aria-label')).toBe('Список проектов');
    expect(pills(screen)).toEqual(['Активен', 'В архиве']);
    component.viewMode = 'cards';
    redraw(fixture);
    expect(pills(screen)).toEqual(['Активен', 'В архиве']);
  });

  it('creates through the native form once, locks every way out meanwhile and shows why it failed', async () => {
    const { fixture, api, screen } = await setup();
    const pendingSave = new Subject<Project>();
    api.post.mockReturnValue(pendingSave);

    (screen.querySelector('.view-header__actions .smt-button') as HTMLButtonElement).click();
    redraw(fixture);
    // Let the opened dialog settle before typing.
    await fixture.whenStable();
    redraw(fixture);
    const name = screen.querySelector('#project-create-name') as HTMLInputElement;
    expect(screen.querySelector(`label[for="${name.id}"]`)).not.toBeNull();
    expect(name.required).toBe(true);
    type(name, '  Native create  ');
    type(screen.querySelector('#project-create-description') as HTMLTextAreaElement, '  Submitted from the form  ');
    redraw(fixture);
    const form = screen.querySelector('#project-create-form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    redraw(fixture);

    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post).toHaveBeenCalledWith('/tasks/projects', {
      name: 'Native create',
      description: 'Submitted from the form',
    });
    expect((screen.querySelector('fieldset.project-create-form') as HTMLFieldSetElement).disabled).toBe(true);
    expect(footerButtons(screen).every((button) => button.disabled)).toBe(true);
    expect(screen.querySelector('[role="dialog"] .smt-modal__close')).toBeNull();

    pendingSave.error({ status: 422, detail: 'Normalized create detail' });
    redraw(fixture);
    expect(screen.querySelector('[data-testid="project-create-save-error"][role="alert"]')?.textContent).toContain(
      'Normalized create detail',
    );
  });

  it('keeps an entered create draft visible when Cancel or Escape asks to dismiss it', async () => {
    const { fixture, settle, screen } = await setup();
    const draft = () => (screen.querySelector('#project-create-name') as HTMLInputElement).value;

    (screen.querySelector('.view-header__actions .smt-button') as HTMLButtonElement).click();
    await settle();
    type(screen.querySelector('#project-create-name') as HTMLInputElement, 'Unsaved project');
    redraw(fixture);

    footerButtons(screen)[0].click();
    redraw(fixture);
    expect(screen.querySelectorAll('[role="dialog"]')).toHaveLength(2);
    expect(draft()).toBe('Unsaved project');

    const confirmation = screen.querySelectorAll('[role="dialog"]')[1] as HTMLElement;
    (confirmation.querySelector('[footer] button') as HTMLButtonElement).click();
    redraw(fixture);
    expect(screen.querySelectorAll('[role="dialog"]')).toHaveLength(1);
    expect(draft()).toBe('Unsaved project');

    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    (document.activeElement ?? document.body).dispatchEvent(escape);
    redraw(fixture);
    expect(escape.defaultPrevented).toBe(true);
    expect(screen.querySelectorAll('[role="dialog"]')).toHaveLength(2);
    expect(draft()).toBe('Unsaved project');
  });

  it('edits the fresh project instead of the list row, offers a retry and sends one sparse PATCH', async () => {
    const summary = project(5, 'A', { name: 'List name', description: 'Версия из списка' });
    const [failed, fresh] = [new Subject<Project>(), new Subject<Project>()];
    const reads = [failed, fresh];
    const { fixture, api, screen } = await setup({
      items: [summary],
      read: (url) => (url === '/tasks/projects/5' ? reads.shift() : undefined),
    });
    const pendingPatch = new Subject<void>();
    api.patch.mockReturnValue(pendingPatch);

    (screen.querySelector('.icon-ghost-btn') as HTMLButtonElement).click();
    redraw(fixture);
    expect(screen.querySelector('[data-testid="project-edit-loading"][role="status"]')).not.toBeNull();
    expect(screen.querySelector('#project-edit-name')).toBeNull();
    failed.error({ status: 503, detail: 'Unavailable' });
    redraw(fixture);
    expect(screen.querySelector('[data-testid="project-edit-load-error"][role="alert"]')).not.toBeNull();

    (screen.querySelector('button.project-edit-retry') as HTMLButtonElement).click();
    fresh.next(project(5, 'A', { name: 'Fresh name', description: 'Актуальное описание с сервера' }));
    fresh.complete();
    redraw(fixture);
    await fixture.whenStable();
    redraw(fixture);
    const name = screen.querySelector('#project-edit-name') as HTMLInputElement;
    expect(name.value).toBe('Fresh name');
    expect((screen.querySelector('#project-edit-description') as HTMLTextAreaElement).value).toBe(
      'Актуальное описание с сервера',
    );

    type(name, '  Native rename  ');
    redraw(fixture);
    const form = screen.querySelector('#project-edit-form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    redraw(fixture);
    expect(api.patch).toHaveBeenCalledTimes(1);
    expect(api.patch).toHaveBeenCalledWith('/tasks/projects/5', { name: 'Native rename' });
    expect(screen.querySelector('.project-edit-form')?.disabled).toBe(true);
    pendingPatch.error({ status: 409, detail: 'Normalized edit detail' });
    redraw(fixture);
    expect(screen.querySelector('[data-testid="project-edit-save-error"]')?.textContent).toContain('Normalized edit');
  });

  it('asks the server for one page with the search, the state and the sort; a header sorts the whole list', async () => {
    vi.useFakeTimers();
    const { fixture, component, api, screen } = await setup({ items: [project(1), project(2)] });
    const lastQuery = () => api.get.mock.calls.filter(([url]) => url === '/tasks/projects/page').at(-1);
    await vi.advanceTimersByTimeAsync(0);
    redraw(fixture);
    expect(api.get).toHaveBeenCalledWith(
      '/tasks/projects/page',
      expect.objectContaining({ limit: 10, cursor: undefined }),
      { notifyError: false },
    );
    expect(screen.querySelector('.count-badge')?.textContent?.trim()).toBe('2');

    component.setSearchQuery('Proj');
    await vi.advanceTimersByTimeAsync(300);
    expect(lastQuery()?.[1]).toEqual(expect.objectContaining({ q: 'Proj' }));
    component.setSelectedState('P');
    expect(lastQuery()?.[1]).toEqual(expect.objectContaining({ state: 'P', q: 'Proj' }));
    component.onSort({ column: 'progress', sortBy: 'DESC' as never });
    expect(lastQuery()?.[1]).toEqual(expect.objectContaining({ sort: '-progress' }));
    expect(component.exportOptions()).toEqual({ state: 'P' });
  });

  it('shows recoverable list loading and error states without empty results in list and cards', async () => {
    const [first, retry] = [new Subject<unknown>(), new Subject<unknown>()];
    const reads = [first, retry];
    const { fixture, component, screen } = await setup({
      read: (url) => (url === '/tasks/projects/page' ? (reads.shift() ?? of(page([]))) : undefined),
    });

    expect(screen.querySelector('[data-testid="projects-list-loading"][role="status"]')).not.toBeNull();
    expect(screen.textContent).not.toContain('Проекты не найдены');
    component.viewMode = 'cards';
    redraw(fixture);
    expect(screen.querySelector('.project-card')).toBeNull();

    first.error({ status: 503, detail: 'Unavailable' });
    redraw(fixture);
    expect(screen.querySelector('[data-testid="projects-list-error"][role="alert"]')).not.toBeNull();
    const retryButton = screen.querySelector('.projects-list-retry') as HTMLButtonElement;
    expect(retryButton.textContent).toContain('Повторить загрузку проектов');
    retryButton.click();
    retry.next(page([project(1)]));
    retry.complete();
    redraw(fixture);

    expect(screen.querySelector('[data-testid="projects-list-error"]')).toBeNull();
    expect(screen.querySelector('.project-card')?.textContent).toContain('Project 1');
  });

  it('counts closed tasks from each row, without separate stats requests, and opens a project’s tasks', async () => {
    const { fixture, component, api, router, screen } = await setup({
      items: [{ ...project(1), totalTasks: 4, doneTasks: 2, progress: 50 }],
    });

    expect(component.projectStats()[1]).toEqual({ projectId: 1, totalTasks: 4, doneTasks: 2, activeTasks: 2 });
    expect(screen.querySelector('.progress-count')?.textContent?.trim()).toBe('2 / 4 закрыто');
    expect(screen.querySelector('[data-testid="projects-stats-scope"]')?.textContent).toContain(
      'конечных статусах, включая выполненные и отменённые',
    );
    expect(api.get.mock.calls.filter(([url]) => String(url).endsWith('/stats'))).toHaveLength(0);
    component.viewMode = 'cards';
    redraw(fixture);
    expect(screen.querySelector('[role="progressbar"]')?.getAttribute('aria-valuenow')).toBe('50');

    component.viewProjectTasks(project(1));
    expect(router.navigate).toHaveBeenCalledWith(['/tasks'], { queryParams: { project_id: 1 } });
  });

  it('keeps someone who may only view projects on plain data, without statistics, drilldowns or changes', async () => {
    const withoutProgress = { ...PROJECTS_META, fields: PROJECTS_META.fields.filter((f) => f.key !== 'progress') };
    const { fixture, component, router, screen } = await setup({
      items: [project(1)],
      permissions: ['tasks.projects.view', 'tasks.items.create', 'tasks.items.update'],
      meta: withoutProgress,
    });

    expect(screen.querySelector('.project-name')).toBeNull();
    expect(screen.querySelector('.project-name-text')?.textContent).toContain('Project 1');
    expect(screen.querySelector('[role="progressbar"]')).toBeNull();
    expect(screen.querySelector('[data-testid="projects-stats-permission"][role="status"]')).not.toBeNull();
    expect(screen.querySelector('.view-header__actions .smt-button')).toBeNull();
    expect(screen.querySelector('.icon-ghost-btn')).toBeNull();
    component.viewMode = 'cards';
    redraw(fixture);
    expect(screen.querySelector('.view-tasks-link')).toBeNull();
    expect(screen.querySelector('.card-progress')).toBeNull();

    component.viewProjectTasks(project(1));
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('reloads the first page with the filters cleared after a project is created', async () => {
    const { component, api } = await setup({ items: [project(1)] });
    api.post.mockReturnValue(of(project(11)));
    component.searchQuery = 'old filter';
    component.selectedState = 'P';
    component.openCreateModal();
    component.createForm = { name: 'Newly created project', description: '' };

    component.submitCreateProject();

    expect(component.searchQuery).toBe('');
    expect(component.selectedState).toBe('all');
    const request = api.get.mock.calls.filter(([url]) => url === '/tasks/projects/page').at(-1)?.[1] as Params;
    expect([request['state'], request['q'], request['cursor']]).toEqual([undefined, undefined, undefined]);
  });

  it('opens the members of a project from its row', async () => {
    const alice = { projectId: 1, userId: 10, userName: 'Alice', userEmail: 'a@example.com', accessKind: 'MANAGER' };
    const { component, settle, screen } = await setup({
      items: [project(1)],
      read: (url) => (url === '/tasks/projects/1/members' ? of([alice]) : undefined),
    });

    (screen.querySelector('.members-btn') as HTMLButtonElement).click();
    await settle();

    expect(component.members.selectedProjectForMembers()?.id).toBe(1);
    expect(component.members.projectMembers()).toEqual([alice]);
    expect(screen.querySelector('[role="dialog"]')?.textContent).toContain('Alice');
  });
});

describe('ProjectsComponent record in the address', () => {
  it('shows the project the address names, and reads the next one when the address changes', async () => {
    const paramMap = new BehaviorSubject(convertToParamMap({ id: '41' }));
    const { component, api, router, settle, screen } = await setup({
      paramMap,
      read: (url) => (/\/tasks\/projects\/4[12]$/.test(url) ? of(project(Number(url.slice(-2)))) : undefined),
    });
    await settle();

    expect(component.routeRecordId()).toBe('41');
    expect(api.get).toHaveBeenCalledWith('/tasks/projects/41', undefined, { notifyError: false });
    expect(component.viewingProject()?.id).toBe(41);
    expect(screen.querySelector('[data-record-id="41"]')?.textContent).toContain('Project 41');

    paramMap.next(convertToParamMap({ id: '42' }));
    await settle();
    expect(component.viewingProject()?.id).toBe(42);
    component.closeRecordView();
    expect(router.navigate).toHaveBeenCalledWith(['/tasks/projects'], { queryParamsHandling: 'preserve' });

    paramMap.next(convertToParamMap({}));
    await settle();
    expect(component.routeRecordId()).toBeNull();
    expect(component.viewingProject()).toBeNull();
    expect(component.recordError()).toBe(false);
  });

  it('offers a retry after a failed read, calls a missing or malformed project not found, and a mismatch an error', async () => {
    const paramMap = new BehaviorSubject(convertToParamMap({ id: '7' }));
    const retry = new Subject<Project>();
    const sevens: Observable<unknown>[] = [throwError(() => ({ status: 503 })), retry];
    const reads: Record<string, () => Observable<unknown> | undefined> = {
      '/tasks/projects/7': () => sevens.shift(),
      '/tasks/projects/8': () => of(project(9)),
      '/tasks/projects/404': () => throwError(() => ({ status: 404 })),
    };
    const { component, settle, screen } = await setup({ paramMap, read: (url) => reads[url]?.() });
    await settle();
    expect(component.recordError()).toBe(true);
    expect(component.recordNotFound()).toBe(false);

    (screen.querySelector('[role="dialog"] [role="alert"] button') as HTMLButtonElement).click();
    expect(component.recordLoading()).toBe(true);
    expect(component.recordError()).toBe(false);
    retry.next(project(7));
    await settle();
    expect(component.viewingProject()?.id).toBe(7);

    paramMap.next(convertToParamMap({ id: '8' }));
    await settle();
    expect(component.recordError()).toBe(true);
    expect(component.viewingProject()).toBeNull();

    paramMap.next(convertToParamMap({ id: '404' }));
    await settle();
    expect(component.recordNotFound()).toBe(true);

    paramMap.next(convertToParamMap({ id: '9223372036854775808' }));
    await settle();
    expect(component.recordError()).toBe(true);
    expect(component.recordNotFound()).toBe(true);
    expect(component.recordLoading()).toBe(false);
  });
});
