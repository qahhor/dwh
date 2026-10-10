import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Role } from '@core/models/rbac.models';
import { RoleCardsBarComponent } from './role-cards-bar.component';

describe('RoleCardsBarComponent', () => {
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
  const analyst = role(2, 'Аналитик', { state: 'P' });

  function setup(inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(RoleCardsBarComponent);
    fixture.componentRef.setInput('roles', [admin, analyst]);
    fixture.componentRef.setInput('roleUserCounts', { 1: 3 });
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    const component = fixture.componentInstance;
    const asked = {
      search: vi.fn(),
      select: vi.fn(),
      users: vi.fn(),
      edit: vi.fn(),
      remove: vi.fn(),
      create: vi.fn(),
    };
    component.searchQueryChange.subscribe(asked.search);
    component.selectRole.subscribe(asked.select);
    component.navigateToUsers.subscribe(asked.users);
    component.openEdit.subscribe(asked.edit);
    component.openDelete.subscribe(asked.remove);
    component.openCreate.subscribe(asked.create);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const button = (label: string) => host.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | null;
    return { fixture, host, button, asked };
  }

  it('shows each role with its code or custom mark, its state and how many users it has', () => {
    const { host, button } = setup();

    const cards = Array.from(host.querySelectorAll('.role-card-btn')) as HTMLElement[];
    expect(cards).toHaveLength(2);
    expect(cards[0].querySelector('.role-sys-tag')?.textContent?.trim()).toBe('admin');
    expect(cards[0].querySelector('.status-text')?.textContent?.trim()).toBe('Активна');
    expect(cards[1].querySelector('.role-custom-tag')?.textContent?.trim()).toBe('Кастомная');
    expect(cards[1].querySelector('.status-text')?.textContent?.trim()).toBe('Отключена');
    expect(button('Пользователи роли Администратор: 3')).not.toBeNull();
    expect(button('Пользователи роли Аналитик: 0')).not.toBeNull();
  });

  it('marks the chosen role as pressed and asks for another on a click', () => {
    const { button, asked } = setup({ selectedRole: admin });

    expect(button('Выбрать роль Администратор')!.getAttribute('aria-pressed')).toBe('true');
    expect(button('Выбрать роль Аналитик')!.getAttribute('aria-pressed')).toBe('false');
    button('Выбрать роль Аналитик')!.click();

    expect(asked.select).toHaveBeenCalledWith(analyst);
  });

  it('opens the users of a role from its counter', () => {
    const { button, asked } = setup();

    button('Пользователи роли Администратор: 3')!.click();

    expect(asked.users).toHaveBeenCalledWith(expect.objectContaining({ role: admin }));
  });

  it('offers editing, deleting a custom role and creating only with the matching right', () => {
    const viewer = setup();
    expect(viewer.button('Редактировать роль Аналитик')).toBeNull();
    expect(viewer.button('Удалить роль Аналитик')).toBeNull();
    expect(viewer.host.querySelector('.add-role-dashed-btn')).toBeNull();

    const manager = setup({ canUpdateRole: true, canDeleteRole: true, canCreateRole: true });
    // A system role is never deleted from here.
    expect(manager.button('Удалить роль Администратор')).toBeNull();
    manager.button('Редактировать роль Администратор')!.click();
    manager.button('Удалить роль Аналитик')!.click();
    (manager.host.querySelector('.add-role-dashed-btn') as HTMLButtonElement).click();
    expect(manager.asked.edit).toHaveBeenCalledWith(admin);
    expect(manager.asked.remove).toHaveBeenCalledWith(analyst);
    expect(manager.asked.create).toHaveBeenCalledTimes(1);
  });

  it('holds role switching and deleting while something is being saved', () => {
    const { button } = setup({ canDeleteRole: true, isSaving: true });

    expect(button('Выбрать роль Аналитик')!.disabled).toBe(true);
    expect(button('Удалить роль Аналитик')!.disabled).toBe(true);
  });

  it('filters the roles through a labelled search field', () => {
    const { host, asked } = setup({ searchQuery: 'ана' });
    const field = host.querySelector('#role-search') as HTMLInputElement;

    expect(field.getAttribute('aria-label')).toBe('Поиск ролей');
    expect(field.value).toBe('ана');
    field.value = 'адм';
    field.dispatchEvent(new Event('input'));

    expect(asked.search).toHaveBeenLastCalledWith('адм');
  });
});
