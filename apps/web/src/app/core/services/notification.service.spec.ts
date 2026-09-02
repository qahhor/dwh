import { firstValueFrom, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { NotificationService } from './notification.service';
import { ToastService } from './toast.service';

describe('NotificationService API contract', () => {
  it('maps the backend inbox and unread-count contracts to the UI model', async () => {
    const api = {
      get: vi.fn()
        .mockReturnValueOnce(of({ unread_count: 3 }))
        .mockReturnValueOnce(of([{
          id: 7,
          userId: 2,
          type: 'info',
          title: 'Task assigned',
          body: 'Open the task',
          formLink: '/tasks/42',
          sourceCode: 'tasks',
          isRead: false,
          createdAt: '2026-09-02T00:00:00Z',
        }])),
      post: vi.fn(() => of(undefined)),
    };
    const service = new NotificationService(
      api as unknown as ApiService,
      {} as ToastService,
    );

    const count = await firstValueFrom(service.fetchUnreadCount());
    const page = await firstValueFrom(service.fetchNotifications(50));

    expect(api.get).toHaveBeenNthCalledWith(1, '/notifications/unread-count');
    expect(api.get).toHaveBeenNthCalledWith(2, '/notifications/inbox', { limit: 50 });
    expect(count).toEqual({ unreadCount: 3 });
    expect(service.unreadCount()).toBe(3);
    expect(page).toEqual({
      items: [{
        id: 7,
        userId: 2,
        title: 'Task assigned',
        bodyMarkdown: 'Open the task',
        sourceModule: 'tasks',
        targetUrl: '/tasks/42',
        isRead: false,
        createdAt: '2026-09-02T00:00:00Z',
      }],
      nextCursor: null,
      hasMore: false,
      totalReturned: 1,
    });
  });

  it('uses the inbox mutation routes and updates unread state', async () => {
    const api = {
      get: vi.fn(),
      post: vi.fn(() => of(undefined)),
    };
    const service = new NotificationService(
      api as unknown as ApiService,
      {} as ToastService,
    );
    service.unreadCount.set(2);

    await firstValueFrom(service.markAsRead(9));
    await firstValueFrom(service.markAllAsRead());

    expect(api.post).toHaveBeenNthCalledWith(1, '/notifications/inbox/9/read');
    expect(api.post).toHaveBeenNthCalledWith(2, '/notifications/inbox/read-all');
    expect(service.unreadCount()).toBe(0);
  });
});
