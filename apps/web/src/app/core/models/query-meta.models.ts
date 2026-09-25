/** Server field registry for lists (ADR-0016): what `GET /api/v1/query-meta/{list}` returns. */

export type QueryFieldType = 'text' | 'number' | 'date' | 'instant' | 'boolean' | 'enum';

export type QueryOp =
  | 'eq' | 'ne' | 'in' | 'contains' | 'starts_with'
  | 'gt' | 'gte' | 'lt' | 'lte' | 'between'
  | 'empty' | 'not_empty';

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
