import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { SMTInputComponent, SMTInputValue } from '@shared/ui-kit/components/forms/input';
import { formatQuotaMb } from '../settings-format';
import { SettingChange } from '../settings.models';

@Component({
  selector: 'app-settings-storage-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTControlComponent,
    SMTInputComponent,
    TranslatePipe,
    UiFormActionsComponent,
    UiFocusFirstInvalidDirective,
  ],
  template: `
    <div class="settings-card">
      <div class="card-header-bar">
        <div class="card-title-group">
          <span class="material-symbols-outlined card-icon" aria-hidden="true">folder_shared</span>
          <div>
            <h3 class="card-title">{{ 'settings.storage.title' | t }}</h3>
            <p class="card-desc">{{ 'settings.storage.subtitle' | t }}</p>
          </div>
        </div>
        @if (!canUpdateSystemSettings()) {
          <span class="badge badge-neutral">{{ 'settings.readonly_badge' | t }}</span>
        }
      </div>

      <form
        id="settings-storage-form"
        class="form-grid"
        uiFocusFirstInvalid
        novalidate
        (submit)="$event.preventDefault(); onSubmit()"
      >
        <smt-control
          class="form-group"
          [smtLabel]="'settings.default_user_quota' | t"
          [smtHint]="'settings.storage.quota_hint' | t"
          [smtError]="errorOf('storage.default_user_quota_mb')"
          [required]="true"
        >
          <smt-input
            smtFieldId="settings-user-quota"
            name="settingsUserQuota"
            type="number"
            [smtMin]="100"
            [smtMax]="102400"
            [disabled]="!canUpdateSystemSettings() || isSaving()"
            [value]="systemSettings()['storage.default_user_quota_mb']"
            (valueChange)="changeSetting('storage.default_user_quota_mb', $event)"
          />
          @if (formatQuotaMb(systemSettings()['storage.default_user_quota_mb']); as unit) {
            <span class="unit-badge" data-testid="user-quota-unit">{{ unit }}</span>
          }
        </smt-control>
      </form>

      @if (canUpdateSystemSettings()) {
        <ui-form-actions
          class="card-footer-actions"
          form="settings-storage-form"
          [showCancel]="false"
          [submitting]="isSaving()"
        />
      }
    </div>
  `,
  styleUrl: './settings-storage-panel.component.css',
})
export class SettingsStoragePanelComponent {
  private readonly i18n = inject(I18nService);

  readonly canUpdateSystemSettings = input(false);
  readonly isSaving = input(false);

  readonly systemSettings = input<Record<string, string>>({});
  /** The store's refusals by setting key (i18n keys), shown under the fields. */
  readonly errors = input<Record<string, string>>({});

  readonly save = output<void>();
  /** The settings object belongs to the store, so an edit goes up and the store keeps it. */
  readonly settingChange = output<SettingChange>();

  /** A number field gives a number, or null when empty; the settings keep text, validated on save. */
  changeSetting(key: string, value: SMTInputValue): void {
    this.settingChange.emit({ key, value: value === null ? '' : String(value) });
  }

  formatQuotaMb(mb: string | number | undefined): string {
    return formatQuotaMb(mb, (key) => this.i18n.translate(key));
  }

  /** Enter in a field and the Save button both save; nothing is sent twice while a save runs. */
  onSubmit(): void {
    if (!this.isSaving()) this.save.emit();
  }

  /** The store's refusal of a setting as words under its field (forms standard, section 4). */
  errorOf(key: string): string {
    const message = this.errors()[key];
    return message ? this.i18n.translate(message) : '';
  }
}
