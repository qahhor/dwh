import { Injectable, signal } from '@angular/core';
import { Observable, map, of, tap } from 'rxjs';
import { ApiService } from './api.service';
import { ToastService } from './toast.service';
import { NotificationItem, Announcement } from '../models/notification.models';
import { KeysetPage } from '../models/common.models';

interface BackendNotification {
  id: number;
  userId: number;
  type: string;
  title: string;
  body?: string;
  formLink?: string;
  sourceCode?: string;
  isRead: boolean;
  createdAt: string;
}

interface BackendUnreadCount {
  unread_count: number;
}

@Injectable({
  providedIn: 'root'
})
export class NotificationService {
  readonly unreadCount = signal<number>(0);
  readonly activeAnnouncement = signal<Announcement | null>(null);

  private eventSource: EventSource | null = null;
  private isConnecting = false;

  constructor(
    private api: ApiService,
    private toast: ToastService
  ) {}

  fetchUnreadCount(): Observable<{ unreadCount: number }> {
    return this.api.get<BackendUnreadCount>('/notifications/unread-count').pipe(
      map(res => ({ unreadCount: res.unread_count })),
      tap(res => this.unreadCount.set(res.unreadCount))
    );
  }

  fetchNotifications(limit: number = 20, _cursor?: string): Observable<KeysetPage<NotificationItem>> {
    return this.api.get<BackendNotification[]>('/notifications/inbox', { limit }).pipe(
      map(records => {
        const items = records.map(record => ({
          id: record.id,
          userId: record.userId,
          title: record.title,
          bodyMarkdown: record.body,
          sourceModule: record.sourceCode,
          targetUrl: record.formLink,
          isRead: record.isRead,
          createdAt: record.createdAt,
        }));
        return {
          items,
          nextCursor: null,
          hasMore: false,
          totalReturned: items.length,
        };
      })
    );
  }

  markAsRead(id: number): Observable<void> {
    return this.api.post<void>(`/notifications/inbox/${id}/read`);
  }

  markAllAsRead(): Observable<void> {
    return this.api.post<void>('/notifications/inbox/read-all').pipe(
      tap(() => this.unreadCount.set(0))
    );
  }

  fetchActiveAnnouncement(): Observable<Announcement | null> {
    return this.api.get<Announcement[] | Announcement | null>('/announcements/active').pipe(
      map(res => {
        if (Array.isArray(res)) {
          return res.length > 0 ? res[0] : null;
        }
        return res || null;
      }),
      tap(a => this.activeAnnouncement.set(a))
    );
  }

  dismissAnnouncement(id?: number): Observable<void> {
    if (!id) {
      this.activeAnnouncement.set(null);
      return of(undefined as unknown as void);
    }
    return this.api.post<void>(`/announcements/${id}/read`).pipe(
      tap(() => this.activeAnnouncement.set(null))
    );
  }

  /**
   * Подключение к Server-Sent Events (SSE) потоку /api/v1/events для получения уведомлений в реальном времени.
   */
  connectSse(): void {
    if (this.eventSource || this.isConnecting) {
      return;
    }

    try {
      this.isConnecting = true;
      this.eventSource = new EventSource('/api/v1/events', { withCredentials: true });

      this.eventSource.onopen = () => {
        this.isConnecting = false;
      };

      // Слушатель события 'notification'
      this.eventSource.addEventListener('notification', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data);
          this.unreadCount.update(c => c + 1);

          const title = data.title || 'Новое уведомление';
          const body = data.body || '';
          this.toast.info(body ? `${body}` : title, title);
        } catch (err) {
          console.debug('SSE: Error parsing notification event', err);
        }
      });

      // Слушатель события 'announcement'
      this.eventSource.addEventListener('announcement', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data);
          this.activeAnnouncement.set(data);
          this.toast.warning(data.title || 'Системное объявление', 'Объявление');
        } catch (err) {
          console.debug('SSE: Error parsing announcement event', err);
        }
      });

      this.eventSource.onerror = (err) => {
        this.isConnecting = false;
        // EventSource автоматически выполняет реконнект в браузере
      };
    } catch (e) {
      this.isConnecting = false;
      console.debug('SSE connection init error:', e);
    }
  }

  /**
   * Закрытие SSE соединения при выходе из системы.
   */
  disconnectSse(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
      this.isConnecting = false;
    }
  }
}
