import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, Subject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { routes } from '@app/app.routes';
import { ApiService } from '../services/api.service';
import { PROJECTS_META, TASKS_META } from '@testing/registry-meta';
import { PermissionService } from '../services/permission.service';
import { AuthService } from '../services/auth.service';
import { TasksComponent } from '@features/tasks/tasks.component';
import { ProjectsComponent } from '@features/tasks/projects/projects.component';

/** The open "discard changes?" question (common.discard.title), or null. */
const discardQuestion = () =>
  [...document.querySelectorAll<HTMLElement>('[role="alertdialog"]')].find((node) =>
    node.textContent?.includes('Отменить изменения?'),
  ) ?? null;

/** Answers the open question: leave (common.discard.confirm) or stay (common.discard.keep). */
function answerDiscard(leave: boolean) {
  const label = leave ? 'Не сохранять' : 'Продолжить редактирование';
  const button = [...(discardQuestion()?.querySelectorAll('button') ?? [])].find(
    (node) => node.textContent?.trim() === label,
  );
  if (!button) throw new Error('no discard question open');
  button.click();
}

describe('Record routes with the actual router and actual templates', () => {
  async function setup() {
    const requests = new Map<string, Subject<any>>();
    const api = {
      get: vi.fn((path: string) => {
        if (/^\/iam\/org-units\/users\/\d+\/scope$/.test(path)) {
          return of({ rule: 'ALL', visibleOrgUnitIds: [] });
        }
        if (/\/(?:tasks|ms\.tasks|ms\.projects|users)\/\d+(?:\/comments)?$/.test(path)) {
          const response = new Subject<any>();
          requests.set(path, response);
          return response.asObservable();
        }
        if (path === '/query-meta/ms.tasks') return of(TASKS_META);
        if (path === '/query-meta/ms.projects') return of(PROJECTS_META);
        return of(
          path.startsWith('/entities/') ? { items: [], hasMore: false, nextCursor: null, totalReturned: 0 } : [],
        );
      }),
      post: vi.fn(() => of({})),
      patch: vi.fn(() => of({})),
      delete: vi.fn(() => of({})),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([routes[0], ...routes.find((route) => route.path === '' && route.children)!.children!]),
        { provide: ApiService, useValue: api },
        {
          provide: PermissionService,
          useValue: {
            canCreate: () => true,
            canUpdate: () => true,
            canView: () => true,
            canDelete: () => true,
            hasPermission: () => true,
          },
        },
      ],
    });
    return { harness: await RouterTestingHarness.create(), api, requests, router: TestBed.inject(Router) };
  }

  it('keeps task filters across real list/detail navigation and fetches a record absent from the list', async () => {
    const { harness, requests } = await setup();
    const list = await harness.navigateByUrl('/tasks', TasksComponent);
    list.searchQuery = 'keep filter';
    list.selectedPriority = 'high';
    const detail = await harness.navigateByUrl('/tasks/items/123').catch(() => null);
    expect(detail === list).toBe(true);
    expect(requests.has('/entities/ms.tasks/123')).toBe(true);
    requests.get('/entities/ms.tasks/123')!.next({
      id: 123,
      title: 'Fresh detail',
      typeCode: 'task',
      statusCode: 'new',
      priority: 'high',
      attributes: {},
      createdAt: '2026-01-01',
    });
    harness.detectChanges();
    expect(document.body.textContent).toContain('Fresh detail');
    const returned = await harness.navigateByUrl('/tasks/items', TasksComponent);
    expect(returned === list).toBe(true);
    expect(returned.searchQuery).toBe('keep filter');
    expect(returned.selectedPriority).toBe('high');
    expect(returned.selectedTask()).toBeNull();
  });

  it.each([
    [
      '/tasks/projects',
      '/tasks/projects/42',
      ProjectsComponent,
      '/entities/ms.projects/42',
      { id: 42, name: 'Fresh project', description: 'Detail', state: 'A' },
      'Fresh project',
    ],
  ] as const)(
    'opens readonly detail and preserves filters for %s',
    async (listUrl, detailUrl, type, endpoint, record, title) => {
      const { harness, api, requests } = await setup();
      const list = (await harness.navigateByUrl(listUrl)) as ProjectsComponent;
      list.searchQuery = 'keep';
      const detail = await harness.navigateByUrl(detailUrl).catch(() => null);
      expect(detail === list).toBe(true);
      expect(requests.has(endpoint)).toBe(true);
      requests.get(endpoint)!.next(record);
      harness.detectChanges();
      expect(document.body.querySelector('[role="dialog"]')?.textContent).toContain(title);
      expect(list.isEditModalOpen()).toBe(false);
      expect(api.post).not.toHaveBeenCalled();
      expect(api.patch).not.toHaveBeenCalled();
      await harness.navigateByUrl(listUrl);
      expect(list.searchQuery).toBe('keep');
      expect(document.body.querySelector('[role="dialog"]')).toBeNull();
    },
  );

  it('asks before cross-page navigation can destroy a dirty project draft', async () => {
    const { harness, router } = await setup();
    const page = await harness.navigateByUrl('/tasks/projects', ProjectsComponent);
    page.openCreateModal();
    page.createForm.name = 'unsaved';
    let settled = false;
    const navigation = router.navigateByUrl('/iam/roles').then((value) => {
      settled = true;
      return value;
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(settled).toBe(false);
    expect(discardQuestion()).not.toBeNull();
    answerDiscard(true);
    expect(await navigation).toBe(true);
    expect(page.isCreateModalOpen()).toBe(false);
  });

  it('preserves a task draft on list-to-detail cancel and settles superseded confirmations', async () => {
    const { harness, router, requests } = await setup();
    const page = await harness.navigateByUrl('/tasks', TasksComponent);
    page.openCreateTaskModal();
    page.createForm.title = 'unsaved task';
    const first = router.navigateByUrl('/tasks/items/123');
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(discardQuestion()).not.toBeNull();
    answerDiscard(false);
    expect(await first).toBe(false);
    expect(router.url).toBe('/tasks');
    expect(page.createForm.title).toBe('unsaved task');
    expect(requests.has('/entities/ms.tasks/123')).toBe(false);

    const replaced = router.navigateByUrl('/tasks/items/123');
    await new Promise((resolve) => setTimeout(resolve, 0));
    const replacement = router.navigateByUrl('/tasks/projects');
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(await replaced).toBe(false);
    // The replaced navigation and its replacement share one question: one dialog, not two.
    expect(document.querySelectorAll('[role="alertdialog"]')).toHaveLength(1);
    answerDiscard(true);
    expect(await replacement).toBe(true);
    expect(router.url).toBe('/tasks/projects');
  });

  it('guards a dirty task editor for same-component ID changes and preserves safe editing', async () => {
    const { harness, router, requests, api } = await setup();
    const page = await harness.navigateByUrl('/tasks/items/123', TasksComponent);
    const record = {
      id: 123,
      title: 'Original',
      typeCode: 'task',
      statusCode: 'new',
      priority: 'high' as const,
      attributes: {},
      createdAt: '2026-01-01',
    };
    requests.get('/entities/ms.tasks/123')!.next(record);
    page.openEditModal(record);
    requests.get('/entities/ms.tasks/123')!.next(record);
    page.editForm.title = 'Unsaved';
    const cancelled = router.navigateByUrl('/tasks/items/124');
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(discardQuestion()).not.toBeNull();
    answerDiscard(false);
    expect(await cancelled).toBe(false);
    expect(page.editForm.title).toBe('Unsaved');
    page.submitEditTask();
    expect(api.patch).toHaveBeenCalledWith(
      '/entities/ms.tasks/123',
      expect.objectContaining({ title: 'Unsaved' }),
      expect.any(Object),
    );
    expect(router.url).toBe('/tasks/items/123');
  });

  it('protects project edits on same-family parameter changes and confirms the destination', async () => {
    const { harness, router, requests } = await setup();
    const page = await harness.navigateByUrl('/tasks/projects/41', ProjectsComponent);
    const record = { id: 41, name: 'Project', description: '', state: 'A' as const, createdAt: '2026-01-01' };
    requests.get('/entities/ms.projects/41')!.next(record);
    page.openEditModal(record);
    requests.get('/entities/ms.projects/41')!.next(record);
    page.editForm.name = 'Unsaved project';
    const cancelled = router.navigateByUrl('/tasks/projects/42');
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(discardQuestion()).not.toBeNull();
    answerDiscard(false);
    expect(await cancelled).toBe(false);
    expect(page.editForm.name).toBe('Unsaved project');
    const confirmed = router.navigateByUrl('/tasks/projects/42');
    await new Promise((resolve) => setTimeout(resolve, 0));
    answerDiscard(true);
    expect(await confirmed).toBe(true);
    expect(router.url).toBe('/tasks/projects/42');
    expect(page.isEditModalOpen()).toBe(false);
    expect(requests.has('/entities/ms.projects/42')).toBe(true);
  });

  it('keeps a bigint route key exact despite rounded JSON and exposes no unsafe task action', async () => {
    const { harness, requests, api } = await setup();
    const page = await harness.navigateByUrl('/tasks/items/9223372036854775807', TasksComponent);
    expect(requests.has('/entities/ms.tasks/9223372036854775807')).toBe(true);
    expect(requests.has('/tasks/9223372036854775807/comments')).toBe(true);
    const record = JSON.parse(
      '{"id":9223372036854775807,"title":"Exact record","typeCode":"task","statusCode":"new","priority":"high","attributes":{},"createdAt":"2026-01-01"}',
    );
    requests.get('/entities/ms.tasks/9223372036854775807')!.next(record);
    requests.get('/tasks/9223372036854775807/comments')!.next([]);
    harness.detectChanges();
    await Promise.resolve();
    harness.detectChanges();
    expect(document.body.querySelector('[data-record-id]')?.getAttribute('data-record-id')).toBe('9223372036854775807');
    expect(document.body.querySelector('[role="dialog"]')?.textContent).toContain('#9223372036854775807');
    expect(document.body.querySelector('.side-edit-btn')).toBeNull();
    expect(document.body.querySelector('.add-subtask-btn')).toBeNull();
    expect(document.body.querySelector('.add-comment-box')).toBeNull();
    expect((document.body.querySelector('.status-select [role="combobox"]') as HTMLButtonElement).disabled).toBe(true);
    page.openEditModal(record);
    page.openAddSubtaskModal(record);
    page.updateStatus(record.id, 'done');
    page.commentDraft = 'text';
    page.submitComment();
    page.onTaskFileAttached({ fileId: 1, fileName: 'fixture' } as any);
    page.onTaskFileRemoved({ fileId: 1, fileName: 'fixture' } as any);
    page.onTaskDrop({ item: { data: record } } as any, 'done');
    page.retryTaskDetails();
    page.retryComments();
    // The only change sent is the viewed mark, by the exact key of the route.
    expect(api.post.mock.calls.map((call: unknown[]) => call[0])).toEqual(['/tasks/9223372036854775807/view']);
    expect(api.patch).not.toHaveBeenCalled();
    expect(api.delete).not.toHaveBeenCalled();
    expect(api.get.mock.calls.some(([path]) => path.includes('9223372036854776000'))).toBe(false);
  });

  it.each([
    ['/tasks/projects/9223372036854775807', ProjectsComponent, '/entities/ms.projects/9223372036854775807'],
  ] as const)(
    'uses the exact bigint identity and prevents numeric editor handoff for %s',
    async (url, type, endpoint) => {
      const { harness, requests, api } = await setup();
      const page = (await harness.navigateByUrl(url)) as ProjectsComponent;
      const record = JSON.parse(
        '{"id":9223372036854775807,"name":"Exact record","description":"x","login":"fixture","state":"A","createdAt":"2026-01-01","roleIds":[],"attributes":{}}',
      );
      requests.get(endpoint)!.next(record);
      harness.detectChanges();
      expect(document.body.querySelector('[data-record-id]')?.getAttribute('data-record-id')).toBe(
        '9223372036854775807',
      );
      page.openEditModal(record);
      expect(page.isEditModalOpen()).toBe(false);
      expect(api.get.mock.calls.some(([path]) => path.includes('9223372036854776000'))).toBe(false);
      expect(api.patch).not.toHaveBeenCalled();
    },
  );

  it('preserves safe task file removal with the existing UUID file contract', async () => {
    const { harness, requests, api } = await setup();
    const page = await harness.navigateByUrl('/tasks/items/123', TasksComponent);
    requests.get('/entities/ms.tasks/123')!.next({
      id: 123,
      title: 'Safe record',
      typeCode: 'task',
      statusCode: 'new',
      priority: 'medium',
      attributes: {},
      createdAt: '2026-01-01',
    });
    page.onTaskFileRemoved({ fileId: '6f7ea128-94e7-462c-b09e-5b183ff86e20', fileName: 'fixture.txt' } as any);
    expect(api.delete).toHaveBeenCalledWith('/tasks/123/files/6f7ea128-94e7-462c-b09e-5b183ff86e20');
  });

  it('still confirms an authenticated move to login, but lets a completed session exit replace that navigation', async () => {
    const { harness, router } = await setup();
    const auth = TestBed.inject(AuthService);
    auth.currentUser.set({ id: 1, name: 'Fixture' } as any);
    const page = await harness.navigateByUrl('/tasks/projects', ProjectsComponent);
    page.openCreateModal();
    page.createForm.name = 'Draft';
    const authenticated = router.navigateByUrl('/login');
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(discardQuestion()).not.toBeNull();
    answerDiscard(false);
    expect(await authenticated).toBe(false);

    const pending = router.navigateByUrl('/tasks/items/123');
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(discardQuestion()).not.toBeNull();
    auth.currentUser.set(null);
    let exited = false;
    const exit = router.navigateByUrl('/login?reason=expired#session').then((value) => {
      exited = value;
      return value;
    });
    // The exit completes by itself, the discard question unanswered (the router takes a few turns since 22.2).
    await vi.waitFor(() => expect(exited).toBe(true));
    expect(await pending).toBe(false);
    expect(await exit).toBe(true);
    expect(router.url).toBe('/login?reason=expired#session');
    // Nobody asks again after the exit, so the question closes by itself instead of covering the login screen.
    await vi.waitFor(() => expect(discardQuestion()).toBeNull());
  });

  it('allows a completed session exit without asking about a dirty task', async () => {
    const { harness, router } = await setup();
    const page = await harness.navigateByUrl('/tasks', TasksComponent);
    page.openCreateTaskModal();
    page.createForm.title = 'Draft';
    TestBed.inject(AuthService).currentUser.set(null);
    let exited = false;
    const exit = router.navigateByUrl('/login').then((value) => {
      exited = value;
      return value;
    });
    // The exit completes by itself, without asking (the router takes a few turns since 22.2).
    await vi.waitFor(() => expect(exited).toBe(true));
    expect(await exit).toBe(true);
    expect(discardQuestion()).toBeNull();
  });

  it.each([
    ['/tasks/items', '/entities/ms.tasks', TasksComponent],
    ['/tasks/projects', '/entities/ms.projects', ProjectsComponent],
  ] as const)(
    'cancels old detail requests and shows a localized 404 with a working list action for %s',
    async (route, endpoint, _type) => {
      const { harness, requests, router } = await setup();
      await harness.navigateByUrl(`${route}/41`);
      const old = requests.get(`${endpoint}/41`)!;
      expect(old.observed).toBe(true);
      await harness.navigateByUrl(`${route}/42`);
      expect(old.observed).toBe(false);
      requests.get(`${endpoint}/42`)!.error({ status: 404 });
      harness.detectChanges();
      expect(document.body.querySelector('[role="alert"]')?.textContent).toContain(
        '404 — Запись не найдена или недоступна.',
      );
      const back = Array.from(document.body.querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Вернуться к списку',
      )!;
      back.click();
      await new Promise((resolve) => setTimeout(resolve, 0));
      harness.detectChanges();
      expect(router.url).toBe(route);
      expect(document.body.querySelector('[role="dialog"]')).toBeNull();
    },
  );
});
