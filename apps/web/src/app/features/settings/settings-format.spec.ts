import { describe, expect, it } from 'vitest';
import { translateTest } from '@testing/i18n-test.stub';
import { formatQuotaMb, formatSessionHours } from './settings-format';

describe('settings units', () => {
  it('reads session hours with whole days and the hours left over', () => {
    expect(formatSessionHours('720', translateTest)).toBe('720 ч. (30 дн.)');
    expect(formatSessionHours('24', translateTest)).toBe('24 ч. (1 дн.)');
    expect(formatSessionHours('12', translateTest)).toBe('12 ч.');
    expect(formatSessionHours(30, translateTest)).toBe('30 ч. (1 дн. 6 ч.)');
  });

  it('reads a quota in megabytes, with gigabytes from 1024 up', () => {
    expect(formatQuotaMb('1024', translateTest)).toBe('1024 МБ (~1 ГБ)');
    expect(formatQuotaMb('5120', translateTest)).toBe('5120 МБ (~5 ГБ)');
    expect(formatQuotaMb(1536, translateTest)).toBe('1536 МБ (~1.5 ГБ)');
    expect(formatQuotaMb('500', translateTest)).toBe('500 МБ');
  });

  it('shows nothing for a missing or invalid value and a Latin unit without a translation', () => {
    for (const value of [undefined, '', '0', '-5', 'abc']) {
      expect(formatSessionHours(value, translateTest)).toBe('');
      expect(formatQuotaMb(value, translateTest)).toBe('');
    }
    expect(formatSessionHours('48', () => '')).toBe('48 h (2 d)');
    expect(formatQuotaMb('2048', () => '')).toBe('2048 MB (~2 GB)');
  });
});
