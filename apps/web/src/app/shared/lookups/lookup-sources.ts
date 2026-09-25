import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { User } from '../../core/models/auth.models';
import { Task } from '../../core/models/task.models';
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
  /** The row inside that read's answer, when it is wrapped (`{ task, members }`). */
  readOne?: (body: unknown) => Row | null | undefined;
}

/** What a user picker needs of a user; a full User fits, and so does a task member. */
export type UserRef = Pick<User, 'id' | 'name' | 'login'>;

/** What a task picker needs of a task. */
export type TaskRef = Pick<Task, 'id' | 'title'>;

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
      api.get<unknown>(`${lookup.path}/${encodeURIComponent(String(key))}`, undefined, quiet).pipe(
        map(body => (lookup.readOne ? lookup.readOne(body) : body as Row) ?? null),
        catchError(() => of(null))
      )
    )).pipe(map(rows => rows.filter((row, index): row is NonNullable<typeof row> => row != null && lookup.key(row as Row) === keys[index]) as Row[]));
  }
  return source;
}

/** The reference lists screens pick from, each defined once. */
@Injectable({ providedIn: 'root' })
export class LookupSources {
  private readonly api = inject(ApiService);

  /** Active users, shown as "Name" with "@login" beside it. */
  readonly activeUsers = restLookup<UserRef>(this.api, {
    path: '/iam/users',
    params: { state: 'A' },
    key: user => user.id,
    option: user => ({ label: user.name, subLabel: `@${user.login}` }),
  });

  /** Tasks by number or title, shown as "#12 Title"; a chosen one is read from its card. */
  readonly tasks = restLookup<TaskRef>(this.api, {
    path: '/tasks',
    key: task => task.id,
    option: task => ({ label: `#${task.id} ${task.title}`, icon: 'task_alt' }),
    readOne: body => (body as { task?: TaskRef } | null)?.task,
  });
}
