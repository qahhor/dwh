import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { ResetPasswordComponent } from './reset-password.component';

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
    component.newPassword.set('New-Password-2026');
    component.confirmPassword.set('New-Password-2026');
    component.submit();

    expect(api.post).toHaveBeenCalledWith(
      '/auth/password-reset/confirm',
      { token: 'link-token', newPassword: 'New-Password-2026' },
      { notifyError: false },
    );
    expect(component.state()).toBe('done');
    expect(component.newPassword()).toBe('');
  });

  it('shows an invalid link without a token and sends nothing', async () => {
    const fixture = await open('');

    expect(fixture.componentInstance.state()).toBe('invalid');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
  });

  it('checks the length and the repeat before sending', async () => {
    const fixture = await open('#token=link-token');
    const component = fixture.componentInstance;

    component.newPassword.set('short');
    component.confirmPassword.set('short');
    component.submit();
    expect(component.formError()).not.toBe('');

    component.newPassword.set('New-Password-2026');
    component.confirmPassword.set('Other-Password-2026');
    component.submit();
    expect(component.formError()).not.toBe('');
    expect(api.post).not.toHaveBeenCalled();
  });

  it('turns a used or expired link into the invalid state', async () => {
    api.post.mockReturnValue(throwError(() => ({ code: 'RESET_CODE_INVALID', detail: 'expired' })));
    const fixture = await open('#token=used-token');
    const component = fixture.componentInstance;

    component.newPassword.set('New-Password-2026');
    component.confirmPassword.set('New-Password-2026');
    component.submit();

    expect(component.state()).toBe('invalid');
  });

  it('keeps the form and shows the reason for any other refusal', async () => {
    api.post.mockReturnValue(throwError(() => ({ code: 'validation_failed', detail: 'Пароль слишком простой' })));
    const fixture = await open('#token=link-token');
    const component = fixture.componentInstance;

    component.newPassword.set('New-Password-2026');
    component.confirmPassword.set('New-Password-2026');
    component.submit();

    expect(component.state()).toBe('form');
    expect(component.formError()).toBe('Пароль слишком простой');
  });
});
