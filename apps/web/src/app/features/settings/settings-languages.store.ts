import { Injectable, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { I18nService } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { problemText } from '@shared/ui/problem-text';
import { SettingsApi } from './settings.api';
import { NewLanguage } from './settings.models';

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
  /** The server's refusal of a new language by dialog field (`code`, `name`, `json`). */
  readonly addLanguageErrors = signal<Readonly<Record<string, string>>>({});

  openAddLanguage(): void {
    this.addLanguageErrors.set({});
    this.isAddLangModalOpen.set(true);
  }

  openLanguageEditor(code: string): void {
    this.editingLanguageCode.set(code);
  }

  onLanguageSaved(): void {
    this.i18n.refreshLanguages().subscribe();
  }

  /**
   * Registers a language the dialog has checked. A refusal about a field goes under that field (forms standard,
   * section 5); any other keeps the dialog open with the server's words in a toast.
   */
  saveNewLanguage(language: NewLanguage): void {
    if (this.isAddingLang()) return;
    this.addLanguageErrors.set({});
    this.isAddingLang.set(true);
    this.i18n
      .registerLanguage(language.code, language.name, language.dictionary)
      .pipe(finalize(() => this.isAddingLang.set(false)))
      .subscribe({
        next: () => {
          this.isAddLangModalOpen.set(false);
          this.toast.success(this.i18n.translate('settings.language_added', { name: language.name }));
        },
        error: (error: unknown) => {
          const { fields, other } = problemFieldErrors(error, {
            known: ['code', 'name', 'json'],
            rename: { translations: 'json' },
          });
          this.addLanguageErrors.set(fields);
          if (Object.keys(fields).length === 0 || other.length > 0) {
            this.toast.error(other[0] ?? (problemText(error) || this.i18n.translate('common.error')));
          }
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
