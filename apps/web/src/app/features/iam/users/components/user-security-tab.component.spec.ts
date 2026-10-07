import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserSecuritySummary } from '@core/models/auth.models';
import { PermissionService } from '@core/services/permission.service';
import type { EntityRecord } from '@shared/entity/entities.api';
import { translateTest } from '@testing/i18n-test.stub';
import { UserSecurityService } from '../services/user-security.service';
import { UserSecurityTabComponent } from './user-security-tab.component';

const SUMMARY: UserSecuritySummary = {
  userId: 5,
  login: 'operator',
  is2faEnabled: true,
  forcePasswordChange: false,
  createdAt: '2026-09-01T08:00:00Z',
  authVersion: 3,
  activeSessionsCount: 1,
  activeSessions: [
    {
      id: 41,
      userId: 5,
      ip: '10.0.0.7',
      userAgent: 'Firefox',
      deviceInfo: '',
      createdAt: '2026-10-01T08:00:00Z',
      lastSeenAt: '2026-10-02T09:00:00Z',
    },
  ],
  recentLoginAttempts: [
    { id: 1, login: 'operator', ip: '10.0.0.7', isSuccess: true, attemptAt: '2026-10-02T08:59:00Z' },
    {
      id: 2,
      login: 'operator',
      ip: '10.0.0.9',
      isSuccess: false,
      failureReason: 'BAD_PASSWORD',
      attemptAt: '2026-10-01T07:00:00Z',
    },
  ],
};

/* The tab "Sessions and security" of a user on the general record page (ADR-0032 7.2). */
describe('UserSecurityTabComponent', () => {
  const security = {
    userSecurity: signal<UserSecuritySummary | null>(null),
    isLoadingSecurity: signal(false),
    isSecurityActionPending: signal(false),
    loadUserSecurity: vi.fn(),
    terminateUserSessions: vi.fn(),
    terminateSingleSession: vi.fn(),
  };
  const canBlock = signal(true);

  beforeEach(() => {
    security.userSecurity.set(null);
    security.isLoadingSecurity.set(false);
    security.loadUserSecurity.mockClear();
    security.terminateUserSessions.mockClear();
    security.terminateSingleSession.mockClear();
    canBlock.set(true);
    TestBed.configureTestingModule({
      providers: [
        { provide: UserSecurityService, useValue: security },
        {
          provide: PermissionService,
          useValue: {
            hasPermission: (form: string, action: string) => form === 'md.users' && action === 'block' && canBlock(),
          },
        },
      ],
    });
  });

  function render(record: EntityRecord = { id: 5, revision: 1 } as EntityRecord) {
    const fixture = TestBed.createComponent(UserSecurityTabComponent);
    fixture.componentRef.setInput('record', record);
    fixture.detectChanges();
    return { fixture, tab: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  it('reads the summary of the user, again when the record moves to a new revision', () => {
    const { fixture } = render();
    expect(security.loadUserSecurity).toHaveBeenCalledWith(5);

    fixture.componentRef.setInput('record', { id: 5, revision: 2 } as EntityRecord);
    fixture.detectChanges();
    expect(security.loadUserSecurity).toHaveBeenCalledTimes(2);
  });

  it('says it is loading until the first summary arrives', () => {
    security.isLoadingSecurity.set(true);
    const { host } = render();
    expect(host.querySelector('[role="status"]')?.textContent).toContain(translateTest('common.loading'));
  });

  it('shows the second factor, the sessions and the sign-in attempts', () => {
    security.userSecurity.set(SUMMARY);
    const { tab, host } = render();

    expect(host.querySelector('.sec-metric-badge.success')?.textContent).toContain(
      translateTest('iam.users.enabled_feminine'),
    );
    expect(host.querySelector('.font-mono')?.textContent).toContain('v3');
    expect(host.querySelector('[data-testid="user-sessions-table"]')?.textContent).toContain('10.0.0.7');
    expect(host.querySelector('[data-testid="user-login-attempts-table"]')?.textContent).toContain('BAD_PASSWORD');
    expect(tab.attemptStatus(SUMMARY.recentLoginAttempts[1])).toBe(translateTest('iam.users.detail.attempt_failed'));
  });

  it('closes one session or all of them for a holder of the right to block', () => {
    security.userSecurity.set(SUMMARY);
    const { host } = render();

    (host.querySelector('[data-testid="user-close-sessions"]') as HTMLButtonElement).click();
    expect(security.terminateUserSessions).toHaveBeenCalledWith(5);

    const one = host.querySelector('[data-testid="user-sessions-table"] button') as HTMLButtonElement;
    expect(one.getAttribute('aria-label')).toBe(translateTest('iam.terminate_session_ip_named', { ip: '10.0.0.7' }));
    one.click();
    expect(security.terminateSingleSession).toHaveBeenCalledWith(41, 5);
  });

  it('offers no closing without the right, and empty states without sessions or attempts', () => {
    canBlock.set(false);
    security.userSecurity.set({ ...SUMMARY, activeSessionsCount: 0, activeSessions: [], recentLoginAttempts: [] });
    const { host } = render();

    expect(host.querySelector('[data-testid="user-close-sessions"]')).toBeNull();
    expect(host.textContent).toContain(translateTest('iam.common.no_active_sessions'));
    expect(host.textContent).toContain(translateTest('iam.users.detail.no_login_attempts'));
  });
});
