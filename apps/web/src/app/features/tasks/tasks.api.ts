import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { KeysetPage } from '@core/models/common.models';
import { ListQuery } from '@core/models/query-meta.models';
import { Task } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { toQueryParams } from '@core/services/query-meta.service';
import { BulkResult } from '@shared/bulk/bulk';

/** Tasks: the list and bulk changes; projects are picked through LookupSources.projects. */
@Injectable({ providedIn: 'root' })
export class TasksApi {
  private readonly api = inject(ApiService);

  /** A page of tasks: the screen's own filters and paging, then the view's filter, order and search. */
  page(params: Record<string, unknown>, query: ListQuery): Observable<KeysetPage<Task>> {
    return this.api.get<KeysetPage<Task>>('/tasks', { ...params, ...toQueryParams(query) });
  }

  /** One action over the chosen tasks; the result names the ones that failed and why. */
  bulk(action: string, ids: number[], params: Record<string, unknown>): Observable<BulkResult> {
    return this.api.post<BulkResult>('/tasks/bulk', { action, ids, params }, { notifyError: false });
  }
}
