import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SMTSwitchComponent } from '../../../shared/ui-kit/components/forms/switch';
import { FormsModule } from '@angular/forms';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { SMTSelectComponent, SMTSelectOption } from '../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../shared/ui-kit/components/forms/radio-group/radio-options';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';

@Component({
  selector: 'app-settings-preferences-panel',
  standalone: true,
  imports: [
    SMTSwitchComponent,
    SMTSelectComponent,
    CommonModule,
    FormsModule,
    TranslatePipe,
    UiButtonComponent
  ],
  template: `
    <div class="settings-card">
      <div class="card-header-bar">
        <div class="card-title-group">
          <span class="material-symbols-outlined card-icon" aria-hidden="true">palette</span>
          <div>
            <h3 class="card-title">{{ 'settings.personalnye_predpochteniya' | t }}</h3>
            <p class="card-desc">{{ 'settings.nastroyki_vneshnego_vida_i_yazyka_dlya_vashey_uc' | t }}</p>
          </div>
        </div>
      </div>

      <div class="form-grid">
        <div class="form-group">
          <label class="form-label" for="settings-interface-language">{{ 'settings.yazyk_interfeysa' | t }}</label>
          <smt-select
            smtTriggerId="settings-interface-language"
            [options]="languageOptions()"
            [allowClear]="false"
            [disabled]="isSaving"
            [value]="currentLang"
            (valueChange)="$event && changeLanguage.emit($event)"
          />
        </div>

        <div class="form-group">
          <label class="form-label" for="settings-theme">{{ 'settings.theme' | t }}</label>
          <smt-select
            smtTriggerId="settings-theme"
            [options]="themeOptions()"
            [allowClear]="false"
            [disabled]="isSaving"
            [value]="userThemePreference"
            (valueChange)="$event && themeChange.emit($event)"
          />
        </div>

        <div class="form-group full-width">
          <div class="toggle-row">
            <div class="toggle-info">
              <span id="settings-notification-sound-label" class="toggle-title">{{ 'settings.notifications_sound' | t }}</span>
              <span id="settings-notification-sound-desc" class="toggle-desc">{{ 'settings.vosproizvodit_zvukovoy_signal_pri_poluchenii_nov' | t }}</span>
            </div>
            <smt-switch
              smtFieldId="settings-notification-sound"
              smtLabelledBy="settings-notification-sound-label"
              smtDescribedBy="settings-notification-sound-desc"
              [disabled]="isSaving"
              [checked]="userSettings['user.notifications_sound'] !== 'false'"
              (smtUserChange)="toggleSound.emit($event)" />
          </div>
        </div>
      </div>

      <div class="card-footer-actions">
        <ui-button [loading]="isSaving" (onClick)="save.emit()">
          {{ 'common.save' | t }}
        </ui-button>
      </div>
    </div>
  `,
  styles: [`
    .settings-card {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 24px;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }
    .card-header-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid var(--border-subtle);
      padding-bottom: 16px;
    }
    .card-title-group {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .card-icon {
      font-size: 28px;
      color: var(--primary-text);
    }
    .card-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }
    .card-desc {
      font-size: 13px;
      color: var(--text-light);
      margin: 2px 0 0 0;
    }
    .form-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 18px;
    }
    @media (max-width: 768px) {
      .form-grid {
        grid-template-columns: 1fr;
      }
    }
    .form-group {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .form-group.full-width {
      grid-column: span 2;
    }
    @media (max-width: 768px) {
      .form-group.full-width {
        grid-column: span 1;
      }
    }
    .form-label {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-main);
    }
    .toggle-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 14px 16px;
      background: var(--bg-hover);
      border: 1px solid var(--border-subtle);
      border-radius: 8px;
    }
    .toggle-info {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .toggle-title {
      font-size: 14px;
      font-weight: 500;
      color: var(--text-main);
    }
    .toggle-desc {
      font-size: 12px;
      color: var(--text-light);
    }
    .card-footer-actions {
      display: flex;
      justify-content: flex-end;
      padding-top: 12px;
      border-top: 1px solid var(--border-subtle);
    }
  `]
})
export class SettingsPreferencesPanelComponent {
  private readonly i18n = inject(I18nService);

  @Input() userSettings: Record<string, string> = {};
  @Input() isSaving = false;
  @Input() currentLang = '';
  @Input() languages: Array<{ code: string; name: string }> = [];
  @Input() userThemePreference = '';

  @Output() save = new EventEmitter<void>();
  @Output() changeLanguage = new EventEmitter<string>();
  @Output() themeChange = new EventEmitter<string>();
  @Output() toggleSound = new EventEmitter<boolean>();

  private readonly languageMemo = optionsMemo<SMTSelectOption<string>[]>();

  private readonly themeMemo = optionsMemo<SMTSelectOption<string>[]>();

  languageOptions(): SMTSelectOption<string>[] {
    return this.languageMemo([this.languages], () =>
      this.languages.map(lang => ({ id: lang.code, label: `${lang.name} (${lang.code.toUpperCase()})` }))
    );
  }

  themeOptions(): SMTSelectOption<string>[] {
    return this.themeMemo([this.i18n.currentLang()], () => [
      { id: 'dark', label: this.i18n.translate('settings.temnaya_dark_premium') },
      { id: 'light', label: this.i18n.translate('settings.svetlaya_light_clean') },
      { id: 'system', label: this.i18n.translate('settings.sistemnaya_tema') },
    ]);
  }
}
