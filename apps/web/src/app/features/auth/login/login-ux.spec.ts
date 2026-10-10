import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { User } from '@core/models/auth.models';
import { ApiService } from '@core/services/api.service';
import { AuthService } from '@core/services/auth.service';
import { ToastService } from '@core/services/toast.service';
import { LoginComponent } from './login.component';

describe('Login form interactions', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let component: LoginComponent;
  let http: HttpTestingController;
  const forcedUser: User = {
    id: 17,
    name: 'Login UX',
    login: 'login-ux',
    email: 'login-ux@example.test',
    state: 'A',
    language: 'ru',
    timezone: 'UTC',
    attributes: {},
    is2faEnabled: false,
    forcePasswordChange: true,
    createdAt: '2026-09-06T00:00:00Z',
    modifiedAt: '2026-09-06T00:00:00Z',
  };

  beforeEach(async () => {
    localStorage.setItem('smc_theme', 'dark');
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'login', component: LoginComponent }]),
      ],
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
      localStorage.removeItem('smc_theme');
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
    component.credentials.set({ login: 'login-ux', password: 'Synthetic-pass-26!' });
    fixture.detectChanges();
  }

  async function enterStep(step: 'otp' | 'must_change_password'): Promise<void> {
    fillCredentials();
    submit();
    http
      .expectOne('/api/v1/auth/login')
      .flush(step === 'otp' ? { step: 'otp', otpToken: 'synthetic-challenge' } : { step: 'success', user: forcedUser });
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
    ['must_change_password', 'confirm-new-password'],
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
    http.expectNone((request) => request.method === 'POST');
  });

  it.each(['password', 'new-password', 'confirm-new-password'])(
    'announces Caps Lock only for the active %s field',
    async (id) => {
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
    },
  );

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

  it('shows login failure once above the button and restores retry focus', async () => {
    fillCredentials();
    submit();
    http
      .expectOne('/api/v1/auth/login')
      .flush({ detail: 'Неверный логин или пароль' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelectorAll('[role="alert"]')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('#login-error').textContent).toContain('Неверный логин или пароль');
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(0);
    expect(document.activeElement).toBe(input('password'));
    expect(input('password').value).toBe('Synthetic-pass-26!');
    input('password').dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('explains missing credentials under each field instead of silently ignoring submit', async () => {
    submit();
    await fixture.whenStable();
    fixture.detectChanges();
    const errors = [...fixture.nativeElement.querySelectorAll('.smt-control__error')].map((node) =>
      (node as HTMLElement).textContent?.trim(),
    );
    expect(errors).toEqual(['Укажите логин или email', 'Укажите пароль']);
    expect(input('login').getAttribute('aria-invalid')).toBe('true');
    expect(input('password').getAttribute('aria-invalid')).toBe('true');
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(document.activeElement).toBe(input('login'));
    http.expectNone('/api/v1/auth/login');
  });

  it('marks both credentials required', () => {
    const marks = fixture.nativeElement.querySelectorAll('.smt-control__required');
    expect(marks).toHaveLength(2);
    expect(input('login').getAttribute('aria-required')).toBe('true');
    expect(input('password').getAttribute('aria-required')).toBe('true');
  });

  it('puts a refusal the server ties to a field under that field', async () => {
    fillCredentials();
    submit();
    http.expectOne('/api/v1/auth/login').flush(
      {
        status: 422,
        code: 'validation_failed',
        errors: [{ field: 'login', code: 'NotBlank', message: 'Логин пуст' }],
      },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.smt-control__error').textContent).toContain('Логин пуст');
    expect(input('login').getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(input('login'));
  });

  it.each(['otp', 'must_change_password'] as const)('moves focus to the first field on %s transition', async (step) => {
    await enterStep(step);
    expect(document.activeElement).toBe(input(step === 'otp' ? 'otp-code' : 'new-password'));
  });

  it('rejects incomplete and non-numeric OTP locally', async () => {
    await enterStep('otp');
    for (const code of ['', '123', 'abc123']) {
      component.otp.set({ code });
      submit();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.smt-control__error').textContent).toContain('Введите код из 6 цифр');
      expect(input('otp-code').getAttribute('aria-invalid')).toBe('true');
      http.expectNone('/api/v1/auth/otp');
    }
  });

  it('blocks duplicate OTP submits and Back while verification is pending', async () => {
    await enterStep('otp');
    component.otp.set({ code: '246810' });
    submit();
    submit();
    const requests = http.match('/api/v1/auth/otp');
    expect(requests).toHaveLength(1);
    const back = fixture.nativeElement.querySelector('.smt-button--ghost') as HTMLButtonElement;
    expect(back.disabled).toBe(true);
    back.click();
    expect(component.step()).toBe('otp');
    requests[0].flush({ detail: 'Неверный код' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('#otp-error').textContent).toContain('Неверный код');
    expect(input('otp-code').getAttribute('aria-describedby')).toContain('otp-hint');
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(0);
    expect(document.activeElement).toBe(input('otp-code'));
  });

  it('puts a wrong one-time code under the code field', async () => {
    await enterStep('otp');
    component.otp.set({ code: '246810' });
    submit();
    http
      .expectOne('/api/v1/auth/otp')
      .flush({ code: 'otp_invalid', detail: 'Неверный код' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.smt-control__error').textContent).toContain('Неверный код');
    expect(input('otp-code').getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(input('otp-code'));
  });

  it('moves from OTP to forced change with an empty, masked password draft', async () => {
    await enterStep('otp');
    component.otp.set({ code: '246810' });
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
    component.newPassword.set({ newPassword: 'Synthetic-pass-26!', confirmPassword: 'Different-pass-26!' });
    submit();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(document.activeElement).toBe(input('confirm-new-password'));
    const error = fixture.nativeElement.querySelector('.smt-control__error') as HTMLElement;
    expect(error.textContent).toContain('не совпадают');
    expect(input('confirm-new-password').getAttribute('aria-describedby')).toContain(error.id);
    http.expectNone('/api/v1/auth/password');
  });

  it('shows the policy refusal of the server under the new password', async () => {
    await enterStep('must_change_password');
    component.newPassword.set({ newPassword: 'Synth-new-pass-26!', confirmPassword: 'Synth-new-pass-26!' });
    submit();
    http
      .expectOne('/api/v1/auth/password')
      .flush(
        { code: 'password_policy', detail: 'Пароль слишком простой' },
        { status: 422, statusText: 'Unprocessable' },
      );
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.smt-control__error').textContent).toContain('Пароль слишком простой');
    expect(input('new-password').getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(input('new-password'));
  });

  it('blocks duplicate password saves and cancellation, and keeps failures inline', async () => {
    await enterStep('must_change_password');
    component.newPassword.set({ newPassword: 'Synth-new-pass-26!', confirmPassword: 'Synth-new-pass-26!' });
    submit();
    submit();
    const requests = http.match('/api/v1/auth/password');
    expect(requests).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('.smt-button--ghost').disabled).toBe(true);
    requests[0].flush({ detail: 'Попробуйте снова' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(component.step()).toBe('must_change_password');
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(0);
    expect(document.activeElement).toBe(input('new-password'));
  });

  it.each(['otp', 'must_change_password'] as const)(
    'clears abandoned secrets and errors when returning from %s',
    async (step) => {
      await enterStep(step);
      component.otp.set({ code: '246810' });
      component.newPassword.set({ newPassword: 'Synthetic-draft', confirmPassword: 'Synthetic-draft' });
      component.formError.set('Previous step error');
      fixture.detectChanges();
      fixture.nativeElement.querySelector('.smt-button--ghost').click();
      fixture.detectChanges();
      await fixture.whenStable();
      expect(component.step()).toBe('credentials');
      expect(component.credentials().login).toBe('login-ux');
      expect([
        component.credentials().password,
        component.tempOldPassword(),
        component.otpToken(),
        component.otp().code,
        component.newPassword().newPassword,
        component.newPassword().confirmPassword,
      ]).toEqual(['', '', '', '', '', '']);
      expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
      expect(document.activeElement).toBe(input('login'));
    },
  );

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
    component.newPassword.set({ newPassword: 'Synth-new-pass-26!', confirmPassword: 'Synth-new-pass-26!' });
    submit();
    const request = http.expectOne('/api/v1/auth/password');
    fixture.destroy();
    expect(request.cancelled).toBe(false);
    request.flush(null);
    expect(auth.currentUser()).toBeNull();
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(1);
  });

  it('retains default error toasts for unrelated POST callers', () => {
    TestBed.inject(ApiService)
      .post('/example')
      .subscribe({ error: () => {} });
    http.expectOne('/api/v1/example').flush({ detail: 'Обычная ошибка' }, { status: 400, statusText: 'Bad Request' });
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(1);
  });
});
