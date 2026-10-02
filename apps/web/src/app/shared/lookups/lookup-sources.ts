import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { User } from '@core/models/auth.models';
import { Project, Task } from '@core/models/task.models';
import { KeysetPage } from '@core/models/common.models';
import type { SMTLookupKey, SMTLookupSource } from '../ui-kit/components/forms/data-select/lookup-source';
import type { SMTSelectOption } from '../ui-kit/components/forms/select/select.component';
import { ApiService } from '@core/services/api.service';

export interface RestLookup<Row, K extends SMTLookupKey> {
  /** The list endpoint under /api/v1, answering keyset pages with `search`, `cursor` and `limit`. */
  path: string;
  /** The name of the search parameter: `search` by default; registry lists (ADR-0016) take `q`. */
  searchParam?: string;
  /** Rows per request; the select's own page size when absent. */
  pageSize?: number;
  /** Fixed filters, such as `{ state: 'A' }`. */
  params?: Record<string, string | number | boolean>;
  key(row: Row): K;
  option(row: Row): Omit<SMTSelectOption<K>, 'id'>;
  /** Names a chosen key with `GET {path}/{key}`; false when the endpoint has no such read. */
  resolveById?: boolean;
  /** Where that read lives when it is not under `path` (a list paged under `/page`). */
  readPath?: string;
  /** The row inside that read's answer, when it is wrapped (`{ task, members }`). */
  readOne?: (body: unknown) => Row | null | undefined;
}

/** The list of the user entity on the general runtime (ADR-0032 8): user pickers read it. */
export const USERS_PATH = '/entities/md.users';

/** What a user picker needs of a user; a full User fits, and so does a task member. */
export type UserRef = Pick<User, 'id' | 'name' | 'login'>;

/** What a task picker needs of a task. */
export type TaskRef = Pick<Task, 'id' | 'title'>;

/** What a project picker needs of a project; a task row's `projectId` and `projectName` make one. */
export type ProjectRef = Pick<Project, 'id' | 'name'>;

/**
 * A lookup source over one of our list endpoints (roadmap item 32). Failures
 * stay inside the field — it shows "could not load" with a retry — so no
 * toast is raised for them.
 */
export function restLookup<Row, K extends SMTLookupKey = number>(
  api: ApiService,
  lookup: RestLookup<Row, K>,
): SMTLookupSource<Row, K> {
  const quiet = { notifyError: false };
  const source: SMTLookupSource<Row, K> = {
    page: (search, cursor, limit) =>
      api.get<KeysetPage<Row>>(
        lookup.path,
        {
          ...lookup.params,
          limit: lookup.pageSize ?? limit,
          cursor: cursor ?? undefined,
          [lookup.searchParam ?? 'search']: search || undefined,
        },
        quiet,
      ),
    key: (row) => lookup.key(row),
    option: (row) => lookup.option(row),
  };
  if (lookup.resolveById !== false) {
    const readPath = lookup.readPath ?? lookup.path;
    source.resolve = (keys): Observable<readonly Row[]> =>
      forkJoin(
        keys.map((key) =>
          api.get<unknown>(`${readPath}/${encodeURIComponent(String(key))}`, undefined, quiet).pipe(
            map((body) => (lookup.readOne ? lookup.readOne(body) : (body as Row)) ?? null),
            catchError(() => of(null)),
          ),
        ),
      ).pipe(
        map(
          (rows) =>
            rows.filter(
              (row, index): row is NonNullable<typeof row> => row != null && lookup.key(row as Row) === keys[index],
            ) as Row[],
        ),
      );
  }
  return source;
}

/** The reference lists screens pick from, each defined once. */
@Injectable({ providedIn: 'root' })
export class LookupSources {
  private readonly api = inject(ApiService);

  /**
   * Active users of the user entity's list (ADR-0032 8), shown as "Name" with "@login" beside it; a chosen one is read
   * from its record, a blocked one too.
   */
  readonly activeUsers = restLookup<UserRef>(this.api, {
    path: USERS_PATH,
    searchParam: 'q',
    params: { filter: JSON.stringify([{ field: 'state', op: 'eq', value: 'A' }]) },
    key: (user) => user.id,
    option: (user) => ({ label: user.name, subLabel: `@${user.login}` }),
  });

  /** Tasks by number or title, shown as "#12 Title"; a chosen one is read from its card. */
  readonly tasks = restLookup<TaskRef>(this.api, {
    path: '/tasks',
    key: (task) => task.id,
    option: (task) => ({ label: `#${task.id} ${task.title}`, icon: 'task_alt' }),
    readOne: (body) => (body as { task?: TaskRef } | null)?.task,
  });

  /**
   * Projects by name, 20 at a time, archived ones included, from the paged project list (plan 10/10, item 3.5);
   * a chosen one is read from its own card.
   */
  readonly projects = restLookup<ProjectRef>(this.api, {
    path: '/tasks/projects/page',
    searchParam: 'q',
    pageSize: 20,
    readPath: '/tasks/projects',
    key: (project) => project.id,
    option: (project) => ({ label: project.name }),
  });
}
