import { Component, EventEmitter, Input, Output, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NotificationPrefItem } from '../../../core/models/notification.models';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { SMTCheckboxComponent } from '../../../shared/ui-kit/components/forms/checkbox';
import { SMTButtonComponent } from '../../../shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '../../../shared/ui-kit/components/modal';

export interface EventTypeRow {
  code: string;
  titleKey: string;
}

@Component({
  selector: 'app-notification-preferences-modal',
  standalone: true,
  imports: [SMTDialogComponent, SMTDialogContentDirective, SMTButtonComponent, SMTCheckboxComponent, CommonModule, FormsModule, TranslatePipe],
  template: `
    <smt-dialog [open]="true" [smtTitle]="'notifications.preferences_title' | t" smtSize="lg" [dismissible]="!isSaving" (closed)="close.emit()">
      <ng-template smtDialogContent>
        <p class="modal-desc">
          {{ 'notifications.preferences_desc' | t }}
        </p>

        <div class="table-wrap">
          <table class="pref-table" [attr.aria-label]="'notifications.preferences_title' | t">
            <thead>
              <tr>
                <th scope="col" class="event-col">{{ 'notifications.event_type' | t }}</th>
                <th scope="col" class="channel-col">
                  <span class="material-symbols-outlined channel-icon" aria-hidden="true">notifications</span>
                  In-App
                </th>
                <th scope="col" class="channel-col">
                  <span class="material-symbols-outlined channel-icon" aria-hidden="true">mail</span>
                  Email
                </th>
                <th scope="col" class="channel-col">
                  <span class="material-symbols-outlined channel-icon" aria-hidden="true">send</span>
                  Telegram
                </th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let row of eventRows">
                <td class="event-title">
                  {{ row.titleKey | t }}
                </td>
                <td class="channel-cell">
                  <span smt-checkbox smtHideLabel
                    [checked]="isEnabled(row.code, 'in_app')"
                    (checkedChange)="toggle(row.code, 'in_app', $event)"
                    [smtAriaLabel]="(row.titleKey | t) + ' - In-App'"></span>
                </td>
                <td class="channel-cell">
                  <span smt-checkbox smtHideLabel
                    [checked]="isEnabled(row.code, 'email')"
                    (checkedChange)="toggle(row.code, 'email', $event)"
                    [smtAriaLabel]="(row.titleKey | t) + ' - Email'"></span>
                </td>
                <td class="channel-cell">
                  <span smt-checkbox smtHideLabel
                    [checked]="isEnabled(row.code, 'telegram')"
                    (checkedChange)="toggle(row.code, 'telegram', $event)"
                    [smtAriaLabel]="(row.titleKey | t) + ' - Telegram'"></span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div footer>
          <button smt-button smtVariant="secondary" type="button" (click)="close.emit()" [disabled]="isSaving">
            {{ 'common.cancel' | t }}
          </button>
          <button smt-button type="button" [smtLoading]="isSaving" (click)="onSave()">{{ 'common.save' | t }}</button>
        </div>
      </ng-template>
    </smt-dialog>
  `,
  styles: [`
    .modal-desc {
      margin: 0;
      font-size: 13px;
      color: var(--text-muted);
      line-height: 1.4;
    }
    .table-wrap {
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      overflow: hidden;
    }
    .pref-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
    }
    .pref-table th {
      background: var(--bg-hover);
      padding: 10px 14px;
      text-align: left;
      font-weight: 600;
      color: var(--text-main);
      border-bottom: 1px solid var(--border-color);
    }
    .pref-table td {
      padding: 10px 14px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
    }
    .pref-table tr:last-child td {
      border-bottom: none;
    }
    .pref-table tr:hover td {
      background: var(--bg-hover);
    }
    .event-col { width: 52%; }
    .channel-col {
      width: 16%;
      text-align: center;
    }
    .channel-cell {
      text-align: center;
    }
    .channel-icon {
      font-size: 15px;
      vertical-align: text-bottom;
      margin-right: 3px;
      color: var(--text-muted);
    }
    @keyframes spin {
      to { transform: rotate(360deg); }
    }
  `]
})
export class NotificationPreferencesModalComponent implements OnInit {
  @Input() initialPreferences: NotificationPrefItem[] = [];
  @Input() isSaving = false;

  @Output() close = new EventEmitter<void>();
  @Output() save = new EventEmitter<NotificationPrefItem[]>();

  readonly eventRows: EventTypeRow[] = [
    { code: 'task_assigned', titleKey: 'notifications.pref_task_assigned' },
    { code: 'task_observer', titleKey: 'notifications.pref_task_observer' },
    { code: 'task_status', titleKey: 'notifications.pref_task_status' },
    { code: 'task_deadline', titleKey: 'notifications.pref_task_deadline' },
    { code: 'task_deadline_reminder', titleKey: 'notifications.pref_task_deadline_reminder' },
    { code: 'task_member_removed', titleKey: 'notifications.pref_task_member_removed' }
  ];

  private readonly prefsMap = new Map<string, boolean>();

  ngOnInit(): void {
    // Populate map with defaults (true) and overrides from initialPreferences
    for (const row of this.eventRows) {
      for (const channel of ['in_app', 'email', 'telegram']) {
        const key = `${row.code}:${channel}`;
        this.prefsMap.set(key, true);
      }
    }
    for (const item of this.initialPreferences) {
      const key = `${item.eventType}:${item.channel}`;
      this.prefsMap.set(key, item.isEnabled);
    }
  }

  isEnabled(eventType: string, channel: string): boolean {
    return this.prefsMap.get(`${eventType}:${channel}`) ?? true;
  }

  toggle(eventType: string, channel: string, enabled: boolean): void {
    this.prefsMap.set(`${eventType}:${channel}`, enabled);
  }

  onSave(): void {
    const items: NotificationPrefItem[] = [];
    for (const [key, isEnabled] of this.prefsMap.entries()) {
      const [eventType, channel] = key.split(':');
      items.push({ eventType, channel, isEnabled });
    }
    this.save.emit(items);
  }
}
