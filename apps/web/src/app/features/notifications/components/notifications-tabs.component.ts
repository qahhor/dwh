import { ChangeDetectionStrategy, Component, input, output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { NotificationFilterTab } from '../notifications.models';
import { SMTTabBarComponent, SMTTabItem } from '../../../shared/ui-kit/components/tab-bar';
import { optionsMemo } from '../../../shared/ui-kit/components/forms/radio-group';
import { I18nService } from '../../../core/services/i18n.service';

@Component({
  selector: 'app-notifications-tabs',
  standalone: true,
  imports: [SMTTabBarComponent, CommonModule, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <smt-tab-bar
      class="notif-tabs"
      [tabs]="tabs()"
      [value]="filterTab()"
      [smtAriaLabel]="'notifications.spisok_uvedomleniy' | t"
      (valueChange)="$event && tabChange.emit($event)" />
  `,
  styles: [`
    :host { display: block; }
    .notif-tabs {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 10px 16px;
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-surface);
    }
  `]
})
export class NotificationsTabsComponent {
  /** Texts of the tabs below; translated again when the language changes. */
  private readonly tabText = inject(I18nService);

  readonly filterTab = input.required<NotificationFilterTab>();
  readonly totalCount = input.required<number>();
  readonly unreadCount = input.required<number>();

  readonly tabChange = output<NotificationFilterTab>();

  private readonly tabsMemo = optionsMemo<SMTTabItem<NotificationFilterTab>[]>();

  /** All and unread, with their counts; the unread count stands out while there are any. */
  tabs(): SMTTabItem<NotificationFilterTab>[] {
    return this.tabsMemo([this.tabText.currentLang(), this.totalCount(), this.unreadCount()], () => [
      { value: 'all', label: this.tabText.translate('notifications.vse'), count: this.totalCount() },
      { value: 'unread', label: this.tabText.translate('notifications.neprochitannye'), count: this.unreadCount() || undefined, countTone: 'attention' },
    ] as SMTTabItem<NotificationFilterTab>[]);
  }
}
