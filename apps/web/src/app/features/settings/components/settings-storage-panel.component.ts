import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-settings-storage-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTInputComponent, SMTInputValueAccessor, FormsModule, TranslatePipe, SMTButtonComponent],
  template: `
    <div class="settings-card">
      <div class="card-header-bar">
        <div class="card-title-group">
          <span class="material-symbols-outlined card-icon" aria-hidden="true">folder_shared</span>
          <div>
            <h3 class="card-title">{{ 'settings.parametry_hranilischa_i_kvoty' | t }}</h3>
            <p class="card-desc">{{ 'settings.limity_diskovogo_prostranstva_dlya_novyh_sotrudn' | t }}</p>
          </div>
        </div>
        @if (!canUpdateSystemSettings()) {
          <span class="badge badge-neutral">{{ 'settings.readonly_badge' | t }}</span>
        }
      </div>

      <div class="form-grid">
        <div class="form-group">
          <label class="form-label" for="settings-user-quota">
            {{ 'settings.default_user_quota' | t }}
            @if (formatQuotaMb(systemSettings()['storage.default_user_quota_mb']); as quotaBadge) {
              <span class="unit-badge">
                {{ quotaBadge }}
              </span>
            }
          </label>
          <smt-input
            smtFieldId="settings-user-quota"
            name="settingsUserQuota"
            type="number"
            [smtMin]="100"
            [smtMax]="102400"
            [disabled]="!canUpdateSystemSettings() || isSaving()"
            smtDescribedBy="settings-user-quota-hint"
            [(ngModel)]="systemSettings()['storage.default_user_quota_mb']"
          />
          <span id="settings-user-quota-hint" class="hint-text">{{
            'settings.1024_mb_1_gb_na_kazhdogo_sotrudnika' | t
          }}</span>
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
  styleUrl: './settings-storage-panel.component.css',
})
export class SettingsStoragePanelComponent {
  private readonly i18n = inject(I18nService);

  readonly canUpdateSystemSettings = input(false);
  readonly isSaving = input(false);

  readonly systemSettings = input<Record<string, string>>({});

  readonly save = output<void>();

  formatQuotaMb(mb: string | number | undefined): string {
    if (mb === undefined || mb === '') return '';
    const num = Number(mb);
    if (!Number.isFinite(num) || num <= 0) return '';
    const mbUnit = this.i18n.translate('settings.unit_mb') || 'MB';
    const gbUnit = this.i18n.translate('settings.unit_gb') || 'GB';
    if (num >= 1024) {
      const gb = (num / 1024).toFixed(1).replace(/\.0$/, '');
      return `${num} ${mbUnit} (~${gb} ${gbUnit})`;
    }
    return `${num} ${mbUnit}`;
  }
}
