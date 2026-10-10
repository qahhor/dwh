import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { SMTSwitchComponent } from '@shared/ui-kit/components/forms/switch';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { SMTInputComponent, SMTInputValue } from '@shared/ui-kit/components/forms/input';
import { PASSWORD_POLICY } from '@core/security/password-policy';
import { formatSessionHours } from '../settings-format';
import { SettingChange } from '../settings.models';

@Component({
  selector: 'app-settings-security-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTControlComponent,
    SMTInputComponent,
    SMTSwitchComponent,
    TranslatePipe,
    UiFormActionsComponent,
    UiFocusFirstInvalidDirective,
  ],
  template: `
    <div class="settings-card">
      <div class="card-header-bar">
        <div class="card-title-group">
          <span class="material-symbols-outlined card-icon" aria-hidden="true">lock</span>
          <div>
            <h3 class="card-title">{{ 'settings.security.title' | t }}</h3>
            <p class="card-desc">{{ 'settings.security.subtitle' | t }}</p>
          </div>
        </div>
        @if (!canUpdateSystemSettings()) {
          <span class="badge badge-neutral">{{ 'settings.readonly_badge' | t }}</span>
        }
      </div>

      <form
        id="settings-security-form"
        class="form-grid"
        uiFocusFirstInvalid
        novalidate
        (submit)="$event.preventDefault(); onSubmit()"
      >
        <div class="form-group">
          <span class="form-label" id="settings-password-length">{{ 'settings.password_length' | t }}</span>
          <p class="hint-text">{{ 'password.policy.hint' | t: passwordPolicy }}</p>
        </div>

        <smt-control
          class="form-group"
          [smtLabel]="'settings.session_lifetime' | t"
          [smtHint]="'settings.security.session_ttl_hint' | t"
          [smtError]="errorOf('security.session_lifetime_hours')"
          [required]="true"
        >
          <smt-input
            smtFieldId="settings-session-lifetime"
            name="settingsSessionLifetime"
            type="number"
            [smtMin]="1"
            [smtMax]="8760"
            [disabled]="!canUpdateSystemSettings() || isSaving()"
            [value]="systemSettings()['security.session_lifetime_hours']"
            (valueChange)="changeSetting('security.session_lifetime_hours', $event)"
          />
          @if (sessionUnit(); as unit) {
            <span class="unit-badge" data-testid="session-lifetime-unit">{{ unit }}</span>
          }
        </smt-control>

        <smt-control class="form-group" [smtLabel]="'settings.idle_lock' | t" [smtHint]="'settings.idle_lock_hint' | t">
          <smt-input
            smtFieldId="settings-idle-lock"
            name="settingsIdleLock"
            type="number"
            [smtMin]="0"
            [smtMax]="1440"
            [disabled]="!canUpdateSystemSettings() || isSaving()"
            [value]="systemSettings()['security.idle_lock_minutes']"
            (valueChange)="changeSetting('security.idle_lock_minutes', $event)"
          />
        </smt-control>

        <div class="form-group full-width">
          <div class="toggle-row">
            <div class="toggle-info">
              <span id="settings-require-2fa-label" class="toggle-title">{{ 'settings.require_2fa' | t }}</span>
              <span id="settings-require-2fa-desc" class="toggle-desc">{{
                'settings.security.require_two_factor_hint' | t
              }}</span>
            </div>
            <smt-switch
              smtFieldId="settings-require-2fa"
              smtLabelledBy="settings-require-2fa-label"
              smtDescribedBy="settings-require-2fa-desc"
              [disabled]="!canUpdateSystemSettings() || isSaving()"
              [checked]="systemSettings()['security.require_2fa'] === 'true'"
              (smtUserChange)="toggleRequire2fa.emit($event)"
            />
          </div>
        </div>
      </form>

      @if (canUpdateSystemSettings()) {
        <ui-form-actions
          class="card-footer-actions"
          form="settings-security-form"
          [showCancel]="false"
          [submitting]="isSaving()"
        />
      }
    </div>
  `,
  styleUrl: './settings-security-panel.component.css',
})
export class SettingsSecurityPanelComponent {
  private readonly i18n = inject(I18nService);

  readonly canUpdateSystemSettings = input(false);
  readonly isSaving = input(false);

  readonly systemSettings = input<Record<string, string>>({});
  /** The store's refusals by setting key (i18n keys), shown under the fields. */
  readonly errors = input<Record<string, string>>({});

  readonly save = output<void>();
  /** The settings object belongs to the store, so an edit goes up and the store keeps it. */
  readonly settingChange = output<SettingChange>();
  readonly toggleRequire2fa = output<boolean>();

  readonly passwordPolicy = PASSWORD_POLICY;

  /** A number field gives a number, or null when empty; the settings keep text, validated on save. */
  changeSetting(key: string, value: SMTInputValue): void {
    this.settingChange.emit({ key, value: value === null ? '' : String(value) });
  }

  formatSessionHours(hours: string | number | undefined): string {
    return formatSessionHours(hours, (key) => this.i18n.translate(key));
  }

  /** The lifetime read in hours and days beside the field; nothing while it is not a positive number. */
  sessionUnit(): string {
    return this.formatSessionHours(this.systemSettings()['security.session_lifetime_hours']);
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
