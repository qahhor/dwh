import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Observable } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { I18nService } from '../../core/services/i18n.service';
import { NotificationService } from '../../core/services/notification.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { AppShellComponent } from './app-shell.component';

class TestEventSource {
  static instances: TestEventSource[] = [];
  private listeners = new Map<string, (event: MessageEvent) => void>();
  constructor() { TestEventSource.instances.push(this); }
  addEventListener(type: string, listener: (event: MessageEvent) => void) { this.listeners.set(type, listener); }
  close() {}
  // A callback queued before close may still run after a replacement connects.
  emit(type: string, data: unknown) { this.listeners.get(type)?.(new MessageEvent(type, { data: JSON.stringify(data) })); }
}

describe('Header interactions with HTTP state', () => {
  let fixture: ComponentFixture<AppShellComponent>;
  let http: HttpTestingController;
  let i18n: I18nService;

  beforeEach(async () => {
    TestEventSource.instances = [];
    vi.stubGlobal('EventSource', TestEventSource);
    await TestBed.configureTestingModule({ imports: [AppShellComponent], providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([])
    ] }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    i18n = TestBed.inject(I18nService);
    i18n.languages.set(['ru', 'de', 'en'].map(code => ({
      code, name: code, builtin: true, active: true, revision: 1, translated: 1, total: 1, coverage: 100
    })));
    TestBed.inject(PermissionService).setPermissions(['*.*']);
    fixture = TestBed.createComponent(AppShellComponent);
  });

  afterEach(() => {
    fixture.destroy();
    vi.unstubAllGlobals();
    localStorage.removeItem('dwh_lang');
    localStorage.removeItem('dwh_theme');
    document.documentElement.removeAttribute('data-theme');
    document.documentElement.lang = 'ru';
  });

  function renderAdmin(): void {
    fixture.detectChanges();
    http.expectOne('/api/v1/notifications/unread-count').flush({ unread_count: 3 });
    http.expectOne('/api/v1/announcements/active?language=ru').flush([]);
    fixture.detectChanges();
  }

  function changeLanguage(code: string): HTMLSelectElement {
    const select = fixture.nativeElement.querySelector('#app-language-selector') as HTMLSelectElement;
    select.value = code;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
    return select;
  }

  it('disables language selection until both dictionary loading and preference saving finish', () => {
    renderAdmin();
    const select = changeLanguage('de');
    expect(select.disabled).toBe(true);
    http.expectOne('/api/v1/i18n/de').flush({ 'common.save': 'Speichern' });
    fixture.detectChanges();
    http.expectOne('/api/v1/announcements/active?language=de').flush([]);
    expect(select.disabled).toBe(true);
    http.expectOne('/api/v1/settings/user').flush(null);
    fixture.detectChanges();
    expect(select.disabled).toBe(false);
    expect(select.value).toBe('de');
    http.verify();
  });

  it('does not start a competing preference write while one language change is pending', () => {
    renderAdmin();
    changeLanguage('de');
    http.expectOne('/api/v1/i18n/de').flush({});
    changeLanguage('en');
    http.expectOne('/api/v1/announcements/active?language=de').flush([]);
    http.expectNone('/api/v1/i18n/en');
    http.expectOne('/api/v1/settings/user').flush(null);
    fixture.detectChanges();
    expect(i18n.currentLang()).toBe('de');
    expect((fixture.nativeElement.querySelector('#app-language-selector') as HTMLSelectElement).value).toBe('de');
    http.verify();
  });

  it('restores the previous language and reports a failed save once', () => {
    renderAdmin();
    const select = changeLanguage('de');
    http.expectOne('/api/v1/i18n/de').flush({});
    http.expectOne('/api/v1/settings/user').flush({ detail: 'Synthetic failure' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();

    expect(i18n.currentLang()).toBe('ru');
    expect(select.value).toBe('ru');
    expect(select.disabled).toBe(false);
    const messages = TestBed.inject(ToastService).toasts();
    expect(messages).toHaveLength(1);
    expect(messages[0].type).toBe('error');
    expect(messages[0].message).toContain('язык');
    http.expectOne('/api/v1/announcements/active?language=ru').flush([]);
    http.verify();
  });

  it('does not save the already selected language', () => {
    renderAdmin();
    changeLanguage('ru');
    http.expectNone(request => request.method === 'PATCH' || request.url.startsWith('/api/v1/i18n/'));
    http.verify();
  });

  it('does not apply or save a delayed language choice after its shell has ended', () => {
    renderAdmin();
    changeLanguage('de');
    const dictionary = http.expectOne('/api/v1/i18n/de');
    fixture.destroy();
    i18n.setLanguage('en', false).subscribe();
    http.expectOne('/api/v1/i18n/en').flush({ 'common.save': 'Save' });
    if (!dictionary.cancelled) dictionary.flush({ 'common.save': 'Speichern' });
    expect(i18n.currentLang()).toBe('en');
    http.expectNone('/api/v1/settings/user');
    http.verify();
  });

  it('keeps a pending logout disabled and restores it after failure', () => {
    renderAdmin();
    const disconnect = vi.spyOn(TestBed.inject(NotificationService), 'disconnectSse');
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const logout = fixture.nativeElement.querySelector('.logout-btn') as HTMLButtonElement;
    logout.click();
    fixture.detectChanges();
    expect(logout.disabled).toBe(true);
    expect(disconnect).not.toHaveBeenCalled();
    http.expectOne('/api/v1/auth/logout').flush({ detail: 'Synthetic failure' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(logout.disabled).toBe(false);
    expect(disconnect).not.toHaveBeenCalled();
    http.verify();
  });

  it('does not expose or load notifications and announcements without their read permissions', () => {
    TestBed.inject(PermissionService).setPermissions(['tasks.items.view']);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.notif-btn')).toBeNull();
    http.expectNone('/api/v1/notifications/unread-count');
    http.expectNone('/api/v1/announcements/active');
    http.verify();
  });

  it('loads the bell after asynchronous permissions arrive without recreating the shell', () => {
    const permissions = TestBed.inject(PermissionService);
    permissions.clear();
    fixture.detectChanges();
    http.expectNone('/api/v1/notifications/unread-count');

    permissions.setPermissions(['notify.inbox.view']);
    fixture.detectChanges();
    http.expectOne('/api/v1/notifications/unread-count').flush({ unread_count: 3 });
    fixture.detectChanges();
    const bell = fixture.nativeElement.querySelector('.notif-btn') as HTMLButtonElement;
    expect(bell).not.toBeNull();
    const descriptionId = bell.getAttribute('aria-describedby');
    expect(descriptionId).toBeTruthy();
    expect(fixture.nativeElement.querySelector(`#${descriptionId}`)?.textContent).toContain('3');
    http.verify();
  });

  it('cancels notification reads and mutation callbacks when the authenticated shell is destroyed', () => {
    renderAdmin();
    const notifications = TestBed.inject(NotificationService);
    let delivered = 0;
    const requests: Observable<unknown>[] = [notifications.fetchUnreadCount(), notifications.fetchActiveAnnouncement(),
      notifications.fetchNotifications(), notifications.markAsRead(7),
      notifications.markAllAsRead(), notifications.dismissAnnouncement(9)
    ];
    requests.forEach(request => request.subscribe(() => delivered++));
    const pending = http.match(request => /\/notifications\/|\/announcements\//.test(request.url));
    expect(pending).toHaveLength(6);

    fixture.destroy();

    expect(pending.every(request => request.cancelled)).toBe(true);
    expect(delivered).toBe(0);
    expect(notifications.unreadCount()).toBe(0);
    expect(notifications.activeAnnouncement()).toBeNull();
    http.verify();
  });

  it('ignores queued SSE events from the previous shell while accepting events from its replacement', () => {
    renderAdmin();
    const notifications = TestBed.inject(NotificationService);
    const oldSource = TestEventSource.instances[0];
    fixture.destroy();
    notifications.connectSse();
    const newSource = TestEventSource.instances[1];
    newSource.emit('notification', { title: 'Current session', body: 'Current notification' });
    oldSource.emit('notification', { title: 'Previous session', body: 'Private old notification' });
    oldSource.emit('announcement', { id: 9, title: 'Previous announcement' });

    expect(notifications.unreadCount()).toBe(1);
    expect(notifications.activeAnnouncement()).toBeNull();
    expect(TestBed.inject(ToastService).toasts().map(toast => toast.message)).toEqual(['Current notification']);
    notifications.disconnectSse();
    http.verify();
  });
});
