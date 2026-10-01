import { Injectable, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { I18nService } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { SettingsApi } from './settings.api';

/**
 * The languages tab of the settings screen: adding a language, its editor and
 * dictionary export. Provided by the screen next to SettingsStore.
 */
@Injectable()
export class SettingsLanguagesStore {
  private readonly settingsApi = inject(SettingsApi);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  readonly isAddLangModalOpen = signal<boolean>(false);
  readonly isAddingLang = signal<boolean>(false);
  readonly editingLanguageCode = signal<string | null>(null);

  openLanguageEditor(code: string): void {
    this.editingLanguageCode.set(code);
  }

  onLanguageSaved(): void {
    this.i18n.refreshLanguages().subscribe();
  }

  saveNewLanguage(code: string, name: string, json: string): void {
    const rawCode = code.trim().toLowerCase();
    const rawName = name.trim();
    if (!rawCode || !rawName) return;

    if (!/^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$/.test(rawCode)) {
      this.toast.error(this.i18n.translate('settings.validation.lang_code'));
      return;
    }

    let dict: Record<string, string> = {};
    if (json) {
      try {
        dict = JSON.parse(json);
      } catch {
        this.toast.error(this.i18n.translate('settings.languages.invalid_json_format'));
        return;
      }
    }

    this.isAddingLang.set(true);
    this.i18n
      .registerLanguage(rawCode, rawName, dict)
      .pipe(finalize(() => this.isAddingLang.set(false)))
      .subscribe({
        next: () => {
          this.isAddLangModalOpen.set(false);
          this.toast.success(this.i18n.translate('settings.language_added', { name: rawName }));
        },
        error: () => {
          this.toast.error(this.i18n.translate('common.error'));
        },
      });
  }

  switchLanguage(lang: string): void {
    this.i18n.setLanguage(lang).subscribe();
  }

  exportLangJson(langCode: string): void {
    this.settingsApi.dictionary(langCode).subscribe((dictionary) => {
      const blob = new Blob([JSON.stringify(dictionary, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `smartupcms-translations-${langCode}.json`;
      a.click();
      URL.revokeObjectURL(url);
      this.toast.info(
        this.i18n.translate('settings.dictionary_exported', {
          code: langCode.toUpperCase(),
        }),
      );
    });
  }
}
