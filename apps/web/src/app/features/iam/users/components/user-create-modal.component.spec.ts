import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Role } from '@core/models/rbac.models';
import { ApiService } from '@core/services/api.service';
import { buttonText } from '@testing/button-text';
import { inScreen } from '@testing/in-screen';
import { UserCreateForm, createDefaultUserCreateForm } from '../users.models';
import { UserCreateModalComponent } from './user-create-modal.component';

describe('UserCreateModalComponent', () => {
  const role = (id: number, name: string): Role => ({
    id,
    name,
    state: 'A',
    orderNo: id,
    createdAt: '2026-08-30T00:00:00Z',
    modifiedAt: '2026-08-30T00:00:00Z',
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: ApiService, useValue: { get: vi.fn(() => of({ items: [], nextCursor: null })) } }],
    });
  });

  function setup(inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(UserCreateModalComponent);
    fixture.componentRef.setInput('isOpen', true);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    const component = fixture.componentInstance;
    const asked = { close: vi.fn(), submit: vi.fn(), generate: vi.fn(), copy: vi.fn() };
    component.closeModal.subscribe(asked.close);
    component.submitForm.subscribe(asked.submit);
    component.generatePassword.subscribe(asked.generate);
    component.copyPassword.subscribe(asked.copy);
    fixture.detectChanges();
    const screen = inScreen(fixture.nativeElement);
    const byText = (text: string) =>
      (Array.from(screen.querySelectorAll('button')) as HTMLButtonElement[]).find((item) => buttonText(item) === text)!;
    const errors = () =>
      (Array.from(screen.querySelectorAll('.smt-control__error')) as HTMLElement[]).map((item) =>
        item.textContent?.trim(),
      );
    return { fixture, screen, byText, errors, asked };
  }

  const filled = (extra: Partial<UserCreateForm> = {}): UserCreateForm => ({
    ...createDefaultUserCreateForm(),
    name: 'Анна Иванова',
    login: 'anna',
    email: 'anna@example.test',
    password: 'Secret-2026',
    ...extra,
  });

  it('opens as a named dialog whose fields carry their labels', async () => {
    const { fixture, screen } = setup();
    // smt-control ties its label to the field after the first render.
    await fixture.whenStable();
    fixture.detectChanges();

    expect(screen.querySelector('.smt-modal__title').textContent).toBe('Создать пользователя');
    for (const [id, label] of [
      ['user-create-name', 'ФИО'],
      ['user-create-login', 'Логин'],
      ['user-create-email', 'Email'],
      ['user-create-password', 'Временный пароль'],
    ]) {
      expect(screen.querySelector(`label[for="${id}"]`)?.textContent).toContain(label);
    }
  });

  it('says what is missing only after an attempt to create', () => {
    const fresh = setup();
    expect(fresh.errors()).toEqual([]);
    fresh.fixture.destroy();

    const tried = setup({ isCreateSubmitted: true });
    expect(tried.errors()).toEqual([
      'Укажите ФИО пользователя',
      'Укажите логин',
      'Укажите email',
      'Пароль должен содержать от 8 до 20 символов',
    ]);
  });

  it('generates a password on request and offers copying and the strength check once there is one', () => {
    const empty = setup();
    expect(empty.screen.querySelector('button[aria-label="Скопировать пароль"]')).toBeNull();
    expect(empty.screen.querySelector('.pwd-strength-container')).toBeNull();
    (empty.screen.querySelector('button[aria-label="Сгенерировать надёжный пароль"]') as HTMLButtonElement).click();
    expect(empty.asked.generate).toHaveBeenCalledTimes(1);
    empty.fixture.destroy();

    const typed = setup({
      createForm: filled(),
      passwordStrength: { score: 3, label: 'Хороший пароль', color: 'var(--info)' },
      hasMinLength: true,
      hasUpperAndLower: true,
    });
    (typed.screen.querySelector('button[aria-label="Скопировать пароль"]') as HTMLButtonElement).click();
    expect(typed.asked.copy).toHaveBeenCalledTimes(1);
    expect(typed.screen.querySelector('.pwd-strength-label').textContent.trim()).toBe('Хороший пароль');
    const said = (Array.from(typed.screen.querySelectorAll('.check-item .sr-only')) as HTMLElement[]).map((item) =>
      item.textContent?.trim(),
    );
    expect(said).toEqual(['Выполнено', 'Выполнено', 'Не выполнено', 'Не выполнено']);
  });

  it('offers the roles as tags and writes the chosen ones into the form', () => {
    const none = setup();
    expect(none.screen.querySelector('smt-tag-group')).toBeNull();
    none.fixture.destroy();

    const form = filled({ roleIds: [2] });
    const { fixture, screen } = setup({ roles: [role(1, 'Администратор'), role(2, 'Аналитик')], createForm: form });
    const tags = Array.from(screen.querySelectorAll('smt-tag-group button')) as HTMLButtonElement[];
    expect(tags.map((tag) => tag.getAttribute('aria-pressed'))).toEqual(['false', 'true']);
    tags[0].click();
    fixture.detectChanges();

    expect(form.roleIds).toEqual([1, 2]);
  });

  it('asks the page to create or to close', () => {
    const { byText, asked } = setup({ createForm: filled() });

    byText('Создать').click();
    byText('Отмена').click();

    expect(asked.submit).toHaveBeenCalledTimes(1);
    expect(asked.close).toHaveBeenCalledTimes(1);
  });
});
