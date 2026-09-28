import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { User } from '@core/models/auth.models';
import { Role } from '@core/models/rbac.models';
import { ApiService } from '@core/services/api.service';
import { buttonText } from '@testing/button-text';
import { inScreen } from '@testing/in-screen';
import { UserEditForm, createDefaultUserEditForm } from '../users.models';
import { UserEditModalComponent } from './user-edit-modal.component';

describe('UserEditModalComponent', () => {
  const person = (id: number, name: string, login: string): User => ({
    id,
    name,
    login,
    email: `${login}@example.test`,
    state: 'A',
    language: 'ru',
    timezone: 'Asia/Tashkent',
    attributes: {},
    is2faEnabled: false,
    forcePasswordChange: false,
    createdAt: '2026-08-30T00:00:00Z',
    modifiedAt: '2026-08-30T00:00:00Z',
  });
  const anna = person(7, 'Анна Иванова', 'anna');
  const boss = person(3, 'Бахтиёр Каримов', 'bahtiyor');
  const admin = person(1, 'Администратор', 'admin');
  const adminRole: Role = {
    id: 1,
    name: 'Администратор',
    pcode: 'admin',
    state: 'A',
    orderNo: 1,
    createdAt: '2026-08-30T00:00:00Z',
    modifiedAt: '2026-08-30T00:00:00Z',
  };
  let api: { get: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    api = { get: vi.fn(() => of({ items: [boss, anna], nextCursor: null })) };
    TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: api }] });
  });

  function setup(user: User | null, inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(UserEditModalComponent);
    const form: UserEditForm = user ? createDefaultUserEditForm(user) : createDefaultUserEditForm(anna);
    fixture.componentRef.setInput('isOpen', true);
    fixture.componentRef.setInput('editingUser', user);
    fixture.componentRef.setInput('editForm', form);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    const component = fixture.componentInstance;
    const asked = { close: vi.fn(), submit: vi.fn() };
    component.close.subscribe(asked.close);
    component.submit.subscribe(asked.submit);
    fixture.detectChanges();
    const screen = inScreen(fixture.nativeElement);
    const byText = (text: string) =>
      (Array.from(screen.querySelectorAll('button')) as HTMLButtonElement[]).find((item) => buttonText(item) === text)!;
    return { fixture, screen, byText, form, asked };
  }

  it('shows the login and email of the person as read-only', () => {
    const { screen } = setup(anna);

    expect(screen.querySelector('.smt-modal__title').textContent).toBe('Редактировать пользователя');
    const login = screen.querySelector('#user-edit-login') as HTMLInputElement;
    const email = screen.querySelector('#user-edit-email') as HTMLInputElement;
    expect([login.value, login.disabled]).toEqual(['anna', true]);
    expect([email.value, email.disabled]).toEqual(['anna@example.test', true]);
  });

  it('shows no form before the person to edit is known', () => {
    const { screen } = setup(null);

    expect(screen.querySelector('.smt-modal__title')).not.toBeNull();
    expect(screen.querySelector('#user-edit-name')).toBeNull();
  });

  it('asks for the full name after an attempt without one', () => {
    const fresh = setup(anna);
    expect(fresh.screen.querySelector('.smt-control__error')).toBeNull();
    fresh.fixture.destroy();

    const tried = setup({ ...anna, name: '' }, { isEditSubmitted: true });
    expect(tried.screen.querySelector('.smt-control__error').textContent.trim()).toBe('Укажите ФИО пользователя');
  });

  it('keeps the admin role of the built-in admin locked, and only there', () => {
    const builtIn = setup(admin, { roles: [adminRole] });
    const locked = builtIn.screen.querySelector('smt-tag-group button') as HTMLButtonElement;
    expect(locked.disabled).toBe(true);
    expect(locked.textContent).toContain('Защищено');
    builtIn.fixture.destroy();

    const other = setup(anna, { roles: [adminRole] });
    expect((other.screen.querySelector('smt-tag-group button') as HTMLButtonElement).disabled).toBe(false);
  });

  it('never offers the person as their own manager', async () => {
    const { fixture, screen } = setup(anna);
    const settle = async () => {
      await fixture.whenStable();
      fixture.detectChanges();
    };
    const manager = screen.querySelector('smt-data-select [role="combobox"]') as HTMLElement;

    manager.click();
    await settle();
    await settle();

    const options = (Array.from(document.querySelectorAll('[role="option"]')) as HTMLElement[]).map((option) =>
      option.textContent?.trim(),
    );
    expect(api.get).toHaveBeenCalled();
    expect(options.some((label) => label?.includes('Бахтиёр Каримов'))).toBe(true);
    expect(options.some((label) => label?.includes('Анна Иванова'))).toBe(false);
  });

  it('asks the page to save or to close', () => {
    const { byText, asked } = setup(anna);

    byText('Сохранить').click();
    byText('Отмена').click();

    expect(asked.submit).toHaveBeenCalledTimes(1);
    expect(asked.close).toHaveBeenCalledTimes(1);
  });
});
