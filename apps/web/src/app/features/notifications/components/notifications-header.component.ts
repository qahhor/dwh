import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { SMTButtonComponent } from '../../../shared/ui-kit/components/button';

@Component({
  selector: 'app-notifications-header',
  standalone: true,
  imports: [CommonModule, TranslatePipe, SMTButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="view-header">
      <div class="header-left">
        <h1 class="view-title">{{ 'notifications.centr_uvedomleniy' | t }}</h1>
        <span class="count-badge">
          {{ totalCount() }}
        </span>
      </div>
      <div class="header-right">
        <button smt-button type="button"
          smtVariant="secondary"
          smtIcon="refresh"
          [smtLoading]="isLoading()"
          [attr.aria-label]="'notifications.obnovit' | t"
          (click)="refresh.emit()"
        >
          {{ 'notifications.obnovit' | t }}
        </button>
        <button smt-button type="button"
          smtVariant="secondary"
          smtIcon="done_all"
          [disabled]="unreadCount() === 0 || hasPendingReads() || isMarkingAll()"
          [smtLoading]="isMarkingAll()"
          (click)="markAllRead.emit()"
        >
          {{ 'notifications.prochitat_vse' | t }}
        </button>
        <button smt-button type="button"
          smtVariant="secondary"
          smtIcon="tune"
          [attr.aria-label]="'notifications.preferences_title' | t"
          (click)="openPreferences.emit()"
        >
          {{ 'notifications.preferences_title' | t }}
        </button>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .view-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
    }
    .header-left {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .view-title {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-main);
      letter-spacing: -0.3px;
      margin: 0;
    }
    .count-badge {
      font-size: 12px;
      font-weight: 600;
      padding: 2px 8px;
      border-radius: 12px;
      background-color: var(--bg-hover);
      color: var(--text-muted);
    }
    .header-right {
      display: flex;
      align-items: center;
      gap: 8px;
    }
  `]
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
