import { ChangeDetectionStrategy, Component, OnInit, input, output } from '@angular/core';

import { NotificationPrefItem } from '@core/models/notification.models';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';

export interface EventTypeRow {
  code: string;
  titleKey: string;
}

@Component({
  selector: 'app-notification-preferences-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTDialogComponent, SMTDialogContentDirective, UiFormActionsComponent, SMTCheckboxComponent, TranslatePipe],
  template: `
    <smt-dialog
      [open]="true"
      [smtTitle]="'notifications.preferences_title' | t"
      smtSize="lg"
      [dismissible]="!isSaving()"
      (closed)="requestClose()"
    >
      <ng-template smtDialogContent>
        <form body id="notification-preferences" novalidate (submit)="$event.preventDefault(); onSave()">
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
                @for (row of eventRows; track row) {
                  <tr>
                    <td class="event-title">
                      {{ row.titleKey | t }}
                    </td>
                    <td class="channel-cell">
                      <span
                        smt-checkbox
                        smtHideLabel
                        [checked]="isEnabled(row.code, 'in_app')"
                        (checkedChange)="toggle(row.code, 'in_app', $event)"
                        [smtAriaLabel]="(row.titleKey | t) + ' - In-App'"
                      ></span>
                    </td>
                    <td class="channel-cell">
                      <span
                        smt-checkbox
                        smtHideLabel
                        [checked]="isEnabled(row.code, 'email')"
                        (checkedChange)="toggle(row.code, 'email', $event)"
                        [smtAriaLabel]="(row.titleKey | t) + ' - Email'"
                      ></span>
                    </td>
                    <td class="channel-cell">
                      <span
                        smt-checkbox
                        smtHideLabel
                        [checked]="isEnabled(row.code, 'telegram')"
                        (checkedChange)="toggle(row.code, 'telegram', $event)"
                        [smtAriaLabel]="(row.titleKey | t) + ' - Telegram'"
                      ></span>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </form>

        <ui-form-actions
          footer
          form="notification-preferences"
          [submitting]="isSaving()"
          (cancelled)="requestClose()"
        />
      </ng-template>
    </smt-dialog>
  `,
  styleUrl: './notification-preferences-modal.component.css',
})
export class NotificationPreferencesModalComponent implements OnInit {
  readonly initialPreferences = input<NotificationPrefItem[]>([]);
  readonly isSaving = input(false);

  readonly closeModal = output<void>();
  readonly save = output<NotificationPrefItem[]>();

  readonly eventRows: EventTypeRow[] = [
    { code: 'task_assigned', titleKey: 'notifications.pref_task_assigned' },
    { code: 'task_status', titleKey: 'notifications.pref_task_status' },
    { code: 'task_comment', titleKey: 'notifications.pref_task_comment' },
    { code: 'task_deadline', titleKey: 'notifications.pref_task_deadline' },
    { code: 'task_deadline_reminder', titleKey: 'notifications.pref_task_deadline_reminder' },
    { code: 'task_member_removed', titleKey: 'notifications.pref_task_member_removed' },
  ];

  private readonly prefsMap = new Map<string, boolean>();

  /** The choice as it was opened, to tell whether closing loses anything. */
  private initial = '';

  private readonly askDiscard = discardChangesQuestion();

  ngOnInit(): void {
    // Populate map with defaults (true) and overrides from initialPreferences
    for (const row of this.eventRows) {
      for (const channel of ['in_app', 'email', 'telegram']) {
        const key = `${row.code}:${channel}`;
        this.prefsMap.set(key, true);
      }
    }
    for (const item of this.initialPreferences()) {
      const key = `${item.eventType}:${item.channel}`;
      this.prefsMap.set(key, item.isEnabled);
    }
    this.initial = this.snapshot();
  }

  /** A flipped box makes closing ask first (docs/guidelines/forms-ux-standard.md, section 8). */
  dirty(): boolean {
    return this.snapshot() !== this.initial;
  }

  requestClose(): void {
    if (this.isSaving()) return;
    this.askDiscard(this.dirty()).subscribe((discard) => {
      if (discard) this.closeModal.emit();
    });
  }

  isEnabled(eventType: string, channel: string): boolean {
    return this.prefsMap.get(`${eventType}:${channel}`) ?? true;
  }

  toggle(eventType: string, channel: string, enabled: boolean): void {
    this.prefsMap.set(`${eventType}:${channel}`, enabled);
  }

  onSave(): void {
    if (this.isSaving()) return;
    const items: NotificationPrefItem[] = [];
    for (const [key, isEnabled] of this.prefsMap.entries()) {
      const [eventType, channel] = key.split(':');
      items.push({ eventType, channel, isEnabled });
    }
    this.save.emit(items);
  }

  private snapshot(): string {
    return JSON.stringify([...this.prefsMap.entries()].sort(([a], [b]) => a.localeCompare(b)));
  }
}
