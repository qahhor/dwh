import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { Role } from '@core/models/rbac.models';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { translateTest } from '@testing/i18n-test.stub';
import { ModuleGroup } from '../roles.models';
import { RolePermissionsEditor } from './role-permissions-editor.service';

const role = (id: number, pcode?: string): Role =>
  ({ id, name: `Role ${id}`, pcode, state: 'A', orderNo: 0, createdAt: '', modifiedAt: '' }) as Role;
const audit: ModuleGroup[] = [
  {
    moduleCode: 'audit',
    moduleName: 'Аудит',
    isExpanded: true,
    forms: [
      {
        module: 'audit',
        formCode: 'audit.events',
        formName: 'События',
        actions: [
          { action: 'view', actionName: 'Просмотр' },
          { action: 'edit', actionName: 'Редактирование' },
        ],
      },
    ],
  },
];

describe('RolePermissionsEditor', () => {
  function setup(permissions: (roleId: number) => Observable<string[]> = () => of(['audit.events.view'])) {
    const api = {
      get: vi.fn((path: string) => permissions(Number(path.split('/')[3]))),
      put: vi.fn((_path: string, _body: unknown, _options?: unknown): Observable<unknown> => of({})),
    };
    const toast = { success: vi.fn(), error: vi.fn(), show: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        RolePermissionsEditor,
        PermissionService,
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: toast },
        { provide: I18nService, useValue: { translate: translateTest, currentLang: signal('ru') } },
      ],
    });
    TestBed.inject(PermissionService).setPermissions(['md.roles.view', 'md.roles.grant']);
    return { editor: TestBed.inject(RolePermissionsEditor), api, toast };
  }
  /** The matrix is a resource: its request starts on a tick and its answer lands on a later task. */
  async function settle() {
    TestBed.tick();
    await new Promise((resolve) => setTimeout(resolve));
  }

  it('loads the matrix of the chosen role and counts the draft against it', async () => {
    const { editor, api } = setup();
    editor.load(role(1));
    expect(editor.canEditPermissions()).toBe(false);
    await settle();

    expect(api.get).toHaveBeenCalledWith('/iam/roles/1/permissions', undefined, { notifyError: false });
    expect(editor.isLoading()).toBe(false);
    expect(editor.canEditPermissions()).toBe(true);
    expect(editor.isPermissionsDirty()).toBe(false);

    editor.togglePermission('audit.events', 'edit', true);
    expect(editor.isPermissionsDirty()).toBe(true);
    expect(editor.dirtyPermissionsCount()).toBe(1);
    expect(editor.isPermissionDirty('audit.events', 'edit')).toBe(true);
    expect(editor.isPermissionDirty('audit.events', 'view')).toBe(false);
  });

  it('grants, narrows to reading and resets every module at once', async () => {
    const { editor } = setup();
    editor.load(role(1));
    await settle();

    editor.toggleAllPermissions(audit, true);
    expect([...editor.rolePermissions()]).toEqual(['audit.events.view', 'audit.events.edit']);
    expect(editor.isPermissionsDirty()).toBe(true);
    editor.toggleReadOnlyAllPermissions(audit);
    expect([...editor.rolePermissions()]).toEqual(['audit.events.view']);

    editor.toggleAllPermissions(audit, true);
    editor.resetMatrixChanges();
    expect(editor.isPermissionsDirty()).toBe(false);
    expect([...editor.rolePermissions()]).toEqual(['audit.events.view']);
  });

  it('never edits the administrator matrix, a matrix without the grant right, or a failed one', async () => {
    const { editor } = setup((roleId) => (roleId === 2 ? throwError(() => ({ title: 'Нет ответа' })) : of([])));
    editor.load(role(3, 'admin'));
    await settle();
    expect(editor.hasPermission('any.form', 'view')).toBe(true);
    expect(editor.canEditPermissions()).toBe(false);

    editor.load(role(2));
    await settle();
    expect(editor.permissionsError()).toBe('Нет ответа');
    expect(editor.canEditPermissions()).toBe(false);

    editor.load(role(1));
    await settle();
    TestBed.inject(PermissionService).setPermissions(['md.roles.view', 'md.roles.update']);
    editor.toggleAllPermissions(audit, true);
    editor.savePermissions();
    expect(editor.rolePermissions().size).toBe(0);
  });

  it('drops the answer still due for a role no longer chosen', async () => {
    const answers = new Map([1, 2].map((id) => [id, new Subject<string[]>()]));
    const { editor } = setup((roleId) => answers.get(roleId)!);
    editor.load(role(1));
    await settle();
    editor.load(role(2));
    await settle();

    answers.get(1)!.next(['audit.events.view']);
    await settle();
    expect(editor.rolePermissions().size).toBe(0);
    expect(editor.canEditPermissions()).toBe(false);

    answers.get(2)!.next(['audit.events.edit']);
    await settle();
    expect([...editor.rolePermissions()]).toEqual(['audit.events.edit']);
    expect(editor.canEditPermissions()).toBe(true);
  });

  it('saves the draft once and makes it the saved state', async () => {
    const { editor, api } = setup();
    const saved = vi.fn();
    editor.load(role(1));
    await settle();
    editor.togglePermission('audit.events', 'edit', true);

    editor.savePermissions(saved);

    expect(api.put).toHaveBeenCalledTimes(1);
    expect(api.put).toHaveBeenCalledWith(
      '/iam/roles/1/permissions',
      [
        { formCode: 'audit.events', action: 'view' },
        { formCode: 'audit.events', action: 'edit' },
      ],
      expect.any(Object),
    );
    expect(saved).toHaveBeenCalledTimes(1);
    expect(editor.isPermissionsDirty()).toBe(false);
  });

  it('gives the saved role its new revision and takes a re-read role without dropping the draft', async () => {
    const { editor, api } = setup();
    const saved = vi.fn();
    editor.load({ ...role(1), revision: 3 });
    await settle();
    editor.togglePermission('audit.events', 'edit', true);

    editor.savePermissions(saved);

    expect(api.put.mock.calls[0][2]).toEqual(expect.objectContaining({ notifyError: false, ifMatch: 3 }));
    expect(saved).toHaveBeenCalledWith(expect.objectContaining({ id: 1, revision: 4 }));
    editor.togglePermission('audit.events', 'view', false);
    editor.refreshSelected({ ...role(1), name: 'Renamed', revision: 5 });
    expect(editor.selectedRole()).toEqual(expect.objectContaining({ name: 'Renamed', revision: 5 }));
    expect(editor.isPermissionsDirty()).toBe(true);
    editor.refreshSelected({ ...role(2), revision: 9 });
    expect(editor.selectedRole()?.id).toBe(1);
  });

  it('shows a matrix save refused over a newer revision once, keeps the draft and offers to read it again', async () => {
    const { editor, api, toast } = setup();
    api.put.mockReturnValueOnce(
      throwError(() => ({ status: 409, code: 'revision_conflict', detail: 'Запись уже изменил другой пользователь' })),
    );
    const reload = vi.fn();
    editor.load({ ...role(1), revision: 3 });
    await settle();
    editor.togglePermission('audit.events', 'edit', true);

    editor.savePermissions(undefined, reload);

    expect(toast.error).not.toHaveBeenCalled();
    expect(toast.show).toHaveBeenCalledTimes(1);
    expect(editor.isPermissionsDirty()).toBe(true);
    toast.show.mock.calls[0][4].run();
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('forgets the matrix and its draft when the chosen role is deleted, so nothing is left unsaved', async () => {
    const { editor } = setup();
    editor.load(role(1));
    await settle();
    editor.togglePermission('audit.events', 'edit', true);

    editor.clearIfSelected(2);
    expect(editor.selectedRole()?.id).toBe(1);
    editor.clearIfSelected(1);

    expect(editor.selectedRole()).toBeNull();
    expect(editor.rolePermissions().size).toBe(0);
    expect(editor.isPermissionsDirty()).toBe(false);
    expect(editor.isLoading()).toBe(false);
  });
});
