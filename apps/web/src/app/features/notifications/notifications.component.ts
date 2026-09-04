import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NotificationService } from '../../core/services/notification.service';
import { ToastService } from '../../core/services/toast.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiPaginationComponent } from '../../shared/ui/ui-pagination.component';
import { NotificationItem } from '../../core/models/notification.models';
import { TranslatePipe, I18nService } from '../../core/services/i18n.service';

@Component({
  selector: 'app-notifications',
  standalone: true,
  imports: [
    TranslatePipe,CommonModule, UiButtonComponent, UiPaginationComponent],
  template: `
    <div class="notifications-container">
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'notifications.centr_uvedomleniy' | t }}</h1>
          <span class="count-badge">{{ items().length }}</span>
        </div>
        <div class="header-right">
          <ui-button
            variant="secondary"
            icon="done_all"
            [disabled]="notifService.unreadCount() === 0"
            (onClick)="markAllAsRead()"
          >
            {{ 'notifications.prochitat_vse' | t }}
          </ui-button>
        </div>
      </div>

      <div class="card notif-card">
        <div class="notif-list" role="region" [attr.aria-label]="'notifications.spisok_uvedomleniy' | t">
          <article
            *ngFor="let n of paginatedItems()"
            class="notif-item"
            [class.unread]="!n.isRead"
          >
            <div class="notif-icon-box" [class.unread-icon]="!n.isRead">
              <span class="material-symbols-outlined" aria-hidden="true">
                {{ n.isRead ? 'drafts' : 'mark_email_unread' }}
              </span>
            </div>
            <div class="notif-body">
              <div class="notif-header">
                <span class="notif-title font-medium">{{ n.title }}</span>
                <span class="notif-time tabular-nums text-muted">{{ n.createdAt | date:'dd.MM.yyyy HH:mm' }}</span>
              </div>
              <p class="notif-text" *ngIf="n.bodyMarkdown">{{ n.bodyMarkdown }}</p>
            </div>
            <div class="notif-actions" *ngIf="!n.isRead">
              <span class="dot" aria-hidden="true"></span>
              <button
                type="button"
                class="mark-read-btn"
                [attr.aria-label]="'notifications.mark_named_read' | t:{title: n.title}"
                (click)="markAsRead(n)"
              >
                <span class="material-symbols-outlined" aria-hidden="true">done</span>
              </button>
            </div>
          </article>

          <div *ngIf="items().length === 0" class="empty-notif">
            {{ 'notifications.u_vas_net_uvedomleniy' | t }}
          </div>
        </div>

        <ui-pagination
          [totalItems]="items().length"
          [currentPage]="currentPage"
          [pageSize]="pageSize"
          (pageChange)="currentPage = $event"
          (pageSizeChange)="pageSize = $event; currentPage = 1"
        ></ui-pagination>
      </div>
    </div>
  `,

  styles: [`
    .notifications-container {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 900px;
    }

    .page-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .page-title {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-main);
    }

    .page-subtitle {
      font-size: 12px;
      color: var(--text-muted);
    }

    .card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      overflow: hidden;
    }

    .notif-list {
      display: flex;
      flex-direction: column;
    }

    .notif-item {
      display: flex;
      align-items: flex-start;
      gap: 14px;
      padding: 14px 18px;
      border-bottom: 1px solid var(--border-color);
      transition: background-color 0.1s ease;
    }
    .notif-item:last-child {
      border-bottom: none;
    }
    .notif-item:hover {
      background-color: var(--bg-hover);
    }
    .notif-item.unread {
      background-color: var(--primary-subtle);
    }

    .notif-icon-box {
      width: 32px;
      height: 32px;
      border-radius: 50%;
      background-color: var(--bg-hover);
      color: var(--text-muted);
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }

    .notif-icon-box.unread-icon {
      background-color: var(--primary);
      color: #ffffff;
    }

    .notif-body {
      flex: 1;
      min-width: 0;
    }

    .notif-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 4px;
    }

    .notif-title {
      font-size: 13px;
      color: var(--text-main);
    }

    .notif-time {
      font-size: 11px;
    }

    .notif-text {
      font-size: 12px;
      color: var(--text-muted);
      line-height: 1.4;
    }

    .notif-actions {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .notif-actions .dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background-color: var(--primary);
      display: inline-block;
    }

    .mark-read-btn {
      width: 30px;
      height: 30px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background: var(--bg-surface);
      color: var(--primary);
      cursor: pointer;
    }

    .mark-read-btn:hover {
      border-color: var(--primary);
      background: var(--bg-hover);
    }

    .mark-read-btn .material-symbols-outlined { font-size: 18px; }

    .font-medium { font-weight: 500; }
    .text-muted { color: var(--text-muted); }

    .empty-notif {
      padding: 40px;
      text-align: center;
      color: var(--text-muted);
    }
  `]
})
export class NotificationsComponent implements OnInit {
  private readonly uiI18n = inject(I18nService);
  readonly items = signal<NotificationItem[]>([]);
  currentPage = 1;
  pageSize = 10;

  paginatedItems(): NotificationItem[] {
    const list = this.items();
    const start = (this.currentPage - 1) * this.pageSize;
    return list.slice(start, start + this.pageSize);
  }


  constructor(
    public notifService: NotificationService,
    private toast: ToastService
  ) {}

  ngOnInit() {
    this.loadNotifications();
  }

  loadNotifications() {
    this.notifService.fetchNotifications(50).subscribe(res => {
      this.items.set(res.items || []);
    });
  }

  markAllAsRead() {
    this.notifService.markAllAsRead().subscribe(() => {
      this.toast.success(this.uiI18n.translate('notifications.vse_uvedomleniya_prochitany'));
      this.loadNotifications();
    });
  }

  markAsRead(item: NotificationItem) {
    if (item.isRead) return;
    this.notifService.markAsRead(item.id).subscribe(() => {
      this.items.update(list => list.map(i => i.id === item.id ? { ...i, isRead: true } : i));
      this.notifService.fetchUnreadCount().subscribe();
    });
  }
}
