import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, switchMap } from 'rxjs';
import { User } from '@core/models/auth.models';
import { KeysetPage } from '@core/models/common.models';
import { ListQuery } from '@core/models/query-meta.models';
import { Project } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import { toQueryParamsWith } from '@core/services/query-meta.service';
import { EntitiesApi, EntityRecord } from '@shared/entity/entities.api';
import { USERS_PATH } from '@shared/lookups/lookup-sources';
import { ProjectListItem, ProjectMember, ProjectStateFilter } from './projects.models';

/** The projects on the general runtime (ADR-0032 8): `/api/v1/entities/ms.projects`. */
export const PROJECTS = 'ms.projects';

/** The progress of a project over the viewer's tasks (`GET /tasks/projects/progress`). */
interface ProjectProgress {
  projectId: number;
  totalTasks: number;
  doneTasks: number;
  progress: number;
}

/** A project of the screen from its record: a project in the archive is the paused one. */
export function toProject(record: EntityRecord): Project {
  return {
    id: record.id,
    name: String(record['name'] ?? ''),
    description: (record['description'] as string | undefined) ?? undefined,
    state: record.archived ? 'P' : 'A',
    attributes: record.attributes,
    createdAt: String(record['createdAt'] ?? ''),
    modifiedAt: (record['modifiedAt'] as string | undefined) ?? undefined,
    createdBy: (record['createdBy'] as number | undefined) ?? undefined,
    revision: record.revision,
  };
}

/**
 * The archive switch as the list's filter (ADR-0032 5.4): in use by default, the archive alone, or both together by
 * an "any" group over `archived`.
 */
export function archiveFilter(state: ProjectStateFilter | undefined): unknown[] {
  if (state === 'P') return [{ field: 'archived', op: 'eq', value: true }];
  if (state === 'A' || state === undefined) return [];
  return [
    {
      any: [
        { field: 'archived', op: 'eq', value: true },
        { field: 'archived', op: 'eq', value: false },
      ],
    },
  ];
}

/** Projects: the list with the viewer's task counts, one project and its members. */
@Injectable({ providedIn: 'root' })
export class ProjectsApi {
  private readonly api = inject(ApiService);
  private readonly entities = inject(EntitiesApi);
  private readonly permissions = inject(PermissionService, { optional: true });

  /**
   * A page of the project list, each project with its progress over the viewer's tasks when the viewer may see tasks;
   * the screen shows a failed page itself.
   */
  page(
    filters: { state?: ProjectStateFilter },
    query: ListQuery,
    cursor: string | null,
    limit: number,
  ): Observable<KeysetPage<ProjectListItem>> {
    const params = { limit, cursor: cursor ?? undefined, ...toQueryParamsWith(query, archiveFilter(filters.state)) };
    return this.api
      .get<KeysetPage<EntityRecord>>(`/entities/${PROJECTS}`, params, { notifyError: false })
      .pipe(switchMap((page) => this.withProgress(page)));
  }

  /** One project for the card, by the exact id of the address; the card shows a failure itself. */
  get(id: number | string): Observable<Project> {
    return this.api
      .get<EntityRecord>(`/entities/${PROJECTS}/${id}`, undefined, { notifyError: false })
      .pipe(map(toProject));
  }

  /** A page of the members of a project, by name (plan item 3.5); the dialog shows a failure itself. */
  membersPage(projectId: number, cursor: string | null, limit: number): Observable<KeysetPage<ProjectMember>> {
    return this.api.get<KeysetPage<ProjectMember>>(
      `/tasks/projects/${projectId}/members/page`,
      { limit, cursor: cursor ?? undefined },
      { notifyError: false },
    );
  }

  /** Adds a member by the project's record action, from its current revision (ADR-0032 6.7). */
  addMember(projectId: number, userId: number, accessKind: string): Observable<EntityRecord> {
    return this.memberAction(projectId, 'add_member', { userId, accessKind });
  }

  /** The confirmation shows the failure, so no general error toast. */
  removeMember(projectId: number, userId: number): Observable<EntityRecord> {
    return this.memberAction(projectId, 'remove_member', { userId });
  }

  /** Active users whose name, login or e-mail matches, for adding a member. */
  searchActiveUsers(term: string, limit = 15): Observable<{ items: User[] }> {
    return this.api.get<{ items: User[] }>(USERS_PATH, {
      filter: JSON.stringify([{ field: 'state', op: 'eq', value: 'A' }]),
      q: term,
      limit,
    });
  }

  private memberAction(projectId: number, action: string, params: Record<string, unknown>): Observable<EntityRecord> {
    return this.entities
      .get(PROJECTS, projectId)
      .pipe(switchMap((project) => this.entities.action(PROJECTS, projectId, action, project.revision, params)));
  }

  /** The page's projects with their task counts; without the right to view tasks, or when the counts fail, none. */
  private withProgress(page: KeysetPage<EntityRecord>): Observable<KeysetPage<ProjectListItem>> {
    const items = page.items.map((record) => ({ ...toProject(record) }) as ProjectListItem);
    const canViewTasks = this.permissions?.hasPermission('tasks.items', 'view') ?? false;
    if (!canViewTasks || items.length === 0) return of({ ...page, items });
    return this.api
      .get<ProjectProgress[]>(
        '/tasks/projects/progress',
        { ids: items.map((item) => item.id).join(',') },
        { notifyError: false },
      )
      .pipe(
        catchError(() => of<ProjectProgress[]>([])),
        map((progress) => {
          const byId = new Map(progress.map((entry) => [entry.projectId, entry]));
          return {
            ...page,
            items: items.map((item) => {
              const entry = byId.get(item.id);
              return entry
                ? { ...item, totalTasks: entry.totalTasks, doneTasks: entry.doneTasks, progress: entry.progress }
                : item;
            }),
          };
        }),
      );
  }
}
