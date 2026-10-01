import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

@Component({
  selector: 'app-notifications-header',
  imports: [UiPageHeaderComponent, TranslatePipe, SMTButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-page-header [title]="'notifications.inbox.title' | t" [count]="totalCount()">
      <button
        smt-button
        type="button"
        smtVariant="secondary"
        smtIcon="refresh"
        [smtLoading]="isLoading()"
        [attr.aria-label]="'notifications.inbox.refresh' | t"
        (click)="refresh.emit()"
      >
        {{ 'notifications.inbox.refresh' | t }}
      </button>
      <button
        smt-button
        type="button"
        smtVariant="secondary"
        smtIcon="done_all"
        [disabled]="unreadCount() === 0 || hasPendingReads() || isMarkingAll()"
        [smtLoading]="isMarkingAll()"
        (click)="markAllRead.emit()"
      >
        {{ 'notifications.inbox.read_all' | t }}
      </button>
      <button
        smt-button
        type="button"
        smtVariant="secondary"
        smtIcon="tune"
        [attr.aria-label]="'notifications.preferences_title' | t"
        (click)="openPreferences.emit()"
      >
        {{ 'notifications.preferences_title' | t }}
      </button>
    </ui-page-header>
  `,
  styles: [
    `
      :host {
        display: block;
      }
    `,
  ],
})
export class NotificationsHeaderComponent {
  readonly totalCount = input.required<number>();
  readonly unreadCount = input.required<number>();
  readonly isLoading = input.required<boolean>();
  readonly hasPendingReads = input.required<boolean>();
  readonly isMarkingAll = input.required<boolean>();

  readonly refresh = output<void>();
  readonly markAllRead = output<void>();
  readonly openPreferences = output<void>();
}
