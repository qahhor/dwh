import { Injectable, inject } from '@angular/core';
import { map, type Observable } from 'rxjs';
import type { ApiSchema } from '@core/api/api-schema';
import type { KeysetPage } from '@core/models/common.models';
import { ApiService } from '@core/services/api.service';
import { reportResult, type ReportResult } from '@shared/entity/report/entity-reports';
import type { DashboardSummary, DashboardDetail, DashboardWrite, DashboardPeriod } from './dashboard.models';
@Injectable({ providedIn: 'root' })
export class DashboardApi {
  private readonly api = inject(ApiService);
  list(cursor?: string | null, limit = 20): Observable<KeysetPage<DashboardSummary>> {
    return this.api.get('/dashboards', { limit, cursor }, { notifyError: false });
  }
  get(id: number): Observable<DashboardDetail> {
    return this.api.get(`/dashboards/${id}`, undefined, { notifyError: false });
  }
  create(body: DashboardWrite): Observable<DashboardDetail> {
    return this.api.post('/dashboards', body, { notifyError: false });
  }
  update(id: number, body: DashboardWrite, revision: number): Observable<DashboardDetail> {
    return this.api.put(`/dashboards/${id}`, body, { ifMatch: revision, notifyError: false });
  }
  remove(id: number, revision: number): Observable<void> {
    return this.api.delete(`/dashboards/${id}`, { ifMatch: revision, notifyError: false });
  }
  result(id: number, viewId: number, revision: number, period: DashboardPeriod | null): Observable<ReportResult> {
    return this.api
      .get<ApiSchema<'QueryAggregateResult'>>(
        `/dashboards/${id}/widgets/${viewId}/result`,
        period ? { ...period } : undefined,
        { ifMatch: revision, notifyError: false },
      )
      .pipe(map(reportResult));
  }
}
