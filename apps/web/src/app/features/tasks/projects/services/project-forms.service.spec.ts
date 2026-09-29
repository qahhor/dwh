import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Project } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { ProjectFormsService } from './project-forms.service';

const project = (id: number, name = `Project ${id}`, description = ''): Project => ({
  id,
  name,
  description,
  state: 'A',
  createdAt: '2026-09-06T00:00:00Z',
});

describe('ProjectFormsService', () => {
  let reads: Record<number, Observable<unknown> | Observable<unknown>[]>;
  let api: { get: ReturnType<typeof vi.fn>; post: ReturnType<typeof vi.fn>; patch: ReturnType<typeof vi.fn> };
  let toast: { success: ReturnType<typeof vi.fn>; warning: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  let forms: ProjectFormsService;

  function setup(permissions = ['*.*']) {
    api = {
      get: vi.fn((url: string) => {
        const read = reads[Number(url.split('/').at(-1))];
        return Array.isArray(read) ? read.shift()! : (read ?? of(project(Number(url.split('/').at(-1)))));
      }),
      post: vi.fn(() => of(project(10))),
      patch: vi.fn(() => of(undefined)),
    };
    toast = { success: vi.fn(), warning: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        ProjectFormsService,
        PermissionService,
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: toast },
      ],
    });
    TestBed.inject(PermissionService).setPermissions(permissions);
    forms = TestBed.inject(ProjectFormsService);
  }

  beforeEach(() => {
    reads = {};
  });

  it('closes a pristine create draft directly and asks before dropping a changed one', () => {
    setup();
    forms.openCreateModal();
    forms.requestCloseCreate();
    expect(forms.isCreateModalOpen()).toBe(false);

    forms.openCreateModal();
    forms.createForm.name = 'Unsaved project';
    forms.requestCloseCreate();
    expect(forms.isCreateModalOpen()).toBe(true);
    expect(forms.isCreateDiscardConfirmationOpen()).toBe(true);
    forms.submitCreateProject();
    expect(api.post).not.toHaveBeenCalled();

    forms.cancelNavigationDiscard('create');
    expect(forms.createForm.name).toBe('Unsaved project');
    forms.requestCloseCreate();
    forms.confirmDiscardCreate();
    expect(forms.isCreateModalOpen()).toBe(false);
    expect(forms.isCreateDiscardConfirmationOpen()).toBe(false);
  });

  it('creates once while pending, keeps a failed draft with the server reason, and succeeds on retry', () => {
    setup();
    const [first, retry] = [new Subject<Project>(), new Subject<Project>()];
    const saves = [first, retry];
    api.post.mockImplementation(() => saves.shift()!);
    const created = vi.fn();
    forms.onProjectCreated = created;
    forms.openCreateModal();
    forms.createForm = { name: '  New project  ', description: '  Draft description  ' };

    forms.submitCreateProject();
    forms.submitCreateProject();
    forms.requestCloseCreate();
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post).toHaveBeenCalledWith('/tasks/projects', { name: 'New project', description: 'Draft description' });
    expect(forms.isCreateModalOpen()).toBe(true);

    first.error({ status: 422, detail: 'Normalized create detail' });
    expect(forms.createForm.name).toBe('  New project  ');
    expect(forms.isSubmitting()).toBe(false);
    expect(forms.createSaveError()).toBe('Normalized create detail');
    expect(toast.error).not.toHaveBeenCalled();

    forms.submitCreateProject();
    retry.next(project(10));
    expect(forms.isCreateModalOpen()).toBe(false);
    expect(created).toHaveBeenCalledWith(project(10));
  });

  it('needs a name to create a project', () => {
    setup();
    forms.openCreateModal();
    forms.createForm.name = '   ';
    forms.submitCreateProject();
    expect(toast.warning).toHaveBeenCalled();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('reads a fresh project before editing and exposes mismatch, error and retry states', () => {
    const [mismatch, failed, fresh] = [new Subject<Project>(), new Subject<Project>(), new Subject<Project>()];
    reads[7] = [mismatch, failed, fresh];
    setup();

    forms.openEditModal(project(7));
    expect(api.get).toHaveBeenCalledWith('/tasks/projects/7', undefined, { notifyError: false });
    expect(forms.editLoading()).toBe(true);
    expect(forms.editingProject).toBeNull();

    mismatch.next(project(8, 'Wrong project'));
    expect(forms.editLoadError()).toBe(true);
    expect(forms.editingProject).toBeNull();

    forms.retryEditLoad();
    failed.error({ status: 503 });
    expect(forms.editLoadError()).toBe(true);

    forms.retryEditLoad();
    fresh.next(project(7, ' Current ', ' Current description '));
    expect(forms.editLoading()).toBe(false);
    expect(forms.editLoadError()).toBe(false);
    expect(forms.editForm).toEqual({ name: 'Current', description: 'Current description', state: 'A' });
  });

  it('drops an obsolete edit read on close and lets no other dialog reset a live draft', () => {
    const [oldRead, currentRead] = [new Subject<Project>(), new Subject<Project>()];
    reads[1] = oldRead;
    reads[2] = currentRead;
    setup();

    forms.openEditModal(project(1));
    forms.requestCloseEdit();
    forms.openEditModal(project(2));
    oldRead.next(project(1, 'Obsolete'));
    currentRead.next(project(2, 'Current'));
    expect(forms.editingProject?.id).toBe(2);
    expect(forms.editForm.name).toBe('Current');

    forms.editForm.name = 'Live draft';
    forms.openEditModal(project(1));
    forms.openCreateModal();
    expect(forms.editingProject?.id).toBe(2);
    expect(forms.editForm.name).toBe('Live draft');
    expect(forms.isCreateModalOpen()).toBe(false);
  });

  it('asks before dropping a whitespace-only change and drops it only after confirmation', () => {
    reads[1] = of(project(1, 'Current', 'Description'));
    setup();
    forms.openEditModal(project(1));
    forms.editForm.name = 'Current ';

    forms.requestCloseEdit();
    expect(forms.isEditDiscardConfirmationOpen()).toBe(true);
    forms.submitEditProject();
    expect(api.patch).not.toHaveBeenCalled();
    forms.cancelNavigationDiscard('edit');
    expect(forms.editForm.name).toBe('Current ');

    forms.requestCloseEdit();
    forms.confirmDiscardEdit();
    expect(forms.isEditModalOpen()).toBe(false);
    expect(forms.isEditDiscardConfirmationOpen()).toBe(false);
  });

  it('sends only the changed normalized fields and closes an unchanged edit without a PATCH', () => {
    for (const id of [1, 2, 3]) reads[id] = of(project(id, 'Current', 'Current description'));
    setup();
    const updated = vi.fn();
    forms.onProjectUpdated = updated;

    forms.openEditModal(project(1));
    forms.editForm.name = ' Renamed ';
    forms.submitEditProject();
    expect(api.patch).toHaveBeenLastCalledWith('/tasks/projects/1', { name: 'Renamed' }, expect.any(Object));
    expect(updated).toHaveBeenCalledTimes(1);

    forms.openEditModal(project(2));
    forms.editForm.description = '   ';
    forms.editForm.state = 'P';
    forms.submitEditProject();
    expect(api.patch).toHaveBeenLastCalledWith(
      '/tasks/projects/2',
      { description: '', state: 'P' },
      expect.any(Object),
    );

    forms.openEditModal(project(3));
    forms.submitEditProject();
    expect(api.patch).toHaveBeenCalledTimes(2);
    expect(toast.success).toHaveBeenCalledTimes(2);
    expect(forms.isEditModalOpen()).toBe(false);
  });

  it('saves an edit once while pending, keeps a failed draft retryable and ignores answers after destroy', () => {
    reads[1] = of(project(1, 'Current', 'Description'));
    setup();
    const pending = new Subject<void>();
    api.patch.mockReturnValue(pending);
    forms.openEditModal(project(1));
    forms.editForm.name = 'Changed';

    forms.submitEditProject();
    forms.submitEditProject();
    forms.requestCloseEdit();
    forms.confirmDiscardEdit();
    expect(api.patch).toHaveBeenCalledTimes(1);
    expect(forms.isEditModalOpen()).toBe(true);

    pending.error({ status: 409, detail: 'Normalized edit detail' });
    expect(forms.editForm.name).toBe('Changed');
    expect(forms.editSaveError()).toBe('Normalized edit detail');
    expect(toast.error).not.toHaveBeenCalled();

    const lateCreate = new Subject<Project>();
    api.post.mockReturnValue(lateCreate);
    forms.requestCloseEdit();
    forms.confirmDiscardEdit();
    forms.openCreateModal();
    forms.createForm.name = 'Late';
    forms.submitCreateProject();
    forms.destroy();
    lateCreate.next(project(99));
    expect(toast.success).not.toHaveBeenCalled();
  });

  it('does not let task create or update permissions grant project actions', () => {
    setup(['tasks.projects.view', 'tasks.items.create', 'tasks.items.update']);

    expect(forms.canCreateProject()).toBe(false);
    expect(forms.canUpdateProject()).toBe(false);
    forms.openCreateModal();
    forms.openEditModal(project(1));
    expect(forms.isCreateModalOpen()).toBe(false);
    expect(forms.isEditModalOpen()).toBe(false);
    expect(api.get).not.toHaveBeenCalled();
  });

  it('asks before leaving the page with a changed draft, and closes clean dialogs on the way out', () => {
    setup();
    forms.openCreateModal();
    expect(forms.canLeaveRecordPage()).toBe(true);
    expect(forms.isCreateModalOpen()).toBe(false);

    forms.openCreateModal();
    forms.createForm.name = 'Draft';
    expect(forms.canLeaveRecordPage()).toBeInstanceOf(Observable);
  });
});
