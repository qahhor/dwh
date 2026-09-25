import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { User } from '../../core/models/auth.models';
import { KeysetPage } from '../../core/models/common.models';
import type { SMTLookupKey, SMTLookupSource } from '../ui-kit/components/forms/data-select/lookup-source';
import type { SMTSelectOption } from '../ui-kit/components/forms/select/select.component';
import { ApiService } from '../../core/services/api.service';

export interface RestLookup<Row, K extends SMTLookupKey> {
  /** The list endpoint under /api/v1, answering keyset pages with `search`, `cursor` and `limit`. */
  path: string;
  /** Fixed filters, such as `{ state: 'A' }`. */
  params?: Record<string, string | number | boolean>;
  key(row: Row): K;
  option(row: Row): Omit<SMTSelectOption<K>, 'id'>;
  /** Names a chosen key with `GET {path}/{key}`; false when the endpoint has no such read. */
  resolveById?: boolean;
}

/**
 * A lookup source over one of our list endpoints (roadmap item 32). Failures
 * stay inside the field — it shows "could not load" with a retry — so no
 * toast is raised for them.
 */
export function restLookup<Row, K extends SMTLookupKey = number>(api: ApiService, lookup: RestLookup<Row, K>): SMTLookupSource<Row, K> {
  const quiet = { notifyError: false };
  const source: SMTLookupSource<Row, K> = {
    page: (search, cursor, limit) => api.get<KeysetPage<Row>>(lookup.path, {
      ...lookup.params,
      limit,
      cursor: cursor ?? undefined,
      search: search || undefined,
    }, quiet),
    key: row => lookup.key(row),
    option: row => lookup.option(row),
  };
  if (lookup.resolveById !== false) {
    source.resolve = (keys): Observable<readonly Row[]> => forkJoin(keys.map(key =>
      api.get<Row>(`${lookup.path}/${encodeURIComponent(String(key))}`, undefined, quiet).pipe(catchError(() => of(null)))
    )).pipe(map(rows => rows.filter((row, index): row is NonNullable<typeof row> => row != null && lookup.key(row as Row) === keys[index]) as Row[]));
  }
  return source;
}

/** The reference lists screens pick from, each defined once. */
@Injectable({ providedIn: 'root' })
export class LookupSources {
  private readonly api = inject(ApiService);

  /** Active users, shown as "Name" with "@login" beside it. */
  readonly activeUsers = restLookup<User>(this.api, {
    path: '/iam/users',
    params: { state: 'A' },
    key: user => user.id,
    option: user => ({ label: user.name, subLabel: `@${user.login}` }),
  });
}
