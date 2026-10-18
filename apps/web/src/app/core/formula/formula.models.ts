import type { Decimal } from './formula-decimal';

/** The static types of the formula language (ADR-0042). */
export type FormulaType = 'number' | 'text' | 'bool' | 'date' | 'datetime' | 'time' | 'option' | 'ref' | 'null';

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

/** A value of a formula: a number, a text or code, a yes/no, a date, a moment, a time, a row's key, or null. */
export type FormulaValue = Decimal | string | boolean | FormulaDate | FormulaMoment | FormulaTime | bigint | null;

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
