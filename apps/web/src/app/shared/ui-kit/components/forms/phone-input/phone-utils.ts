/* Our code: the countries smt-phone-input offers and how their numbers are written. */

export interface SMTPhoneCountry {
  /** ISO 3166 code, shown in the country list. */
  readonly iso: string;
  /** Calling code without "+". */
  readonly code: string;
  /** Digits after the calling code; `0` marks a digit, anything else is written as is. */
  readonly mask: string;
}

/** The markets the product is sold in, Uzbekistan first; any other number goes as "other". */
export const SMT_PHONE_COUNTRIES: readonly SMTPhoneCountry[] = [
  { iso: 'UZ', code: '998', mask: '(00) 000-00-00' },
  { iso: 'KZ', code: '7', mask: '(000) 000-00-00' },
  { iso: 'KG', code: '996', mask: '(000) 000-000' },
  { iso: 'TJ', code: '992', mask: '(00) 000-0000' },
  { iso: 'RU', code: '7', mask: '(000) 000-00-00' },
  { iso: 'TR', code: '90', mask: '(000) 000 00 00' },
  { iso: 'DE', code: '49', mask: '000 00000000' },
];

/** Digits a country's number has after its calling code. */
export function nationalLength(country: SMTPhoneCountry): number {
  return country.mask.replace(/[^0]/g, '').length;
}

/** Writes national digits into the country's mask, as far as they go. */
export function formatNational(digits: string, country: SMTPhoneCountry | null): string {
  if (!country) return digits;
  let out = '';
  let next = 0;
  for (const char of country.mask) {
    if (next >= digits.length) break;
    if (char === '0') out += digits[next++];
    else out += char;
  }
  return out + digits.slice(next);
}

/**
 * Splits a stored number into a country and its national digits. The longest
 * calling code wins (+998 before +9…); +7 is read as Kazakhstan unless the
 * caller prefers another country with the same code. A number no country
 * matches keeps its digits under "other" (country null).
 */
export function splitPhone(value: string | null | undefined, preferred: SMTPhoneCountry | null = null): { country: SMTPhoneCountry | null; digits: string } {
  const digits = (value ?? '').replace(/\D/g, '');
  if (!digits) return { country: preferred ?? SMT_PHONE_COUNTRIES[0], digits: '' };
  const matches = SMT_PHONE_COUNTRIES.filter(country => digits.startsWith(country.code))
    .sort((a, b) => b.code.length - a.code.length);
  if (matches.length === 0) return { country: null, digits };
  const best = matches.find(country => country.code === matches[0].code && country === preferred) ?? matches[0];
  return { country: best, digits: digits.slice(best.code.length) };
}

/** The stored form: `+` and all digits (E.164), or '' when nothing was typed. */
export function joinPhone(country: SMTPhoneCountry | null, digits: string): string {
  const national = digits.replace(/\D/g, '');
  if (!national) return '';
  return country ? `+${country.code}${national}` : `+${national}`;
}
