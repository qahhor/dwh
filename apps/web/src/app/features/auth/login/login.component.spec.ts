import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { throwError } from 'rxjs';
import { ApiService } from '../../../core/services/api.service';
import { AuthService } from '../../../core/services/auth.service';
import { ToastService } from '../../../core/services/toast.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  const authService = {
    login: vi.fn(),
    verifyOtp: vi.fn()
  };
  const apiService = {
    post: vi.fn()
  };
  const toastService = {
    success: vi.fn()
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        { provide: AuthService, useValue: authService },
        { provide: ApiService, useValue: apiService },
        { provide: ToastService, useValue: toastService }
      ]
    }).compileComponents();
  });

  it('starts with an empty login and associated credential labels', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();

    const login = fixture.nativeElement.querySelector('#login') as HTMLInputElement;
    const password = fixture.nativeElement.querySelector('#password') as HTMLInputElement;
    expect(login.value).toBe('');
    expect(fixture.nativeElement.querySelector('label[for="login"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="password"]')).not.toBeNull();
    expect(password.autocomplete).toBe('current-password');
  });

  it('exposes the login screen as the primary page landmark', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();

    const main = fixture.nativeElement.querySelector('main.login-wrapper') as HTMLElement;
    expect(main).not.toBeNull();
    expect(main.querySelector('h1')?.textContent).toContain('Корпоративный вход');
  });

  it('renders the product name outside the fixed-size brand mark', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();

    const lockup = fixture.nativeElement.querySelector('.brand-lockup') as HTMLElement;
    const mark = lockup?.querySelector('.brand-mark') as HTMLElement;
    const name = lockup?.querySelector('.brand-name') as HTMLElement;

    expect(lockup.getAttribute('aria-label')).toBe('SmartupCMS');
    expect(mark.textContent?.trim()).toBe('S');
    expect(name.textContent?.trim()).toBe('SmartupCMS');
  });

  it('renders password recovery as a real button', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();

    const trigger = fixture.nativeElement.querySelector('.forgot-link') as HTMLButtonElement;
    expect(trigger.tagName).toBe('BUTTON');
    expect(trigger.type).toBe('button');
  });

  it('exposes the OTP input contract', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.step.set('otp');
    fixture.detectChanges();

    const otp = fixture.nativeElement.querySelector('#otp-code') as HTMLInputElement;
    expect(otp.inputMode).toBe('numeric');
    expect(otp.autocomplete).toBe('one-time-code');
    expect(otp.getAttribute('aria-describedby')).toBe('otp-hint');
  });

  it('keeps a failed login recoverable with inline feedback', () => {
    authService.login.mockReturnValue(throwError(() => ({ detail: 'Неверный логин или пароль' })));
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.login = 'user';
    fixture.componentInstance.password = 'wrong';
    fixture.detectChanges();

    fixture.componentInstance.onLoginSubmit();
    fixture.detectChanges();

    const error = fixture.nativeElement.querySelector('.form-error[role="alert"]') as HTMLElement;
    expect(error.textContent).toContain('Неверный логин или пароль');
    expect(fixture.componentInstance.password).toBe('wrong');
  });
});
