import { Injectable, inject, signal } from '@angular/core';
import { Observable, concatMap, finalize, from, switchMap, toArray } from 'rxjs';
import { TranslationDictionary } from '@core/models/i18n.models';
import { I18nService } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { SettingsApi } from './settings.api';
import { LegacyLanguage, filterKnownTranslations, readLegacyLanguages } from './settings.models';
import { SettingsStore } from './settings.store';

/**
 * The languages tab of the settings screen: adding a language, its editor,
 * dictionary export and the one-off move of browser-kept language packs to
 * the server. Provided by the screen next to SettingsStore.
 */
@Injectable()
export class SettingsLanguagesStore {
  private readonly settingsApi = inject(SettingsApi);
  private readonly settings = inject(SettingsStore);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

  readonly isAddLangModalOpen = signal<boolean>(false);
  readonly isAddingLang = signal<boolean>(false);
  readonly editingLanguageCode = signal<string | null>(null);
  readonly legacyLanguageCount = signal(0);
  readonly isMigratingLegacyLanguages = signal(false);

  countLegacyLanguages(): void {
    this.legacyLanguageCount.set(Object.keys(readLegacyLanguages()).length);
  }

  openLanguageEditor(code: string): void {
    this.editingLanguageCode.set(code);
  }

  onLanguageSaved(): void {
    this.i18n.refreshLanguages().subscribe();
  }

  migrateLegacyLanguages(): void {
    const legacyLanguages = readLegacyLanguages();
    const entries = Object.entries(legacyLanguages);
    if (entries.length === 0 || !this.settings.canUpdateSystemSettings()) return;
    this.modal
      .confirm({
        message: this.i18n.translate('settings.confirm_legacy_migration', { count: entries.length }),
      })
      .subscribe((confirmed) => {
        if (confirmed) this.runLegacyLanguageMigration(entries);
      });
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

  /** Browser copies are removed only after every language reached the server. */
  private runLegacyLanguageMigration(entries: [string, LegacyLanguage][]): void {
    this.isMigratingLegacyLanguages.set(true);
    this.settingsApi
      .translations('ru')
      .pipe(
        switchMap((russianEditor) => {
          const knownKeys = new Set(russianEditor.entries.map((entry) => entry.key));
          return from(entries).pipe(
            concatMap(([code, legacy]) => this.migrateLegacyLanguage(code, legacy, knownKeys)),
            toArray(),
          );
        }),
        switchMap(() => this.i18n.refreshLanguages()),
        finalize(() => this.isMigratingLegacyLanguages.set(false)),
      )
      .subscribe({
        next: () => {
          localStorage.removeItem('dwh_custom_languages');
          this.legacyLanguageCount.set(0);
          this.toast.success(this.i18n.translate('settings.languages.local_packs_migrated'));
        },
        error: () => this.toast.error(this.i18n.translate('settings.languages.local_packs_migrate_failed')),
      });
  }

  /** An existing language keeps its server overrides; the browser copy wins where both have a key. */
  private migrateLegacyLanguage(code: string, legacy: LegacyLanguage, knownKeys: Set<string>): Observable<unknown> {
    const translations = filterKnownTranslations(legacy.dict, knownKeys);
    const existing = this.i18n.languages().some((language) => language.code === code);
    if (!existing) {
      return this.i18n.registerLanguage(code, legacy.name, translations);
    }

    return this.settingsApi.translations(code).pipe(
      switchMap((editor) => {
        const merged: TranslationDictionary = {};
        for (const entry of editor.entries) {
          if (entry.overrideValue) merged[entry.key] = entry.overrideValue;
        }
        Object.assign(merged, translations);
        return this.settingsApi.saveTranslations(code, editor.language.revision, merged);
      }),
    );
  }
}
