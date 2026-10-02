import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { KeysetPage } from '@core/models/common.models';
import { ListQuery } from '@core/models/query-meta.models';
import { Task, TaskFile, TaskMember } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { toQueryParamsWith } from '@core/services/query-meta.service';
import { BulkResult } from '@shared/bulk/bulk';
import { EntityRecord } from '@shared/entity/entities.api';

/** The tasks on the general runtime (ADR-0032 8): `/api/v1/entities/ms.tasks`. */
export const TASKS = 'ms.tasks';

/** The record action that moves a task to another status. */
export const SET_STATUS = 'set_status';

/** How many subtasks the card shows: they are created by hand, one page is plenty. */
const SUBTASKS_PAGE = 100;

/** A task of the screen from its record: the fields come by their keys, custom fields in `attributes`. */
export function toTask(record: EntityRecord): Task {
  return {
    ...(record as unknown as Task),
    attributes: record.attributes ?? {},
  };
}

/** Tasks: the list, one task with its people and files, its changes and bulk actions. */
@Injectable({ providedIn: 'root' })
export class TasksApi {
  private readonly api = inject(ApiService);

  /** A page of tasks: the view's filter, order and search, then the screen's own quick filters as conditions. */
  page(
    conditions: readonly unknown[],
    query: ListQuery,
    cursor: string | null,
    limit: number,
  ): Observable<KeysetPage<Task>> {
    const params = { limit, cursor: cursor ?? undefined, ...toQueryParamsWith(query, conditions) };
    return this.api
      .get<KeysetPage<EntityRecord>>(`/entities/${TASKS}`, params)
      .pipe(map((page) => ({ ...page, items: page.items.map(toTask) })));
  }

  /** One task as it is now, by the exact id of the address; out of the viewer's scope it is a 404. */
  get(id: number | string): Observable<Task> {
    return this.api.get<EntityRecord>(`/entities/${TASKS}/${id}`, undefined, { notifyError: false }).pipe(map(toTask));
  }

  /** The subtasks of a task, in the list's order. */
  subtasks(id: number | string): Observable<Task[]> {
    const filter = JSON.stringify([{ field: 'parentTaskId', op: 'eq', value: Number(id) }]);
    return this.api
      .get<KeysetPage<EntityRecord>>(`/entities/${TASKS}`, { filter, limit: SUBTASKS_PAGE }, { notifyError: false })
      .pipe(map((page) => page.items.map(toTask)));
  }

  /** The people of a task with their parts: author, responsible person, executors, observers. */
  members(id: number | string): Observable<TaskMember[]> {
    return this.api.get<TaskMember[]>(`/tasks/${id}/members`, undefined, { notifyError: false });
  }

  /** The files attached to a task. */
  files(id: number | string): Observable<TaskFile[]> {
    return this.api.get<TaskFile[]>(`/tasks/${id}/files`, undefined, { notifyError: false });
  }

  /** Creates a task; a 422 names the fields it rejects. */
  create(body: Record<string, unknown>): Observable<Task> {
    return this.api.post<EntityRecord>(`/entities/${TASKS}`, body, { notifyError: false }).pipe(map(toTask));
  }

  /** Changes the fields of `body` from `revision`: a stale one is refused with 409 (ADR-0024). */
  patch(id: number, body: Record<string, unknown>, revision: number | undefined): Observable<Task> {
    return this.api
      .patch<EntityRecord>(`/entities/${TASKS}/${id}`, body, { notifyError: false, ifMatch: revision })
      .pipe(map(toTask));
  }

  /** Moves a task to the status with `code` from `revision`; a terminal status resolves it. */
  setStatus(id: number, code: string, revision: number | undefined): Observable<Task> {
    return this.api
      .post<EntityRecord>(
        `/entities/${TASKS}/${id}/actions/${SET_STATUS}`,
        { status: code },
        { notifyError: false, ifMatch: revision },
      )
      .pipe(map(toTask));
  }

  /** One action over the chosen tasks — `set_status` or `update` — with its parameters; failures are per task. */
  bulk(action: string, ids: number[], params: Record<string, unknown>): Observable<BulkResult> {
    return this.api.post<BulkResult>(`/entities/${TASKS}/bulk`, { action, ids, params }, { notifyError: false });
  }
}
