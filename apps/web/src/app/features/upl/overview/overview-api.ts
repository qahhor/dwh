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

export type UplFreshnessState = 'fresh' | 'due' | 'overdue' | 'never' | 'adhoc';

/** How fresh a source's data is, from its periodicity and deadline. */
export interface UplSourceFreshness {
  sourceId: number;
  code: string;
  name: string;
  periodicity: string;
  state: UplFreshnessState;
  lastPeriodTo?: string | null;
  lastAppliedAt?: string | null;
  expectedPeriodTo?: string | null;
  dueBy?: string | null;
}

/** Something to act on: an overdue source, a rejected upload not replaced, a checked upload waiting. */
export interface UplAttentionItem {
  kind: 'overdue' | 'rejected' | 'waiting';
  sourceId: number;
  sourceCode: string;
  sourceName: string;
  packageId?: string | null;
  fileName?: string | null;
  periodFrom?: string | null;
  periodTo?: string | null;
  uploadedAt?: string | null;
  dueBy?: string | null;
  daysLate?: number | null;
}

/** Uploads of one UTC day by outcome. */
export interface UplOverviewDay {
  day: string;
  applied: number;
  rejected: number;
  other: number;
}

/** `GET /upl/overview?days=`: everything the data overview shows about one period. */
export interface UplOverview {
  days: number;
  generatedAt: string;
  totals: UplOverviewTotals;
  /** The same number of days just before, for the change of each figure. */
  previous: UplOverviewTotals;
  /** Every day of the period, oldest first. */
  daily: UplOverviewDay[];
  freshness: UplSourceFreshness[];
  attention: UplAttentionItem[];
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
