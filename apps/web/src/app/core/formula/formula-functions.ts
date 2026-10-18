import { Decimal } from './formula-decimal';
import { blank, bounded, calendar, compare, date, epochDay, moment, monthLength, trim } from './formula-values';
import { FormulaFailure, type FormulaDate, type FormulaScope, type FormulaValue } from './formula.models';

const DAY_MS = 86_400_000;

/** The functions of the formula language, as the editor lists them (ADR-0042); the server checks the same set. */
export const FORMULA_FUNCTIONS = [
  'round',
  'abs',
  'min',
  'max',
  'if',
  'coalesce',
  'isEmpty',
  'len',
  'upper',
  'lower',
  'trim',
  'contains',
  'today',
  'now',
  'date',
  'year',
  'month',
  'day',
  'weekday',
  'hour',
  'minute',
  'dateOf',
  'addDays',
  'addMonths',
  'daysBetween',
  'sum',
  'count',
] as const;

/**
 * The functions that evaluate all their arguments first, with the server's rules: a null argument gives a null
 * result, except `min`/`max` (nulls are passed over), `isEmpty`, `len` (no characters) and `contains` (false); dates
 * stay within the years 1 to 9999; a count of days, months or digits is a whole number.
 */
export function callFunction(name: string, args: FormulaValue[], scope: FormulaScope): FormulaValue {
  switch (name) {
    case 'isEmpty':
      return args[0] === null || (typeof args[0] === 'string' && blank(args[0]));
    case 'len':
      return Decimal.from(args[0] === null ? 0 : [...(args[0] as string)].length);
    case 'contains':
      return args[0] !== null && args[1] !== null && (args[0] as string).includes(args[1] as string);
    case 'min':
    case 'max':
      return extreme(name === 'max', args);
    case 'sum':
      return filled(args[0]).reduce<Decimal>((total, value) => total.add(value as Decimal), Decimal.from(0));
    case 'count':
      return Decimal.from(filled(args[0]).length);
    case 'today':
      return date(zonedDays(scope.now, scope.zone));
    case 'now':
      return moment(scope.now);
    default:
      if (args.includes(null)) return null;
      return strict(name, args, scope);
  }
}

/**
 * The filled values of a list of rows (ADR-0043): neither null nor a text of only spaces. `sum` of none is zero, as a
 * document's total of no lines is zero on the server.
 */
function filled(list: FormulaValue): unknown[] {
  if (list === null || typeof list !== 'object' || !('tag' in list) || list.tag !== 'list') return [];
  return list.items.filter((value) => value !== null && !(typeof value === 'string' && blank(value)));
}

function strict(name: string, args: FormulaValue[], scope: FormulaScope): FormulaValue {
  const first = args[0];
  switch (name) {
    case 'round':
      return (first as Decimal).round(args.length > 1 ? (args[1] as Decimal).whole() : 0);
    case 'abs':
      return (first as Decimal).abs();
    case 'upper':
      return bounded((first as string).toUpperCase());
    case 'lower':
      return bounded((first as string).toLowerCase());
    case 'trim':
      return trim(first as string);
    case 'date':
      return makeDate(args);
    case 'dateOf':
      return date(zonedDays((first as { ms: number }).ms, scope.zone));
    case 'addDays':
      return dated((first as FormulaDate).days + (args[1] as Decimal).whole());
    case 'addMonths':
      return addMonths(first as FormulaDate, (args[1] as Decimal).whole());
    case 'daysBetween':
      return Decimal.from((args[1] as FormulaDate).days - (first as FormulaDate).days);
    default:
      return part(name, first, scope);
  }
}

/** `year`, `month`, `day`, `weekday` (Monday 1), `hour`, `minute`; the parts of a moment in the viewer's zone. */
function part(name: string, value: FormulaValue, scope: FormulaScope): Decimal {
  let days: number | null = null;
  let seconds: number | null = null;
  if (value !== null && typeof value === 'object' && 'tag' in value) {
    if (value.tag === 'moment') {
      const zoned = zonedParts(value.ms, scope.zone);
      days = epochDay(zoned.year, zoned.month, zoned.day);
      seconds = zoned.hour * 3600 + zoned.minute * 60;
    } else if (value.tag === 'date') {
      days = value.days;
    } else if (value.tag === 'time') {
      seconds = value.seconds;
    }
  }
  const required = (part: number | null): number => {
    if (part === null) throw new FormulaFailure('invalid_argument');
    return part;
  };
  switch (name) {
    case 'year':
      return Decimal.from(calendar(required(days)).year);
    case 'month':
      return Decimal.from(calendar(required(days)).month);
    case 'day':
      return Decimal.from(calendar(required(days)).day);
    case 'weekday':
      return Decimal.from(((new Date(required(days) * DAY_MS).getUTCDay() + 6) % 7) + 1);
    case 'hour':
      return Decimal.from(Math.floor(required(seconds) / 3600));
    default:
      return Decimal.from(Math.floor((required(seconds) % 3600) / 60));
  }
}

function extreme(max: boolean, args: FormulaValue[]): FormulaValue {
  let best: FormulaValue = null;
  for (const value of args) {
    if (value === null) continue;
    if (best === null) {
      best = value;
      continue;
    }
    const order = compare(value, best);
    if (max ? order > 0 : order < 0) best = value;
  }
  return best;
}

function makeDate(args: FormulaValue[]): FormulaDate {
  const [year, month, day] = args.map((arg) => (arg as Decimal).whole());
  if (year < 1 || year > 9999 || month < 1 || month > 12 || day < 1 || day > 31) {
    throw new FormulaFailure('invalid_date');
  }
  if (day > monthLength(year, month)) throw new FormulaFailure('invalid_date');
  return date(epochDay(year, month, day));
}

function addMonths(start: FormulaDate, months: number): FormulaDate {
  const { year, month, day } = calendar(start.days);
  const total = year * 12 + (month - 1) + months;
  const targetYear = Math.floor(total / 12);
  const targetMonth = total - targetYear * 12 + 1;
  if (targetYear < 1 || targetYear > 9999) throw new FormulaFailure('invalid_date');
  return date(epochDay(targetYear, targetMonth, Math.min(day, monthLength(targetYear, targetMonth))));
}

const FIRST_DAY = epochDay(1, 1, 1);
const LAST_DAY = epochDay(9999, 12, 31);

function dated(days: number): FormulaDate {
  if (days < FIRST_DAY || days > LAST_DAY) throw new FormulaFailure('invalid_date');
  return date(days);
}

/** The calendar date and time of a moment in a time zone. */
function zonedParts(
  ms: number,
  zone: string,
): { year: number; month: number; day: number; hour: number; minute: number } {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: 'numeric',
    hourCycle: 'h23',
    era: 'short',
  }).formatToParts(new Date(ms));
  const value = (type: string): number => Number(parts.find((item) => item.type === type)?.value ?? 0);
  const era = parts.find((item) => item.type === 'era')?.value ?? 'AD';
  const year = era.startsWith('B') ? 1 - value('year') : value('year');
  return { year, month: value('month'), day: value('day'), hour: value('hour'), minute: value('minute') };
}

function zonedDays(ms: number, zone: string): number {
  const zoned = zonedParts(ms, zone);
  return epochDay(zoned.year, zoned.month, zoned.day);
}
