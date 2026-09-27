import { Observable, catchError, forkJoin, map, of, shareReplay } from 'rxjs';
import type { SMTLookupKey, SMTLookupSource } from '../ui-kit/components/forms/data-select/lookup-source';
import type { QueryRefMeta } from '../../core/models/query-meta.models';
import type { KeysetPage } from '../../core/models/common.models';
import { ApiService } from '../../core/services/api.service';

type Row = Record<string, unknown>;

/**
 * The rows a reference field is picked from (ADR-0019 2.4, roadmap item 53): the endpoint the server names
 * in `query-meta`, whose own rights and data scope decide what is offered. A paged endpoint is searched on
 * the server; a whole (short) list is loaded once and searched here. Failures stay inside the field.
 */
export function refLookup(api: ApiService, ref: QueryRefMeta): SMTLookupSource<Row, SMTLookupKey> {
  const quiet = { notifyError: false };
  const key = (row: Row) => row[ref.keyField] as SMTLookupKey;
  const option = (row: Row) => ({ label: String(row[ref.labelField] ?? row[ref.keyField] ?? '') });
  if (ref.paged) {
    return {
      page: (search, cursor, limit) =>
        api.get<KeysetPage<Row>>(
          ref.path,
          {
            limit,
            cursor: cursor ?? undefined,
            q: search || undefined,
          },
          quiet,
        ),
      key,
      option,
      // A chosen key is named by its own read, as the reference lists do (lookup-sources.ts).
      resolve: (keys) =>
        forkJoin(
          keys.map((one) =>
            api
              .get<Row>(`${ref.path}/${encodeURIComponent(String(one))}`, undefined, quiet)
              .pipe(catchError(() => of(null))),
          ),
        ).pipe(map((found) => found.filter((row): row is Row => row != null))),
    };
  }
  let whole: Observable<Row[]> | null = null;
  const rows = () =>
    (whole ??= api.get<Row[] | KeysetPage<Row>>(ref.path, undefined, quiet).pipe(
      map((body) => (Array.isArray(body) ? body : (body?.items ?? []))),
      shareReplay({ bufferSize: 1, refCount: false }),
    ));
  return {
    page: (search) =>
      rows().pipe(
        map((items) => {
          const text = search.trim().toLowerCase();
          const found = text ? items.filter((row) => option(row).label.toLowerCase().includes(text)) : items;
          return { items: found, nextCursor: null, hasMore: false };
        }),
      ),
    key,
    option,
    resolve: (keys) =>
      rows().pipe(
        map((items) => items.filter((row) => keys.includes(key(row)))),
        catchError(() => of([])),
      ),
  };
}
