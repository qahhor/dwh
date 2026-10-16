import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import type { ReportState } from './entity-reports';
export type ReportExportSource =
  | { kind: 'query'; code: string; state: ReportState }
  | { kind: 'saved'; code: string; id: number }
  | { kind: 'dashboard'; id: number; viewId: number; revision: number; period: { from: string; to: string } | null };
@Injectable({ providedIn: 'root' })
export class ReportExportApi {
  private readonly api = inject(ApiService);
  download(source: ReportExportSource, lang: string): Observable<Blob> {
    if (source.kind === 'dashboard')
      return this.api.getBlob(
        `/dashboards/${source.id}/widgets/${source.viewId}/export`,
        { ...source.period, lang },
        { ifMatch: source.revision, notifyError: false },
      );
    const base = `/entities/${encodeURIComponent(source.code)}`;
    if (source.kind === 'saved')
      return this.api.getBlob(`${base}/reports/${source.id}/export`, { lang }, { notifyError: false });
    return this.api.getBlob(
      `${base}/report/export`,
      {
        dataset: source.state.dataset,
        groupBy: JSON.stringify(source.state.groupBy),
        measures: JSON.stringify(source.state.measures),
        filter: source.state.filter.length ? JSON.stringify(source.state.filter) : undefined,
        lang,
      },
      { notifyError: false },
    );
  }
}
