import { By } from '@angular/platform-browser';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Subject, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { Project } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { SMTDialogComponent } from '@shared/ui-kit/components/modal';
import { ProjectsComponent } from './projects.component';
import { inScreen, redraw } from '@testing/in-screen';
import { PROJECTS_META, registryProviders } from '@testing/registry-meta';
import { QueryListMeta } from '@core/models/query-meta.models';
import { ProjectListItem } from './projects.models';

/** A page of the project list as the server answers it. */
const page = (items: ProjectListItem[], nextCursor: string | null = null) => ({
  items,
  nextCursor,
  hasMore: nextCursor !== null,
  totalEstimated: items.length,
});

/** An API that answers the project list with one page of these projects and every other read empty. */
const listApi = (items: ProjectListItem[]): ApiDouble => {
  const api = emptyApi();
  api.get.mockImplementation((url: string) => of(url === '/tasks/projects/page' ? page(items) : []));
  return api;
};

interface ApiDouble {
  get: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  patch: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
}

const emptyApi = (): ApiDouble => ({
  get: vi.fn(() => of([])),
  post: vi.fn(() => of({})),
  patch: vi.fn(() => of({})),
  delete: vi.fn(() => of({})),
});

describe('ProjectsComponent UI contracts', () => {
  async function createFixture(
    options: {
      api?: ApiDouble;
      permissions?: string[];
      meta?: QueryListMeta;
    } = {},
  ) {
    const api = options.api ?? emptyApi();
    const router = { navigate: vi.fn() };
    const toast = { success: vi.fn(), warning: vi.fn(), error: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [ProjectsComponent],
      providers: [
        { provide: ApiService, useValue: api },
        PermissionService,
        { provide: ToastService, useValue: toast },
        { provide: Router, useValue: router },
        ...registryProviders(options.meta ?? PROJECTS_META),
      ],
    }).compileComponents();
    TestBed.inject(PermissionService).setPermissions(options.permissions ?? ['*.*']);
    const fixture = TestBed.createComponent(ProjectsComponent);
    redraw(fixture);
    return { fixture, api, router, toast };
  }

  const project = (id: number, state: 'A' | 'P' = 'A'): Project => ({
    id,
    name: `Project ${id}`,
    state,
    createdAt: '2026-09-06T00:00:00Z',
  });

  it('labels filters and keeps the projects table inside a named scroll region', async () => {
    const { fixture } = await createFixture();

    const search = inScreen(fixture.nativeElement).querySelector('#project-search') as HTMLInputElement;
    const region = inScreen(fixture.nativeElement).querySelector('.table-card[role="region"]') as HTMLElement;

    expect(inScreen(fixture.nativeElement).querySelector(`label[for="${search.id}"]`)).not.toBeNull();
    expect(
      inScreen(fixture.nativeElement).querySelector('[role="radiogroup"][aria-label="Режим отображения проектов"]'),
    ).not.toBeNull();
    expect(region.getAttribute('aria-label')).toBe('Таблица проектов');
    expect(region.querySelector('[role="table"]')?.getAttribute('aria-label')).toBe('Список проектов');
  });

  it('connects project modal labels, required state and validation message', async () => {
    const { fixture } = await createFixture();
    fixture.componentInstance.openCreateModal();
    fixture.componentInstance.forms.isCreateSubmitted = true;
    redraw(fixture);
    TestBed.tick(); // smt-control wires label, error and aria state after render

    const name = inScreen(fixture.nativeElement).querySelector('#project-create-name') as HTMLInputElement;
    const error = (name.getAttribute('aria-describedby') ?? '')
      .split(' ')
      .map((id) => inScreen(fixture.nativeElement).querySelector('#' + id))
      .find((node) => node?.classList.contains('smt-control__error')) as HTMLElement;

    expect(inScreen(fixture.nativeElement).querySelector(`label[for="${name.id}"]`)).not.toBeNull();
    expect(name.required).toBe(true);
    expect(name.getAttribute('aria-invalid')).toBe('true');
    expect(name.getAttribute('aria-describedby')).toBe(error.id);
    expect(inScreen(fixture.nativeElement).querySelector('#project-create-description')).not.toBeNull();
  });

  it('renders plain project state copy while retaining A and P option values', async () => {
    const api = emptyApi();
    api.get.mockImplementation((url: string) => {
      if (url === '/tasks/projects/page') return of(page([project(1), project(2, 'P')]));
      if (url === '/tasks/projects/1') return of(project(1));
      return of([]);
    });
    const { fixture } = await createFixture({ api });
    const host = inScreen(fixture.nativeElement);

    expect(
      Array.from(host.querySelectorAll('.status-pill') as Element[]).map((node) => node.textContent?.trim()),
    ).toEqual(['Активен', 'В архиве']);

    fixture.componentInstance.viewMode = 'cards';
    redraw(fixture);
    expect(
      Array.from(host.querySelectorAll('.status-pill') as Element[]).map((node) => node.textContent?.trim()),
    ).toEqual(['Активен', 'В архиве']);

    fixture.componentInstance.openEditModal(project(1));
    redraw(fixture);
    await fixture.whenStable();
    redraw(fixture);
    const state = host.querySelector('#project-edit-state') as HTMLButtonElement;
    expect(state.getAttribute('role')).toBe('combobox');
    expect(state.textContent).toContain('Активен');
    state.click();
    redraw(fixture);
    const options = Array.from(document.querySelectorAll('.smt-select__option')) as HTMLElement[];
    expect(options.map((option) => option.querySelector('.smt-select__option-label')?.textContent?.trim())).toEqual([
      'Активен',
      'В архиве',
    ]);
    options[1].click();
    redraw(fixture);
    expect(fixture.componentInstance.editForm.state).toBe('P');
  });

  it('submits entered create values through the native form only once while pending', async () => {
    const pendingSave = new Subject<Project>();
    const api = emptyApi();
    api.post.mockReturnValue(pendingSave);
    const { fixture } = await createFixture({ api });
    const host = inScreen(fixture.nativeElement);

    fixture.componentInstance.openCreateModal();
    redraw(fixture);
    await fixture.whenStable();
    redraw(fixture);
    const name = host.querySelector('#project-create-name') as HTMLInputElement;
    const description = host.querySelector('#project-create-description') as HTMLTextAreaElement;
    name.value = '  Native create  ';
    name.dispatchEvent(new Event('input'));
    description.value = '  Submitted from the form  ';
    description.dispatchEvent(new Event('input'));
    redraw(fixture);

    const form = host.querySelector('#project-create-form') as HTMLFormElement;
    expect(form).not.toBeNull();
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    redraw(fixture);

    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post).toHaveBeenCalledWith('/tasks/projects', {
      name: 'Native create',
      description: 'Submitted from the form',
    });
  });

  it('keeps an entered create draft visible when Cancel or Escape requests dismissal', async () => {
    const { fixture } = await createFixture();
    const host = inScreen(fixture.nativeElement);

    (host.querySelector('.view-header__actions .smt-button') as HTMLButtonElement).click();
    redraw(fixture);
    // Let the opened dialog settle before typing.
    await fixture.whenStable();
    redraw(fixture);
    const name = host.querySelector('#project-create-name') as HTMLInputElement;
    name.value = 'Unsaved project';
    name.dispatchEvent(new Event('input'));
    redraw(fixture);

    const createButtons = host.querySelectorAll('[role="dialog"] [footer] button');
    (createButtons[0] as HTMLButtonElement).click();
    redraw(fixture);

    expect(host.querySelectorAll('[role="dialog"]')).toHaveLength(2);
    expect((host.querySelector('#project-create-name') as HTMLInputElement).value).toBe('Unsaved project');

    const confirmation = host.querySelectorAll('[role="dialog"]')[1] as HTMLElement;
    (confirmation.querySelector('[footer] button') as HTMLButtonElement).click();
    redraw(fixture);
    expect(host.querySelectorAll('[role="dialog"]')).toHaveLength(1);
    expect((host.querySelector('#project-create-name') as HTMLInputElement).value).toBe('Unsaved project');

    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    (document.activeElement ?? document.body).dispatchEvent(escape);
    redraw(fixture);
    expect(escape.defaultPrevented).toBe(true);
    expect(host.querySelectorAll('[role="dialog"]')).toHaveLength(2);
    expect((host.querySelector('#project-create-name') as HTMLInputElement).value).toBe('Unsaved project');
  });

  it('renders fresh project detail instead of the list row when Edit is clicked', async () => {
    const summary = { ...project(5), name: 'List name', description: 'Версия из списка' };
    const freshDetail = new Subject<Project>();
    const api = emptyApi();
    api.get.mockImplementation((url: string) => {
      if (url === '/tasks/projects/page') return of(page([summary]));
      if (url === '/tasks/projects/5') return freshDetail;
      return of([]);
    });
    const { fixture } = await createFixture({ api });
    const host = inScreen(fixture.nativeElement);

    (host.querySelector('.icon-ghost-btn') as HTMLButtonElement).click();
    redraw(fixture);
    expect(host.querySelector('[data-testid="project-edit-loading"][role="status"]')).not.toBeNull();
    expect(host.querySelector('#project-edit-name')).toBeNull();

    freshDetail.next({ ...project(5), name: 'Fresh name', description: 'Актуальное описание с сервера' });
    freshDetail.complete();
    redraw(fixture);
    await fixture.whenStable();
    redraw(fixture);

    expect((host.querySelector('#project-edit-name') as HTMLInputElement).value).toBe('Fresh name');
    expect((host.querySelector('#project-edit-description') as HTMLTextAreaElement).value).toBe(
      'Актуальное описание с сервера',
    );
  });

  it('submits one sparse edit PATCH through the native form while pending', async () => {
    const pendingPatch = new Subject<void>();
    const api = emptyApi();
    api.get.mockImplementation((url: string) =>
      url === '/tasks/projects/3' ? of({ ...project(3), name: 'Current', description: 'Current description' }) : of([]),
    );
    api.patch.mockReturnValue(pendingPatch);
    const { fixture } = await createFixture({ api });
    const host = inScreen(fixture.nativeElement);

    fixture.componentInstance.openEditModal(project(3));
    redraw(fixture);
    await fixture.whenStable();
    redraw(fixture);
    const name = host.querySelector('#project-edit-name') as HTMLInputElement;
    name.value = '  Native rename  ';
    name.dispatchEvent(new Event('input'));
    redraw(fixture);
    expect(fixture.componentInstance.editForm.name).toBe('  Native rename  ');

    const form = host.querySelector('#project-edit-form') as HTMLFormElement;
    expect(form).not.toBeNull();
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    redraw(fixture);

    expect(api.patch).toHaveBeenCalledTimes(1);
    expect(api.patch).toHaveBeenCalledWith('/tasks/projects/3', { name: 'Native rename' });
  });

  it('issues one create request and locks every dismissal path when Save is activated twice while pending', async () => {
    const pendingSave = new Subject<Project>();
    const api = emptyApi();
    api.post.mockReturnValue(pendingSave);
    const { fixture } = await createFixture({ api });
    const host = inScreen(fixture.nativeElement);

    (host.querySelector('.view-header__actions .smt-button') as HTMLButtonElement).click();
    redraw(fixture);
    // Let the opened dialog settle before typing.
    await fixture.whenStable();
    redraw(fixture);
    const name = host.querySelector('#project-create-name') as HTMLInputElement;
    name.value = 'Pending project';
    name.dispatchEvent(new Event('input'));
    redraw(fixture);

    const footerButtons = host.querySelectorAll('[role="dialog"] [footer] button');
    const save = footerButtons[1] as HTMLButtonElement;
    save.click();
    save.click();
    redraw(fixture);

    expect(api.post).toHaveBeenCalledTimes(1);
    expect((host.querySelector('fieldset.project-create-form') as HTMLFieldSetElement).disabled).toBe(true);
    expect((host.querySelectorAll('[role="dialog"] [footer] button')[0] as HTMLButtonElement).disabled).toBe(true);
    expect((host.querySelectorAll('[role="dialog"] [footer] button')[1] as HTMLButtonElement).disabled).toBe(true);
    expect(host.querySelector('[role="dialog"] .smt-modal__close')).toBeNull();
  });

  it('guards create drafts for Cancel and modal dismissal while pristine drafts close directly', async () => {
    const { fixture, api } = await createFixture();
    const component = fixture.componentInstance;

    component.openCreateModal();
    component.requestCloseCreate();
    expect(component.isCreateModalOpen()).toBe(false);

    component.openCreateModal();
    component.createForm.name = 'Unsaved project';
    component.requestCloseCreate();
    expect(component.isCreateModalOpen()).toBe(true);
    expect(component.isCreateDiscardConfirmationOpen()).toBe(true);
    component.submitCreateProject();
    expect(api.post).not.toHaveBeenCalled();

    component.isCreateDiscardConfirmationOpen.set(false);
    expect(component.createForm.name).toBe('Unsaved project');
    redraw(fixture);
    const createModal = fixture.debugElement
      .queryAll(By.directive(SMTDialogComponent))
      .find((dialog) => dialog.componentInstance.open())!.componentInstance as SMTDialogComponent;
    createModal.closed.emit();
    expect(component.isCreateModalOpen()).toBe(true);
    expect(component.isCreateDiscardConfirmationOpen()).toBe(true);

    component.confirmDiscardCreate();
    expect(component.isCreateModalOpen()).toBe(false);
    expect(component.isCreateDiscardConfirmationOpen()).toBe(false);
  });

  it('prevents duplicate create submits and preserves a failed draft with one inline normalized error', async () => {
    const firstSave = new Subject<Project>();
    const retrySave = new Subject<Project>();
    const saves = [firstSave, retrySave];
    const api = emptyApi();
    api.post.mockImplementation(() => saves.shift()!);
    const { fixture, toast } = await createFixture({ api });
    const component = fixture.componentInstance;
    component.openCreateModal();
    component.createForm = { name: '  New project  ', description: '  Draft description  ' };

    component.submitCreateProject();
    component.submitCreateProject();
    redraw(fixture);

    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post).toHaveBeenCalledWith('/tasks/projects', {
      name: 'New project',
      description: 'Draft description',
    });
    expect(inScreen(fixture.nativeElement).querySelector('.project-create-form')?.disabled).toBe(true);
    expect(
      fixture.debugElement
        .queryAll(By.directive(SMTDialogComponent))
        .find((dialog) => dialog.componentInstance.open())!
        .componentInstance.dismissible(),
    ).toBe(false);
    component.requestCloseCreate();
    expect(component.isCreateModalOpen()).toBe(true);

    firstSave.error({ status: 422, title: 'Invalid', code: 'VALIDATION_ERROR', detail: 'Normalized create detail' });
    redraw(fixture);
    expect(component.createForm.name).toBe('  New project  ');
    expect(component.isSubmitting()).toBe(false);
    expect(
      inScreen(fixture.nativeElement).querySelector('[data-testid="project-create-save-error"][role="alert"]')
        ?.textContent,
    ).toContain('Normalized create detail');
    expect(toast.error).not.toHaveBeenCalled();

    component.submitCreateProject();
    expect(api.post).toHaveBeenCalledTimes(2);
    retrySave.next(project(10));
    retrySave.complete();
    expect(component.isCreateModalOpen()).toBe(false);
  });

  it('loads a fresh project before editing and exposes mismatch, error and retry states', async () => {
    const mismatch = new Subject<Project>();
    const failed = new Subject<Project>();
    const fresh = new Subject<Project>();
    const detailReads = [mismatch, failed, fresh];
    const api = emptyApi();
    api.get.mockImplementation((url: string) => {
      if (url === '/tasks/projects/7') return detailReads.shift()!;
      return of([]);
    });
    const { fixture } = await createFixture({ api });
    const component = fixture.componentInstance;

    component.openEditModal(project(7));
    redraw(fixture);
    expect(api.get).toHaveBeenCalledWith('/tasks/projects/7', undefined, { notifyError: false });
    expect(component.editLoading()).toBe(true);
    expect(component.forms.editingProject).toBeNull();
    expect(
      inScreen(fixture.nativeElement).querySelector('[data-testid="project-edit-loading"][role="status"]'),
    ).not.toBeNull();

    mismatch.next({ ...project(8), name: 'Wrong project' });
    mismatch.complete();
    redraw(fixture);
    expect(component.editLoadError()).toBe(true);
    expect(component.forms.editingProject).toBeNull();
    expect(
      inScreen(fixture.nativeElement).querySelector('[data-testid="project-edit-load-error"][role="alert"]'),
    ).not.toBeNull();

    component.retryEditLoad();
    failed.error({ status: 503, title: 'Unavailable', code: 'API_ERROR', detail: 'Unavailable' });
    redraw(fixture);
    expect(component.editLoadError()).toBe(true);

    (inScreen(fixture.nativeElement).querySelector('button.project-edit-retry') as HTMLButtonElement).click();
    fresh.next({ ...project(7), name: ' Current ', description: ' Current description ' });
    fresh.complete();
    redraw(fixture);
    expect(component.editLoading()).toBe(false);
    expect(component.editLoadError()).toBe(false);
    expect(component.editForm).toEqual({ name: 'Current', description: 'Current description', state: 'A' });
    expect(inScreen(fixture.nativeElement).querySelector('.project-edit-form')).not.toBeNull();
  });

  it('cancels obsolete edit detail reads on close and prevents another modal from resetting a live draft', async () => {
    const oldDetail = new Subject<Project>();
    const currentDetail = new Subject<Project>();
    const api = emptyApi();
    api.get.mockImplementation((url: string) => {
      if (url === '/tasks/projects/1') return oldDetail;
      if (url === '/tasks/projects/2') return currentDetail;
      return of([]);
    });
    const { fixture } = await createFixture({ api });
    const component = fixture.componentInstance;

    component.openEditModal(project(1));
    component.requestCloseEdit();
    component.openEditModal(project(2));
    oldDetail.next({ ...project(1), name: 'Obsolete' });
    currentDetail.next({ ...project(2), name: 'Current' });
    expect(component.forms.editingProject?.id).toBe(2);
    expect(component.editForm.name).toBe('Current');

    component.editForm.name = 'Live draft';
    component.openEditModal(project(1));
    component.openCreateModal();
    expect(component.forms.editingProject?.id).toBe(2);
    expect(component.editForm.name).toBe('Live draft');
    expect(component.isCreateModalOpen()).toBe(false);
  });

  it('guards whitespace-only edit draft changes and discards only after confirmation', async () => {
    const api = emptyApi();
    api.get.mockImplementation((url: string) =>
      url === '/tasks/projects/1' ? of({ ...project(1), name: 'Current', description: 'Description' }) : of([]),
    );
    const { fixture } = await createFixture({ api });
    const component = fixture.componentInstance;
    component.openEditModal(project(1));
    component.editForm.name = 'Current ';

    component.requestCloseEdit();
    expect(component.isEditModalOpen()).toBe(true);
    expect(component.isEditDiscardConfirmationOpen()).toBe(true);
    component.submitEditProject();
    expect(api.patch).not.toHaveBeenCalled();
    expect(component.isEditModalOpen()).toBe(true);
    component.isEditDiscardConfirmationOpen.set(false);
    expect(component.editForm.name).toBe('Current ');

    component.requestCloseEdit();
    component.confirmDiscardEdit();
    expect(component.isEditModalOpen()).toBe(false);
    expect(component.isEditDiscardConfirmationOpen()).toBe(false);
  });

  it('sends only changed normalized fields and closes a no-change edit without PATCH or success toast', async () => {
    const api = emptyApi();
    api.get.mockImplementation((url: string) =>
      /^\/tasks\/projects\/\d+$/.test(url)
        ? of({ ...project(Number(url.split('/').at(-1))), name: 'Current', description: 'Current description' })
        : of([]),
    );
    const { fixture, toast } = await createFixture({ api });
    const component = fixture.componentInstance;

    component.openEditModal(project(1));
    component.editForm.name = ' Renamed ';
    component.submitEditProject();
    expect(api.patch).toHaveBeenLastCalledWith('/tasks/projects/1', { name: 'Renamed' });

    component.openEditModal(project(2));
    component.editForm.description = '   ';
    component.submitEditProject();
    expect(api.patch).toHaveBeenLastCalledWith('/tasks/projects/2', { description: '' });

    component.openEditModal(project(3));
    const patchCount = api.patch.mock.calls.length;
    const successCount = toast.success.mock.calls.length;
    component.submitEditProject();
    expect(api.patch).toHaveBeenCalledTimes(patchCount);
    expect(toast.success).toHaveBeenCalledTimes(successCount);
    expect(component.isEditModalOpen()).toBe(false);
  });

  it('prevents duplicate edit saves, keeps a failed draft retryable and ignores mutation callbacks after destroy', async () => {
    const pendingPatch = new Subject<void>();
    const api = emptyApi();
    api.get.mockImplementation((url: string) =>
      url === '/tasks/projects/1' ? of({ ...project(1), name: 'Current', description: 'Description' }) : of([]),
    );
    api.patch.mockReturnValue(pendingPatch);
    const { fixture, toast } = await createFixture({ api });
    const component = fixture.componentInstance;
    component.openEditModal(project(1));
    component.editForm.name = 'Changed';

    component.submitEditProject();
    component.submitEditProject();
    redraw(fixture);
    expect(api.patch).toHaveBeenCalledTimes(1);
    expect(inScreen(fixture.nativeElement).querySelector('.project-edit-form')?.disabled).toBe(true);
    component.requestCloseEdit();
    component.confirmDiscardEdit();
    expect(component.isEditModalOpen()).toBe(true);

    pendingPatch.error({ status: 409, title: 'Conflict', code: 'CONFLICT', detail: 'Normalized edit detail' });
    redraw(fixture);
    expect(component.editForm.name).toBe('Changed');
    expect(
      inScreen(fixture.nativeElement).querySelector('[data-testid="project-edit-save-error"][role="alert"]')
        ?.textContent,
    ).toContain('Normalized edit detail');
    expect(toast.error).not.toHaveBeenCalled();

    const lateCreate = new Subject<Project>();
    api.post.mockReturnValue(lateCreate);
    component.requestCloseEdit();
    component.confirmDiscardEdit();
    component.openCreateModal();
    component.createForm.name = 'Late';
    component.submitCreateProject();
    fixture.destroy();
    lateCreate.next(project(99));
    expect(toast.success).not.toHaveBeenCalled();
  });

  it('asks the server for one page with the search, the state and the sort; a header sorts the whole list', async () => {
    vi.useFakeTimers();
    try {
      const api = listApi([project(1), project(2)]);
      const { fixture } = await createFixture({ api });
      const component = fixture.componentInstance;
      await vi.advanceTimersByTimeAsync(0);
      redraw(fixture);
      expect(api.get).toHaveBeenCalledWith(
        '/tasks/projects/page',
        expect.objectContaining({ limit: 10, cursor: undefined }),
        { notifyError: false },
      );
      expect(inScreen(fixture.nativeElement).querySelector('.count-badge')?.textContent?.trim()).toBe('2');

      component.setSearchQuery('Proj');
      await vi.advanceTimersByTimeAsync(300);
      expect(api.get).toHaveBeenLastCalledWith('/tasks/projects/page', expect.objectContaining({ q: 'Proj' }), {
        notifyError: false,
      });

      component.setSelectedState('P');
      expect(api.get).toHaveBeenLastCalledWith(
        '/tasks/projects/page',
        expect.objectContaining({ state: 'P', q: 'Proj' }),
        { notifyError: false },
      );

      component.onSort({ column: 'progress', sortBy: 'DESC' as never });
      expect(api.get).toHaveBeenLastCalledWith('/tasks/projects/page', expect.objectContaining({ sort: '-progress' }), {
        notifyError: false,
      });
      expect(component.exportOptions()).toEqual({ state: 'P' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('shows recoverable list loading and error states without empty results in list and cards', async () => {
    const first = new Subject<unknown>();
    const retry = new Subject<unknown>();
    const reads = [first, retry];
    const api = emptyApi();
    api.get.mockImplementation((url: string) =>
      url === '/tasks/projects/page' ? (reads.shift() ?? of(page([]))) : of([]),
    );
    const { fixture } = await createFixture({ api });

    expect(
      inScreen(fixture.nativeElement).querySelector('[data-testid="projects-list-loading"][role="status"]'),
    ).not.toBeNull();
    expect(inScreen(fixture.nativeElement).textContent).not.toContain('Проекты не найдены');

    fixture.componentInstance.viewMode = 'cards';
    redraw(fixture);
    expect(inScreen(fixture.nativeElement).querySelector('.project-card')).toBeNull();

    first.error({ status: 503, title: 'Unavailable', code: 'API_ERROR', detail: 'Unavailable' });
    redraw(fixture);
    expect(
      inScreen(fixture.nativeElement).querySelector('[data-testid="projects-list-error"][role="alert"]'),
    ).not.toBeNull();

    const retryButton = inScreen(fixture.nativeElement).querySelector('.projects-list-retry') as HTMLButtonElement;
    expect(retryButton.textContent).toContain('Повторить загрузку проектов');
    retryButton.click();
    retry.next(page([project(1)]));
    retry.complete();
    redraw(fixture);

    expect(inScreen(fixture.nativeElement).querySelector('[data-testid="projects-list-error"]')).toBeNull();
    expect(inScreen(fixture.nativeElement).querySelector('.project-card')?.textContent).toContain('Project 1');
  });

  it('describes terminal project tasks as closed in list and card progress, from the counts on each row', async () => {
    const api = listApi([{ ...project(1), totalTasks: 4, doneTasks: 2, progress: 50 }]);
    const { fixture } = await createFixture({ api });
    const host = inScreen(fixture.nativeElement);

    expect(host.querySelector('.progress-count')?.textContent?.trim()).toBe('2 / 4 закрыто');
    expect(host.querySelector('[role="progressbar"]')?.getAttribute('aria-valuenow')).toBe('50');
    expect(host.querySelector('[role="progressbar"]')?.getAttribute('aria-label')).toBe(
      'Доля закрытых задач проекта Project 1',
    );
    expect(host.querySelector('[data-testid="projects-stats-scope"]')?.textContent).toContain(
      'конечных статусах, включая выполненные и отменённые',
    );
    expect(api.get.mock.calls.filter(([url]) => String(url).endsWith('/stats'))).toHaveLength(0);

    fixture.componentInstance.viewMode = 'cards';
    redraw(fixture);

    expect(host.querySelector('.progress-count')?.textContent?.trim()).toBe('2 / 4 закрыто');
    expect(host.querySelector('[role="progressbar"]')?.getAttribute('aria-valuenow')).toBe('50');
  });

  it('keeps project-view-only users on plain project data without task statistics or drilldowns', async () => {
    const api = listApi([project(1)]);
    const withoutProgress = {
      ...PROJECTS_META,
      fields: PROJECTS_META.fields.filter((field) => field.key !== 'progress'),
    };
    const { fixture, router } = await createFixture({
      api,
      permissions: ['tasks.projects.view'],
      meta: withoutProgress,
    });

    expect(inScreen(fixture.nativeElement).querySelector('.project-name')).toBeNull();
    expect(inScreen(fixture.nativeElement).querySelector('.project-name-text')?.textContent).toContain('Project 1');
    expect(inScreen(fixture.nativeElement).querySelector('[role="progressbar"]')).toBeNull();
    expect(
      inScreen(fixture.nativeElement).querySelector('[data-testid="projects-stats-permission"][role="status"]'),
    ).not.toBeNull();

    fixture.componentInstance.viewMode = 'cards';
    redraw(fixture);
    expect(inScreen(fixture.nativeElement).querySelector('.project-title-btn')).toBeNull();
    expect(inScreen(fixture.nativeElement).querySelector('.view-tasks-link')).toBeNull();
    expect(inScreen(fixture.nativeElement).querySelector('.card-progress')).toBeNull();

    fixture.componentInstance.viewProjectTasks(project(1));
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('does not let task create or update permissions grant project actions', async () => {
    const api = listApi([project(1)]);
    const { fixture } = await createFixture({
      api,
      permissions: ['tasks.projects.view', 'tasks.items.create', 'tasks.items.update'],
    });
    const component = fixture.componentInstance;

    expect(component.canCreateProject()).toBe(false);
    expect(component.canUpdateProject()).toBe(false);
    component.openCreateModal();
    component.createForm = { name: 'Disallowed', description: '' };
    component.submitCreateProject();
    component.openEditModal(project(1));
    component.forms.editingProject = project(1);
    component.editForm = { name: 'Disallowed edit', description: '', state: 'A' };
    component.submitEditProject();

    expect(component.isCreateModalOpen()).toBe(false);
    expect(component.isEditModalOpen()).toBe(false);
    expect(api.post).not.toHaveBeenCalled();
    expect(api.patch).not.toHaveBeenCalled();
  });

  it('retains project create and update actions for scoped users and wildcard administrators', async () => {
    const api = listApi([project(1)]);
    const inner = api.get.getMockImplementation() as (url: string, ...rest: unknown[]) => unknown;
    api.get.mockImplementation((url: string, ...rest: unknown[]) =>
      url === '/tasks/projects/1' ? of(project(1)) : inner(url, ...rest),
    );
    api.post.mockReturnValue(of(project(2)));
    const { fixture } = await createFixture({
      api,
      permissions: ['tasks.projects.view', 'tasks.projects.create', 'tasks.projects.update', 'tasks.items.view'],
    });
    expect(fixture.componentInstance.canCreateProject()).toBe(true);
    expect(fixture.componentInstance.canUpdateProject()).toBe(true);
    expect(inScreen(fixture.nativeElement).querySelector('.smt-button')).not.toBeNull();
    expect(inScreen(fixture.nativeElement).querySelector('.icon-ghost-btn')).not.toBeNull();

    fixture.componentInstance.openCreateModal();
    fixture.componentInstance.createForm = { name: 'Created', description: '' };
    fixture.componentInstance.submitCreateProject();
    expect(api.post).toHaveBeenCalledTimes(1);

    fixture.componentInstance.openEditModal(project(1));
    fixture.componentInstance.editForm.name = 'Updated';
    fixture.componentInstance.submitEditProject();
    expect(api.patch).toHaveBeenCalledTimes(1);
  });

  it('reloads the first page with the filters cleared after a project is created', async () => {
    const created = { ...project(11), name: 'Newly created project' };
    const api = listApi([project(1)]);
    api.post.mockReturnValue(of(created));
    const { fixture } = await createFixture({ api });
    const component = fixture.componentInstance;
    component.searchQuery = 'old filter';
    component.selectedState = 'P';
    component.openCreateModal();
    component.createForm = { name: created.name, description: '' };

    component.submitCreateProject();

    expect(component.searchQuery).toBe('');
    expect(component.selectedState).toBe('all');
    const request = api.get.mock.calls.filter(([url]) => url === '/tasks/projects/page').at(-1)?.[1] as Record<
      string,
      unknown
    >;
    expect(request['state']).toBeUndefined();
    expect(request['q']).toBeUndefined();
    expect(request['cursor']).toBeUndefined();
  });

  it('manages project members modal: opens, loads members, adds and removes members', async () => {
    const api = emptyApi();
    const testProject = project(42);
    const mockMembers = [
      { projectId: 42, userId: 10, userName: 'Alice', userEmail: 'alice@example.com', accessKind: 'MANAGER' as const },
    ];

    api.get.mockImplementation((url: string) => {
      if (url === '/tasks/projects/42/members') return of(mockMembers);
      return of([]);
    });
    api.post.mockImplementation((url: string, body: any) => {
      if (url === '/tasks/projects/42/members') return of({ projectId: 42, ...body });
      return of({});
    });
    api.delete.mockImplementation((_url: string) => {
      return of({});
    });

    const { fixture, toast } = await createFixture({ api });
    const component = fixture.componentInstance;

    // 1. Open members modal
    component.members.openMembersModal(testProject);
    expect(component.members.selectedProjectForMembers()).toEqual(testProject);
    expect(api.get).toHaveBeenCalledWith('/tasks/projects/42/members');
    expect(component.members.projectMembers()).toEqual(mockMembers);

    // 2. Add member
    component.members.onAddProjectMember({ projectId: 42, userId: 20, accessKind: 'MEMBER' });
    expect(api.post).toHaveBeenCalledWith(
      '/tasks/projects/42/members',
      { userId: 20, accessKind: 'MEMBER' },
      { notifyError: false },
    );
    expect(toast.success).toHaveBeenCalled();

    // 3. Remove member
    component.members.onRemoveProjectMember({ projectId: 42, userId: 10, userName: 'Иван' });
    redraw(fixture);
    await fixture.whenStable();
    const dialog = document.querySelector('.smt-modal-confirm') as HTMLElement;
    expect(dialog.textContent).toContain('Иван');
    expect(api.delete).not.toHaveBeenCalled();
    [...dialog.querySelectorAll<HTMLButtonElement>('button')].at(-1)!.click();
    expect(api.delete).toHaveBeenCalledWith('/tasks/projects/42/members/10', { notifyError: false });
    expect(toast.success).toHaveBeenCalled();
    document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove());

    // 4. Close modal
    component.members.closeMembersModal();
    expect(component.members.selectedProjectForMembers()).toBeNull();
    expect(component.members.projectMembers()).toEqual([]);
  });
});
