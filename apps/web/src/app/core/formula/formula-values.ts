import { Decimal } from './formula-decimal';
import {
  FormulaFailure,
  type FormulaDate,
  type FormulaList,
  type FormulaMoment,
  type FormulaTime,
  type FormulaType,
  type FormulaValue,
} from './formula.models';

/** Characters a formula's text may have. */
export const MAX_TEXT = 4000;

const DAY_MS = 86_400_000;
const SPACES = ' \t\r\n';
const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const ISO_TIME = /^(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?$/;
const ISO_MOMENT =
  /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?([Zz]|[+-]\d{2}:\d{2}(?::\d{2})?)$/;

/** A date of the formula language. */
export function date(days: number): FormulaDate {
  return { tag: 'date', days };
}

/** A moment of the formula language, to the second. */
export function moment(ms: number): FormulaMoment {
  return { tag: 'moment', ms: Math.floor(ms / 1000) * 1000 };
}

/** A time of day of the formula language. */
export function time(seconds: number): FormulaTime {
  return { tag: 'time', seconds };
}

/** The days since 1970-01-01 of a calendar date (years 1 to 9999 are read as written, not as 19xx). */
export function epochDay(year: number, month: number, day: number): number {
  const value = new Date(0);
  value.setUTCFullYear(year, month - 1, day);
  return Math.floor(value.getTime() / DAY_MS);
}

/** The calendar date of a count of days since 1970-01-01. */
export function calendar(days: number): { year: number; month: number; day: number } {
  const value = new Date(days * DAY_MS);
  return { year: value.getUTCFullYear(), month: value.getUTCMonth() + 1, day: value.getUTCDate() };
}

/** The days of a month. */
export function monthLength(year: number, month: number): number {
  return epochDay(month === 12 ? year + 1 : year, month === 12 ? 1 : month + 1, 1) - epochDay(year, month, 1);
}

/** Whether a text holds only spaces, tabs and line breaks — the characters `trim` removes. */
export function blank(text: string): boolean {
  return trim(text) === '';
}

/** The text without the spaces, tabs and line breaks at its ends. */
export function trim(text: string): string {
  let start = 0;
  let end = text.length;
  while (start < end && SPACES.includes(text[start])) start++;
  while (end > start && SPACES.includes(text[end - 1])) end--;
  return text.slice(start, end);
}

/**
 * A field's value as a value of its formula type, read as the server reads it: an empty text and a value that does
 * not read as the type are null, an empty yes/no is false.
 */
export function formulaValue(type: FormulaType, raw: unknown): FormulaValue {
  if (type === 'bool') return raw === true || (typeof raw !== 'boolean' && String(raw) === 'true');
  if (type === 'numbers' || type === 'rows') return listOf(type, raw);
  if (raw === null || raw === undefined) return null;
  if (typeof raw === 'string' && blank(raw)) return null;
  switch (type) {
    case 'number':
      return numberOf(raw);
    case 'text':
    case 'option':
      return String(raw);
    case 'date':
      return dateOf(String(raw).trim());
    case 'datetime':
      return momentOf(String(raw).trim());
    case 'time':
      return timeOf(String(raw).trim());
    case 'ref':
      return keyOf(raw);
    default:
      return null;
  }
}

/**
 * The values of a column of rows (ADR-0043), as the server reads them: numbers as numbers — a value that does not read
 * as one is null — rows and other values as they are, an empty text as null; anything that is not a list is no rows.
 */
function listOf(type: 'numbers' | 'rows', raw: unknown): FormulaList {
  if (!Array.isArray(raw)) return { tag: 'list', items: [] };
  const items = raw.map((item: unknown) => {
    if (item === null || item === undefined || (typeof item === 'string' && blank(item))) return null;
    return type === 'numbers' ? numberOf(item) : item;
  });
  return { tag: 'list', items };
}

/** The canonical text of a value: what `&` joins and what a text field keeps. */
export function formulaText(value: FormulaValue): string {
  if (value === null) return '';
  if (value instanceof Decimal) return value.toString();
  if (typeof value === 'object') {
    switch (value.tag) {
      case 'date':
        return dateText(value.days);
      case 'moment':
        return `${new Date(value.ms).toISOString().slice(0, 19)}Z`;
      case 'time':
        return timeText(value.seconds);
    }
  }
  return String(value);
}

function numberOf(raw: unknown): Decimal | null {
  return Decimal.parse(typeof raw === 'number' ? String(raw) : String(raw).trim());
}

function dateOf(text: string): FormulaDate | null {
  const match = ISO_DATE.exec(text.length > 10 ? text.slice(0, 10) : text);
  if (!match) return null;
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  if (month < 1 || month > 12 || day < 1 || day > monthLength(year, month)) return null;
  return date(epochDay(year, month, day));
}

function momentOf(text: string): FormulaMoment | null {
  const match = ISO_MOMENT.exec(text);
  if (!match) return null;
  const [year, month, day, hour, minute] = [1, 2, 3, 4, 5].map((index) => Number(match[index]));
  const second = Number(match[6] ?? '0');
  if (month < 1 || month > 12 || day < 1 || day > monthLength(year, month)) return null;
  if (hour > 23 || minute > 59 || second > 59) return null;
  const offset = offsetSeconds(match[8]);
  if (offset === null) return null;
  const local = epochDay(year, month, day) * DAY_MS + ((hour * 60 + minute) * 60 + second) * 1000;
  return moment(local - offset * 1000);
}

function offsetSeconds(text: string): number | null {
  if (text === 'Z' || text === 'z') return 0;
  const [hours, minutes, seconds = '0'] = text.slice(1).split(':');
  const total = Number(hours) * 3600 + Number(minutes) * 60 + Number(seconds);
  if (Number(hours) > 18 || Number(minutes) > 59 || Number(seconds) > 59 || total > 18 * 3600) return null;
  return text.startsWith('-') ? -total : total;
}

function timeOf(text: string): FormulaTime | null {
  const match = ISO_TIME.exec(text);
  if (!match) return null;
  const [hour, minute, second] = [Number(match[1]), Number(match[2]), Number(match[3] ?? '0')];
  if (hour > 23 || minute > 59 || second > 59) return null;
  return time((hour * 60 + minute) * 60 + second);
}

function keyOf(raw: unknown): bigint | null {
  if (typeof raw === 'number') return Number.isFinite(raw) ? BigInt(Math.trunc(raw)) : null;
  if (typeof raw === 'bigint') return raw;
  const text = String(raw).trim();
  if (!/^[+-]?\d{1,19}$/.test(text)) return null;
  const key = BigInt(text);
  return key > 9223372036854775807n || key < -9223372036854775808n ? null : key;
}

function dateText(days: number): string {
  const { year, month, day } = calendar(days);
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

function timeText(seconds: number): string {
  const hours = String(Math.floor(seconds / 3600)).padStart(2, '0');
  const minutes = String(Math.floor((seconds % 3600) / 60)).padStart(2, '0');
  const rest = seconds % 60;
  return rest === 0 ? `${hours}:${minutes}` : `${hours}:${minutes}:${String(rest).padStart(2, '0')}`;
}

/** A text within the bound of a formula's texts. */
export function bounded(text: string): string {
  if ([...text].length > MAX_TEXT) throw new FormulaFailure('text_too_long');
  return text;
}

/** The order of two values of comparable types: numbers by value (a key as a number), the rest naturally. */
export function compare(left: Exclude<FormulaValue, null>, right: Exclude<FormulaValue, null>): number {
  if (typeof left === 'bigint' || typeof right === 'bigint' || left instanceof Decimal) {
    return decimal(left).compare(decimal(right));
  }
  if (typeof left === 'string') return left < (right as string) ? -1 : left > (right as string) ? 1 : 0;
  if (typeof left === 'boolean') return Number(left) - Number(right as boolean);
  const a = ordinal(left);
  const b = ordinal(right);
  return a < b ? -1 : a > b ? 1 : 0;
}

function ordinal(value: Exclude<FormulaValue, null>): number {
  if (typeof value === 'object' && !(value instanceof Decimal)) {
    switch (value.tag) {
      case 'date':
        return value.days;
      case 'moment':
        return value.ms;
      case 'time':
        return value.seconds;
    }
  }
  throw new FormulaFailure('invalid_argument');
}

function decimal(value: Exclude<FormulaValue, null>): Decimal {
  if (typeof value === 'bigint') return Decimal.of(value, 0);
  if (value instanceof Decimal) return value;
  throw new FormulaFailure('invalid_argument');
}
