import { evaluateFormula } from '../formula/formula-eval';
import { formulaText } from '../formula/formula-values';
import { Decimal } from '../formula/formula-decimal';
import { FormulaFailure, type FormulaNode, type FormulaScope, type FormulaValue } from '../formula/formula.models';
import type { FormFieldMeta, FormMeta, FormProblems, FormValues } from '../models/form-meta.models';

type Translate = (key: string, params?: Record<string, string | number>) => string;

const LOCAL_MOMENT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/;

/**
 * The form logic of a live form on the screen (ADR-0042): the checked formulas form-meta gives are evaluated with the
 * server's semantics while the person types — computed values, defaults, when a field is shown, required or read-only,
 * and the rules. The order is the server's: defaults, computed fields, hidden fields cleared, computed fields again.
 * The server evaluates the same formulas on save and stays authoritative; a formula that cannot give a value here
 * leaves the field as it is and the server answers the problem.
 */
export function viewerScope(): FormulaScope {
  return { zone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC', now: Date.now() };
}

/** Whether the form has formulas at all. */
export function hasLogic(meta: Pick<FormMeta, 'fields' | 'rules'>): boolean {
  return (
    !!meta.rules?.length ||
    meta.fields.some(
      (field) => field.formula || field.defaultFormula || field.visibleIf || field.requiredIf || field.readonlyIf,
    )
  );
}

/** Whether the field's own condition shows it over these values; a field without one is shown. */
export function logicVisible(field: FormFieldMeta, values: FormValues, scope = viewerScope()): boolean {
  return !field.visibleIf || condition(field.visibleIf, values, scope, true);
}

/** Whether the field's condition makes it required over these values. */
export function logicRequired(field: FormFieldMeta, values: FormValues, scope = viewerScope()): boolean {
  return !!field.requiredIf && condition(field.requiredIf, values, scope, false);
}

/** Whether the field's condition keeps it read-only over the record as it is stored. */
export function logicReadonly(field: FormFieldMeta, stored: FormValues, scope = viewerScope()): boolean {
  return !!field.readonlyIf && condition(field.readonlyIf, stored, scope, false);
}

/**
 * The values with each empty field of a new record given its default formula, in the form's order: a default sees
 * the values before it.
 */
export function withDefaults(meta: Pick<FormMeta, 'fields'>, values: FormValues, scope = viewerScope()): FormValues {
  let result = values;
  for (const field of meta.fields) {
    const current = result[field.key];
    if (!field.defaultFormula || (current !== null && current !== undefined && current !== '')) continue;
    const value = evaluate(field.defaultFormula, result, scope);
    if (value === undefined || value === null) continue;
    result = { ...result, [field.key]: shown(field, value) };
  }
  return result;
}

/**
 * The values with the computed fields evaluated as the server evaluates them: each after the computed fields it reads,
 * then again over the values the hidden fields leave. The same object when nothing changes.
 */
export function withComputed(meta: Pick<FormMeta, 'fields'>, values: FormValues, scope = viewerScope()): FormValues {
  const computed = ordered(meta.fields.filter((field) => field.formula));
  if (computed.length === 0) return values;
  const record = compute(computed, { ...values }, scope);
  const hidden = meta.fields.filter((field) => !field.formula && !logicVisible(field, record, scope));
  if (hidden.length > 0) {
    hidden.forEach((field) => (record[field.key] = null));
    compute(computed, record, scope);
  }
  let result = values;
  for (const field of computed) {
    if (!Object.is(result[field.key], record[field.key])) result = { ...result, [field.key]: record[field.key] };
  }
  return result;
}

/** The problems of the rules over the values: under the rule's field, or under `''` for the whole record. */
export function ruleProblems(meta: Pick<FormMeta, 'rules'>, values: FormValues, translate: Translate): FormProblems {
  const problems: FormProblems = {};
  const scope = viewerScope();
  for (const rule of meta.rules ?? []) {
    const at = rule.field ?? '';
    try {
      if (evaluateFormula(rule.formula, reader(values, rule.formula), scope) === false && !problems[at]) {
        problems[at] = translate(rule.messageKey);
      }
    } catch (failure) {
      if (failure instanceof FormulaFailure && !problems[at])
        problems[at] = translate(`error.formula.${failure.reason}`);
      else if (!(failure instanceof FormulaFailure)) throw failure;
    }
  }
  return problems;
}

function compute(fields: FormFieldMeta[], record: FormValues, scope: FormulaScope): FormValues {
  for (const field of fields) {
    const value = evaluate(field.formula as FormulaNode, record, scope);
    if (value !== undefined) record[field.key] = value === null ? null : shown(field, value);
  }
  return record;
}

/** The computed fields, each after the computed fields it reads; the form's order otherwise. */
function ordered(fields: FormFieldMeta[]): FormFieldMeta[] {
  const keys = new Set(fields.map((field) => field.key));
  const placed = new Set<string>();
  const result: FormFieldMeta[] = [];
  while (result.length < fields.length) {
    const ready = fields.find(
      (field) => !placed.has(field.key) && reads(field.formula).every((key) => !keys.has(key) || placed.has(key)),
    );
    if (!ready) return fields;
    placed.add(ready.key);
    result.push(ready);
  }
  return result;
}

function reads(node: FormulaNode | null | undefined): string[] {
  if (!node) return [];
  if (node.kind === 'field' && node.name) return [node.name];
  return (node.args ?? []).flatMap(reads);
}

function condition(node: FormulaNode, values: FormValues, scope: FormulaScope, fallback: boolean): boolean {
  const value = evaluate(node, values, scope);
  return value === undefined ? fallback : value === true;
}

/** The value of a formula over the form's values; undefined when it cannot give one. */
function evaluate(node: FormulaNode, values: FormValues, scope: FormulaScope): FormulaValue | undefined {
  try {
    return evaluateFormula(node, reader(values, node), scope);
  } catch (failure) {
    if (failure instanceof FormulaFailure) return undefined;
    throw failure;
  }
}

/**
 * The form's values as the server reads them: a moment field typed in the viewer's time (`yyyy-MM-ddTHH:mm`) is the
 * moment it names; the moment fields are those the formula reads as moments.
 */
function reader(values: FormValues, node: FormulaNode): (key: string) => unknown {
  const moments = new Set(fieldsOfType(node, 'datetime'));
  return (key) => {
    const raw = values[key];
    if (moments.has(key) && typeof raw === 'string' && LOCAL_MOMENT.test(raw)) {
      const moment = new Date(raw);
      return Number.isNaN(moment.getTime()) ? raw : moment.toISOString();
    }
    return raw;
  };
}

function fieldsOfType(node: FormulaNode, type: string): string[] {
  if (node.kind === 'field') return node.type === type && node.name ? [node.name] : [];
  return (node.args ?? []).flatMap((arg) => fieldsOfType(arg, type));
}

/** A formula's value as the field's control edits it: a number with the field's decimals, a moment in local time. */
function shown(field: FormFieldMeta, value: FormulaValue): unknown {
  if (value instanceof Decimal) return field.scale != null ? value.fixed(field.scale) : value.toString();
  if (typeof value === 'boolean') return value;
  const text = formulaText(value);
  if (field.type === 'datetime') {
    const moment = new Date(text);
    const pad = (n: number) => String(n).padStart(2, '0');
    return (
      `${moment.getFullYear()}-${pad(moment.getMonth() + 1)}-${pad(moment.getDate())}` +
      `T${pad(moment.getHours())}:${pad(moment.getMinutes())}`
    );
  }
  return text;
}
