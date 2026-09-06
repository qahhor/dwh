import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, Subject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MeResponse, User } from '../../../core/models/auth.models';
import { ApiService } from '../../../core/services/api.service';
import { AuthService } from '../../../core/services/auth.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { ProfileComponent } from '../../iam/profile/profile.component';
import { LoginComponent } from './login.component';

describe('Password change ends the old authenticated session', () => {
  const user: User = {
    id: 17, name: 'Password Test', login: 'password-test', email: 'password@example.test',
    state: 'A', language: 'ru', timezone: 'UTC', attributes: {}, is2faEnabled: false,
    forcePasswordChange: true, createdAt: '2026-09-06T00:00:00Z', modifiedAt: '2026-09-06T00:00:00Z'
  };
  let response: Subject<void>;
  let auth: AuthService;
  let permissions: PermissionService;
  let toast: ToastService;
  const router = { navigate: vi.fn(() => Promise.resolve(true)) };
  const api = {
    get: vi.fn((path: string) => of(path === '/auth/me'
      ? { user, permissions: ['*.*'], permissionsVersion: 7 }
      : [])),
    post: vi.fn(),
    delete: vi.fn()
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    response = new Subject<void>();
    api.post.mockImplementation(() => response.asObservable());
    await TestBed.configureTestingModule({
      imports: [LoginComponent, ProfileComponent],
      providers: [
        AuthService, PermissionService, ToastService,
        { provide: ApiService, useValue: api },
        { provide: Router, useValue: router }
      ]
    }).compileComponents();
    auth = TestBed.inject(AuthService);
    permissions = TestBed.inject(PermissionService);
    toast = TestBed.inject(ToastService);
    auth.currentUser.set(user);
    auth.isLoading.set(false);
    permissions.setPermissions(['*.*'], 7);
  });

  function assertSignedOut() {
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.currentUser()).toBeNull();
    expect(permissions.hasPermission('tasks.items', 'view')).toBe(false);
    expect(toast.toasts()).toHaveLength(1);
    expect(toast.toasts()[0]).toMatchObject({ type: 'success', message: 'Пароль изменён. Войдите снова с новым паролем.' });
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { replaceUrl: true });
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.get).not.toHaveBeenCalledWith('/auth/me');
  }

  it('returns forced password change to credentials and erases password and OTP drafts after success', async () => {
    const fixture = TestBed.createComponent(LoginComponent);
    const component = fixture.componentInstance;
    component.login = user.login;
    component.password = 'Before-Change-2026!';
    component.tempOldPassword = component.password;
    component.newPassword = 'After-Change-2026!';
    component.confirmNewPassword = component.newPassword;
    component.otpToken = 'synthetic-old-challenge';
    component.otpCode = '246810';
    component.step.set('must_change_password');
    fixture.detectChanges();

    component.onChangePasswordSubmit();
    expect(component.step()).toBe('must_change_password');
    expect(auth.isAuthenticated()).toBe(true);
    response.next();
    response.complete();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(component.step()).toBe('credentials');
    expect(component.login).toBe(user.login);
    expect([component.password, component.tempOldPassword, component.newPassword,
      component.confirmNewPassword, component.otpToken, component.otpCode]).toEqual(['', '', '', '', '', '']);
    expect(fixture.nativeElement.querySelector('#password')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#new-password')).toBeNull();
    assertSignedOut();
  });

  it('keeps forced change retryable and authenticated when saving fails', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    const component = fixture.componentInstance;
    component.password = 'Before-Change-2026!';
    component.newPassword = 'After-Change-2026!';
    component.confirmNewPassword = component.newPassword;
    component.step.set('must_change_password');
    component.onChangePasswordSubmit();

    response.error({ detail: 'Попробуйте ещё раз' });
    fixture.detectChanges();

    expect(component.step()).toBe('must_change_password');
    expect(component.isLoading()).toBe(false);
    expect(component.newPassword).toBe('After-Change-2026!');
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('Попробуйте ещё раз');
    expect(auth.isAuthenticated()).toBe(true);
    expect(permissions.hasPermission('tasks.items', 'view')).toBe(true);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('clears profile password fields and local authentication only after a successful change', () => {
    const fixture = TestBed.createComponent(ProfileComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();
    component.passwordForm = {
      oldPassword: 'Before-Change-2026!', newPassword: 'After-Change-2026!', confirmPassword: 'After-Change-2026!'
    };
    component.submitChangePassword(new Event('submit'));
    expect(auth.isAuthenticated()).toBe(true);
    toast.error('Earlier failed password attempt');

    response.next();
    response.complete();

    expect(component.passwordForm).toEqual({ oldPassword: '', newPassword: '', confirmPassword: '' });
    expect(component.isChangingPassword()).toBe(false);
    assertSignedOut();
  });

  it('preserves profile session and draft when changing the password fails', () => {
    const fixture = TestBed.createComponent(ProfileComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();
    component.passwordForm = {
      oldPassword: 'Before-Change-2026!', newPassword: 'After-Change-2026!', confirmPassword: 'After-Change-2026!'
    };
    component.submitChangePassword(new Event('submit'));

    response.error({ error: { detail: 'Не удалось сохранить пароль' } });

    expect(component.passwordForm.newPassword).toBe('After-Change-2026!');
    expect(component.isChangingPassword()).toBe(false);
    expect(auth.isAuthenticated()).toBe(true);
    expect(permissions.hasPermission('tasks.items', 'view')).toBe(true);
    expect(router.navigate).not.toHaveBeenCalled();
    expect(toast.toasts()).toHaveLength(0);
  });

  it.each(['checkSession', 'refreshMe'] as const)('ignores a late %s response from before password revocation', (method) => {
    const previousSession = new Subject<MeResponse>();
    api.get.mockReturnValueOnce(previousSession.asObservable());
    const observed = vi.fn();
    auth[method]().subscribe(observed);
    const fixture = TestBed.createComponent(ProfileComponent);
    const component = fixture.componentInstance;
    component.passwordForm = {
      oldPassword: 'Before-Change-2026!', newPassword: 'After-Change-2026!', confirmPassword: 'After-Change-2026!'
    };
    component.submitChangePassword(new Event('submit'));
    response.next();
    response.complete();
    expect(auth.isAuthenticated()).toBe(false);

    previousSession.next({ user, permissions: ['*.*'], permissionsVersion: 7 });
    previousSession.complete();

    expect(auth.isAuthenticated()).toBe(false);
    expect(permissions.hasPermission('tasks.items', 'view')).toBe(false);
    expect(auth.isLoading()).toBe(false);
    if (method === 'checkSession') expect(observed).toHaveBeenCalledWith(null);
    else expect(observed).not.toHaveBeenCalled();
  });
});
