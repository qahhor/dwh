import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { SMTSwitchComponent } from '@shared/ui-kit/components/forms/switch';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';

@Component({
  selector: 'app-settings-preferences-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTSwitchComponent, SMTSelectComponent, TranslatePipe, SMTButtonComponent],
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
            [disabled]="isSaving()"
            [value]="currentLang()"
            (valueChange)="$event && changeLanguage.emit($event)"
          />
        </div>

        <div class="form-group">
          <label class="form-label" for="settings-theme">{{ 'settings.theme' | t }}</label>
          <smt-select
            smtTriggerId="settings-theme"
            [options]="themeOptions()"
            [allowClear]="false"
            [disabled]="isSaving()"
            [value]="userThemePreference()"
            (valueChange)="$event && themeChange.emit($event)"
          />
        </div>

        <div class="form-group full-width">
          <div class="toggle-row">
            <div class="toggle-info">
              <span id="settings-notification-sound-label" class="toggle-title">{{
                'settings.notifications_sound' | t
              }}</span>
              <span id="settings-notification-sound-desc" class="toggle-desc">{{
                'settings.vosproizvodit_zvukovoy_signal_pri_poluchenii_nov' | t
              }}</span>
            </div>
            <smt-switch
              smtFieldId="settings-notification-sound"
              smtLabelledBy="settings-notification-sound-label"
              smtDescribedBy="settings-notification-sound-desc"
              [disabled]="isSaving()"
              [checked]="userSettings()['user.notifications_sound'] !== 'false'"
              (smtUserChange)="toggleSound.emit($event)"
            />
          </div>
        </div>
      </div>

      <div class="card-footer-actions">
        <button smt-button type="button" [smtLoading]="isSaving()" (click)="save.emit()">
          {{ 'common.save' | t }}
        </button>
      </div>
    </div>
  `,
  styleUrl: './settings-preferences-panel.component.css',
})
export class SettingsPreferencesPanelComponent {
  private readonly i18n = inject(I18nService);

  readonly userSettings = input<Record<string, string>>({});
  readonly isSaving = input(false);
  readonly currentLang = input('');
  readonly languages = input<
    Array<{
      code: string;
      name: string;
    }>
  >([]);
  readonly userThemePreference = input('');

  readonly save = output<void>();
  readonly changeLanguage = output<string>();
  readonly themeChange = output<string>();
  readonly toggleSound = output<boolean>();

  private readonly languageMemo = optionsMemo<SMTSelectOption<string>[]>();

  private readonly themeMemo = optionsMemo<SMTSelectOption<string>[]>();

  languageOptions(): SMTSelectOption<string>[] {
    return this.languageMemo([this.languages()], () =>
      this.languages().map((lang) => ({ id: lang.code, label: `${lang.name} (${lang.code.toUpperCase()})` })),
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
