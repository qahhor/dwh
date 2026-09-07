import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, Subject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { routes } from '../../app.routes';
import { ApiService } from '../services/api.service';
import { PermissionService } from '../services/permission.service';
import { AuthService } from '../services/auth.service';
import { TasksComponent } from '../../features/tasks/tasks.component';
import { ProjectsComponent } from '../../features/tasks/projects/projects.component';
import { UsersComponent } from '../../features/iam/users/users.component';

describe('Record routes with the actual router and actual templates', () => {
  async function setup() {
    const requests = new Map<string, Subject<any>>();
    const api = {
      get: vi.fn((path: string) => {
        if (/\/(?:tasks|projects|users)\/\d+(?:\/comments)?$/.test(path)) {
          const response = new Subject<any>(); requests.set(path, response); return response.asObservable();
        }
        return of(path === '/tasks' || path === '/iam/users' ? { items: [], hasMore: false, nextCursor: null, totalReturned: 0 } : []);
      }), post: vi.fn(() => of({})), patch: vi.fn(() => of({})), delete: vi.fn(() => of({}))
    };
    TestBed.configureTestingModule({ providers: [provideRouter([routes[0], ...routes[1].children!]),
      { provide: ApiService, useValue: api },
      { provide: PermissionService, useValue: { canCreate: () => true, canUpdate: () => true, canView: () => true, canDelete: () => true, hasPermission: () => true } }
    ] });
    return { harness: await RouterTestingHarness.create(), api, requests, router: TestBed.inject(Router) };
  }

  it('keeps task filters across real list/detail navigation and fetches a record absent from the list', async () => {
    const { harness, requests } = await setup();
    const list = await harness.navigateByUrl('/tasks', TasksComponent);
    list.searchQuery = 'keep filter'; list.selectedPriority = 'high';
    const detail = await harness.navigateByUrl('/tasks/items/123').catch(() => null);
    expect(detail === list).toBe(true);
    expect(requests.has('/tasks/123')).toBe(true);
    requests.get('/tasks/123')!.next({ task: { id: 123, title: 'Fresh detail', statusId: 1, priority: 'high', attributes: {}, createdAt: '2026-01-01' }, members: [], subtasks: [], ancestors: [], files: [] });
    harness.detectChanges();
    expect(harness.routeNativeElement?.textContent).toContain('Fresh detail');
    const returned = await harness.navigateByUrl('/tasks/items', TasksComponent);
    expect(returned === list).toBe(true);
    expect(returned.searchQuery).toBe('keep filter');
    expect(returned.selectedPriority).toBe('high');
    expect(returned.selectedTask()).toBeNull();
  });

  it.each([
    ['/tasks/projects', '/tasks/projects/42', ProjectsComponent, '/tasks/projects/42', { id: 42, name: 'Fresh project', description: 'Detail', state: 'A' }, 'Fresh project'],
    ['/iam/users', '/iam/users/42', UsersComponent, '/iam/users/42', { id: 42, name: 'Fresh user', login: 'fixture', state: 'A', roleIds: [], attributes: {}, createdAt: '2026-01-01' }, 'Fresh user']
  ] as const)('opens readonly detail and preserves filters for %s', async (listUrl, detailUrl, type, endpoint, record, title) => {
    const { harness, api, requests } = await setup();
    const list = await harness.navigateByUrl(listUrl) as ProjectsComponent | UsersComponent;
    list.searchQuery = 'keep';
    const detail = await harness.navigateByUrl(detailUrl).catch(() => null);
    expect(detail === list).toBe(true);
    expect(requests.has(endpoint)).toBe(true);
    requests.get(endpoint)!.next(record);
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('[role="dialog"]')?.textContent).toContain(title);
    expect(list.isEditModalOpen()).toBe(false);
    expect(api.post).not.toHaveBeenCalled(); expect(api.patch).not.toHaveBeenCalled();
    await harness.navigateByUrl(listUrl);
    expect(list.searchQuery).toBe('keep');
    expect(harness.routeNativeElement?.querySelector('[role="dialog"]')).toBeNull();
  });

  it('asks before cross-page navigation can destroy a dirty project draft', async () => {
    const { harness, router } = await setup();
    const page = await harness.navigateByUrl('/tasks/projects', ProjectsComponent);
    page.openCreateModal(); page.createForm.name = 'unsaved';
    let settled = false;
    const navigation = router.navigateByUrl('/iam/users').then(value => { settled = true; return value; });
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(settled).toBe(false);
    expect(page.isCreateDiscardConfirmationOpen()).toBe(true);
    page.confirmDiscardCreate();
    expect(await navigation).toBe(true);
  });

  it('preserves a task draft on list-to-detail cancel and settles superseded confirmations', async () => {
    const { harness, router, requests } = await setup();
    const page = await harness.navigateByUrl('/tasks', TasksComponent);
    page.openCreateTaskModal(); page.createForm.title = 'unsaved task';
    const first = router.navigateByUrl('/tasks/items/123');
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(page.isEditDiscardConfirmationOpen()).toBe(true);
    page.cancelDiscardEdit();
    expect(await first).toBe(false);
    expect(router.url).toBe('/tasks');
    expect(page.createForm.title).toBe('unsaved task');
    expect(requests.has('/tasks/123')).toBe(false);

    const replaced = router.navigateByUrl('/tasks/items/123');
    await new Promise(resolve => setTimeout(resolve, 0));
    const replacement = router.navigateByUrl('/tasks/projects');
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(await replaced).toBe(false);
    expect(page.isEditDiscardConfirmationOpen()).toBe(true);
    page.confirmDiscardEdit();
    expect(await replacement).toBe(true);
    expect(router.url).toBe('/tasks/projects');
  });

  it('guards a dirty task editor for same-component ID changes and preserves safe editing', async () => {
    const { harness, router, requests, api } = await setup();
    const page = await harness.navigateByUrl('/tasks/items/123', TasksComponent);
    const record = { id: 123, title: 'Original', statusId: 1, priority: 'high' as const, attributes: {}, createdAt: '2026-01-01' };
    const detail = { task: record, members: [], subtasks: [], ancestors: [], files: [] };
    requests.get('/tasks/123')!.next(detail);
    page.openEditModal(record);
    requests.get('/tasks/123')!.next(detail);
    page.editForm.title = 'Unsaved';
    const cancelled = router.navigateByUrl('/tasks/items/124');
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(page.isEditDiscardConfirmationOpen()).toBe(true);
    page.cancelDiscardEdit();
    expect(await cancelled).toBe(false);
    expect(page.editForm.title).toBe('Unsaved');
    page.submitEditTask();
    expect(api.patch).toHaveBeenCalledWith('/tasks/123', expect.objectContaining({ title: 'Unsaved' }));
    expect(router.url).toBe('/tasks/items/123');
  });

  it('protects project edits on same-family parameter changes and confirms the destination', async () => {
    const { harness, router, requests } = await setup();
    const page = await harness.navigateByUrl('/tasks/projects/41', ProjectsComponent);
    const record = { id: 41, name: 'Project', description: '', state: 'A' as const, createdAt: '2026-01-01' };
    requests.get('/tasks/projects/41')!.next(record);
    page.openEditModal(record);
    requests.get('/tasks/projects/41')!.next(record);
    page.editForm.name = 'Unsaved project';
    const cancelled = router.navigateByUrl('/tasks/projects/42');
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(page.isEditDiscardConfirmationOpen()).toBe(true);
    page.cancelNavigationDiscard('edit');
    expect(await cancelled).toBe(false);
    expect(page.editForm.name).toBe('Unsaved project');
    const confirmed = router.navigateByUrl('/tasks/projects/42');
    await new Promise(resolve => setTimeout(resolve, 0));
    page.confirmDiscardEdit();
    expect(await confirmed).toBe(true);
    expect(router.url).toBe('/tasks/projects/42');
    expect(page.isEditModalOpen()).toBe(false);
    expect(requests.has('/tasks/projects/42')).toBe(true);
  });

  it('keeps a bigint route key exact despite rounded JSON and exposes no unsafe task action', async () => {
    const { harness, requests, api } = await setup();
    const page = await harness.navigateByUrl('/tasks/items/9223372036854775807', TasksComponent);
    expect(requests.has('/tasks/9223372036854775807')).toBe(true);
    expect(requests.has('/tasks/9223372036854775807/comments')).toBe(true);
    const record = JSON.parse('{"id":9223372036854775807,"title":"Exact record","statusId":1,"priority":"high","attributes":{},"createdAt":"2026-01-01"}');
    requests.get('/tasks/9223372036854775807')!.next({ task: record, members: [], subtasks: [], ancestors: [], files: [] });
    requests.get('/tasks/9223372036854775807/comments')!.next([]);
    harness.detectChanges();
    await Promise.resolve();
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('[data-record-id]')?.getAttribute('data-record-id')).toBe('9223372036854775807');
    expect(harness.routeNativeElement?.querySelector('[role="dialog"]')?.textContent).toContain('#9223372036854775807');
    expect(harness.routeNativeElement?.querySelector('.side-edit-btn')).toBeNull();
    expect(harness.routeNativeElement?.querySelector('.add-subtask-btn')).toBeNull();
    expect(harness.routeNativeElement?.querySelector('.add-comment-box')).toBeNull();
    expect((harness.routeNativeElement?.querySelector('.status-select') as HTMLSelectElement).disabled).toBe(true);
    page.openEditModal(record); page.openAddSubtaskModal(record); page.updateStatus(record.id, 2);
    page.commentDraft = 'text'; page.submitComment();
    page.onTaskFileAttached({ fileId: 1, fileName: 'fixture' } as any);
    page.onTaskFileRemoved({ fileId: 1, fileName: 'fixture' } as any);
    page.onTaskDrop({ item: { data: record } } as any, 2);
    page.retryTaskDetails(); page.retryComments();
    expect(api.post).not.toHaveBeenCalled(); expect(api.patch).not.toHaveBeenCalled(); expect(api.delete).not.toHaveBeenCalled();
    expect(api.get.mock.calls.some(([path]) => path.includes('9223372036854776000'))).toBe(false);
  });

  it.each([
    ['/tasks/projects/9223372036854775807', ProjectsComponent, '/tasks/projects/9223372036854775807'],
    ['/iam/users/9223372036854775807', UsersComponent, '/iam/users/9223372036854775807']
  ] as const)('uses the exact bigint identity and prevents numeric editor handoff for %s', async (url, type, endpoint) => {
    const { harness, requests, api } = await setup();
    const page = await harness.navigateByUrl(url) as ProjectsComponent | UsersComponent;
    const record = JSON.parse('{"id":9223372036854775807,"name":"Exact record","description":"x","login":"fixture","state":"A","createdAt":"2026-01-01","roleIds":[],"attributes":{}}');
    requests.get(endpoint)!.next(record);
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('[data-record-id]')?.getAttribute('data-record-id')).toBe('9223372036854775807');
    if (page instanceof UsersComponent) page.openEditFromView();
    page.openEditModal(record);
    expect(page.isEditModalOpen()).toBe(false);
    expect(api.get.mock.calls.some(([path]) => path.includes('9223372036854776000'))).toBe(false);
    expect(api.patch).not.toHaveBeenCalled();
  });

  it('preserves safe task file removal with the existing UUID file contract', async () => {
    const { harness, requests, api } = await setup();
    const page = await harness.navigateByUrl('/tasks/items/123', TasksComponent);
    requests.get('/tasks/123')!.next({ task: { id: 123, title: 'Safe record', statusId: 1, priority: 'medium', attributes: {}, createdAt: '2026-01-01' }, members: [], subtasks: [], ancestors: [], files: [] });
    page.onTaskFileRemoved({ fileId: '6f7ea128-94e7-462c-b09e-5b183ff86e20', fileName: 'fixture.txt' } as any);
    expect(api.delete).toHaveBeenCalledWith('/tasks/123/files/6f7ea128-94e7-462c-b09e-5b183ff86e20');
  });

  it('still confirms an authenticated move to login, but lets a completed session exit replace that navigation', async () => {
    const { harness, router } = await setup();
    const auth = TestBed.inject(AuthService);
    auth.currentUser.set({ id: 1, name: 'Fixture' } as any);
    const page = await harness.navigateByUrl('/tasks/projects', ProjectsComponent);
    page.openCreateModal(); page.createForm.name = 'Draft';
    const authenticated = router.navigateByUrl('/login');
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(page.isCreateDiscardConfirmationOpen()).toBe(true);
    page.cancelNavigationDiscard('create');
    expect(await authenticated).toBe(false);

    const pending = router.navigateByUrl('/tasks/items/123');
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(page.isCreateDiscardConfirmationOpen()).toBe(true);
    auth.currentUser.set(null);
    let exited = false;
    const exit = router.navigateByUrl('/login?reason=expired#session').then(value => { exited = value; return value; });
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(await pending).toBe(false);
    expect(exited).toBe(true);
    expect(await exit).toBe(true);
    expect(router.url).toBe('/login?reason=expired#session');
    expect(page.isCreateDiscardConfirmationOpen()).toBe(false);
  });

  it('allows a completed session exit without asking about a dirty task', async () => {
    const { harness, router } = await setup();
    const page = await harness.navigateByUrl('/tasks', TasksComponent);
    page.openCreateTaskModal(); page.createForm.title = 'Draft';
    TestBed.inject(AuthService).currentUser.set(null);
    let exited = false;
    const exit = router.navigateByUrl('/login').then(value => { exited = value; return value; });
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(exited).toBe(true);
    expect(await exit).toBe(true);
    expect(page.isEditDiscardConfirmationOpen()).toBe(false);
  });

  it.each([
    ['/tasks/items', '/tasks', TasksComponent],
    ['/tasks/projects', '/tasks/projects', ProjectsComponent],
    ['/iam/users', '/iam/users', UsersComponent]
  ] as const)('cancels old detail requests and shows a localized 404 with a working list action for %s', async (route, endpoint, type) => {
    const { harness, requests, router } = await setup();
    await harness.navigateByUrl(`${route}/41`);
    const old = requests.get(`${endpoint}/41`)!;
    expect(old.observed).toBe(true);
    await harness.navigateByUrl(`${route}/42`);
    expect(old.observed).toBe(false);
    requests.get(`${endpoint}/42`)!.error({ status: 404 });
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('[role="alert"]')?.textContent).toContain('404 — Запись не найдена или недоступна.');
    const back = Array.from(harness.routeNativeElement!.querySelectorAll('button')).find(button => button.textContent?.trim() === 'Вернуться к списку')!;
    back.click();
    await new Promise(resolve => setTimeout(resolve, 0));
    harness.detectChanges();
    expect(router.url).toBe(route);
    expect(harness.routeNativeElement?.querySelector('[role="dialog"]')).toBeNull();
  });
});
