/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path utils/biruni-number.ts.
 * Per ADR-0015: behaviour changes belong upstream; only compatibility
 * edits are made here and each is marked. See NOTICE. */
import BigNumber from 'bignumber.js';

export const SMT_BIRUNI_NUMBER_REGEXP = /^-?[0-9 ]*[.]?[0-9 ]*$/;
export const SMT_BIRUNI_NUMBER_DEFAULT_PRECISION = 14;
export const SMT_BIRUNI_NUMBER_DEFAULT_SCALE = 6;

const SHORTCUTS = {
  b: { multiplier: '1000000000', zeros: '000 000 000' },
  h: { multiplier: '100', zeros: '00' },
  k: { multiplier: '1000', zeros: '000' },
  m: { multiplier: '1000000', zeros: '000 000' },
} as const;

export interface SMTBiruniNumberShortcutResult {
  handled: boolean;
  selectionEnd: number;
  selectionStart: number;
  value: string;
}

function hasValue(value: unknown): boolean {
  return value !== null && value !== undefined && value !== '';
}

function allTrim(value: string): string {
  return value.replace(/\s+/g, ' ').trim();
}

export function formatBiruniNumberDisplay(
  value: string | number | null | undefined,
  scale = SMT_BIRUNI_NUMBER_DEFAULT_SCALE,
  fillWithZero = false
): string {
  if (!hasValue(value)) {
    return value == null ? '' : String(value);
  }

  const trimmed = allTrim(String(value));
  const sign = trimmed[0] === '-' ? '-' : '';
  const [integerPartRaw, fractionalPartRaw = ''] = trimmed
    .replace(/[^0-9.]/g, '')
    .replace(/^0+/, '')
    .split('.');
  const integerPart = integerPartRaw || '0';
  const leadingGroupLength = integerPart.length % 3;
  const groupedInteger = (
    (leadingGroupLength ? `${integerPart.slice(0, leadingGroupLength)} ` : '') +
    integerPart.slice(leadingGroupLength).replace(/(\d{3})(?=\d)/g, '$1 ')
  ).trim();

  let fractionalPart = fractionalPartRaw;
  if (scale && fractionalPart.length <= scale && fillWithZero) {
    fractionalPart = fractionalPart.padEnd(scale, '0');
  } else {
    fractionalPart = fractionalPart.replace(/0+$/, '');
  }

  const text = fractionalPart ? `${groupedInteger}.${fractionalPart}` : groupedInteger;
  return sign + text;
}

export function sanitizeBiruniNumberInput(value: string, signed = false): string {
  let transformed = value.replace(/[,]/g, '.').replace(/[^0-9.-]/g, '');

  if (!transformed) {
    return '';
  }

  transformed = transformed.replace(/[.]/, '#').replace(/[.]/g, '').replace('#', '.');

  if (signed) {
    return transformed[0] + transformed.slice(1).replace(/[-]/g, '');
  }

  return transformed.replace(/[-]/g, '');
}

export function normalizeBiruniNumberModel(
  value: string | number,
  scale = SMT_BIRUNI_NUMBER_DEFAULT_SCALE,
  fillWithZero = false
): string {
  const formatted = formatBiruniNumberDisplay(value, scale, fillWithZero).replace(/\s/g, '');
  const trimmedScale = scale && formatted.includes('.') ? formatted.replace(/0+$/, '') : formatted;
  return trimmedScale.endsWith('.') ? trimmedScale.slice(0, -1) : trimmedScale;
}

export function isBiruniNumberViewValue(value: string | null | undefined): boolean {
  if (!hasValue(value)) {
    return true;
  }

  return SMT_BIRUNI_NUMBER_REGEXP.test(String(value));
}

export function isBiruniNumberWithinLimits(
  value: string,
  precision = SMT_BIRUNI_NUMBER_DEFAULT_PRECISION,
  scale = SMT_BIRUNI_NUMBER_DEFAULT_SCALE
): boolean {
  if (!value) {
    return true;
  }

  const [integerPart = '', fractionalPart = ''] = value.replace(/[- ]/g, '').split('.');
  return integerPart.length <= precision && fractionalPart.length <= scale;
}

export function applyBiruniNumberShortcut(
  rawValue: string,
  key: string,
  ctrlKey: boolean,
  selectionStart: number | null,
  selectionEnd: number | null,
  scale = SMT_BIRUNI_NUMBER_DEFAULT_SCALE,
  fillWithZero = false,
  signed = false
): SMTBiruniNumberShortcutResult {
  const shortcut = SHORTCUTS[key.toLowerCase() as keyof typeof SHORTCUTS];

  if (!shortcut) {
    return {
      handled: false,
      selectionEnd: selectionEnd ?? rawValue.length,
      selectionStart: selectionStart ?? rawValue.length,
      value: rawValue,
    };
  }

  if (ctrlKey) {
    const normalized = normalizeBiruniNumberModel(rawValue, scale, fillWithZero);
    if (!normalized) {
      return {
        handled: true,
        selectionEnd: rawValue.length,
        selectionStart: rawValue.length,
        value: rawValue,
      };
    }

    const multiplied = new BigNumber(normalized).multipliedBy(shortcut.multiplier);
    const next = normalizeBiruniNumberModel(multiplied.toFixed(), scale, fillWithZero);
    return {
      handled: true,
      selectionEnd: next.length,
      selectionStart: next.length,
      value: next,
    };
  }

  const start = selectionStart ?? rawValue.length;
  const end = selectionEnd ?? rawValue.length;
  const inserted = `${rawValue.slice(0, start)}${shortcut.zeros}${rawValue.slice(end)}`;
  const sanitized = sanitizeBiruniNumberInput(inserted, signed);
  const normalized = normalizeBiruniNumberModel(sanitized, scale, fillWithZero);
  const nextCursor = Math.min(normalized.length, start + shortcut.zeros.replace(/\s/g, '').length);

  return {
    handled: true,
    selectionEnd: nextCursor,
    selectionStart: nextCursor,
    value: normalized,
  };
}

export interface SMTBiruniNumberConfig {
  fillWithZero?: boolean;
  precision?: number;
  scale?: number;
  signed?: boolean;
}

export function resolveBiruniNumberInput(
  rawValue: string,
  config: SMTBiruniNumberConfig = {}
): { accepted: boolean; value: string } {
  const precision = config.precision ?? SMT_BIRUNI_NUMBER_DEFAULT_PRECISION;
  const scale = config.scale ?? SMT_BIRUNI_NUMBER_DEFAULT_SCALE;
  const signed = config.signed ?? false;
  const fillWithZero = config.fillWithZero ?? false;
  const sanitized = sanitizeBiruniNumberInput(rawValue, signed);
  const normalized = normalizeBiruniNumberModel(sanitized, scale, fillWithZero);

  if (!isBiruniNumberWithinLimits(normalized, precision, scale)) {
    return {
      accepted: false,
      value: normalized,
    };
  }

  return {
    accepted: true,
    value: normalized,
  };
}
