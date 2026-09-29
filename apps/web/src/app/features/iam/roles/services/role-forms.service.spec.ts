import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { Role } from '@core/models/rbac.models';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { translateTest } from '@testing/i18n-test.stub';
import { RoleFormsService } from './role-forms.service';

const role = (id: number): Role => ({ id, name: `Role ${id}`, state: 'A', orderNo: 3, createdAt: '', modifiedAt: '' });

describe('RoleFormsService', () => {
  function setup() {
    const api = {
      post: vi.fn((_path: string, _body: unknown) => of(role(9))),
      patch: vi.fn((_path: string, _body: unknown) => of({})),
      delete: vi.fn((_path: string): Observable<unknown> => of({})),
    };
    const toast = { success: vi.fn(), warning: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: toast },
        { provide: I18nService, useValue: { translate: translateTest, currentLang: signal('ru') } },
      ],
    });
    return { forms: TestBed.inject(RoleFormsService), api, toast };
  }

  it('creates a role from a trimmed name only, and says what is missing otherwise', () => {
    const { forms, api, toast } = setup();
    const created = vi.fn();
    forms.openCreateModal();
    expect(forms.isCreateModalOpen()).toBe(true);
    expect(forms.isCreateSubmitted).toBe(false);

    forms.newRoleForm.name = '   ';
    forms.submitCreateRole(created);
    expect(forms.isCreateSubmitted).toBe(true);
    expect(toast.warning).toHaveBeenCalledTimes(1);
    expect(api.post).not.toHaveBeenCalled();

    forms.newRoleForm = { name: '  Аналитик ', orderNo: 0 };
    forms.submitCreateRole(created);
    expect(api.post).toHaveBeenCalledWith('/iam/roles', { name: 'Аналитик', orderNo: 0 });
    expect(created).toHaveBeenCalledWith(role(9));
    expect(forms.isCreateModalOpen()).toBe(false);
    expect(forms.isSubmittingRole()).toBe(false);
  });

  it('edits the chosen role and keeps the dialog open while the name is empty', () => {
    const { forms, api, toast } = setup();
    const saved = vi.fn();
    forms.submitEditRole(saved);
    expect(api.patch).not.toHaveBeenCalled();

    forms.openEditRoleModal(role(4));
    expect(forms.editRoleForm).toEqual({ name: 'Role 4', state: 'A', orderNo: 3 });
    forms.editRoleForm.name = '';
    forms.submitEditRole(saved);
    expect(toast.warning).toHaveBeenCalledTimes(1);
    expect(forms.isEditModalOpen()).toBe(true);

    forms.editRoleForm.name = 'Ревизор';
    forms.submitEditRole(saved);
    expect(api.patch).toHaveBeenCalledWith(
      '/iam/roles/4',
      { name: 'Ревизор', state: 'A', orderNo: 3 },
      expect.any(Object),
    );
    expect(saved).toHaveBeenCalledTimes(1);
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
