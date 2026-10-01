import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { LanguageInfo, TranslationDictionary, TranslationEditor } from '@core/models/i18n.models';
import { ApiService } from '@core/services/api.service';

/** Settings as the server keeps them: key to value. */
export type SettingsValues = Record<string, string>;

/**
 * The system settings with the revision of the set: a save of any of them names it (plan item 3.6), so of two
 * administrators saving from the same read the second is refused instead of undoing the first.
 */
export interface SystemSettings {
  values: SettingsValues;
  revision: number;
}

/** The settings endpoints: system and personal settings, and the translation editor of a language. */
@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly api = inject(ApiService);

  systemSettings(): Observable<SystemSettings> {
    return this.api.get<SystemSettings>('/settings/system');
  }

  userSettings(): Observable<SettingsValues> {
    return this.api.get<SettingsValues>('/settings/user');
  }

  /** Saves from the revision the screen read; a refusal is reported by the screen (409/428 with a reload). */
  saveSystemSettings(values: SettingsValues, revision: number | undefined): Observable<unknown> {
    return this.api.patch('/settings/system', values, { notifyError: false, ifMatch: revision });
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
