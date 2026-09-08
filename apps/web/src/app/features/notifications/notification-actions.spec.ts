import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationService } from '../../core/services/notification.service';
import { ToastService } from '../../core/services/toast.service';
import { NotificationsComponent } from './notifications.component';

describe('Notification action lifecycle', () => {
  let fixture: ComponentFixture<NotificationsComponent>;
  let http: HttpTestingController;
  let service: NotificationService;
  const record = { id: 7, userId: 3, type: 'info', title: 'New task', body: 'Please review',
    isRead: false, createdAt: '2026-09-07T00:00:00Z' };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [NotificationsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()] }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(NotificationService);
    fixture = TestBed.createComponent(NotificationsComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/notifications/inbox?limit=50').flush([record]);
    service.unreadCount.set(1);
    fixture.detectChanges();
  });
  afterEach(() => { fixture.destroy(); service.disconnectSse(); vi.unstubAllGlobals(); });

  it('sends only one read per pending notification and disables its action', () => {
    const item = fixture.componentInstance.items()[0];
    fixture.componentInstance.markAsRead(item);
    fixture.componentInstance.markAsRead(item);
    const requests = http.match('/api/v1/notifications/inbox/7/read');
    expect(requests).toHaveLength(1);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.mark-read-btn').disabled).toBe(true);
    requests[0].flush(null);
    http.expectOne('/api/v1/notifications/unread-count').flush({ unread_count: 0 });
    expect(fixture.componentInstance.items()[0].isRead).toBe(true);
    http.verify();
  });

  it('keeps a failed read actionable with one error and allows a successful retry', () => {
    const item = fixture.componentInstance.items()[0];
    fixture.componentInstance.markAsRead(item);
    http.expectOne('/api/v1/notifications/inbox/7/read').flush({ detail: 'Temporary failure' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.componentInstance.items()[0].isRead).toBe(false);
    expect(fixture.nativeElement.querySelector('.mark-read-btn').disabled).toBe(false);
    expect(TestBed.inject(ToastService).toasts().filter(toast => toast.type === 'error')).toHaveLength(1);
    fixture.componentInstance.markAsRead(item);
    http.expectOne('/api/v1/notifications/inbox/7/read').flush(null);
    http.expectOne('/api/v1/notifications/unread-count').flush({ unread_count: 0 });
    expect(fixture.componentInstance.items()[0].isRead).toBe(true);
    http.verify();
  });

  it('serializes read-all and reconciles the server count instead of erasing a new notification', () => {
    fixture.componentInstance.markAllAsRead();
    fixture.componentInstance.markAllAsRead();
    fixture.componentInstance.markAsRead(fixture.componentInstance.items()[0]);
    const requests = http.match('/api/v1/notifications/inbox/read-all');
    expect(requests).toHaveLength(1);
    http.expectNone('/api/v1/notifications/inbox/7/read');
    service.unreadCount.set(2); // A new SSE notification arrived after the server mutation snapshot.
    requests[0].flush(null);
    expect(service.unreadCount()).toBe(2);
    http.expectOne('/api/v1/notifications/unread-count').flush({ unread_count: 1 });
    http.expectOne('/api/v1/notifications/inbox?limit=50').flush([{ ...record, isRead: true }]);
    expect(service.unreadCount()).toBe(1);
    http.verify();
  });

  it('cancels an obsolete inbox read and clamps the page after the list shrinks', () => {
    fixture.componentInstance.currentPage = 5;
    fixture.componentInstance.loadNotifications();
    const old = http.expectOne('/api/v1/notifications/inbox?limit=50');
    fixture.componentInstance.loadNotifications();
    expect(old.cancelled).toBe(true);
    http.expectOne('/api/v1/notifications/inbox?limit=50').flush([record]);
    expect(fixture.componentInstance.currentPage).toBe(1);
    expect(fixture.componentInstance.paginatedItems()).toHaveLength(1);
    http.verify();
  });

  it('restarts an unread snapshot when a new SSE notification arrives during reconciliation', () => {
    let notify: (event: MessageEvent) => void = () => {};
    vi.stubGlobal('EventSource', class {
      addEventListener(type: string, listener: (event: MessageEvent) => void) {
        if (type === 'notification') notify = listener;
      }
      close() {}
    });
    service.connectSse();
    fixture.componentInstance.markAllAsRead();
    http.expectOne('/api/v1/notifications/inbox/read-all').flush(null);
    http.expectOne('/api/v1/notifications/inbox?limit=50').flush([{ ...record, isRead: true }]);
    const staleCount = http.expectOne('/api/v1/notifications/unread-count');
    notify(new MessageEvent('notification', { data: JSON.stringify({ id: 8, title: 'Later notification' }) }));
    expect(staleCount.cancelled).toBe(true);
    http.expectOne('/api/v1/notifications/unread-count').flush({ unread_count: 1 });
    expect(service.unreadCount()).toBe(1);
    http.verify();
  });

  it('cancels component callbacks when leaving the notification page', () => {
    fixture.componentInstance.markAsRead(fixture.componentInstance.items()[0]);
    const pending = http.expectOne('/api/v1/notifications/inbox/7/read');
    fixture.componentInstance.loadNotifications();
    const read = http.expectOne('/api/v1/notifications/inbox?limit=50');
    fixture.destroy();
    expect(pending.cancelled).toBe(true);
    expect(read.cancelled).toBe(true);
    http.verify();
  });
});
