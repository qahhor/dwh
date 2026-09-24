/* Our code: how dates are written and read in each product language.
 *
 * The numeric order is set per language rather than read from Intl: Intl
 * gives `2026-24-09` for ky-KG, which nobody types. English follows en-US
 * (month first); every other product language writes day first with dots. */
import { CalendarDate, parseIsoDate, toJsDate } from './date-utils';

export type DateOrder = 'dmy' | 'mdy';

export interface DatePattern {
  readonly order: DateOrder;
  readonly separator: string;
}

const PATTERNS: Record<string, DatePattern> = {
  en: { order: 'mdy', separator: '/' },
};

const DEFAULT_PATTERN: DatePattern = { order: 'dmy', separator: '.' };

export function datePattern(language: string): DatePattern {
  return PATTERNS[language] ?? DEFAULT_PATTERN;
}

function pad(value: number, length = 2): string {
  return String(value).padStart(length, '0');
}

export function formatDate(date: CalendarDate | null, pattern: DatePattern): string {
  if (!date) return '';
  const day = pad(date.day);
  const month = pad(date.month + 1);
  const parts = pattern.order === 'mdy' ? [month, day] : [day, month];
  return [...parts, pad(date.year, 4)].join(pattern.separator);
}

/** Reads typed text in the language's order, any of `. / -` as separator, or ISO. */
export function parseDate(text: string, pattern: DatePattern): CalendarDate | null {
  const value = text.trim();
  if (!value) return null;
  const iso = parseIsoDate(value);
  if (iso) return iso;
  const match = /^(\d{1,2})[./-](\d{1,2})[./-](\d{4})$/.exec(value);
  if (!match) return null;
  const [first, second] = [Number(match[1]), Number(match[2])];
  const day = pattern.order === 'mdy' ? second : first;
  const month = (pattern.order === 'mdy' ? first : second) - 1;
  const year = Number(match[3]);
  const candidate = new Date(year, month, day);
  return candidate.getFullYear() === year && candidate.getMonth() === month && candidate.getDate() === day
    ? { year, month, day }
    : null;
}

export function monthLabel(year: number, month: number, locale: string): string {
  return new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric' }).format(new Date(year, month, 1));
}

export function monthName(month: number, locale: string): string {
  return new Intl.DateTimeFormat(locale, { month: 'long' }).format(new Date(2026, month, 1));
}

/** Short weekday names, Monday first. */
export function weekdayNames(locale: string, style: 'short' | 'long' = 'short'): string[] {
  // 2026-09-21 is a Monday.
  return Array.from({ length: 7 }, (_, index) =>
    new Intl.DateTimeFormat(locale, { weekday: style }).format(new Date(2026, 8, 21 + index))
  );
}

/** The spoken name of a day, e.g. "четверг, 24 сентября 2026 г.". */
export function fullDateLabel(date: CalendarDate, locale: string): string {
  return new Intl.DateTimeFormat(locale, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(toJsDate(date));
}
