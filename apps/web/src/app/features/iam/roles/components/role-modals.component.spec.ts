import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { Role } from '@core/models/rbac.models';
import { ApiService } from '@core/services/api.service';
import { buttonText } from '@testing/button-text';
import { inScreen } from '@testing/in-screen';
import { tickInZone } from '@shared/ui-kit/testing/zone-tick';
import { RoleFormsService } from '../services/role-forms.service';
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
    TestBed.configureTestingModule({
      providers: [{ provide: ApiService, useValue: { post: vi.fn(() => of(null)), patch: vi.fn(), delete: vi.fn() } }],
    });
    const forms = TestBed.inject(RoleFormsService);
    const fixture = TestBed.createComponent(RoleModalsComponent);
    fixture.componentRef.setInput('createForm', forms.createForm);
    fixture.componentRef.setInput('editForm', forms.editForm);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    const component = fixture.componentInstance;
    const asked = {
      closeCreate: vi.fn(),
      submitCreate: vi.fn(),
      closeEdit: vi.fn(),
      submitEdit: vi.fn(),
      closeDelete: vi.fn(),
      confirmDelete: vi.fn(),
      closeDiscard: vi.fn(),
      discard: vi.fn(),
      saveAndSwitch: vi.fn(),
    };
    component.closeCreate.subscribe(asked.closeCreate);
    component.submitCreate.subscribe(asked.submitCreate);
    component.closeEdit.subscribe(asked.closeEdit);
    component.submitEdit.subscribe(asked.submitEdit);
    component.closeDelete.subscribe(asked.closeDelete);
    component.confirmDelete.subscribe(asked.confirmDelete);
    component.closeDiscard.subscribe(asked.closeDiscard);
    component.confirmDiscardAndSwitch.subscribe(asked.discard);
    component.saveAndSwitch.subscribe(asked.saveAndSwitch);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    const screen = inScreen(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    const byText = (text: string) =>
      (Array.from(screen.querySelectorAll('button')) as HTMLButtonElement[]).find((item) => buttonText(item) === text)!;
    return { fixture, forms, screen, byText, asked, settle };
  }

  it('shows no dialog while none is asked for', () => {
    const { screen } = setup();

    expect(screen.querySelector('.smt-modal')).toBeNull();
  });

  it('names the role field, marks it required and submits the form with Enter or the button', async () => {
    const { screen, byText, asked, settle } = setup({ isCreateModalOpen: true });
    await settle();

    expect(screen.querySelector('.smt-modal__title').textContent).toBe('Создание новой роли');
    expect(screen.querySelector('label[for="role-create-name"]').textContent).toContain('Название роли');
    expect(screen.querySelector('#role-create-name').getAttribute('aria-required')).toBe('true');
    expect(screen.querySelector('#role-create-name').hasAttribute('cdkFocusInitial')).toBe(true);
    expect(screen.querySelector('.smt-control__error')).toBeNull();

    byText('Создать').click();
    screen.querySelector('form#role-create-form').dispatchEvent(new Event('submit', { cancelable: true }));
    byText('Отмена').click();
    expect(asked.submitCreate).toHaveBeenCalledTimes(2);
    expect(asked.closeCreate).toHaveBeenCalledTimes(1);
  });

  it('shows the name error under the field after a submit and focuses it', async () => {
    const { forms, screen, settle } = setup({ isCreateModalOpen: true });
    await settle();

    forms.newRole.set({ name: ' ', orderNo: 0 });
    forms.submitCreateRole(vi.fn());
    screen.querySelector('form#role-create-form').dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();

    const field = screen.querySelector('#role-create-name') as HTMLInputElement;
    const error = screen.querySelector('.smt-control__error') as HTMLElement;
    expect(error.textContent.trim()).toBe('Укажите название роли');
    expect(field.getAttribute('aria-invalid')).toBe('true');
    expect(field.getAttribute('aria-describedby')).toContain(error.id);
    expect(document.activeElement).toBe(field);
  });

  it('shows the server field messages under the fields and the others in the summary', async () => {
    const { fixture, screen, settle } = setup({ isCreateModalOpen: true });
    await settle();

    fixture.componentRef.setInput('createErrors', { fields: { name: 'Имя занято' }, other: ['Код недопустим'] });
    await settle();
    await settle();

    expect(screen.querySelector('.smt-control__error').textContent.trim()).toBe('Имя занято');
    expect(screen.querySelector('[data-testid="form-error-summary"]').textContent).toContain('Код недопустим');
    expect(document.activeElement).toBe(screen.querySelector('#role-create-name'));
  });

  it('shows the create button busy while the role is saved', async () => {
    const { screen, settle } = setup({ isCreateModalOpen: true, isSubmittingRole: true });
    await settle();

    expect(screen.querySelector('[data-testid="form-submit"]').getAttribute('aria-busy')).toBe('true');
    expect((screen.querySelector('[data-testid="form-cancel"]') as HTMLButtonElement).disabled).toBe(true);
    expect(screen.querySelector('.smt-modal__close')).toBeNull();
  });

  it('keeps the superadministrator role active: its state cannot be changed', async () => {
    const adminEdit = setup();
    adminEdit.forms.openEditRoleModal(admin);
    adminEdit.fixture.componentRef.setInput('editingRole', admin);
    adminEdit.fixture.componentRef.setInput('isEditModalOpen', true);
    await adminEdit.settle();
    expect(adminEdit.screen.querySelector('.smt-modal__title').textContent).toBe('Редактирование роли');
    expect((adminEdit.screen.querySelector('#role-edit-state') as HTMLButtonElement).disabled).toBe(true);
    expect(adminEdit.screen.textContent).toContain('Роль суперадминистратора всегда активна.');
    adminEdit.byText('Сохранить').click();
    expect(adminEdit.asked.submitEdit).toHaveBeenCalledTimes(1);
    adminEdit.fixture.destroy();
    TestBed.resetTestingModule();

    const other = setup();
    other.forms.openEditRoleModal(analyst);
    other.fixture.componentRef.setInput('editingRole', analyst);
    other.fixture.componentRef.setInput('isEditModalOpen', true);
    await other.settle();
    expect((other.screen.querySelector('#role-edit-state') as HTMLButtonElement).disabled).toBe(false);
    expect(other.screen.textContent).not.toContain('Роль суперадминистратора всегда активна.');
  });

  it('names the role to delete and cannot be dismissed while the deletion runs', () => {
    const asking = setup({ isDeleteModalOpen: true, deletingRole: analyst });
    expect(asking.screen.querySelector('.delete-title strong').textContent).toBe('Аналитик');
    asking.byText('Удалить').click();
    expect(asking.asked.confirmDelete).toHaveBeenCalledTimes(1);
    asking.fixture.destroy();
    TestBed.resetTestingModule();

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
