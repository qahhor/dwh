import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { ListQuery, QueryCondition, QueryListMeta, QuerySort } from '../models/query-meta.models';
import { ApiService } from './api.service';

/** Query parameters for a registry list: `filter` (JSON DSL) and `sort` (`-key` for descending). */
export function toQueryParams(query: ListQuery | null | undefined): { filter?: string; sort?: string; q?: string } {
  const params: { filter?: string; sort?: string; q?: string } = {};
  const conditions = query?.conditions ?? [];
  if (conditions.length > 0) {
    params.filter = JSON.stringify(conditions.map(normalizeCondition));
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

/** Conditions without a value send none, so `empty` does not travel as `"value": undefined`. */
function normalizeCondition(condition: QueryCondition): QueryCondition {
  return condition.value === undefined
    ? { field: condition.field, op: condition.op }
    : { field: condition.field, op: condition.op, value: condition.value };
}

/**
 * Field metadata of server lists. A list's metadata does not change while the app runs,
 * so each list is fetched once; a failed request is not cached and is retried on the next call.
 */
@Injectable({ providedIn: 'root' })
export class QueryMetaService {
  private readonly api = inject(ApiService);
  private readonly cache = new Map<string, Observable<QueryListMeta>>();

  get(list: string): Observable<QueryListMeta> {
    let meta = this.cache.get(list);
    if (!meta) {
      meta = this.api.get<QueryListMeta>(`/query-meta/${encodeURIComponent(list)}`, undefined, { notifyError: false })
        .pipe(shareReplay({ bufferSize: 1, refCount: false }));
      this.cache.set(list, meta);
      meta.subscribe({ error: () => this.cache.delete(list) });
    }
    return meta;
  }
}
