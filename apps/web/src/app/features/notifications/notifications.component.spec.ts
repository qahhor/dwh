import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationItem, NotificationPrefItem } from '@core/models/notification.models';
import { NotificationService } from '@core/services/notification.service';
import { ToastService } from '@core/services/toast.service';
import { NotificationsComponent } from './notifications.component';

/*
 * The screen's own contract: the tabs, what a click on a notification does, and the
 * preferences flow. The list, the tabs and the header have their own specs; the
 * request lifecycle is in notification-actions.spec.ts.
 */
describe('NotificationsComponent', () => {
  const notificationService = {
    unreadCount: signal(1),
    fetchNotifications: vi.fn(() => of({ items: [] })),
    fetchUnreadCount: vi.fn(() => of(1)),
    markAllAsRead: vi.fn(() => of(undefined)),
    markAsRead: vi.fn(() => of(undefined)),
    fetchPreferences: vi.fn<() => Observable<NotificationPrefItem[]>>(() => of([])),
    updatePreferences: vi.fn<(prefs: NotificationPrefItem[]) => Observable<void>>(() => of(undefined)),
  };
  const router = { navigateByUrl: vi.fn() };

  beforeEach(async () => {
    vi.clearAllMocks();
    await TestBed.configureTestingModule({
      imports: [NotificationsComponent],
      providers: [
        { provide: NotificationService, useValue: notificationService },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: Router, useValue: router },
      ],
    }).compileComponents();
  });

  const item = (id: number, patch: Partial<NotificationItem> = {}): NotificationItem => ({
    id,
    userId: 3,
    title: `Item ${id}`,
    isRead: false,
    createdAt: '2026-08-30T00:00:00Z',
    ...patch,
  });

  function render(items: NotificationItem[]) {
    const fixture = TestBed.createComponent(NotificationsComponent);
    fixture.detectChanges();
    fixture.componentInstance.items.set(items);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    return { fixture, host, component: fixture.componentInstance };
  }

  it('filters items between "all" and "unread" tabs', () => {
    const { fixture, component } = render([item(1, { isRead: true }), item(2)]);
    expect(component.paginatedItems()).toHaveLength(2);

    component.setFilter('unread');
    fixture.detectChanges();
    expect(component.paginatedItems().map((shown) => shown.id)).toEqual([2]);

    component.setFilter('all');
    fixture.detectChanges();
    expect(component.paginatedItems()).toHaveLength(2);
  });

  it('navigates to targetUrl when clicking a notification row and marks it read', () => {
    const { host } = render([item(5, { title: 'Задача назначена', targetUrl: '/tasks/100' })]);

    host.querySelector<HTMLElement>('.notif-item.clickable')!.click();

    expect(notificationService.markAsRead).toHaveBeenCalledWith(5);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/tasks/100');
  });

  it('marks a clickable row read from its own button without following its link', () => {
    const { host } = render([item(9, { title: 'Задача', targetUrl: '/tasks/99' })]);

    host.querySelector<HTMLButtonElement>('.mark-read-btn')!.click();

    expect(notificationService.markAsRead).toHaveBeenCalledWith(9);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('opens preferences modal and saves updated preferences', () => {
    const { component } = render([]);

    component.openPreferencesModal();
    expect(notificationService.fetchPreferences).toHaveBeenCalled();
    expect(component.isPreferencesOpen()).toBe(true);

    component.savePreferences([{ eventType: 'task_assigned', channel: 'in_app', isEnabled: true }]);
    expect(notificationService.updatePreferences).toHaveBeenCalledWith([
      { eventType: 'task_assigned', channel: 'in_app', isEnabled: true },
    ]);
    expect(component.isPreferencesOpen()).toBe(false);
  });

  it('keeps failed preferences closed and retries without replacing saved choices with defaults', () => {
    const { component } = render([]);
    const saved = [{ eventType: 'task_status', channel: 'email', isEnabled: false }];
    component.preferences.set(saved);
    notificationService.fetchPreferences.mockReturnValueOnce(throwError(() => new Error('offline')));

    component.openPreferencesModal();

    expect(component.isPreferencesOpen()).toBe(false);
    expect(component.preferences()).toEqual(saved);
    expect(TestBed.inject(ToastService).error).toHaveBeenCalledWith(
      'Не удалось загрузить настройки уведомлений. Попробуйте ещё раз.',
    );
    expect(notificationService.updatePreferences).not.toHaveBeenCalled();

    notificationService.fetchPreferences.mockReturnValueOnce(of(saved));
    component.openPreferencesModal();
    expect(component.isPreferencesOpen()).toBe(true);
    expect(component.preferences()).toEqual(saved);
  });

  it('loads preferences once while pending and does not reload an open draft', () => {
    const { component, fixture, host } = render([]);
    const request = new Subject<NotificationPrefItem[]>();
    notificationService.fetchPreferences.mockReturnValueOnce(request);

    component.openPreferencesModal();
    component.openPreferencesModal();
    fixture.detectChanges();

    expect(notificationService.fetchPreferences).toHaveBeenCalledTimes(1);
    expect(host.querySelector<HTMLButtonElement>('[aria-label="Настройки уведомлений"]')!.disabled).toBe(true);
    request.next([]);
    request.complete();
    component.openPreferencesModal();
    expect(notificationService.fetchPreferences).toHaveBeenCalledTimes(1);
  });

  it('sends a save once while pending and keeps the editor open after failure', () => {
    const { component } = render([]);
    const request = new Subject<void>();
    notificationService.updatePreferences.mockReturnValueOnce(request);
    component.openPreferencesModal();

    component.savePreferences([]);
    component.savePreferences([]);
    expect(notificationService.updatePreferences).toHaveBeenCalledTimes(1);
    request.error(new Error('offline'));
    expect(component.isPreferencesOpen()).toBe(true);
    expect(component.isSavingPreferences()).toBe(false);
  });
});
