import { describe, expect, it } from 'vitest';
import { NotificationItem } from '@core/models/notification.models';
import { resolveNotificationIcon } from './notifications.models';

const notification = (patch: Partial<NotificationItem>): NotificationItem => ({
  id: 1,
  userId: 1,
  title: 'T',
  isRead: false,
  createdAt: '',
  ...patch,
});

describe('resolveNotificationIcon', () => {
  it.each([
    ['tasks', 'task_alt'],
    ['comments', 'chat_bubble'],
    ['system', 'dns'],
    ['auth', 'shield'],
    ['reports', 'analytics'],
    ['files', 'folder_open'],
    ['notes', 'sticky_note_2'],
  ])('shows the icon of the source module %s', (sourceModule, icon) => {
    expect(resolveNotificationIcon(notification({ sourceModule }))).toBe(icon);
  });

  it('falls back to the entity type and then to the read state', () => {
    expect(resolveNotificationIcon(notification({ entityType: 'SECURITY' }))).toBe('shield');
    expect(resolveNotificationIcon(notification({ isRead: true }))).toBe('drafts');
    expect(resolveNotificationIcon(notification({}))).toBe('mark_email_unread');
  });
});
