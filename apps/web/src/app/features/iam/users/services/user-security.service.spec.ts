import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { UserSecuritySummary } from '@core/models/auth.models';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { translateTest } from '@testing/i18n-test.stub';
import { UserSecurityService } from './user-security.service';

interface Confirm {
  destructive?: boolean;
  action: () => Observable<unknown>;
  actionError: (error: unknown) => string;
}

const summary = (userId: number, activeSessionsCount = 1) =>
  ({
    userId,
    login: `u${userId}`,
    activeSessionsCount,
    activeSessions: [],
    recentLoginAttempts: [],
  }) as unknown as UserSecuritySummary;

describe('UserSecurityService', () => {
  function setup(get: (path: string) => Observable<unknown> = (path) => of(summary(Number(path.split('/')[3])))) {
    const api = {
      get: vi.fn(get),
      post: vi.fn((_path: string, _body?: unknown, _options?: unknown) => of({})),
      delete: vi.fn((_path: string, _options?: unknown) => of({})),
    };
    const asked: Confirm[] = [];
    const toast = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: toast },
        { provide: I18nService, useValue: { translate: translateTest, currentLang: signal('ru') } },
        { provide: SMTModalService, useValue: { confirm: (options: Confirm) => (asked.push(options), of(undefined)) } },
      ],
    });
    const security = TestBed.inject(UserSecurityService);
    /** Yes in the dialog: its action runs while the dialog waits. */
    const confirm = () => asked.at(-1)!.action().subscribe();
    return { security, api, asked, toast, confirm };
  }
  /** The summary is a resource: its request starts on a tick and its answer lands on a later task. */
  async function settle() {
    TestBed.tick();
    await new Promise((resolve) => setTimeout(resolve));
  }
  const summaryReads = (api: { get: ReturnType<typeof vi.fn> }, userId: number) =>
    api.get.mock.calls.filter(([path]) => path === `/iam/users/${userId}/security`).length;

  it('loads the summary of a user and keeps it when a reload fails', async () => {
    let fail = false;
    const { security, api } = setup(() => (fail ? throwError(() => ({ status: 500 })) : of(summary(15))));

    security.loadUserSecurity(15);
    await settle();
    expect(api.get).toHaveBeenCalledWith('/iam/users/15/security');
    expect(security.userSecurity()?.userId).toBe(15);
    expect(security.isLoadingSecurity()).toBe(false);

    fail = true;
    security.loadUserSecurity(15);
    await settle();
    expect(summaryReads(api, 15)).toBe(2);
    expect(security.userSecurity()?.userId).toBe(15);
  });

  it('drops the answer still due for the previous user when another is asked', async () => {
    const answers = new Map([7, 8].map((id) => [id, new Subject<UserSecuritySummary>()]));
    const { security } = setup((path) => answers.get(Number(path.split('/')[3]))!);

    security.loadUserSecurity(7);
    await settle();
    expect(security.isLoadingSecurity()).toBe(true);
    security.loadUserSecurity(8);
    await settle();
    answers.get(7)!.next(summary(7));
    await settle();
    expect(security.userSecurity()).toBeNull();

    answers.get(8)!.next(summary(8));
    await settle();
    expect(security.userSecurity()?.userId).toBe(8);
  });

  it('ends every session of a user only after the confirmation, once, then reloads only the summary', async () => {
    const { security, api, asked, toast, confirm } = setup();

    security.terminateUserSessions(42);
    expect(asked[0].destructive).toBe(true);
    expect(asked[0].actionError({ detail: 'Нельзя' })).toBe('Нельзя');
    expect(api.delete).not.toHaveBeenCalled();

    confirm();
    await settle();
    expect(api.delete).toHaveBeenCalledTimes(1);
    expect(api.delete).toHaveBeenCalledWith('/iam/users/42/sessions', { notifyError: false });
    expect(toast.success).toHaveBeenCalledTimes(1);
    expect(summaryReads(api, 42)).toBe(1);
    expect(security.isSecurityActionPending()).toBe(false);
  });

  it('ends a single session by its id and reloads the summary', async () => {
    const { security, api } = setup();

    security.terminateSingleSession(101, 42);
    await settle();

    expect(api.delete).toHaveBeenCalledTimes(1);
    expect(api.delete).toHaveBeenCalledWith('/iam/users/42/sessions/101');
    expect(summaryReads(api, 42)).toBe(1);
    expect(security.isSecurityActionPending()).toBe(false);
  });
});
