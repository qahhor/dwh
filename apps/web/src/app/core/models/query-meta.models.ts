/** Server field registry for lists (ADR-0016): what `GET /api/v1/query-meta/{list}` returns. */

export type QueryFieldType = 'text' | 'number' | 'date' | 'instant' | 'boolean' | 'enum';

/** Every filter operation the server knows; `ui.filter.op.<op>` names each. */
export const QUERY_OPS = [
  'eq', 'ne', 'in', 'contains', 'starts_with',
  'gt', 'gte', 'lt', 'lte', 'between',
  'empty', 'not_empty',
] as const;

export type QueryOp = typeof QUERY_OPS[number];

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
}

/** The field's heading: a custom field's own name, otherwise its dictionary key translated. */
export function fieldLabel(field: Pick<QueryFieldMeta, 'label' | 'labelKey'>, translate: (key: string) => string): string {
  return field.label ?? translate(field.labelKey);
}

/** The field's value in a row: a custom field from the row's attributes. */
export function fieldValue(field: Pick<QueryFieldMeta, 'key' | 'attribute'>, row: unknown): unknown {
  const record = row as Record<string, unknown>;
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
}

export interface QuerySort {
  field: string;
  descending: boolean;
}

/** What a list screen asks for; turned into `filter` / `sort` query parameters by `toQueryParams`. */
export interface ListQuery {
  conditions?: QueryCondition[];
  sort?: QuerySort | null;
  /** Free text matched in any searchable field (`q`). */
  search?: string | null;
}
