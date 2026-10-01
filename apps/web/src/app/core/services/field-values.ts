import type {
  ConditionClauseMeta,
  ConditionItemMeta,
  FormFieldMeta,
  FormFieldType,
  FormValues,
} from '../models/form-meta.models';

/**
 * The value rules of each field type on the web (ADR-0032 4.1–4.2, plan 10/10, item 5.2), the same the server checks
 * (`EntityValidator`, `FieldValueRules`): when a value counts as empty, the problem found before the request, the
 * body the server takes and the value the form edits. A `Record` over every type, so a type added to
 * `FORM_FIELD_TYPES` without its rules fails the typecheck (`field-type-matrix.spec.ts`).
 */
export interface FieldValueRules {
  /** Whether the value counts as none: the field is then required or left out. */
  empty(value: unknown): boolean;
  /** The problem code of a value that is not empty, or null. */
  problem(field: FormFieldMeta, value: unknown): string | null;
  /** The value as the server takes it. */
  payload(field: FormFieldMeta, value: unknown): unknown;
  /** The value of a record as the form edits it. */
  formValue(field: FormFieldMeta, value: unknown): unknown;
}

/** Money as the form edits it: the amount as typed and the currency. */
export interface MoneyValue {
  amount: string | number | null;
  currency: string;
}

/** A stored file as a record reads it back. */
export interface FileValue {
  id: string;
  name?: string;
  size?: number;
  contentType?: string;
}

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const PHONE = /^\+[1-9]\d{6,14}$/;
const PHONE_SEPARATORS = /[\s()-]/g;
const TIME_OF_DAY = /^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?$/;
const DECIMAL = /^-?\d{1,15}(\.\d{1,6})?$/;
const MAX_JSON_BYTES = 64 * 1024;

function blank(value: unknown): boolean {
  return value === null || value === undefined || (typeof value === 'string' && value.trim() === '');
}

function same(value: unknown): unknown {
  return value;
}

function trimmed(value: unknown): unknown {
  return typeof value === 'string' ? value.trim() : value;
}

/** Digits after the point of a decimal written as text. */
function decimals(text: string): number {
  const point = text.indexOf('.');
  return point < 0 ? 0 : text.slice(point + 1).replace(/0+$/, '').length;
}

function textProblem(field: FormFieldMeta, value: unknown): string | null {
  const text = String(value);
  if (field.minLength != null && text.trim().length < field.minLength) return 'too_short';
  if (field.maxLength != null && text.length > field.maxLength) return 'too_long';
  if (field.pattern && !new RegExp(`^(?:${field.pattern})$`).test(text)) return 'invalid';
  return null;
}

function rangeProblem(field: FormFieldMeta, number: number): string | null {
  return (field.min != null && number < field.min) || (field.max != null && number > field.max) ? 'out_of_range' : null;
}

