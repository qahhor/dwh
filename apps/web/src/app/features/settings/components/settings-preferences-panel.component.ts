import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { SMTSwitchComponent } from '@shared/ui-kit/components/forms/switch';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';

@Component({
  selector: 'app-settings-preferences-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTControlComponent,
    SMTSwitchComponent,
    SMTSelectComponent,
    TranslatePipe,
    UiFormActionsComponent,
    UiFocusFirstInvalidDirective,
  ],
  template: `
    <div class="settings-card">
      <div class="card-header-bar">
        <div class="card-title-group">
          <span class="material-symbols-outlined card-icon" aria-hidden="true">palette</span>
          <div>
            <h3 class="card-title">{{ 'settings.preferences.title' | t }}</h3>
            <p class="card-desc">{{ 'settings.preferences.subtitle' | t }}</p>
          </div>
        </div>
      </div>

      <form
        id="settings-preferences-form"
        class="form-grid"
        uiFocusFirstInvalid
        novalidate
        (submit)="$event.preventDefault(); onSubmit()"
      >
        <smt-control class="form-group" [smtLabel]="'settings.common.interface_language' | t">
          <smt-select
            smtTriggerId="settings-interface-language"
            [options]="languageOptions()"
            [allowClear]="false"
            [disabled]="isSaving()"
            [value]="currentLang()"
            (valueChange)="$event && changeLanguage.emit($event)"
          />
        </smt-control>

        <smt-control class="form-group" [smtLabel]="'settings.theme' | t">
          <smt-select
            smtTriggerId="settings-theme"
            [options]="themeOptions()"
            [allowClear]="false"
            [disabled]="isSaving()"
            [value]="userThemePreference()"
            (valueChange)="$event && themeChange.emit($event)"
          />
        </smt-control>

        <div class="form-group full-width">
          <div class="toggle-row">
            <div class="toggle-info">
              <span id="settings-notification-sound-label" class="toggle-title">{{
                'settings.notifications_sound' | t
              }}</span>
              <span id="settings-notification-sound-desc" class="toggle-desc">{{
                'settings.preferences.notification_sound' | t
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
      </form>

      <ui-form-actions
        class="card-footer-actions"
        form="settings-preferences-form"
        [showCancel]="false"
        [submitting]="isSaving()"
      />
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

  /** Enter in a field and the Save button both save; nothing is sent twice while a save runs. */
  onSubmit(): void {
    if (!this.isSaving()) this.save.emit();
  }

  languageOptions(): SMTSelectOption<string>[] {
    return this.languageMemo([this.languages()], () =>
      this.languages().map((lang) => ({ id: lang.code, label: `${lang.name} (${lang.code.toUpperCase()})` })),
    );
  }

  themeOptions(): SMTSelectOption<string>[] {
    return this.themeMemo([this.i18n.currentLang()], () => [
      { id: 'dark', label: this.i18n.translate('settings.preferences.theme_dark') },
      { id: 'light', label: this.i18n.translate('settings.preferences.theme_light') },
      { id: 'system', label: this.i18n.translate('settings.preferences.theme_system') },
    ]);
  }
}
