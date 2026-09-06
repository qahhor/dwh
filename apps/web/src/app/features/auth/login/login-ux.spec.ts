import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { User } from '../../../core/models/auth.models';
import { ApiService } from '../../../core/services/api.service';
import { AuthService } from '../../../core/services/auth.service';
import { ToastService } from '../../../core/services/toast.service';
import { LoginComponent } from './login.component';

describe('Login form interactions', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let component: LoginComponent;
  let http: HttpTestingController;
  const forcedUser: User = {
    id: 17, name: 'Login UX', login: 'login-ux', email: 'login-ux@example.test',
    state: 'A', language: 'ru', timezone: 'UTC', attributes: {}, is2faEnabled: false,
    forcePasswordChange: true, createdAt: '2026-09-06T00:00:00Z', modifiedAt: '2026-09-06T00:00:00Z'
  };

  beforeEach(async () => {
    localStorage.setItem('dwh_theme', 'dark');
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{ path: 'login', component: LoginComponent }])]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
  });

  afterEach(() => {
    try {
      http.verify();
    } finally {
      localStorage.removeItem('dwh_theme');
      document.documentElement.removeAttribute('data-theme');
    }
  });

  function input(id: string): HTMLInputElement {
    return fixture.nativeElement.querySelector(`#${id}`);
  }

  function submit(): void {
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    fixture.detectChanges();
  }

  function fillCredentials(): void {
    component.login = 'login-ux';
    component.password = 'Synthetic-password-2026!';
    fixture.detectChanges();
  }

  async function enterStep(step: 'otp' | 'must_change_password'): Promise<void> {
    fillCredentials();
    submit();
    http.expectOne('/api/v1/auth/login').flush(step === 'otp'
      ? { step: 'otp', otp_token: 'synthetic-challenge' }
      : { step: 'success', user: forcedUser });
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('focuses the username when the form is first rendered', () => {
    expect(document.activeElement).toBe(input('login'));
  });

  it('applies the saved theme before the authenticated shell exists', () => {
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it.each([
    ['credentials', 'password'],
    ['must_change_password', 'new-password'],
    ['must_change_password', 'confirm-new-password']
  ] as const)('allows %s / %s to be revealed without submitting or losing its value', async (step, id) => {
    if (step !== 'credentials') await enterStep(step);
    const field = input(id);
    expect(field.placeholder).not.toMatch(/•/);
    field.value = 'Synthetic-only';
    field.dispatchEvent(new Event('input', { bubbles: true }));
    await fixture.whenStable();
    const toggle = fixture.nativeElement.querySelector(`button[aria-controls="${id}"]`) as HTMLButtonElement;
    expect(toggle).not.toBeNull();
    expect(toggle.type).toBe('button');
    expect(toggle.getAttribute('aria-label')).toContain('Показать');
    toggle.click();
    fixture.detectChanges();
    expect(field.type).toBe('text');
    expect(field.value).toBe('Synthetic-only');
    expect(toggle.getAttribute('aria-label')).toContain('Скрыть');
    toggle.click();
    fixture.detectChanges();
    expect(field.type).toBe('password');
    http.expectNone(request => request.method === 'POST');
  });

  it.each(['password', 'new-password', 'confirm-new-password'])('announces Caps Lock only for the active %s field', async id => {
    if (id !== 'password') await enterStep('must_change_password');
    const field = input(id);
    field.focus();
    field.dispatchEvent(new KeyboardEvent('keyup', { key: 'A', modifierCapsLock: true, bubbles: true }));
    fixture.detectChanges();
    const hint = fixture.nativeElement.querySelector(`#${id}-caps-lock`);
    expect(hint).not.toBeNull();
    expect(hint.textContent).toContain('Caps Lock');
    expect(field.getAttribute('aria-describedby')).toContain(`${id}-caps-lock`);
    field.dispatchEvent(new FocusEvent('blur'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector(`#${id}-caps-lock`).textContent.trim()).toBe('');
    expect(field.getAttribute('aria-describedby') ?? '').not.toContain(`${id}-caps-lock`);
  });

  it('blocks duplicate login submits and recovery while the request is pending', () => {
    fillCredentials();
    submit();
    submit();
    const requests = http.match('/api/v1/auth/login');
    expect(requests).toHaveLength(1);
    expect(input('password').disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.forgot-link').disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('form').getAttribute('aria-busy')).toBe('true');
    requests[0].flush({ detail: 'Попробуйте снова' }, { status: 503, statusText: 'Unavailable' });
  });

  it('shows login failure once, associates it with the field and restores retry focus', async () => {
    fillCredentials();
    submit();
    http.expectOne('/api/v1/auth/login').flush({ detail: 'Неверный логин или пароль' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelectorAll('[role="alert"]')).toHaveLength(1);
    expect(input('password').getAttribute('aria-describedby')).toContain('login-error');
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(0);
    expect(document.activeElement).toBe(input('password'));
    expect(input('password').value).toBe('Synthetic-password-2026!');
    input('password').dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('explains missing credentials instead of silently ignoring submit', async () => {
    submit();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(document.activeElement).toBe(input('login'));
    http.expectNone('/api/v1/auth/login');
  });

  it.each(['otp', 'must_change_password'] as const)('moves focus to the first field on %s transition', async step => {
    await enterStep(step);
    expect(document.activeElement).toBe(input(step === 'otp' ? 'otp-code' : 'new-password'));
  });

  it('rejects incomplete and non-numeric OTP locally', async () => {
    await enterStep('otp');
    for (const code of ['', '123', 'abc123']) {
      component.otpCode = code;
      submit();
      expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
      http.expectNone('/api/v1/auth/otp');
    }
  });

  it('blocks duplicate OTP submits and Back while verification is pending', async () => {
    await enterStep('otp');
    component.otpCode = '246810';
    submit();
    submit();
    const requests = http.match('/api/v1/auth/otp');
    expect(requests).toHaveLength(1);
    const back = fixture.nativeElement.querySelector('.btn-ghost') as HTMLButtonElement;
    expect(back.disabled).toBe(true);
    back.click();
    expect(component.step()).toBe('otp');
    requests[0].flush({ detail: 'Неверный код' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(input('otp-code').getAttribute('aria-describedby')).toBe('otp-hint otp-error');
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(0);
    expect(document.activeElement).toBe(input('otp-code'));
  });

  it('moves from OTP to forced change with an empty, masked password draft', async () => {
    await enterStep('otp');
    component.otpCode = '246810';
    submit();
    http.expectOne('/api/v1/auth/otp').flush({ step: 'success', user: forcedUser });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement).toBe(input('new-password'));
    expect(input('new-password').value).toBe('');
    expect(input('new-password').autocomplete).toBe('new-password');
    expect(input('confirm-new-password').autocomplete).toBe('new-password');
  });

  it('focuses confirmation and associates a password mismatch with its error', async () => {
    await enterStep('must_change_password');
    component.newPassword = 'Synthetic-password-2026!';
    component.confirmNewPassword = 'Different-password-2026!';
    submit();
    await fixture.whenStable();
    expect(document.activeElement).toBe(input('confirm-new-password'));
    expect(input('confirm-new-password').getAttribute('aria-describedby')).toContain('password-change-error');
    http.expectNone('/api/v1/auth/password');
  });

  it('blocks duplicate password saves and cancellation, and keeps failures inline', async () => {
    await enterStep('must_change_password');
    component.newPassword = 'Synthetic-new-password-2026!';
    component.confirmNewPassword = component.newPassword;
    submit();
    submit();
    const requests = http.match('/api/v1/auth/password');
    expect(requests).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('.btn-ghost').disabled).toBe(true);
    requests[0].flush({ detail: 'Попробуйте снова' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(component.step()).toBe('must_change_password');
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(0);
    expect(document.activeElement).toBe(input('new-password'));
  });

  it.each(['otp', 'must_change_password'] as const)('clears abandoned secrets and errors when returning from %s', async step => {
    await enterStep(step);
    component.otpCode = '246810';
    component.newPassword = 'Synthetic-draft';
    component.confirmNewPassword = 'Synthetic-draft';
    component.formError.set('Previous step error');
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.btn-ghost').click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(component.step()).toBe('credentials');
    expect(component.login).toBe('login-ux');
    expect([component.password, component.tempOldPassword, component.otpToken, component.otpCode,
      component.newPassword, component.confirmNewPassword]).toEqual(['', '', '', '', '', '']);
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(document.activeElement).toBe(input('login'));
  });

  it('cancels a pending login subscription when the component is destroyed', () => {
    fillCredentials();
    submit();
    const request = http.expectOne('/api/v1/auth/login');
    fixture.destroy();
    expect(request.cancelled).toBe(true);
  });

  it('ends the authenticated session if password saving succeeds after the form is destroyed', async () => {
    await enterStep('must_change_password');
    const auth = TestBed.inject(AuthService);
    component.newPassword = 'Synthetic-new-password-2026!';
    component.confirmNewPassword = component.newPassword;
    submit();
    const request = http.expectOne('/api/v1/auth/password');
    fixture.destroy();
    expect(request.cancelled).toBe(false);
    request.flush(null);
    expect(auth.currentUser()).toBeNull();
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(1);
  });

  it('retains default error toasts for unrelated POST callers', () => {
    TestBed.inject(ApiService).post('/example').subscribe({ error: () => {} });
    http.expectOne('/api/v1/example').flush({ detail: 'Обычная ошибка' }, { status: 400, statusText: 'Bad Request' });
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(1);
  });
});
