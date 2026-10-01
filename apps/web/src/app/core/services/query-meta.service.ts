import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ListQuery, QueryCondition, QueryListMeta, QueryMatch, QuerySort } from '../models/query-meta.models';
import { ApiService } from './api.service';
import { MetaCache, MetaCacheState } from './meta-cache';

/** Query parameters for a registry list: `filter` (JSON DSL) and `sort` (`-key` for descending). */
export function toQueryParams(query: ListQuery | null | undefined): { filter?: string; sort?: string; q?: string } {
  const params: { filter?: string; sort?: string; q?: string } = {};
  const conditions = query?.conditions ?? [];
  if (conditions.length > 0) {
    params.filter = JSON.stringify(filterDsl(conditions, query?.match));
  }
  const search = query?.search?.trim();
  if (search) {
    params.q = search;
  }
  if (query?.sort) {
    params.sort = formatSort(query.sort);
  }
  return params;
}

export function formatSort(sort: QuerySort): string {
  return (sort.descending ? '-' : '') + sort.field;
}

export function parseSort(sort: string): QuerySort {
  return sort.startsWith('-') ? { field: sort.slice(1), descending: true } : { field: sort, descending: false };
}

/** The filter as the server takes it: the conditions, or one `{"any": [...]}` group of them (roadmap item 53). */
export function filterDsl(conditions: readonly QueryCondition[], match?: QueryMatch, keepLabels = false): unknown[] {
  const plain = conditions.map((condition) => {
    const normal = normalizeCondition(condition);
    return keepLabels && condition.label ? { ...normal, label: condition.label } : normal;
  });
  return match === 'any' && plain.length > 1 ? [{ any: plain }] : plain;
}

/** A saved filter back into its conditions and how they combine; a group is only ever written whole. */
export function readFilterDsl(filter: readonly unknown[] | null | undefined): {
  conditions: QueryCondition[];
  match: QueryMatch;
} {
  const items = filter ?? [];
  const group = items.length === 1 ? (items[0] as { any?: QueryCondition[] }).any : undefined;
  return Array.isArray(group)
    ? { conditions: group, match: 'any' }
    : {
        conditions: items.filter((item): item is QueryCondition => typeof (item as QueryCondition)?.field === 'string'),
        match: 'all',
      };
}

/** Conditions without a value send none, so `empty` does not travel as `"value": undefined`; a label stays on the screen. */
function normalizeCondition(condition: QueryCondition): QueryCondition {
  return condition.value === undefined
    ? { field: condition.field, op: condition.op }
    : { field: condition.field, op: condition.op, value: condition.value };
}

/**
 * Field metadata of server lists, cached like the entity forms (plan 10/10, item 5.0): read again when custom
 * fields change, when the viewer's rights change and after `META_TTL_MS`, so a new custom field becomes a column
 * without a reload. A failed request is not cached and is retried on the next call.
 */
@Injectable({ providedIn: 'root' })
export class QueryMetaService {
  private readonly api = inject(ApiService);
  private readonly cache = new MetaCache<QueryListMeta>(inject(MetaCacheState));

  get(list: string): Observable<QueryListMeta> {
    return this.cache.get(list, () =>
      this.api.get<QueryListMeta>(`/query-meta/${encodeURIComponent(list)}`, undefined, { notifyError: false }),
    );
  }
}
