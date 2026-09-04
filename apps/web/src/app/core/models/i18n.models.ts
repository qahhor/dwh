export type TranslationDictionary = Record<string, string>;

export interface LanguageInfo {
  code: string;
  name: string;
  builtin: boolean;
  /** Transitional template compatibility; prefer !builtin. */
  isCustom?: boolean;
  active: boolean;
  revision: number;
  translated: number;
  total: number;
  coverage: number;
}

export interface TranslationEntry {
  key: string;
  russianValue: string;
  bundledValue: string | null;
  overrideValue: string | null;
  effectiveValue: string;
  translated: boolean;
}

export interface TranslationEditor {
  language: LanguageInfo;
  entries: TranslationEntry[];
}

export interface CreateLanguageRequest {
  code: string;
  name: string;
  translations: TranslationDictionary;
}

export interface UpdateTranslationsRequest {
  expectedRevision: number;
  translations: TranslationDictionary;
}
