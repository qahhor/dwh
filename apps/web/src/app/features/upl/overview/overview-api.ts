import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../../core/services/api.service';

/** Uploads of the period by status and the rows that reached the warehouse. */
export interface UplOverviewTotals {
  uploads: number;
  received: number;
  verified: number;
  rejected: number;
  applied: number;
  rowsApplied: number;
}

/** `GET /upl/overview?days=`: everything the data overview shows about one period. */
export interface UplOverview {
  days: number;
  generatedAt: string;
  totals: UplOverviewTotals;
}

export const UPL_OVERVIEW_PERIODS = [7, 30, 90] as const;
export type UplOverviewPeriod = (typeof UPL_OVERVIEW_PERIODS)[number];

@Injectable({ providedIn: 'root' })
export class UplOverviewApi {
  private readonly api = inject(ApiService);

  get(days: UplOverviewPeriod): Observable<UplOverview> {
    return this.api.get<UplOverview>('/upl/overview', { days }, { notifyError: false });
  }
}
