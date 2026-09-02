import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationService } from '../../core/services/notification.service';
import { ToastService } from '../../core/services/toast.service';
import { NotificationsComponent } from './notifications.component';

describe('NotificationsComponent UI contracts', () => {
  const notificationService = {
    unreadCount: signal(1),
    fetchNotifications: vi.fn(() => of({ items: [] })),
    fetchUnreadCount: vi.fn(() => of(1)),
    markAllAsRead: vi.fn(() => of(undefined)),
    markAsRead: vi.fn(() => of(undefined))
  };
  const toastService = { success: vi.fn() };

  beforeEach(async () => {
    vi.clearAllMocks();
    await TestBed.configureTestingModule({
      imports: [NotificationsComponent],
      providers: [
        { provide: NotificationService, useValue: notificationService },
        { provide: ToastService, useValue: toastService }
      ]
    }).compileComponents();
  });

  it('exposes marking an unread notification as an explicit named button', () => {
    const fixture = TestBed.createComponent(NotificationsComponent);
    fixture.detectChanges();
    fixture.componentInstance.items.set([{
      id: 7,
      userId: 3,
      title: 'Новая задача',
      isRead: false,
      createdAt: '2026-08-30T00:00:00Z'
    }]);
    fixture.detectChanges();

    const action = fixture.nativeElement.querySelector('.mark-read-btn') as HTMLButtonElement;
    expect(action).not.toBeNull();
    expect(action.type).toBe('button');
    expect(action.getAttribute('aria-label')).toBe('Отметить уведомление «Новая задача» как прочитанное');
  });
});
