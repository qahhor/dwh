import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ListQuery } from '@core/models/query-meta.models';
import { ApiService } from '@core/services/api.service';
import { toQueryParams } from '@core/services/query-meta.service';
import { AuditPage, AuditRecord, AuditStats, SecurityEventRecord } from './audit.models';

/** The audit endpoints, typed: the change log, the security events and their counters. */
@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly api = inject(ApiService);

  /** A page of the change log: the screen's own filters, then the view's filter and order. */
  logs(filters: Record<string, string | undefined>, query: ListQuery, cursor: string | null, limit: number) {
    return this.page<AuditRecord>('/audit/logs', filters, query, cursor, limit);
  }

  securityEvents(filters: Record<string, string | undefined>, query: ListQuery, cursor: string | null, limit: number) {
    return this.page<SecurityEventRecord>('/audit/security-events', filters, query, cursor, limit);
  }

  stats(): Observable<AuditStats> {
    return this.api.get<AuditStats>('/audit/stats');
  }

  private page<T>(
    path: string,
    filters: Record<string, string | undefined>,
    query: ListQuery,
    cursor: string | null,
    limit: number,
  ): Observable<AuditPage<T>> {
    return this.api.get<AuditPage<T>>(path, {
      ...filters,
      ...toQueryParams(query),
      limit,
      cursor: cursor ?? undefined,
    });
  }
}
