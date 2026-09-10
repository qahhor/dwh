import { TranslationDictionary } from '../../core/models/i18n.models';

export type SettingsTab =
  | 'general'
  | 'security'
  | 'storage'
  | 'preferences'
  | 'languages'
  | 'search'
  | 'navigation';

export interface LegacyLanguage {
  name: string;
  dict: TranslationDictionary;
}