/** A moment as the viewer's local `yyyy-MM-ddTHH:mm`; null when it is not one. */
export function localMoment(value: unknown): string | null {
  if (typeof value !== 'string' || !value.trim()) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/** A local `yyyy-MM-ddTHH:mm` (or any date and time) as the moment the server keeps, in UTC; null when it is none. */
export function utcMoment(value: unknown): string | null {
  if (typeof value !== 'string' || !value.trim()) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** An e-mail as kept: trimmed, in lower case. */
export function normalEmail(value: unknown): string {
  return String(value).trim().toLowerCase();
}

/** A phone as kept: E.164 without spaces, brackets and dashes. */
export function normalPhone(value: unknown): string {
  return String(value).replace(PHONE_SEPARATORS, '');
}

/** Whether the text is an `http` or `https` address with a host. */
export function isWebAddress(value: unknown): boolean {
  const text = String(value).trim();
  if (!text || text.length > 2048) return false;
  try {
    const url = new URL(text);
    return (url.protocol === 'http:' || url.protocol === 'https:') && url.hostname.length > 0;
  } catch {
    return false;
  }
}

/** The keys of a multiple reference value; empty for none. */
export function keyList(value: unknown): (number | string)[] {
  return Array.isArray(value) ? value.filter((key) => key !== null && key !== undefined && key !== '') : [];
}

/** The id of a file value: its uuid, or the `id` of the file a record reads back. */
export function fileId(value: unknown): string | null {
  if (typeof value === 'string' && value.trim()) return value.trim();
  if (value && typeof value === 'object' && typeof (value as FileValue).id === 'string') return (value as FileValue).id;
  return null;
}

function money(value: unknown): MoneyValue | null {
  return value && typeof value === 'object' && 'currency' in value ? (value as MoneyValue) : null;
}

function jsonValue(field: FormFieldMeta, value: unknown): unknown {
  if (typeof value !== 'string') return value;
  return JSON.parse(value) as unknown;
}

const plain: FieldValueRules = {
  empty: blank,
  problem: () => null,
  payload: (_field, value) => trimmed(value),
  formValue: (_field, value) => same(value),
};

const text: FieldValueRules = { ...plain, problem: textProblem };

/** The rules of every field type. */
export const FIELD_VALUE_RULES: Record<FormFieldType, FieldValueRules> = {
  text,
  textarea: { ...text, payload: (_field, value) => same(value) },
  markdown: { ...text, payload: (_field, value) => same(value) },
  number: {
    ...plain,
    problem: (field, value) => {
      const number = Number(value);
      if (!Number.isFinite(number)) return 'invalid';
      if (field.scale != null && decimals(String(value)) > field.scale) return 'invalid';
      return rangeProblem(field, number);
    },
  },
  date: {
    ...plain,
    problem: (_field, value) =>
      /^\d{4}-\d{2}-\d{2}$/.test(String(value)) && !Number.isNaN(Date.parse(String(value))) ? null : 'invalid',
  },
  datetime: {
    ...plain,
    problem: (_field, value) => (utcMoment(value) ? null : 'invalid'),
    // A moment travels with its offset (plan 10/10, item 5.0); what is not one goes as typed, for the server's 422.
    payload: (_field, value) => utcMoment(trimmed(value)) ?? trimmed(value),
    formValue: (_field, value) => localMoment(value) ?? value ?? null,
  },
  time: { ...plain, problem: (_field, value) => (TIME_OF_DAY.test(String(value)) ? null : 'invalid') },
  boolean: {
    ...plain,
    empty: (value) => value === null || value === undefined,
    formValue: (_field, value) => same(value),
  },
  select: { ...plain, problem: (field, value) => (field.options?.includes(String(value)) ? null : 'invalid') },
  ref: plain,
  email: {
    ...plain,
    problem: (_field, value) => {
      const kept = normalEmail(value);
      return kept.length <= 254 && EMAIL.test(kept) ? null : 'invalid';
    },
    payload: (_field, value) => normalEmail(value),
  },
  phone: {
    ...plain,
    problem: (_field, value) => (PHONE.test(normalPhone(value)) ? null : 'invalid'),
    payload: (_field, value) => normalPhone(value),
  },
  url: { ...plain, problem: (_field, value) => (isWebAddress(value) ? null : 'invalid') },
  money: {
    empty: (value) => blank(money(value)?.amount ?? null),
    problem: (field, value) => {
      const amount = String(money(value)?.amount ?? '').trim();
      const currency = money(value)?.currency ?? '';
      if (!DECIMAL.test(amount) || !(field.currencies ?? []).includes(currency)) return 'invalid';
      const digits = new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions()
        .maximumFractionDigits;
      if (digits !== undefined && decimals(amount) > digits) return 'invalid';
      if (field.scale != null && decimals(amount) > field.scale) return 'invalid';
      return rangeProblem(field, Number(amount));
    },
    payload: (_field, value) => {
      const kept = money(value);
      return kept && !blank(kept.amount) ? { amount: String(kept.amount).trim(), currency: kept.currency } : null;
    },
    formValue: (field, value) => money(value) ?? { amount: null, currency: field.currencies?.[0] ?? '' },
  },
  enum: { ...plain, problem: (field, value) => (field.options?.includes(String(value)) ? null : 'invalid') },
  multi_ref: {
    empty: (value) => keyList(value).length === 0,
    problem: (field, value) => {
      const keys = keyList(value).map(String);
      if (new Set(keys).size !== keys.length) return 'invalid';
      return keys.length > (field.maxItems ?? 100) ? 'too_many' : null;
    },
    payload: (_field, value) => keyList(value).map((key) => (Number.isSafeInteger(Number(key)) ? Number(key) : key)),
    formValue: (_field, value) => keyList(value),
  },
  file: { ...plain, empty: (value) => fileId(value) === null, payload: (_field, value) => fileId(value) },
  image: { ...plain, empty: (value) => fileId(value) === null, payload: (_field, value) => fileId(value) },
  json: {
    ...plain,
    problem: (field, value) => {
      let parsed: unknown;
      try {
        parsed = jsonValue(field, value);
      } catch {
        return 'invalid';
      }
      const isArray = Array.isArray(parsed);
      const isObject = parsed !== null && typeof parsed === 'object' && !isArray;
      const shaped =
        field.jsonRoot === 'array' ? isArray : field.jsonRoot === 'object' ? isObject : isArray || isObject;
      if (!shaped) return 'invalid';
      return new TextEncoder().encode(JSON.stringify(parsed)).length > MAX_JSON_BYTES ? 'invalid' : null;
    },
    payload: (field, value) => {
      try {
        return jsonValue(field, value);
      } catch {
        return value;
      }
    },
    formValue: (_field, value) =>
      value === null || value === undefined || typeof value === 'string' ? value : JSON.stringify(value, null, 2),
  },
};

/** Whether a condition holds over the form's values: every item; an `any` item when one of its clauses does. */
export function conditionHolds(
  condition: readonly ConditionItemMeta[] | null | undefined,
  values: FormValues,
): boolean {
  if (!condition?.length) return true;
  return condition.every((item) =>
    item.any?.length
      ? item.any.some((clause) => clauseHolds(clause, values))
      : clauseHolds(item as ConditionClauseMeta, values),
  );
}

function clauseHolds(clause: Partial<ConditionClauseMeta>, values: FormValues): boolean {
  const raw = clause.field ? values[clause.field] : undefined;
  const text = raw === null || raw === undefined ? '' : String(raw);
  const expected = clause.values ?? [];
  switch (clause.op) {
    case 'eq':
      return text === expected[0];
    case 'ne':
      return text !== expected[0];
    case 'in':
      return expected.includes(text);
    case 'empty':
      return text.trim() === '';
    case 'not_empty':
      return text.trim() !== '';
    default:
      return true;
  }
}

/** Whether the form shows the field over these values (ADR-0032 4.4). */
export function fieldVisible(field: FormFieldMeta, values: FormValues): boolean {
  return conditionHolds(field.visibleWhen, values);
}

/**
 * Whether a save cannot change the field (ADR-0032 4.4, 5.2): it is read-only whatever the state (no right, written
 * by the server, computed), or — on an existing record — set on creation only or while its condition holds.
 */
export function fieldReadonly(field: FormFieldMeta, values: FormValues, creating: boolean): boolean {
  if (field.readonly || field.computed) return true;
  if (creating) return false;
  return !!field.readonlyOnUpdate || (!!field.readonlyWhen?.length && conditionHolds(field.readonlyWhen, values));
}
