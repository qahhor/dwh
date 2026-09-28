import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { PasswordForm, passwordStrengthOf } from '../profile.models';
import { ProfilePasswordCardComponent } from './profile-password-card.component';

describe('ProfilePasswordCardComponent', () => {
  function setup(
    inputs: {
      form?: Partial<PasswordForm>;
      submitted?: boolean;
      passwordsMatch?: boolean;
      hasMinLength?: boolean;
    } = {},
  ) {
    const form: PasswordForm = { oldPassword: '', newPassword: '', confirmPassword: '', ...inputs.form };
    const fixture = TestBed.createComponent(ProfilePasswordCardComponent);
    fixture.componentRef.setInput('passwordForm', form);
    fixture.componentRef.setInput('isPasswordSubmitted', inputs.submitted ?? false);
    fixture.componentRef.setInput('passwordStrength', passwordStrengthOf(form.newPassword));
    fixture.componentRef.setInput('passwordsMatch', inputs.passwordsMatch ?? false);
    fixture.componentRef.setInput('hasMinLength', inputs.hasMinLength ?? false);
    const submit = vi.fn();
    fixture.componentInstance.submitPassword.subscribe(submit);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const error = (id: string) => host.querySelector(`#${id}-error`)?.textContent?.trim() ?? null;
    return { fixture, host, error, submit };
  }

  it('names every field and shows no problem before the first attempt', () => {
    const { host, error } = setup();

    for (const id of ['profile-current-password', 'profile-new-password', 'profile-confirm-password']) {
      expect(host.querySelector(`label[for="${id}"]`)).not.toBeNull();
      expect(error(id)).toBeNull();
    }
    expect(host.querySelector('.strength-meter-container')).toBeNull();
  });

  it('after an empty attempt says what each field needs and ties it to the field', () => {
    const { host, error } = setup({ submitted: true });

    expect(error('profile-current-password')).toBe('Введите текущий пароль');
    expect(error('profile-new-password')).toBe('Пароль должен содержать от 8 до 20 символов');
    expect(error('profile-confirm-password')).toBe('Подтвердите новый пароль');
    const current = host.querySelector('#profile-current-password') as HTMLInputElement;
    expect(current.getAttribute('aria-invalid')).toBe('true');
    expect(current.getAttribute('aria-describedby')).toBe('profile-current-password-error');
  });

  it('says the confirmation differs from the new password', () => {
    const { host, error } = setup({
      submitted: true,
      form: { oldPassword: 'old-secret', newPassword: 'NewSecret1', confirmPassword: 'NewSecret2' },
    });

    expect(error('profile-confirm-password')).toBe('Пароли не совпадают');
    expect(host.querySelector('.match-error')?.textContent).toContain('Пароли не совпадают');
    expect(error('profile-new-password')).toBeNull();
  });

  it('rates a typed password and reads out which requirements it meets', () => {
    const { host } = setup({
      form: { newPassword: 'NewSecret1', confirmPassword: 'NewSecret1' },
      passwordsMatch: true,
      hasMinLength: true,
    });

    expect(host.querySelector('.strength-value')?.textContent?.trim()).toBe('Хороший пароль');
    const said = Array.from(host.querySelectorAll('.check-item .sr-only')).map((item) => item.textContent?.trim());
    expect(said).toEqual(['Выполнено', 'Не выполнено', 'Не выполнено']);
    expect(host.querySelector('.match-ok')?.textContent).toContain('Пароли совпадают');
  });

  it('hands the filled form to the page on submit', () => {
    const { host, submit } = setup({
      form: { oldPassword: 'old-secret', newPassword: 'NewSecret1', confirmPassword: 'NewSecret1' },
    });

    (host.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    expect(submit).toHaveBeenCalledTimes(1);
  });
});
