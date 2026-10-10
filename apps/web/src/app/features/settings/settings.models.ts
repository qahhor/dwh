export type SettingsTab =
  'general' | 'security' | 'storage' | 'preferences' | 'languages' | 'search' | 'navigation' | 'webhooks';

/** One system setting a panel edited, before the store saves them together. */
export interface SettingChange {
  key: string;
  value: string;
}

/** A language the administrator adds: its code, name and the texts it starts with. */
export interface NewLanguage {
  code: string;
  name: string;
  dictionary: Record<string, string>;
}
