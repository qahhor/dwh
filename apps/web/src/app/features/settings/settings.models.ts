export type SettingsTab =
  'general' | 'security' | 'storage' | 'preferences' | 'languages' | 'search' | 'navigation' | 'webhooks';

/** One system setting a panel edited, before the store saves them together. */
export interface SettingChange {
  key: string;
  value: string;
}
