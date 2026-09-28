import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { User } from '@core/models/auth.models';
import { KeysetPage } from '@core/models/common.models';
import { ListQuery } from '@core/models/query-meta.models';
import { Project } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { toQueryParams } from '@core/services/query-meta.service';
import { ProjectListItem, ProjectMember } from './projects.models';

/** Projects: the list with task counts, one project and its members. */
@Injectable({ providedIn: 'root' })
export class ProjectsApi {
  private readonly api = inject(ApiService);

  /** A page of the registry list ms.projects; the screen shows a failed page itself. */
  page(
    filters: Record<string, unknown>,
    query: ListQuery,
    cursor: string | null,
    limit: number,
  ): Observable<KeysetPage<ProjectListItem>> {
    return this.api.get<KeysetPage<ProjectListItem>>(
      '/tasks/projects/page',
      { limit, cursor: cursor ?? undefined, ...filters, ...toQueryParams(query) },
      { notifyError: false },
    );
  }

  /** One project for the card; the card shows a failure itself. */
  get(id: number | string): Observable<Project> {
    return this.api.get<Project>(`/tasks/projects/${id}`, undefined, { notifyError: false });
  }

  members(projectId: number): Observable<ProjectMember[]> {
    return this.api.get<ProjectMember[]>(`/tasks/projects/${projectId}/members`);
  }

  addMember(projectId: number, userId: number, accessKind: string): Observable<void> {
    return this.api.post<void>(`/tasks/projects/${projectId}/members`, { userId, accessKind });
  }

  /** The confirmation shows the failure, so no general error toast. */
  removeMember(projectId: number, userId: number): Observable<unknown> {
    return this.api.delete(`/tasks/projects/${projectId}/members/${userId}`, { notifyError: false });
  }

  /** Active users whose name, login or e-mail matches, for adding a member. */
  searchActiveUsers(term: string, limit = 15): Observable<{ items: User[] }> {
    return this.api.get<{ items: User[] }>('/iam/users', { state: 'A', search: term, limit });
  }
}
