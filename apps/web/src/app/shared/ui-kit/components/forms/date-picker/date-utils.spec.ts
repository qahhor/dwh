import { describe, expect, it } from 'vitest';
import { datePattern, formatDate, parseDate } from './date-format';
import {
  addDays,
  addMonths,
  CalendarDate,
  monthGrid,
  orderedRange,
  parseIsoDate,
  parseIsoTime,
  presetRange,
  toIsoDate,
  weekdayFromMonday,
} from './date-utils';

const d = (iso: string): CalendarDate => parseIsoDate(iso)!;
const iso = (date: CalendarDate) => toIsoDate(date);

describe('date utils', () => {
  it('reads ISO dates and the date part of ISO date-times, rejecting impossible days', () => {
    expect(parseIsoDate('2026-09-24')).toEqual({ year: 2026, month: 8, day: 24 });
    expect(parseIsoDate('2026-09-24T17:30')).toEqual({ year: 2026, month: 8, day: 24 });
    expect(parseIsoDate('2026-02-30')).toBeNull();
    expect(parseIsoDate('24.09.2026')).toBeNull();
    expect(parseIsoDate(null)).toBeNull();
  });

  it('reads the time of an ISO date-time', () => {
    expect(parseIsoTime('2026-09-24T17:30')).toBe('17:30');
    expect(parseIsoTime('2026-09-24T17:30:15.000')).toBe('17:30');
    expect(parseIsoTime('2026-09-24')).toBeNull();
    expect(parseIsoTime('2026-09-24T25:00')).toBeNull();
  });

  it('moves by days and months across boundaries', () => {
    expect(iso(addDays(d('2026-12-31'), 1))).toBe('2027-01-01');
    expect(iso(addDays(d('2026-03-01'), -1))).toBe('2026-02-28');
    expect(iso(addMonths(d('2026-01-31'), 1))).toBe('2026-02-28');
    expect(iso(addMonths(d('2024-01-31'), 1))).toBe('2024-02-29');
    expect(iso(addMonths(d('2026-01-15'), -12))).toBe('2025-01-15');
  });

  it('counts weekdays from Monday', () => {
    expect(weekdayFromMonday(d('2026-09-21'))).toBe(0);
    expect(weekdayFromMonday(d('2026-09-27'))).toBe(6);
  });

  it('lays a month out as six Monday-first weeks', () => {
    const grid = monthGrid(2026, 8);

    expect(grid).toHaveLength(6);
    expect(grid.every(week => week.length === 7)).toBe(true);
    expect(iso(grid[0][0])).toBe('2026-08-31');
    expect(grid.flat().filter(day => day.month === 8)).toHaveLength(30);
  });

  it('computes the presets from a given today', () => {
    const today = d('2026-09-24');
    const range = (key: Parameters<typeof presetRange>[0]) => {
      const { from, to } = presetRange(key, today);
      return `${iso(from)}..${iso(to)}`;
    };

    expect(range('today')).toBe('2026-09-24..2026-09-24');
    expect(range('yesterday')).toBe('2026-09-23..2026-09-23');
    expect(range('last7')).toBe('2026-09-18..2026-09-24');
    expect(range('last30')).toBe('2026-08-26..2026-09-24');
    expect(range('thisMonth')).toBe('2026-09-01..2026-09-24');
    expect(range('lastMonth')).toBe('2026-08-01..2026-08-31');
    expect(range('thisYear')).toBe('2026-01-01..2026-09-24');
    expect(`${iso(presetRange('lastMonth', d('2026-01-10')).from)}`).toBe('2025-12-01');
  });

  it('orders a range picked end first', () => {
    const { from, to } = orderedRange(d('2026-09-30'), d('2026-09-01'));
    expect([iso(from), iso(to)]).toEqual(['2026-09-01', '2026-09-30']);
  });
});

describe('date format', () => {
  it('writes day first with dots except in English', () => {
    expect(formatDate(d('2026-09-04'), datePattern('ru'))).toBe('04.09.2026');
    expect(formatDate(d('2026-09-04'), datePattern('uz'))).toBe('04.09.2026');
    expect(formatDate(d('2026-09-04'), datePattern('en'))).toBe('09/04/2026');
    expect(formatDate(null, datePattern('ru'))).toBe('');
  });

  it('reads typed dates in the language order with any common separator, and ISO', () => {
    const ru = datePattern('ru');
    const en = datePattern('en');
    expect(parseDate('4.9.2026', ru)).toEqual(d('2026-09-04'));
    expect(parseDate('04/09/2026', ru)).toEqual(d('2026-09-04'));
    expect(parseDate('09/04/2026', en)).toEqual(d('2026-09-04'));
    expect(parseDate('2026-09-04', en)).toEqual(d('2026-09-04'));
  });

  it('rejects text that is not a real date', () => {
    const ru = datePattern('ru');
    expect(parseDate('31.02.2026', ru)).toBeNull();
    expect(parseDate('13.13.2026', ru)).toBeNull();
    expect(parseDate('завтра', ru)).toBeNull();
    expect(parseDate('', ru)).toBeNull();
  });
});
