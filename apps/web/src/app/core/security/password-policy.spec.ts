import { describe, expect, it } from 'vitest';
import { fitsPasswordPolicy, PASSWORD_MAX_LENGTH, PASSWORD_MIN_LENGTH } from './password-policy';

describe('password policy', () => {
  it('matches the server: 8 to 20 characters', () => {
    expect([PASSWORD_MIN_LENGTH, PASSWORD_MAX_LENGTH]).toEqual([8, 20]);
    expect(fitsPasswordPolicy('x'.repeat(8))).toBe(true);
    expect(fitsPasswordPolicy('x'.repeat(20))).toBe(true);
    expect(fitsPasswordPolicy('x'.repeat(7))).toBe(false);
    expect(fitsPasswordPolicy('x'.repeat(21))).toBe(false);
    expect(fitsPasswordPolicy(null)).toBe(false);
  });
});
