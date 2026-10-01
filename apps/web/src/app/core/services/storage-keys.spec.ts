import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { LANGUAGE_STORAGE_KEY, THEME_STORAGE_KEY, readStoredValue } from './storage-keys';

describe('product storage keys (plan 10/10, item 4.7)', () => {
  // Other specs of the same run may have saved a theme or a language already.
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('reads the value saved under the product key', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    localStorage.setItem(LANGUAGE_STORAGE_KEY, 'uz');

    expect(readStoredValue(THEME_STORAGE_KEY)).toBe('dark');
    expect(readStoredValue(LANGUAGE_STORAGE_KEY)).toBe('uz');
  });

  it('answers null for a key without a value', () => {
    expect(readStoredValue(THEME_STORAGE_KEY)).toBeNull();
    expect(readStoredValue('smc_unknown')).toBeNull();
  });
});
