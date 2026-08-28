import { Injectable, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ApiService } from './api.service';
import { NotificationItem, Announcement } from '../models/notification.models';
import { KeysetPage } from '../models/common.models';

@Injectable({
  providedIn: 'root'
})
export class NotificationService {
  readonly unreadCount = signal<number>(0);
  readonly activeAnnouncement = signal<Announcement | null>(null);

  constructor(private api: ApiService) {}

  fetchUnreadCount(): Observable<{ unreadCount: number }> {
    return this.api.get<{ unreadCount: number }>('/notifications/unread-count').pipe(
      tap(res => this.unreadCount.set(res.unreadCount))
    );
  }

  fetchNotifications(limit: number = 20, cursor?: string): Observable<KeysetPage<NotificationItem>> {
    return this.api.get<KeysetPage<NotificationItem>>('/notifications', { limit, cursor });
  }

  markAllAsRead(): Observable<void> {
    return this.api.post<void>('/notifications/read-all').pipe(
      tap(() => this.unreadCount.set(0))
    );
  }

  fetchActiveAnnouncement(): Observable<Announcement | null> {
    return this.api.get<Announcement | null>('/announcements/active').pipe(
      tap(a => this.activeAnnouncement.set(a))
    );
  }

  dismissAnnouncement(id: number): Observable<void> {
    return this.api.post<void>(`/announcements/${id}/read`).pipe(
      tap(() => this.activeAnnouncement.set(null))
    );
  }
}
