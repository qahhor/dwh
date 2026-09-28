import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { SMTSwitchComponent } from '@shared/ui-kit/components/forms/switch';
import { FormsModule } from '@angular/forms';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { PASSWORD_POLICY } from '@core/security/password-policy';

@Component({
  selector: 'app-settings-security-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTInputValueAccessor,
    SMTSwitchComponent,
    FormsModule,
    TranslatePipe,
    SMTButtonComponent,
  ],
  template: `
    <div class="settings-card">
      <div class="card-header-bar">
        <div class="card-title-group">
          <span class="material-symbols-outlined card-icon" aria-hidden="true">lock</span>
          <div>
            <h3 class="card-title">{{ 'settings.politiki_bezopasnosti_i_avtorizacii' | t }}</h3>
            <p class="card-desc">{{ 'settings.trebovaniya_k_parolyam_2fa_i_veb_sessiyam' | t }}</p>
          </div>
        </div>
        @if (!canUpdateSystemSettings()) {
          <span class="badge badge-neutral">{{ 'settings.readonly_badge' | t }}</span>
        }
      </div>

      <div class="form-grid">
        <div class="form-group">
          <span class="form-label" id="settings-password-length">{{ 'settings.password_length' | t }}</span>
          <p class="hint-text">{{ 'password.policy.hint' | t: passwordPolicy }}</p>
        </div>

        <div class="form-group">
          <label class="form-label" for="settings-session-lifetime">
            {{ 'settings.session_lifetime' | t }}
            @if (formatSessionHours(systemSettings()['security.session_lifetime_hours']); as sessionBadge) {
              <span class="unit-badge">
                {{ sessionBadge }}
              </span>
            }
          </label>
          <smt-input
            smtFieldId="settings-session-lifetime"
            name="settingsSessionLifetime"
            type="number"
            [smtMin]="1"
            [smtMax]="8760"
            [disabled]="!canUpdateSystemSettings() || isSaving()"
            smtDescribedBy="settings-session-lifetime-hint"
            [(ngModel)]="systemSettings()['security.session_lifetime_hours']"
          />
          <span id="settings-session-lifetime-hint" class="hint-text">{{
            'settings.po_umolchaniyu_720_chasov_30_dney' | t
          }}</span>
        </div>

        <div class="form-group">
          <label class="form-label" for="settings-idle-lock">{{ 'settings.idle_lock' | t }}</label>
          <smt-input
            smtFieldId="settings-idle-lock"
            name="settingsIdleLock"
            type="number"
            [smtMin]="0"
            [smtMax]="1440"
            [disabled]="!canUpdateSystemSettings() || isSaving()"
            smtDescribedBy="settings-idle-lock-hint"
            [(ngModel)]="systemSettings()['security.idle_lock_minutes']"
          />
          <span id="settings-idle-lock-hint" class="hint-text">{{ 'settings.idle_lock_hint' | t }}</span>
        </div>

        <div class="form-group full-width">
          <div class="toggle-row">
            <div class="toggle-info">
              <span id="settings-require-2fa-label" class="toggle-title">{{ 'settings.require_2fa' | t }}</span>
              <span id="settings-require-2fa-desc" class="toggle-desc">{{
                'settings.prinuditelno_trebovat_dvuhfaktornuyu_autentifika' | t
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
      </div>

      @if (canUpdateSystemSettings()) {
        <div class="card-footer-actions">
          <button smt-button type="button" [smtLoading]="isSaving()" (click)="save.emit()">
            {{ 'common.save' | t }}
          </button>
        </div>
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

  readonly save = output<void>();
  readonly toggleRequire2fa = output<boolean>();

  readonly passwordPolicy = PASSWORD_POLICY;

  formatSessionHours(hours: string | number | undefined): string {
    if (hours === undefined || hours === '') return '';
    const num = Number(hours);
    if (!Number.isFinite(num) || num <= 0) return '';
    const days = Math.floor(num / 24);
    const remHours = num % 24;
    const h = this.i18n.translate('settings.unit_hours_short') || 'h';
    const d = this.i18n.translate('settings.unit_days_short') || 'd';
    if (days === 0) return `${num} ${h}`;
    if (remHours === 0) return `${num} ${h} (${days} ${d})`;
    return `${num} ${h} (${days} ${d} ${remHours} ${h})`;
  }
}
