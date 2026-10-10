import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { Role } from '@core/models/rbac.models';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { translateTest } from '@testing/i18n-test.stub';
import { RoleFormsService } from './role-forms.service';

const role = (id: number): Role => ({ id, name: `Role ${id}`, state: 'A', orderNo: 3, createdAt: '', modifiedAt: '' });

describe('RoleFormsService', () => {
  function setup(discard = true) {
    const api = {
      post: vi.fn((_path: string, _body: unknown, _options?: unknown): Observable<unknown> => of(role(9))),
      patch: vi.fn((_path: string, _body: unknown, _options?: unknown): Observable<unknown> => of({})),
      delete: vi.fn((_path: string): Observable<unknown> => of({})),
    };
    const toast = { success: vi.fn(), warning: vi.fn(), error: vi.fn(), show: vi.fn() };
    const modal = { confirm: vi.fn(() => of(discard)) };
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: toast },
        { provide: SMTModalService, useValue: modal },
        { provide: I18nService, useValue: { translate: translateTest, currentLang: signal('ru') } },
      ],
    });
    return { forms: TestBed.inject(RoleFormsService), api, toast, modal };
  }

  it('creates a role from a trimmed name only, and marks the name field otherwise', () => {
    const { forms, api, toast } = setup();
    const created = vi.fn();
    forms.openCreateModal();
    expect(forms.isCreateModalOpen()).toBe(true);
    expect(forms.createForm.name().touched()).toBe(false);
    expect(forms.createForm.name().required()).toBe(true);

    forms.newRole.set({ name: '   ', orderNo: 0 });
    expect(forms.submitCreateRole(created)).toBe(false);
    expect(forms.createForm.name().touched()).toBe(true);
    expect(forms.createForm.name().errors()[0].message).toBe('Укажите название роли');
    expect(toast.warning).not.toHaveBeenCalled();
    expect(api.post).not.toHaveBeenCalled();

    forms.newRole.set({ name: '  Аналитик ', orderNo: 0 });
    forms.submitCreateRole(created);
    expect(api.post).toHaveBeenCalledWith('/iam/roles', { name: 'Аналитик', orderNo: 0 }, { notifyError: false });
    expect(created).toHaveBeenCalledWith(role(9));
    expect(forms.isCreateModalOpen()).toBe(false);
    expect(forms.isSubmittingRole()).toBe(false);
  });

  it('sends one create while the first is running', () => {
    const { forms, api } = setup();
    const answer = new Subject<Role>();
    api.post.mockReturnValueOnce(answer);
    forms.openCreateModal();
    forms.newRole.set({ name: 'Аналитик', orderNo: 0 });

    forms.submitCreateRole(vi.fn());
    forms.submitCreateRole(vi.fn());

    expect(api.post).toHaveBeenCalledTimes(1);
  });

  it('puts the field messages of a refused create under the fields and keeps the dialog', () => {
    const { forms, api, toast } = setup();
    api.post.mockReturnValueOnce(
      throwError(() => ({
        status: 422,
        errors: [
          { field: 'name', code: 'Size', message: 'Слишком длинное название' },
          { field: 'code', code: 'X', message: 'Неизвестное поле' },
        ],
      })),
    );
    forms.openCreateModal();
    forms.newRole.set({ name: 'Аналитик', orderNo: 0 });

    forms.submitCreateRole(vi.fn());

    expect(forms.createErrors()).toEqual({ fields: { name: 'Слишком длинное название' }, other: ['Неизвестное поле'] });
    expect(forms.isCreateModalOpen()).toBe(true);
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('reports a refused create of no field once, as a toast', () => {
    const { forms, api, toast } = setup();
    api.post.mockReturnValueOnce(throwError(() => ({ status: 500, detail: 'Сервер недоступен' })));
    forms.openCreateModal();
    forms.newRole.set({ name: 'Аналитик', orderNo: 0 });

    forms.submitCreateRole(vi.fn());

    expect(toast.error).toHaveBeenCalledWith('Сервер недоступен');
    expect(forms.isCreateModalOpen()).toBe(true);
  });

  it('closes an untouched create dialog at once and asks before a typed one is lost', () => {
    const { forms, modal } = setup(false);
    forms.openCreateModal();
    forms.requestCloseCreate();
    expect(modal.confirm).not.toHaveBeenCalled();
    expect(forms.isCreateModalOpen()).toBe(false);

    forms.openCreateModal();
    forms.newRole.set({ name: 'Аналитик', orderNo: 0 });
    forms.requestCloseCreate();
    expect(modal.confirm).toHaveBeenCalledTimes(1);
    expect(forms.isCreateModalOpen()).toBe(true);
  });

  it('edits the chosen role and keeps the dialog open while the name is empty', () => {
    const { forms, api } = setup();
    const saved = vi.fn();
    forms.submitEditRole(saved);
    expect(api.patch).not.toHaveBeenCalled();

    forms.openEditRoleModal(role(4));
    expect(forms.editRole()).toEqual({ name: 'Role 4', state: 'A', orderNo: 3 });
    forms.editRole.update((value) => ({ ...value, name: '' }));
    expect(forms.submitEditRole(saved)).toBe(false);
    expect(forms.editForm.name().errors()[0].message).toBe('Укажите название роли');
    expect(forms.isEditModalOpen()).toBe(true);

    forms.editRole.update((value) => ({ ...value, name: 'Ревизор' }));
    forms.submitEditRole(saved);
    expect(api.patch).toHaveBeenCalledWith(
      '/iam/roles/4',
      { name: 'Ревизор', state: 'A', orderNo: 3 },
      expect.any(Object),
    );
    expect(saved).toHaveBeenCalledTimes(1);
    expect(forms.isEditModalOpen()).toBe(false);
  });

  it('keeps the state of the superadministrator role fixed', () => {
    const { forms } = setup();
    forms.openEditRoleModal({ ...role(1), pcode: 'admin' });
    expect(forms.editForm.state().disabled()).toBe(true);

    forms.openEditRoleModal(role(4));
    expect(forms.editForm.state().disabled()).toBe(false);
  });

  it('asks before changes of an edited role are lost', () => {
    const { forms, modal } = setup(true);
    forms.openEditRoleModal(role(4));
    forms.requestCloseEdit();
    expect(modal.confirm).not.toHaveBeenCalled();
    expect(forms.isEditModalOpen()).toBe(false);

    forms.openEditRoleModal(role(4));
    forms.editRole.update((value) => ({ ...value, orderNo: 7 }));
    forms.requestCloseEdit();
    expect(modal.confirm).toHaveBeenCalledTimes(1);
    expect(forms.isEditModalOpen()).toBe(false);
  });

  it('asks before deleting, never while something is saved, and deletes once', () => {
    const { forms, api } = setup();
    const deleted = vi.fn();
    const answer = new Subject<unknown>();
    api.delete.mockReturnValueOnce(answer);

    forms.openDeleteRoleModal(role(4), true, false);
    forms.openDeleteRoleModal(role(4), false, true);
    forms.openDeleteRoleModal({ ...role(4), id: Number.MAX_SAFE_INTEGER + 1 }, false, false);
    expect(forms.isDeleteModalOpen()).toBe(false);
    forms.deleteRole(role(4), deleted);
    expect(api.delete).not.toHaveBeenCalled();

    forms.openDeleteRoleModal(role(4), false, false);
    expect(forms.deletingRole?.id).toBe(4);
    forms.deleteRole(role(4), deleted);
    forms.deleteRole(role(4), deleted);
    forms.closeDeleteRoleModal();
    expect(api.delete).toHaveBeenCalledTimes(1);
    expect(forms.isDeleteModalOpen()).toBe(true);

    answer.next({});
    expect(deleted).toHaveBeenCalledTimes(1);
    expect(forms.isDeleteModalOpen()).toBe(false);
    expect(forms.deletingRole).toBeNull();
  });
});
