import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { KeysetPage } from '@core/models/common.models';
import { ApiService } from '@core/services/api.service';

/** A field's value before and after one change. */
export interface HistoryChange {
  field: string;
  labelKey?: string | null;
  /** A custom field's own name, when it has no dictionary key (plan 10/10, item 5.0). */
  label?: string | null;
  oldValue?: unknown;
  newValue?: unknown;
}

/** One change of a record, as `GET /history/{kind}/{id}` returns it (ADR-0017). */
export interface HistoryEntry {
  id: number;
  event: 'I' | 'U' | 'D';
  changedAt: string;
  changedBy?: number | null;
  changedByName?: string | null;
  changedByLogin?: string | null;
  isApi: boolean;
  changes: HistoryChange[];
}

/** The change history of one record, from the audit log (ADR-0017). */
@Injectable({ providedIn: 'root' })
export class RecordHistoryApi {
  private readonly api = inject(ApiService);

  /** A page of changes, newest first; the history section shows a failure itself. */
  page(
    kind: string,
    recordId: number | string,
    cursor: string | null,
    limit: number,
  ): Observable<KeysetPage<HistoryEntry>> {
    return this.api.get<KeysetPage<HistoryEntry>>(
      `/history/${encodeURIComponent(kind)}/${encodeURIComponent(String(recordId))}`,
      { limit, ...(cursor ? { cursor } : {}) },
      { notifyError: false },
    );
  }
}
