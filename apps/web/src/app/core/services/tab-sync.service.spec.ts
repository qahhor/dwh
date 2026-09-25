import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Subject, of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { AuthService } from './auth.service';
import { I18nService } from './i18n.service';
import { LanguageTabSync } from './language-tab-sync';
import { PermissionService } from './permission.service';
import { TabSyncMessage, TabSyncService } from './tab-sync.service';
import { ToastService } from './toast.service';

/** A stand-in BroadcastChannel: every instance with a name hears the others, never itself. */
class FakeChannel {
  static open: FakeChannel[] = [];
  onmessage: ((event: MessageEvent<unknown>) => void) | null = null;
  constructor(readonly name: string) {
    FakeChannel.open.push(this);
  }
  postMessage(data: unknown): void {
    for (const other of FakeChannel.open) {
      if (other !== this && other.name === this.name) other.onmessage?.({ data } as MessageEvent<unknown>);
    }
  }
  close(): void {
    FakeChannel.open = FakeChannel.open.filter(channel => channel !== this);
  }
}

describe('TabSyncService', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    FakeChannel.open = [];
  });

  it('passes well-formed messages between tabs and drops anything else on the channel', () => {
    vi.stubGlobal('BroadcastChannel', FakeChannel);
    const here = TestBed.runInInjectionContext(() => new TabSyncService());
    const there = TestBed.runInInjectionContext(() => new TabSyncService());
    const heard: TabSyncMessage[] = [];
    there.messages.subscribe(message => heard.push(message));

    here.publish({ kind: 'signed-in', userId: 7 });
    here.publish({ kind: 'language', code: 'uz' });
    FakeChannel.open[0].postMessage({ kind: 'language', code: '<script>' });
    FakeChannel.open[0].postMessage('signed-out');

    expect(heard).toEqual([{ kind: 'signed-in', userId: 7 }, { kind: 'language', code: 'uz' }]);
  });

  it('works without BroadcastChannel: nothing syncs and nothing breaks', () => {
    vi.stubGlobal('BroadcastChannel', undefined);
    const tabs = TestBed.runInInjectionContext(() => new TabSyncService());
    expect(() => tabs.publish({ kind: 'signed-out' })).not.toThrow();
  });
});

describe('tab sync of the session and the language', () => {
  function setup() {
    const incoming = new Subject<TabSyncMessage>();
    const tabs = { messages: incoming.asObservable(), publish: vi.fn() };
    const navigate = vi.fn(() => Promise.resolve(true));
    const router = { navigate, navigateByUrl: vi.fn(() => Promise.resolve(true)), url: '/tasks' };
    const toast = { toasts: vi.fn(() => []), dismiss: vi.fn(), info: vi.fn(), success: vi.fn() };
    const permissions = { clear: vi.fn(), setPermissions: vi.fn() };
    const api = { get: vi.fn(() => of({ user: { id: 9, language: 'ru' }, permissions: [], permissionsVersion: 1 })), post: vi.fn(() => of({})) };
    const chosen = new Subject<string>();
    const i18n = {
      translate: (key: string) => key, useAuthenticatedPreference: vi.fn(), currentLang: () => 'ru',
      languageChosen: chosen.asObservable(), setLanguage: vi.fn(() => of(undefined))
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: TabSyncService, useValue: tabs },
        { provide: Router, useValue: router },
        { provide: ToastService, useValue: toast },
        { provide: PermissionService, useValue: permissions },
        { provide: ApiService, useValue: api },
        { provide: I18nService, useValue: i18n }
      ]
    });
    return { incoming, tabs, router, toast, permissions, api, i18n, chosen, auth: TestBed.inject(AuthService) };
  }

  it('signs this tab out when another tab signs out, and tells the others when this one does', () => {
    const { incoming, tabs, router, toast, permissions, auth } = setup();
    auth.currentUser.set({ id: 9 } as never);

    incoming.next({ kind: 'signed-out' });

    expect(auth.currentUser()).toBeNull();
    expect(permissions.clear).toHaveBeenCalled();
    expect(toast.info).toHaveBeenCalledWith('auth.signed_out_elsewhere');
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { replaceUrl: true });

    auth.currentUser.set({ id: 9 } as never);
    auth.logout();
    expect(tabs.publish).toHaveBeenCalledWith({ kind: 'signed-out' });
  });

  it('wakes a tab on the sign-in page when another tab signs in', () => {
    const { incoming, router, api, auth } = setup();
    router.url = '/login';

    incoming.next({ kind: 'signed-in', userId: 9 });

    expect(api.get).toHaveBeenCalledWith('/auth/me');
    expect(auth.currentUser()?.id).toBe(9);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/tasks');
  });

  it('carries a chosen language to the other tabs and follows theirs without saving again', () => {
    const { incoming, tabs, i18n, chosen } = setup();
    TestBed.inject(LanguageTabSync).start();

    chosen.next('uz');
    expect(tabs.publish).toHaveBeenCalledWith({ kind: 'language', code: 'uz' });

    incoming.next({ kind: 'language', code: 'en' });
    expect(i18n.setLanguage).toHaveBeenCalledWith('en', false);
    incoming.next({ kind: 'language', code: 'ru' });
    expect(i18n.setLanguage).toHaveBeenCalledTimes(1);
  });
});
