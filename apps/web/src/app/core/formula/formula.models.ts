import type { Decimal } from './formula-decimal';

/**
 * The reasons the server gives a formula it refuses or cannot evaluate (ADR-0042); each has its text under
 * `error.formula.<reason>`, which the editor and the record form show.
 */
export const FORMULA_REASONS = [
  'syntax',
  'unknown_field',
  'unknown_function',
  'arity',
  'type',
  'argument',
  'limit',
  'self',
  'cycle',
  'result',
  'division_by_zero',
  'number_too_large',
  'text_too_long',
  'invalid_date',
  'invalid_argument',
  'budget',
] as const;

/**
 * The static types of the formula language (ADR-0042); `numbers` and `rows` are the lists of a document's rows that
 * only `sum` and `count` read (ADR-0043).
 */
export type FormulaType =
  | 'number'
  | 'text'
  | 'bool'
  | 'date'
  | 'datetime'
  | 'time'
  | 'option'
  | 'ref'
  | 'null'
  | 'numbers'
  | 'rows';

/**
 * A checked node of a formula as the server gives it in form-meta (ADR-0042): the web interprets it with the server's
 * semantics and never parses formulas itself — the server is the one checker.
 */
export interface FormulaNode {
  kind: 'literal' | 'field' | 'unary' | 'binary' | 'call';
  /** The operator, the function or the field key. */
  name?: string | null;
  /** The canonical text of a literal; none for `null`. */
  value?: string | null;
  type: FormulaType;
  args?: FormulaNode[] | null;
}

/** A date as days since 1970-01-01. */
export interface FormulaDate {
  readonly tag: 'date';
  readonly days: number;
}

/** A moment as milliseconds since the epoch, to the second. */
export interface FormulaMoment {
  readonly tag: 'moment';
  readonly ms: number;
}

/** A time of day as seconds since midnight. */
export interface FormulaTime {
  readonly tag: 'time';
  readonly seconds: number;
}

/**
 * The values of a column of a document's rows, or the rows themselves (ADR-0043): numbers as numbers, the rest as
 * they are, an empty value as null.
 */
export interface FormulaList {
  readonly tag: 'list';
  readonly items: readonly unknown[];
}

/** A value of a formula: a number, a text or code, a yes/no, a date, a moment, a time, a row's key, or null. */
export type FormulaValue =
  | Decimal
  | string
  | boolean
  | FormulaDate
  | FormulaMoment
  | FormulaTime
  | FormulaList
  | bigint
  | null;

/** What a formula reads besides the record: the moment of the evaluation and the viewer's time zone. */
export interface FormulaScope {
  readonly zone: string;
  readonly now: number;
}

/**
 * A formula that cannot give a value for these values (ADR-0042): `division_by_zero`, `number_too_large`,
 * `text_too_long`, `invalid_date`, `invalid_argument` or `budget` — the same reasons the server answers with.
 */
export class FormulaFailure extends Error {
  constructor(readonly reason: string) {
    super(`formula failed: ${reason}`);
    this.name = 'FormulaFailure';
  }
}
