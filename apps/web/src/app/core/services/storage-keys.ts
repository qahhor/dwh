/** Browser storage key of the chosen theme (plan 10/10, item 4.7). */
export const THEME_STORAGE_KEY = 'smc_theme';
/** Browser storage key of the chosen interface language (plan 10/10, item 4.7). */
export const LANGUAGE_STORAGE_KEY = 'smc_lang';

/** The stored value of a product key; null without storage or without a value. */
export function readStoredValue(key: string): string | null {
  if (typeof localStorage === 'undefined') return null;
  return localStorage.getItem(key);
}
