import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { ComponentFixture } from '@angular/core/testing';
import { ResetPasswordComponent } from './reset-password.component';

function errors(fixture: ComponentFixture<ResetPasswordComponent>): string[] {
  return [...fixture.nativeElement.querySelectorAll('.smt-control__error')].map(
    (node) => (node as HTMLElement).textContent?.trim() ?? '',
  );
}

describe('ResetPasswordComponent', () => {
  const api = { post: vi.fn() };
  const router = { navigate: vi.fn(() => Promise.resolve(true)) };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  async function open(hash: string) {
    window.history.replaceState(null, '', '/reset-password' + hash);
    await TestBed.configureTestingModule({
      imports: [ResetPasswordComponent],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: Router, useValue: router },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ResetPasswordComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('takes the token from the fragment, drops it from the address and sets the password', async () => {
    api.post.mockReturnValue(of(undefined));
    const fixture = await open('#token=link-token');
    const component = fixture.componentInstance;

    expect(window.location.hash).toBe('');
    component.model.set({ newPassword: 'New-Password-2026', confirmPassword: 'New-Password-2026' });
    component.submit();

    expect(api.post).toHaveBeenCalledWith(
      '/auth/password-reset/confirm',
      { token: 'link-token', newPassword: 'New-Password-2026' },
      { notifyError: false },
    );
    expect(component.state()).toBe('done');
    expect(component.model().newPassword).toBe('');
  });

  it('shows an invalid link without a token and sends nothing', async () => {
    const fixture = await open('');

    expect(fixture.componentInstance.state()).toBe('invalid');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
  });

  it('marks both fields required and explains an empty submit under each field', async () => {
    const fixture = await open('#token=link-token');
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.smt-control__required')).toHaveLength(2);

    fixture.componentInstance.submit();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(errors(fixture)).toEqual(['Введите новый пароль', 'Введите новый пароль ещё раз']);
    expect(api.post).not.toHaveBeenCalled();
  });

  it('checks the length and the repeat before sending, each under its field', async () => {
    const fixture = await open('#token=link-token');
    const component = fixture.componentInstance;

    component.model.set({ newPassword: 'short', confirmPassword: 'short' });
    component.submit();
    fixture.detectChanges();
    expect(errors(fixture)[0]).toContain('от 8 до 20');

    component.model.set({ newPassword: 'New-Password-2026', confirmPassword: 'Other-Password-2026' });
    component.submit();
    fixture.detectChanges();
    expect(errors(fixture)).toEqual(['Введенные пароли не совпадают']);
    expect(api.post).not.toHaveBeenCalled();
  });

  it('sends what is typed on the form submit, clearing the error as the person types', async () => {
    api.post.mockReturnValue(of(undefined));
    const fixture = await open('#token=link-token');
    const form = fixture.nativeElement.querySelector('form') as HTMLFormElement;
    expect(form.noValidate).toBe(true);
    const type = (id: string, text: string) => {
      const field = fixture.nativeElement.querySelector(`#${id}`) as HTMLInputElement;
      field.value = text;
      field.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    };

    type('reset-new-password', 'short');
    type('reset-confirm-password', 'short');
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
    expect(errors(fixture)).toHaveLength(1);

    type('reset-new-password', 'New-Password-2026');
    type('reset-confirm-password', 'New-Password-2026');
    expect(errors(fixture)).toEqual([]);
    const submit = new Event('submit', { cancelable: true });
    form.dispatchEvent(submit);

    expect(submit.defaultPrevented).toBe(true);
    expect(api.post).toHaveBeenCalledWith(
      '/auth/password-reset/confirm',
      { token: 'link-token', newPassword: 'New-Password-2026' },
      { notifyError: false },
    );
  });

  it('turns a used or expired link into the invalid state', async () => {
    api.post.mockReturnValue(throwError(() => ({ code: 'RESET_CODE_INVALID', detail: 'expired' })));
    const fixture = await open('#token=used-token');
    const component = fixture.componentInstance;

    component.model.set({ newPassword: 'New-Password-2026', confirmPassword: 'New-Password-2026' });
    component.submit();

    expect(component.state()).toBe('invalid');
  });

  it('keeps the form and shows the reason for a refusal of no field above the button', async () => {
    api.post.mockReturnValue(throwError(() => ({ code: 'rate_limited', detail: 'Попробуйте позже' })));
    const fixture = await open('#token=link-token');
    const component = fixture.componentInstance;

    component.model.set({ newPassword: 'New-Password-2026', confirmPassword: 'New-Password-2026' });
    component.submit();
    fixture.detectChanges();

    expect(component.state()).toBe('form');
    expect(fixture.nativeElement.querySelector('#reset-password-error').textContent).toContain('Попробуйте позже');
  });

  it('puts the policy refusal of the server under the new password and focuses it', async () => {
    api.post.mockReturnValue(throwError(() => ({ code: 'password_policy', detail: 'Пароль слишком простой' })));
    const fixture = await open('#token=link-token');
    const component = fixture.componentInstance;

    component.model.set({ newPassword: 'New-Password-2026', confirmPassword: 'New-Password-2026' });
    component.submit();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(errors(fixture)).toEqual(['Пароль слишком простой']);
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('#reset-new-password').getAttribute('aria-invalid')).toBe('true');
  });
});
