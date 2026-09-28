import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { LanguageInfo, TranslationDictionary, TranslationEditor } from '../../core/models/i18n.models';
import { ApiService } from '../../core/services/api.service';

/** Settings as the server keeps them: key to value. */
export type SettingsValues = Record<string, string>;

/** The settings endpoints: system and personal settings, and the translation editor of a language. */
@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly api = inject(ApiService);

  systemSettings(): Observable<SettingsValues> {
    return this.api.get<SettingsValues>('/settings/system');
  }

  userSettings(): Observable<SettingsValues> {
    return this.api.get<SettingsValues>('/settings/user');
  }

  saveSystemSettings(values: SettingsValues): Observable<unknown> {
    return this.api.patch('/settings/system', values);
  }

  saveUserSettings(values: SettingsValues): Observable<unknown> {
    return this.api.patch('/settings/user', values);
  }

  /** The published dictionary of a language, as the application loads it. */
  dictionary(code: string): Observable<TranslationDictionary> {
    return this.api.get<TranslationDictionary>(`/i18n/${code}`);
  }

  /** Every key of a language with its catalogue text and override, for the editor. */
  translations(code: string): Observable<TranslationEditor> {
    return this.api.get<TranslationEditor>(`/i18n/admin/languages/${code}/translations`);
  }

  /** Replaces a language's overrides; the revision refuses a save over a newer one. */
  saveTranslations(
    code: string,
    expectedRevision: number,
    translations: TranslationDictionary,
  ): Observable<LanguageInfo> {
    return this.api.put<LanguageInfo>(`/i18n/admin/languages/${code}/translations`, { expectedRevision, translations });
  }
}
