export interface NotificationItem {
  id: number;
  userId: number;
  title: string;
  bodyMarkdown?: string;
  sourceModule?: string;
  entityType?: string;
  entityId?: string;
  targetUrl?: string;
  isRead: boolean;
  createdAt: string;
}

export interface Announcement {
  id: number;
  titleJson: Record<string, string>;
  bodyMarkdownJson: Record<string, string>;
  severity: 'info' | 'warning' | 'critical';
  beginsAt: string;
  expiresAt: string;
  isDismissible: boolean;
  isReadByMe: boolean;
}
