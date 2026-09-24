/* Our code, after the idea of the kit's date pickers (smartup-ui-kit@6472beb,
 * components/forms/date-picker, date-range-picker). See ADR-0015 rule 2.
 *
 * The kit worked on dayjs objects and Biruni's `dd.MM.yyyy` strings. Our API
 * speaks ISO: a date is `YYYY-MM-DD`, a date with time `YYYY-MM-DDTHH:mm`
 * (what `<input type="datetime-local">` produced before). These helpers work
 * on those strings and on local calendar days, with no library. */

/** A calendar day, independent of time zone. `month` is 0-based like Date. */
export interface CalendarDate {
  readonly year: number;
  readonly month: number;
  readonly day: number;
}

export interface DateRange {
  readonly from: string | null;
  readonly to: string | null;
}

export type DateRangePresetKey =
  | 'today'
  | 'yesterday'
  | 'last7'
  | 'last30'
  | 'thisMonth'
  | 'lastMonth'
  | 'thisYear';

export const DATE_RANGE_PRESETS: readonly DateRangePresetKey[] = [
  'today',
  'yesterday',
  'last7',
  'last30',
  'thisMonth',
  'lastMonth',
  'thisYear',
];

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const ISO_DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::\d{2}(?:\.\d+)?)?$/;

function pad(value: number, length = 2): string {
  return String(value).padStart(length, '0');
}

function isValid(year: number, month: number, day: number): boolean {
  if (month < 0 || month > 11 || day < 1) return false;
  return day <= daysInMonth(year, month);
}

export function daysInMonth(year: number, month: number): number {
  return new Date(year, month + 1, 0).getDate();
}

/** Reads `YYYY-MM-DD` (or the date part of `YYYY-MM-DDTHH:mm`). */
export function parseIsoDate(value: string | null | undefined): CalendarDate | null {
  if (!value) return null;
  const match = ISO_DATE.exec(value) ?? ISO_DATE_TIME.exec(value);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]) - 1;
  const day = Number(match[3]);
  return isValid(year, month, day) ? { year, month, day } : null;
}

/** Reads the time part `HH:mm` of `YYYY-MM-DDTHH:mm`, or null. */
export function parseIsoTime(value: string | null | undefined): string | null {
  if (!value) return null;
  const match = ISO_DATE_TIME.exec(value);
  if (!match) return null;
  const hours = Number(match[4]);
  const minutes = Number(match[5]);
  return hours < 24 && minutes < 60 ? `${match[4]}:${match[5]}` : null;
}

export function toIsoDate(date: CalendarDate): string {
  return `${pad(date.year, 4)}-${pad(date.month + 1)}-${pad(date.day)}`;
}

export function fromJsDate(date: Date): CalendarDate {
  return { year: date.getFullYear(), month: date.getMonth(), day: date.getDate() };
}

export function toJsDate(date: CalendarDate): Date {
  return new Date(date.year, date.month, date.day);
}

export function compareDates(a: CalendarDate, b: CalendarDate): number {
  return a.year - b.year || a.month - b.month || a.day - b.day;
}

export function isSameDate(a: CalendarDate | null, b: CalendarDate | null): boolean {
  return !!a && !!b && compareDates(a, b) === 0;
}

export function addDays(date: CalendarDate, days: number): CalendarDate {
  return fromJsDate(new Date(date.year, date.month, date.day + days));
}

/** Adds months, keeping the day where the target month has it (31 Jan + 1 → 28/29 Feb). */
export function addMonths(date: CalendarDate, months: number): CalendarDate {
  const target = new Date(date.year, date.month + months, 1);
  const year = target.getFullYear();
  const month = target.getMonth();
  return { year, month, day: Math.min(date.day, daysInMonth(year, month)) };
}

/** Weekday with Monday = 0 … Sunday = 6. */
export function weekdayFromMonday(date: CalendarDate): number {
  return (toJsDate(date).getDay() + 6) % 7;
}

export function startOfWeek(date: CalendarDate): CalendarDate {
  return addDays(date, -weekdayFromMonday(date));
}

export function endOfWeek(date: CalendarDate): CalendarDate {
  return addDays(date, 6 - weekdayFromMonday(date));
}

export function clampDate(date: CalendarDate, min: CalendarDate | null, max: CalendarDate | null): CalendarDate {
  if (min && compareDates(date, min) < 0) return min;
  if (max && compareDates(date, max) > 0) return max;
  return date;
}

export function isWithin(date: CalendarDate, min: CalendarDate | null, max: CalendarDate | null): boolean {
  return (!min || compareDates(date, min) >= 0) && (!max || compareDates(date, max) <= 0);
}

/** Six Monday-first weeks covering the month, as the calendar grid shows them. */
export function monthGrid(year: number, month: number): CalendarDate[][] {
  const first = startOfWeek({ year, month, day: 1 });
  return Array.from({ length: 6 }, (_, week) =>
    Array.from({ length: 7 }, (_, weekday) => addDays(first, week * 7 + weekday))
  );
}

/** The range a preset stands for, counted from `today`. Both ends included. */
export function presetRange(key: DateRangePresetKey, today: CalendarDate): { from: CalendarDate; to: CalendarDate } {
  switch (key) {
    case 'today':
      return { from: today, to: today };
    case 'yesterday': {
      const yesterday = addDays(today, -1);
      return { from: yesterday, to: yesterday };
    }
    case 'last7':
      return { from: addDays(today, -6), to: today };
    case 'last30':
      return { from: addDays(today, -29), to: today };
    case 'thisMonth':
      return { from: { ...today, day: 1 }, to: today };
    case 'lastMonth': {
      const previous = addMonths({ ...today, day: 1 }, -1);
      return { from: previous, to: { ...previous, day: daysInMonth(previous.year, previous.month) } };
    }
    case 'thisYear':
      return { from: { year: today.year, month: 0, day: 1 }, to: today };
  }
}

/** Puts a range in order, so picking the end first still gives from ≤ to. */
export function orderedRange(a: CalendarDate, b: CalendarDate): { from: CalendarDate; to: CalendarDate } {
  return compareDates(a, b) <= 0 ? { from: a, to: b } : { from: b, to: a };
}
