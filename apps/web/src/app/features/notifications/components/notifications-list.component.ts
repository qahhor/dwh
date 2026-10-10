import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { UiPaginationComponent } from '@shared/ui/ui-pagination.component';
import { NotificationItem } from '@core/models/notification.models';
import { NotificationFilterTab, resolveNotificationIcon } from '../notifications.models';

@Component({
  selector: 'app-notifications-list',
  imports: [TranslatePipe, SMTButtonComponent, UiPaginationComponent, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (isLoading() && itemsCount() === 0) {
      <div class="notif-loading" role="status">
        <div class="loading-spinner"></div>
        <span class="loading-text">{{ 'notifications.inbox.loading' | t }}</span>
      </div>
    }

    @if (loadError() && !isLoading()) {
      <div class="notif-error" role="alert">
        <span class="material-symbols-outlined error-icon" aria-hidden="true">error</span>
        <span class="notif-error-message">{{ loadError() }}</span>
        <button smt-button type="button" smtVariant="secondary" smtSize="sm" smtIcon="refresh" (click)="retry.emit()">
          {{ 'notifications.inbox.retry' | t }}
        </button>
      </div>
    }

    @if ((!isLoading() || itemsCount() > 0) && !loadError()) {
      <div class="notif-list" role="region" [attr.aria-label]="'notifications.inbox.list' | t">
        @for (n of paginatedItems(); track n) {
          <article
            class="notif-item"
            [class.unread]="!n.isRead"
            [class.clickable]="!!n.targetUrl"
            [attr.tabindex]="n.targetUrl ? 0 : null"
            [attr.role]="n.targetUrl ? 'button' : null"
            (click)="itemClick.emit(n)"
            (keydown)="itemKeydown.emit({ event: $event, item: n })"
          >
            <div class="notif-icon-box" [class.unread-icon]="!n.isRead">
              <span class="material-symbols-outlined" aria-hidden="true">
                {{ getNotificationIcon(n) }}
              </span>
            </div>
            <div class="notif-body">
              <div class="notif-header">
                <span class="notif-title font-medium">{{ n.title }}</span>
                <span class="notif-time tabular-nums text-muted">{{ n.createdAt | date: 'dd.MM.yyyy HH:mm' }}</span>
              </div>
              @if (n.bodyMarkdown) {
                <p class="notif-text">{{ n.bodyMarkdown }}</p>
              }
              @if (n.targetUrl) {
                <div class="notif-target-link">
                  <span class="material-symbols-outlined target-icon" aria-hidden="true">arrow_forward</span>
                  <span>{{ 'notifications.inbox.go_to_item' | t }}</span>
                </div>
              }
            </div>
            <div class="notif-actions">
              @if (!n.isRead) {
                <span class="dot" aria-hidden="true"></span>
              }
              @if (!n.isRead) {
                <button
                  type="button"
                  class="mark-read-btn"
                  [disabled]="isMarkingAll() || pendingReads().has(n.id)"
                  [attr.aria-label]="'notifications.mark_named_read' | t: { title: n.title }"
                  (click)="markAsReadClick.emit({ event: $event, item: n })"
                >
                  <span class="material-symbols-outlined" aria-hidden="true">done</span>
                </button>
              }
            </div>
          </article>
        }

        @if (filteredCount() === 0 && !isLoading()) {
          <div class="empty-notif">
            <div class="empty-icon-wrap">
              <span class="material-symbols-outlined empty-icon" aria-hidden="true">
                {{ filterTab() === 'unread' ? 'mark_email_read' : 'notifications_off' }}
              </span>
            </div>
            <div class="empty-title">
              {{ (filterTab() === 'unread' ? 'notifications.inbox.all_read' : 'notifications.inbox.empty') | t }}
            </div>
            <div class="empty-subtitle">
              {{ (filterTab() === 'unread' ? 'notifications.inbox.no_unread' : 'notifications.inbox.empty_hint') | t }}
            </div>
          </div>
        }
      </div>
    }

    @if (filteredCount() > 0) {
      <ui-pagination
        [totalItems]="filteredCount()"
        [currentPage]="currentPage()"
        [pageSize]="pageSize()"
        (pageChange)="pageChange.emit($event)"
        (pageSizeChange)="pageSizeChange.emit($event)"
      ></ui-pagination>
    }
  `,
  styleUrl: './notifications-list.component.css',
})
export class NotificationsListComponent {
  readonly paginatedItems = input.required<NotificationItem[]>();
  readonly itemsCount = input.required<number>();
  readonly filteredCount = input.required<number>();
  readonly filterTab = input.required<NotificationFilterTab>();
  readonly isLoading = input.required<boolean>();
  readonly isMarkingAll = input.required<boolean>();
  readonly pendingReads = input.required<Set<number>>();
  readonly currentPage = input.required<number>();
  readonly pageSize = input.required<number>();

  readonly loadError = input<string | null>(null);

  readonly itemClick = output<NotificationItem>();
  readonly itemKeydown = output<{ event: KeyboardEvent; item: NotificationItem }>();
  readonly markAsReadClick = output<{ event: MouseEvent; item: NotificationItem }>();
  readonly retry = output<void>();
  readonly pageChange = output<number>();
  readonly pageSizeChange = output<number>();

  getNotificationIcon(item: NotificationItem): string {
    return resolveNotificationIcon(item);
  }
}
