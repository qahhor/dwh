import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Role } from '@core/models/rbac.models';
import { buttonText } from '@testing/button-text';
import { inScreen } from '@testing/in-screen';
import { RoleModalsComponent } from './role-modals.component';

describe('RoleModalsComponent', () => {
  const role = (id: number, name: string, extra: Partial<Role> = {}): Role => ({
    id,
    name,
    state: 'A',
    orderNo: id,
    createdAt: '2026-08-30T00:00:00Z',
    modifiedAt: '2026-08-30T00:00:00Z',
    ...extra,
  });
  const admin = role(1, 'Администратор', { pcode: 'admin' });
  const analyst = role(2, 'Аналитик');

  function setup(inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(RoleModalsComponent);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    const component = fixture.componentInstance;
    const asked = {
      closeCreate: vi.fn(),
      submitCreate: vi.fn(),
      submitEdit: vi.fn(),
      closeDelete: vi.fn(),
      confirmDelete: vi.fn(),
      closeDiscard: vi.fn(),
      discard: vi.fn(),
      saveAndSwitch: vi.fn(),
    };
    component.closeCreate.subscribe(asked.closeCreate);
    component.submitCreate.subscribe(asked.submitCreate);
    component.submitEdit.subscribe(asked.submitEdit);
    component.closeDelete.subscribe(asked.closeDelete);
    component.confirmDelete.subscribe(asked.confirmDelete);
    component.closeDiscard.subscribe(asked.closeDiscard);
    component.confirmDiscardAndSwitch.subscribe(asked.discard);
    component.saveAndSwitch.subscribe(asked.saveAndSwitch);
    fixture.detectChanges();
    const screen = inScreen(fixture.nativeElement);
    const byText = (text: string) =>
      (Array.from(screen.querySelectorAll('button')) as HTMLButtonElement[]).find((item) => buttonText(item) === text)!;
    return { fixture, screen, byText, asked };
  }

  it('shows no dialog while none is asked for', () => {
    const { screen } = setup();

    expect(screen.querySelector('.smt-modal')).toBeNull();
  });

  it('asks for the name of a new role only after an attempt without one', () => {
    const fresh = setup({ isCreateModalOpen: true });
    expect(fresh.screen.querySelector('.smt-modal__title').textContent).toBe('Создание новой роли');
    expect(fresh.screen.querySelector('label[for="role-create-name"]')).not.toBeNull();
    expect(fresh.screen.querySelector('#role-create-name-error')).toBeNull();
    fresh.byText('Создать').click();
    fresh.byText('Отмена').click();
    expect(fresh.asked.submitCreate).toHaveBeenCalledTimes(1);
    expect(fresh.asked.closeCreate).toHaveBeenCalledTimes(1);
    fresh.fixture.destroy();

    const tried = setup({ isCreateModalOpen: true, isCreateSubmitted: true, newRoleForm: { name: ' ', orderNo: 0 } });
    expect(tried.screen.querySelector('#role-create-name-error').textContent).toBe('Укажите название роли');
    expect(tried.screen.querySelector('#role-create-name').getAttribute('aria-describedby')).toBe(
      'role-create-name-error',
    );
  });

  it('keeps the superadministrator role active: its state cannot be changed', () => {
    const adminEdit = setup({
      isEditModalOpen: true,
      editingRole: admin,
      editRoleForm: { name: 'Администратор', state: 'A', orderNo: 1 },
    });
    expect(adminEdit.screen.querySelector('.smt-modal__title').textContent).toBe('Редактирование роли');
    expect((adminEdit.screen.querySelector('#role-edit-state') as HTMLButtonElement).disabled).toBe(true);
    expect(adminEdit.screen.textContent).toContain('Роль суперадминистратора всегда активна.');
    adminEdit.byText('Сохранить').click();
    expect(adminEdit.asked.submitEdit).toHaveBeenCalledTimes(1);
    adminEdit.fixture.destroy();

    const other = setup({
      isEditModalOpen: true,
      editingRole: analyst,
      editRoleForm: { name: 'Аналитик', state: 'A', orderNo: 2 },
    });
    expect((other.screen.querySelector('#role-edit-state') as HTMLButtonElement).disabled).toBe(false);
    expect(other.screen.textContent).not.toContain('Роль суперадминистратора всегда активна.');
  });

  it('names the role to delete and cannot be dismissed while the deletion runs', () => {
    const asking = setup({ isDeleteModalOpen: true, deletingRole: analyst });
    expect(asking.screen.querySelector('.delete-title strong').textContent).toBe('Аналитик');
    asking.byText('Удалить').click();
    expect(asking.asked.confirmDelete).toHaveBeenCalledTimes(1);
    asking.fixture.destroy();

    const deleting = setup({ isDeleteModalOpen: true, deletingRole: analyst, isSubmittingRole: true });
    expect(deleting.screen.querySelector('.smt-modal__close')).toBeNull();
    expect(deleting.byText('Отмена').disabled).toBe(true);
  });

  it('warns about unsaved rights before switching and offers to discard or save them', () => {
    const { screen, byText, asked } = setup({
      isDiscardPermissionsModalOpen: true,
      selectedRole: analyst,
      dirtyPermissionsCount: 4,
    });

    expect(screen.querySelector('.delete-title').textContent).toContain('«Аналитик»');
    expect(screen.querySelector('.delete-title').textContent).toContain('(4)');
    byText('Сбросить и перейти').click();
    byText('Сохранить и перейти').click();
    byText('Отмена').click();

    expect(asked.discard).toHaveBeenCalledTimes(1);
    expect(asked.saveAndSwitch).toHaveBeenCalledTimes(1);
    expect(asked.closeDiscard).toHaveBeenCalledTimes(1);
  });

  it('holds discarding and cancelling while the rights are being saved', () => {
    const { screen, byText } = setup({ isDiscardPermissionsModalOpen: true, selectedRole: analyst, isSaving: true });

    expect(byText('Сбросить и перейти').disabled).toBe(true);
    expect(byText('Отмена').disabled).toBe(true);
    expect(screen.querySelector('.smt-modal__close')).toBeNull();
  });
});
