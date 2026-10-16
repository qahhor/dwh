import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import type { KeysetPage } from '@core/models/common.models';
import { ApiService } from '@core/services/api.service';
import type { ApiSchema } from '@core/api/api-schema';
import type { ReportState } from './entity-reports';
export type ReportViewItem = Omit<ApiSchema<'ReportViewItem'>, 'state'> & { state: ReportState };
export type ReportSharing = ApiSchema<'SharingResponse'>;
export type ReportShareRole = ApiSchema<'SharingRole'>;
@Injectable({ providedIn: 'root' })
export class ReportCatalogApi {
  private readonly api = inject(ApiService);
  list(listCode?: string, cursor?: string | null, limit = 20): Observable<KeysetPage<ReportViewItem>> {
    return this.api.get('/report-views', { listCode, limit, cursor }, { notifyError: false });
  }
  sharing(path: string): Observable<ReportSharing> {
    return this.api.get(`${path}/sharing`, undefined, { notifyError: false });
  }
  share(path: string, roleIds: number[], revision: number): Observable<ReportSharing> {
    return this.api.put(`${path}/sharing`, { roleIds }, { ifMatch: revision, notifyError: false });
  }
  roles(cursor?: string | null, limit = 20): Observable<KeysetPage<ReportShareRole>> {
    return this.api.get('/report-sharing/roles', { limit, cursor }, { notifyError: false });
  }
}
