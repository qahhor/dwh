import { TestBed } from '@angular/core/testing';
import { Observable, of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AuthService } from '@core/services/auth.service';
import { ProfileApi } from '../profile.api';
import { PasswordForm } from '../profile.models';
import { ProfilePasswordCardComponent } from './profile-password-card.component';

const FIELDS = ['profile-current-password', 'profile-new-password', 'profile-confirm-password'];

describe('ProfilePasswordCardComponent', () => {
  async function setup(change: () => Observable<unknown> = () => of(null)) {
    const api = { changePassword: vi.fn(change) };
    const auth = { onPasswordChanged: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: ProfileApi, useValue: api },
        { provide: AuthService, useValue: auth },
      ],
    });
    const fixture = TestBed.createComponent(ProfilePasswordCardComponent);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const host = fixture.nativeElement as HTMLElement;
    const field = (id: string) => host.querySelector(`#${id}`) as HTMLInputElement;
    const errors = () =>
      Array.from(host.querySelectorAll('.smt-control__error')).map((node) => node.textContent?.trim() ?? '');
    const fill = async (value: Partial<PasswordForm>) => {
      fixture.componentInstance.model.update((current) => ({ ...current, ...value }));
      await settle();
    };
    const submit = async () => {
      (host.querySelector('[data-testid="form-submit"]') as HTMLButtonElement).click();
      await settle();
      await settle();
    };
    return { fixture, host, api, auth, field, errors, fill, submit, settle };
  }

  it('names every field, marks it required and shows no problem before the first attempt', async () => {
    const { host, field, errors } = await setup();

    for (const id of FIELDS) {
      expect(host.querySelector(`label[for="${id}"]`)).not.toBeNull();
      expect(field(id).getAttribute('aria-required')).toBe('true');
    }
    expect(host.querySelectorAll('.smt-control__required')).toHaveLength(3);
    expect(errors()).toEqual([]);
    expect(host.querySelector('.strength-meter-container')).toBeNull();
    expect(field('profile-new-password').getAttribute('aria-describedby')).toContain('smt-control-hint');
  });

  it('after an empty attempt says what each field needs under it and focuses the first', async () => {
    const { api, field, errors, submit } = await setup();

    await submit();

    expect(errors()).toEqual([
      'Введите текущий пароль',
      'Символов в пароле должно быть от 8 до 20',
      'Подтвердите новый пароль',
    ]);
    expect(field('profile-current-password').getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(field('profile-current-password'));
    expect(api.changePassword).not.toHaveBeenCalled();
  });

  it('says the confirmation differs from the new password', async () => {
    const { host, errors, fill, submit } = await setup();
    await fill({ oldPassword: 'old-secret', newPassword: 'NewSecret1', confirmPassword: 'NewSecret2' });

    await submit();

    expect(errors()).toEqual(['Пароли не совпадают']);
    expect(host.querySelector('.match-error')?.textContent).toContain('Пароли не совпадают');
  });

  it('rates a typed password and reads out which requirements it meets', async () => {
    const { host, fill } = await setup();
    await fill({ newPassword: 'NewSecret1', confirmPassword: 'NewSecret1' });

    expect(host.querySelector('.strength-value')?.textContent?.trim()).toBe('Хороший пароль');
    const said = Array.from(host.querySelectorAll('.check-item .sr-only')).map((item) => item.textContent?.trim());
    expect(said).toEqual(['Выполнено', 'Выполнено', 'Выполнено']);
    // The length requirement reads the policy the check uses (8 to 20), not a number of its own.
    expect(host.querySelector('.check-item')?.textContent).toContain('Символов: от 8 до 20');
    expect(host.querySelector('.match-ok')?.textContent).toContain('Пароли совпадают');
  });

  it('changes the password once, clears the fields and ends the session', async () => {
    const answer = new Subject<unknown>();
    const { fixture, api, auth, fill, submit } = await setup(() => answer);
    await fill({ oldPassword: 'old-secret', newPassword: 'NewSecret1', confirmPassword: 'NewSecret1' });

    await submit();
    await submit();
    expect(api.changePassword).toHaveBeenCalledTimes(1);
    expect(api.changePassword).toHaveBeenCalledWith('old-secret', 'NewSecret1');

    answer.next(null);
    answer.complete();
    expect(fixture.componentInstance.model()).toEqual({ oldPassword: '', newPassword: '', confirmPassword: '' });
    expect(auth.onPasswordChanged).toHaveBeenCalledTimes(1);
  });

  it('puts a wrong current password under its field and keeps what was typed', async () => {
    const { fixture, auth, field, errors, fill, submit } = await setup(() =>
      throwError(() => ({ status: 401, code: 'invalid_credentials', detail: 'Неверный текущий пароль' })),
    );
    await fill({ oldPassword: 'wrong', newPassword: 'NewSecret1', confirmPassword: 'NewSecret1' });

    await submit();

    expect(errors()).toEqual(['Неверный текущий пароль']);
    expect(document.activeElement).toBe(field('profile-current-password'));
    expect(fixture.componentInstance.model().newPassword).toBe('NewSecret1');
    expect(auth.onPasswordChanged).not.toHaveBeenCalled();
  });

  it('puts the policy refusal under the new password', async () => {
    const { field, errors, fill, submit } = await setup(() =>
      throwError(() => ({ status: 422, code: 'password_policy', detail: 'Пароль слишком простой' })),
    );
    await fill({ oldPassword: 'old', newPassword: 'NewSecret1', confirmPassword: 'NewSecret1' });

    await submit();

    expect(errors()).toEqual(['Пароль слишком простой']);
    expect(field('profile-new-password').getAttribute('aria-invalid')).toBe('true');
  });

  it('shows a refusal of no field above the button', async () => {
    const { host, errors, fill, submit } = await setup(() => throwError(() => ({ status: 503 })));
    await fill({ oldPassword: 'old', newPassword: 'NewSecret1', confirmPassword: 'NewSecret1' });

    await submit();

    expect(errors()).toEqual([]);
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('Не удалось изменить пароль');
  });
});
