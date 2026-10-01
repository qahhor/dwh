/** Browser storage key of the chosen theme (plan 10/10, item 4.7). */
export const THEME_STORAGE_KEY = 'smc_theme';
/** Browser storage key of the chosen interface language (plan 10/10, item 4.7). */
export const LANGUAGE_STORAGE_KEY = 'smc_lang';

/** The keys before plan 10/10, item 4.7: read once, moved to the new key and removed. */
const LEGACY_STORAGE_KEYS: Readonly<Record<string, string>> = {
  [THEME_STORAGE_KEY]: 'dwh_theme',
  [LANGUAGE_STORAGE_KEY]: 'dwh_lang',
};

/**
 * The stored value of a product key. A value saved under the old name is moved to the new one, so a returning user
 * keeps the theme and language picked before the rename.
 */
export function readStoredValue(key: string): string | null {
  if (typeof localStorage === 'undefined') return null;
  const current = localStorage.getItem(key);
  if (current !== null) return current;
  const legacyKey = LEGACY_STORAGE_KEYS[key];
  if (!legacyKey) return null;
  const legacy = localStorage.getItem(legacyKey);
  if (legacy !== null) {
    localStorage.setItem(key, legacy);
    localStorage.removeItem(legacyKey);
  }
  return legacy;
}
