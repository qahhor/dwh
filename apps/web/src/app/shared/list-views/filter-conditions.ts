import { fieldLabel, QueryCondition, QueryFieldMeta, QueryListMeta, QueryOp, QueryValue } from '../../core/models/query-meta.models';

/**
 * One row of the filter builder while it is being edited. Values are kept as
 * text, as inputs give them; `toCondition` turns a row into a DSL condition
 * (ADR-0016) once it is complete. `values` holds the chosen items of an `in`
 * list over an enum.
 */
export interface FilterDraft {
  field: string;
  op: QueryOp;
  value: string;
  valueTo: string;
  values: string[];
}

/** Operations without a value. */
const NO_VALUE: readonly QueryOp[] = ['empty', 'not_empty'];

export function opTakesValue(op: QueryOp): boolean {
  return !NO_VALUE.includes(op);
}

export function filterableFields(meta: QueryListMeta): QueryFieldMeta[] {
  return meta.fields.filter(field => field.ops.length > 0);
}

/** A new row on the first filterable field with its first operation. */
export function newDraft(meta: QueryListMeta): FilterDraft | null {
  const field = filterableFields(meta)[0];
  return field ? { field: field.key, op: field.ops[0], value: '', valueTo: '', values: [] } : null;
}

/** A row for another field keeps the operation when the new field has it, and drops values that no longer fit. */
export function changeField(draft: FilterDraft, field: QueryFieldMeta): FilterDraft {
  return {
    field: field.key,
    op: field.ops.includes(draft.op) ? draft.op : field.ops[0],
    value: '',
    valueTo: '',
    values: [],
  };
}

export function fromCondition(condition: QueryCondition): FilterDraft {
  const list = Array.isArray(condition.value) ? condition.value.map(String) : [];
  const single = condition.value === undefined || Array.isArray(condition.value) ? '' : String(condition.value);
  if (condition.op === 'between') {
    return { field: condition.field, op: condition.op, value: list[0] ?? '', valueTo: list[1] ?? '', values: [] };
  }
  if (condition.op === 'in') {
    return { field: condition.field, op: condition.op, value: list.join(', '), valueTo: '', values: list };
  }
  return { field: condition.field, op: condition.op, value: single, valueTo: '', values: [] };
}

/**
 * Why a row cannot be applied, as a dictionary key, or `null` when it is
 * complete. The server checks the same rules again; this only spares a round
 * trip and points at the row.
 */
export function draftError(draft: FilterDraft, meta: QueryListMeta): string | null {
  const field = meta.fields.find(item => item.key === draft.field);
  if (!field || !field.ops.includes(draft.op)) return 'ui.filter.err.field';
  if (!opTakesValue(draft.op)) return null;
  if (draft.op === 'in') {
    const items = inValues(draft, field);
    if (items.length === 0) return 'ui.filter.err.required';
    if (items.length > meta.maxInValues) return 'ui.filter.err.too_many';
    return items.every(item => valueFits(field, item)) ? null : errorFor(field);
  }
  if (draft.op === 'between') {
    if (draft.value.trim() === '' || draft.valueTo.trim() === '') return 'ui.filter.err.required';
    return valueFits(field, draft.value) && valueFits(field, draft.valueTo) ? null : errorFor(field);
  }
  if (draft.value.trim() === '') return 'ui.filter.err.required';
  return valueFits(field, draft.value) ? null : errorFor(field);
}

/** The DSL condition of a complete row. */
export function toCondition(draft: FilterDraft, meta: QueryListMeta): QueryCondition {
  const field = meta.fields.find(item => item.key === draft.field)!;
  if (!opTakesValue(draft.op)) return { field: field.key, op: draft.op };
  if (draft.op === 'in') return { field: field.key, op: 'in', value: inValues(draft, field).map(item => typed(field, item)) };
  if (draft.op === 'between') {
    return { field: field.key, op: 'between', value: [typed(field, draft.value), typed(field, draft.valueTo)] };
  }
  return { field: field.key, op: draft.op, value: typed(field, draft.value) };
}

/** A short reading of a condition for its chip: "Periodicity: one of month, year". */
export function describeCondition(condition: QueryCondition, meta: QueryListMeta, translate: (key: string) => string): string {
  const field = meta.fields.find(item => item.key === condition.field);
  const label = field ? fieldLabel(field, translate) : condition.field;
  const op = translate(`ui.filter.op.${condition.op}`);
  const show = (value: QueryValue) => displayValue(value, field, translate);
  if (condition.value === undefined) return `${label}: ${op}`;
  if (Array.isArray(condition.value)) {
    const items = condition.value.map(show);
    return condition.op === 'between' ? `${label}: ${items[0]} — ${items[1]}` : `${label}: ${op} ${items.join(', ')}`;
  }
  return `${label}: ${op} ${show(condition.value)}`;
}

export function displayValue(value: QueryValue, field: QueryFieldMeta | undefined, translate: (key: string) => string): string {
  if (field?.type === 'enum') return field.enumLabelPrefix ? translate(`${field.enumLabelPrefix}${value}`) : String(value);
  if (field?.type === 'boolean') return translate(value === true || value === 'true' ? 'common.yes' : 'common.no');
  return String(value);
}

function inValues(draft: FilterDraft, field: QueryFieldMeta): string[] {
  if (field.type === 'enum') return draft.values.filter(item => field.enumValues.includes(item));
  return draft.value.split(',').map(item => item.trim()).filter(item => item.length > 0);
}

function valueFits(field: QueryFieldMeta, text: string): boolean {
  const value = text.trim();
  switch (field.type) {
    case 'number':
      return value !== '' && Number.isFinite(Number(value));
    case 'date':
      return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value));
    case 'instant':
      return !Number.isNaN(Date.parse(value));
    case 'boolean':
      return value === 'true' || value === 'false';
    case 'enum':
      return field.enumValues.includes(value);
    default:
      return value.length > 0 && value.length <= 500;
  }
}

function errorFor(field: QueryFieldMeta): string {
  return field.type === 'number' ? 'ui.filter.err.number'
    : field.type === 'date' || field.type === 'instant' ? 'ui.filter.err.date'
      : 'ui.filter.err.value';
}

function typed(field: QueryFieldMeta, text: string): QueryValue {
  const value = text.trim();
  if (field.type === 'number') return Number(value);
  if (field.type === 'boolean') return value === 'true';
  if (field.type === 'instant') return new Date(value).toISOString();
  return value;
}
