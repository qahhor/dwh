import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { User } from '../models/auth.models';
import { ApiService } from '../services/api.service';
import { AuthService } from '../services/auth.service';
import { TabSyncService } from '../services/tab-sync.service';
import { ToastService } from '../services/toast.service';
import { sessionExpiredInterceptor } from './session-expired.interceptor';
import { isSessionBound } from './session-bound';

describe('sessionExpiredInterceptor', () => {
  const user: User = {
    id: 17, name: 'Session QA', login: 'session-qa', email: 'session@example.test',
    state: 'A', language: 'ru', timezone: 'UTC', attributes: {}, is2faEnabled: false,
    forcePasswordChange: false, createdAt: '2026-09-25T00:00:00Z', modifiedAt: '2026-09-25T00:00:00Z'
  };
  const router = { url: '/upl/packages?open=4', navigate: vi.fn(() => Promise.resolve(true)), navigateByUrl: vi.fn(() => Promise.resolve(true)) };
  let auth: AuthService;
  let api: ApiService;
  let http: HttpTestingController;
  let toast: ToastService;
  let publish: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    router.navigate.mockClear();
    router.navigateByUrl.mockClear();
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(withInterceptors([sessionExpiredInterceptor])), provideHttpClientTesting(),
      { provide: Router, useValue: router }
    ] });
    auth = TestBed.inject(AuthService);
    api = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
    toast = TestBed.inject(ToastService);
    publish = vi.spyOn(TestBed.inject(TabSyncService), 'publish');
    auth.currentUser.set(user);
    auth.isLoading.set(false);
  });

  function unauthorized(url: string): void {
    http.expectOne(url).flush({ code: 'UNAUTHORIZED', detail: 'No session' }, { status: 401, statusText: 'Unauthorized' });
  }

  it('signs the tab out once, says why and tells the other tabs, however many requests failed', () => {
    const failed = vi.fn();
    api.get('/ms/tasks').subscribe({ error: failed });
    api.get('/md/users').subscribe({ error: failed });
    unauthorized('/api/v1/ms/tasks');
    unauthorized('/api/v1/md/users');

    expect(auth.isAuthenticated()).toBe(false);
    expect(failed).toHaveBeenCalledTimes(2);
    expect(router.navigate).toHaveBeenCalledTimes(1);
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { replaceUrl: true });
    expect(publish).toHaveBeenCalledWith({ kind: 'signed-out' });
    expect(toast.toasts().map(t => t.message)).toEqual([expect.stringMatching(/./)]);
    expect(toast.toasts()[0].type).toBe('info');
    http.verify();
  });

  it('brings the person back to the same page after signing in again', () => {
    api.get('/ms/tasks').subscribe({ error: () => undefined });
    unauthorized('/api/v1/ms/tasks');

    auth.login('session-qa', 'secret').subscribe();
    http.expectOne('/api/v1/auth/login').flush({ step: 'success', user });
    http.expectOne('/api/v1/auth/me').flush({ user, permissions: [], permissionsVersion: 1 });
    expect(router.navigateByUrl).toHaveBeenCalledWith('/upl/packages?open=4');

    // Only once: the next sign-in starts on the home page again.
    auth.login('session-qa', 'secret').subscribe();
    http.expectOne('/api/v1/auth/login').flush({ step: 'success', user });
    http.expectOne('/api/v1/auth/me').flush({ user, permissions: [], permissionsVersion: 1 });
    expect(router.navigateByUrl).toHaveBeenLastCalledWith('/tasks');
    http.verify();
  });

  it('leaves the 401 of sign-in itself to the sign-in page', () => {
    auth.currentUser.set(null);
    const failed = vi.fn();
    api.post('/auth/login', {}, { notifyError: false }).subscribe({ error: failed });
    unauthorized('/api/v1/auth/login');
    expect(failed).toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
    expect(publish).not.toHaveBeenCalled();
    http.verify();
  });

  it('ignores a 401 that arrives after the tab already signed out', () => {
    auth.currentUser.set(null);
    api.get('/ms/tasks').subscribe({ error: () => undefined });
    unauthorized('/api/v1/ms/tasks');
    expect(router.navigate).not.toHaveBeenCalled();
    expect(toast.toasts()).toHaveLength(0);
    http.verify();
  });

  it('treats a 401 on logout as a finished sign-out', () => {
    auth.logout();
    unauthorized('/api/v1/auth/logout');
    expect(auth.isAuthenticated()).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { replaceUrl: true });
    http.verify();
  });

  it('keeps other failures as they were', () => {
    api.get('/ms/tasks').subscribe({ error: () => undefined });
    http.expectOne('/api/v1/ms/tasks').flush({ code: 'FORBIDDEN', detail: 'No right' }, { status: 403, statusText: 'Forbidden' });
    expect(auth.isAuthenticated()).toBe(true);
    expect(toast.toasts()).toHaveLength(1);
    http.verify();
  });

  it('knows which calls need a session', () => {
    expect(isSessionBound('/api/v1/ms/tasks?page=1')).toBe(true);
    expect(isSessionBound('https://dwh.example.test/api/v1/md/users')).toBe(true);
    expect(isSessionBound('/api/v1/auth/me')).toBe(false);
    expect(isSessionBound('/api/v1/auth')).toBe(false);
    expect(isSessionBound('/assets/i18n/ru.json')).toBe(false);
  });

  it('only remembers pages inside the app', () => {
    for (const url of ['https://evil.test/', '//evil.test/', '/login?x=1', null]) {
      auth.rememberReturnUrl(url);
      auth.login('session-qa', 'secret').subscribe();
      http.expectOne('/api/v1/auth/login').flush({ step: 'success', user });
      http.expectOne('/api/v1/auth/me').flush({ user, permissions: [], permissionsVersion: 1 });
      expect(router.navigateByUrl).toHaveBeenLastCalledWith('/tasks');
    }
    http.verify();
  });
});
