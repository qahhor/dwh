import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import { ListQuery } from '@core/models/query-meta.models';
import { toQueryParams } from '@core/services/query-meta.service';
import { KeysetPage } from '@core/models/common.models';
import { UplApiService, UplSource, UplSourceItem } from '../upl-api';

export type UplPackageStatus = 'received' | 'verified' | 'applying' | 'rejected' | 'applied';

/** Parameters of the Russian error text: substituted into the braces of the `upl.err.*` key. */
export type UplPackageParams = Record<string, string | number>;

export interface UplPackageItem {
  id: string;
  sourceId: number;
  sourceCode: string;
  sourceName: string;
  formatVersion: number;
  periodFrom: string;
  periodTo: string;
  fileName: string;
  fileSizeBytes: number;
  /** Absent when the viewer may not see who uploaded (a field right on the server, ADR-0016 2.9). */
  uploadedBy?: string;
  uploadedAt: string;
  status: UplPackageStatus;
  rowsTotal: number | null;
  rowsAccepted: number | null;
  rowsRejected: number | null;
  errorsTotal: number | null;
  rejectCode: string | null;
  rejectParams: UplPackageParams | null;
  loadId: number | null;
  rawRows: number | null;
}

export interface UplPackageErrorItem {
  sheet: string | null;
  rowNo: number | null;
  columnName: string | null;
  value: string | null;
  code: string;
  params: UplPackageParams | null;
}

export interface UplPackageErrors {
  total: number;
  shown: number;
  items: UplPackageErrorItem[];
}

export interface UplPackageUpload {
  sourceId: number;
  periodFrom: string;
  periodTo: string;
  file: File;
}

const PACKAGES = '/upl/packages';
/** Sources for the form are read in portions: the source list returns at most 200 records at a time. */

@Injectable({ providedIn: 'root' })
export class UplPackagesApiService {
  private readonly api = inject(ApiService);
  private readonly upl = inject(UplApiService);

  /** A page of loads; `query` is the filter, sort and search of the field registry (`query-meta/upl.packages`). */
  list(limit = 50, cursor?: string | null, query?: ListQuery | null): Observable<KeysetPage<UplPackageItem>> {
    const params = { limit, ...(cursor ? { cursor } : {}), ...toQueryParams(query) };
    return this.api.get<KeysetPage<UplPackageItem>>(PACKAGES, params, { notifyError: false });
  }

  /** `multipart/form-data`: the browser sets the header itself; setting it by hand loses the part boundary. */
  upload(request: UplPackageUpload): Observable<UplPackageItem> {
    const form = new FormData();
    form.append('sourceId', String(request.sourceId));
    form.append('periodFrom', request.periodFrom);
    form.append('periodTo', request.periodTo);
    form.append('file', request.file, request.file.name);
    return this.api.post<UplPackageItem>(PACKAGES, form, { notifyError: false });
  }

  /** One upload by its id, for a card opened from a link. */
  get(id: string): Observable<UplPackageItem> {
    return this.api.get<UplPackageItem>(`${PACKAGES}/${encodeURIComponent(id)}`, undefined, { notifyError: false });
  }

  errors(id: string): Observable<UplPackageErrors> {
    return this.api.get<UplPackageErrors>(`${PACKAGES}/${encodeURIComponent(id)}/errors`, undefined, {
      notifyError: false,
    });
  }

  /**
   * Queues the apply of a verified load (plan 10/10, item 3.9): the answer is 202 with an applying package; the outcome
   * (applied, or rejected by the system with the reconciliation reason) is read through {@link get}.
   */
  apply(id: string): Observable<UplPackageItem> {
    return this.api.post<UplPackageItem>(`${PACKAGES}/${id}/apply`, null, { notifyError: false });
  }

  /** A page of sources for the search in the form: a substring of the code or the name (`q`). */
  searchSources(query: string, cursor: string | null, pageSize: number): Observable<KeysetPage<UplSourceItem>> {
    return this.upl.listSources(pageSize, cursor, { search: query });
  }

  /** One source, to show as selected the one created from the form. */
  source(id: string): Observable<UplSource> {
    return this.upl.getSource(id);
  }
}
