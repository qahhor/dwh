import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { LANGUAGE_STORAGE_KEY, THEME_STORAGE_KEY, readStoredValue } from './storage-keys';

describe('product storage keys (plan 10/10, item 4.7)', () => {
  // Other specs of the same run may have saved a theme or a language already.
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('moves a value saved under the old key to the new one', () => {
    localStorage.setItem('dwh_theme', 'dark');
    localStorage.setItem('dwh_lang', 'uz');

    expect(readStoredValue(THEME_STORAGE_KEY)).toBe('dark');
    expect(readStoredValue(LANGUAGE_STORAGE_KEY)).toBe('uz');

    expect(localStorage.getItem('smc_theme')).toBe('dark');
    expect(localStorage.getItem('smc_lang')).toBe('uz');
    expect(localStorage.getItem('dwh_theme')).toBeNull();
    expect(localStorage.getItem('dwh_lang')).toBeNull();
  });

  it('prefers the new key and leaves an unknown key alone', () => {
    localStorage.setItem('smc_theme', 'light');
    localStorage.setItem('dwh_theme', 'dark');

    expect(readStoredValue(THEME_STORAGE_KEY)).toBe('light');
    expect(readStoredValue('smc_unknown')).toBeNull();
  });
});
