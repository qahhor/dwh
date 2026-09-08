import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { User } from '../models/auth.models';
import { AuthService } from './auth.service';
import { PermissionService } from './permission.service';
import { ToastService } from './toast.service';

describe('Logout lifecycle', () => {
  const user: User = {
    id: 17, name: 'Logout QA', login: 'logout-qa', email: 'logout@example.test',
    state: 'A', language: 'ru', timezone: 'UTC', attributes: {}, is2faEnabled: false,
    forcePasswordChange: false, createdAt: '2026-09-07T00:00:00Z', modifiedAt: '2026-09-07T00:00:00Z'
  };
  let auth: AuthService;
  let http: HttpTestingController;
  let permissions: PermissionService;
  const router = { navigate: vi.fn(() => Promise.resolve(true)) };

  beforeEach(() => {
    router.navigate.mockClear();
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), { provide: Router, useValue: router }
    ] });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    permissions = TestBed.inject(PermissionService);
    auth.currentUser.set(user);
    auth.isLoading.set(false);
    permissions.setPermissions(['*.*'], 7);
  });

  it('sends only one logout while the first request is pending', () => {
    auth.logout();
    auth.logout();
    const requests = http.match('/api/v1/auth/logout');
    expect(requests).toHaveLength(1);
    requests[0].flush(null, { status: 204, statusText: 'No Content' });
    expect(auth.isAuthenticated()).toBe(false);
    http.verify();
  });

  it.each(['checkSession', 'refreshMe'] as const)('does not restore authentication from a late %s after logout', method => {
    const observed: unknown[] = [];
    auth[method]().subscribe(value => observed.push(value));
    const pendingMe = http.expectOne('/api/v1/auth/me');
    auth.logout();
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 204, statusText: 'No Content' });

    pendingMe.flush({ user, permissions: ['*.*'], permissionsVersion: 7 });

    expect(auth.currentUser()).toBeNull();
    expect(permissions.hasPermission('tasks.items', 'view')).toBe(false);
    expect(auth.isLoading()).toBe(false);
    expect(observed).toEqual(method === 'checkSession' ? [null] : []);
    http.verify();
  });

  it('keeps a failed logout retryable instead of pretending the server session ended', () => {
    auth.logout();
    http.expectOne('/api/v1/auth/logout').flush({ detail: 'Logout unavailable' }, { status: 503, statusText: 'Unavailable' });

    expect(auth.isAuthenticated()).toBe(true);
    expect(permissions.hasPermission('tasks.items', 'view')).toBe(true);
    expect(router.navigate).not.toHaveBeenCalled();
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(1);
    auth.logout();
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    expect(auth.isAuthenticated()).toBe(false);
    http.verify();
  });

  it('still initializes permissions from an in-flight session read after logout fails', () => {
    permissions.clear();
    auth.refreshMe().subscribe();
    const pendingMe = http.expectOne('/api/v1/auth/me');
    auth.logout();
    http.expectOne('/api/v1/auth/logout').flush({ detail: 'Logout unavailable' }, { status: 503, statusText: 'Unavailable' });
    pendingMe.flush({ user, permissions: ['tasks.items.view'], permissionsVersion: 8 });

    expect(auth.currentUser()?.id).toBe(17);
    expect(permissions.hasPermission('tasks.items', 'view')).toBe(true);
    expect(router.navigate).not.toHaveBeenCalled();
    http.verify();
  });

  it('discards a session read started during a successful pending logout', () => {
    auth.logout();
    auth.refreshMe().subscribe();
    const pendingMe = http.expectOne('/api/v1/auth/me');
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    pendingMe.flush({ user, permissions: ['*.*'], permissionsVersion: 8 });

    expect(auth.currentUser()).toBeNull();
    expect(permissions.hasPermission('tasks.items', 'view')).toBe(false);
    http.verify();
  });

  it('removes previous user notifications from the login screen after success', () => {
    const toast = TestBed.inject(ToastService);
    toast.info('Synthetic private task notification');
    auth.logout();
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 204, statusText: 'No Content' });

    expect(toast.toasts()).toEqual([]);
    expect(router.navigate).toHaveBeenCalledTimes(1);
    http.verify();
  });
});
