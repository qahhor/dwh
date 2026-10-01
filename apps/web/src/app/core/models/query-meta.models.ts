/** Server field registry for lists (ADR-0016): what `GET /api/v1/query-meta/{list}` returns. */

/**
 * A list field's type: how it is filtered. `ref_set` holds the keys of several rows (`in`, `empty`, `not_empty`);
 * `object` — a file or JSON — is only there or not (ADR-0032 4.1).
 */
export type QueryFieldType =
  'text' | 'number' | 'date' | 'instant' | 'time' | 'boolean' | 'enum' | 'ref_set' | 'object';

/** Every filter operation the server knows; `ui.filter.op.<op>` names each. */
export const QUERY_OPS = [
  'eq',
  'ne',
  'in',
  'contains',
  'starts_with',
  'gt',
  'gte',
  'lt',
  'lte',
  'between',
  'empty',
  'not_empty',
] as const;

export type QueryOp = (typeof QUERY_OPS)[number];

export interface QueryFieldMeta {
  key: string;
  labelKey: string;
  type: QueryFieldType;
  /** Operations the field accepts in a filter; empty — the field cannot be filtered. */
  ops: QueryOp[];
  sortable: boolean;
  nullable: boolean;
  defaultVisible: boolean;
  enumValues: string[];
  /** Dictionary key prefix for enum values, e.g. `upl.periodicity.` + `month`. */
  enumLabelPrefix: string | null;
  /** Looked at by the free-text search `q`. */
  searchable?: boolean;
  /** A custom field's own name, shown instead of translating `labelKey` (ADR-0019, 2.3). */
  label?: string | null;
  /** A custom field's code: its value is `row.attributes[attribute]`, not `row[key]`. */
  attribute?: string | null;
  /** The field holds the key of a row in another list, picked by name (ADR-0019 2.4). */
  ref?: QueryRefMeta | null;
  /**
   * The entity field type when the list type alone does not say how to show the value (`money`, `email`, `file`, …;
   * ADR-0032 4.1); `currency` — the currency of the money field this one filters.
   */
  format?: string | null;
  /** An enumeration's value names read from its reference entity (ADR-0032 4.5), instead of `enumLabelPrefix`. */
  enumLabels?: Record<string, string> | null;
}

/** Where a reference field's values come from: an endpoint under /api/v1 and the row properties it uses. */
export interface QueryRefMeta {
  path: string;
  labelField: string;
  keyField: string;
  /** Keyset pages searched with `q`; false — the whole short list, searched on the screen. */
  paged: boolean;
  /** Where one row is read as `{readPath}/{key}` when that is not `{path}/{key}` (a list paged under `/page`). */
  readPath?: string | null;
}

/** How the conditions of a filter combine: all of them, or any of them (`{"any": [...]}`). */
export type QueryMatch = 'all' | 'any';

/** An enumeration value in words: its reference item's name, its catalog label, or the value itself. */
export function enumLabel(
  field: Pick<QueryFieldMeta, 'enumLabels' | 'enumLabelPrefix'>,
  value: string,
  translate: (key: string) => string,
): string {
  if (field.enumLabels?.[value]) return field.enumLabels[value];
  return field.enumLabelPrefix ? translate(`${field.enumLabelPrefix}${value}`) : value;
}

/** The field's heading: a custom field's own name, otherwise its dictionary key translated. */
export function fieldLabel(
  field: Pick<QueryFieldMeta, 'label' | 'labelKey'>,
  translate: (key: string) => string,
): string {
  return field.label ?? translate(field.labelKey);
}

/** The suffix of the hidden field that filters money by its currency (`totalCurrency`). */
const CURRENCY_SUFFIX = 'Currency';

/**
 * The field's value in a row: a custom field from the row's attributes, the currency field of money from its money
 * (`{amount, currency}`).
 */
export function fieldValue(field: Pick<QueryFieldMeta, 'key' | 'attribute' | 'format'>, row: unknown): unknown {
  const record = row as Record<string, unknown>;
  if (field.format === 'currency' && field.key.endsWith(CURRENCY_SUFFIX)) {
    const money = record[field.key.slice(0, -CURRENCY_SUFFIX.length)] as { currency?: unknown } | null | undefined;
    return money?.currency;
  }
  if (!field.attribute) return record[field.key];
  const attributes = record['attributes'] as Record<string, unknown> | null | undefined;
  return attributes?.[field.attribute];
}

export interface QueryListMeta {
  code: string;
  fields: QueryFieldMeta[];
  /** Field key, with a leading minus for descending order. */
  defaultSort: string;
  defaultLimit: number;
  maxLimit: number;
  maxConditions: number;
  maxInValues: number;
}

export type QueryValue = string | number | boolean;

/** One condition of the filter DSL; conditions are joined with "and". */
export interface QueryCondition {
  field: string;
  op: QueryOp;
  /** One value, a list for `in`, a pair for `between`, nothing for `empty` / `not_empty`. */
  value?: QueryValue | QueryValue[];
  /** The name of a referenced row, kept for the chip and in saved views; the server ignores it. */
  label?: string;
}

export interface QuerySort {
  field: string;
  descending: boolean;
}

/** What a list screen asks for; turned into `filter` / `sort` query parameters by `toQueryParams`. */
export interface ListQuery {
  conditions?: QueryCondition[];
  /** `any` sends the conditions as one `{"any": [...]}` group. */
  match?: QueryMatch;
  sort?: QuerySort | null;
  /** Free text matched in any searchable field (`q`). */
  search?: string | null;
}
