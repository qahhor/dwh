import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  SearchJobPage,
  SearchJobReceipt,
  SearchJobStatus,
  SearchManagementStatus,
  SearchPreviewRequest,
  SearchPreviewResult,
  SearchRetryJobRequest,
  SearchSettingsSnapshot,
  SearchStartJobRequest,
} from '../models/search-management.models';
import { SearchCategory } from '../models/search.models';
import { ApiService } from './api.service';

@Injectable({ providedIn: 'root' })
export class SearchManagementService {
  private readonly api = inject(ApiService);
  private readonly localErrors = { notifyError: false } as const;

  status(): Observable<SearchManagementStatus> {
    return this.api.get('/search/status', undefined, this.localErrors);
  }

  /** The entities the administrator may search, with the names of their searched fields (ADR-0032, 10.3). */
  categories(): Observable<SearchCategory[]> {
    return this.api.get('/search/entities', undefined, this.localErrors);
  }

  settings(): Observable<SearchSettingsSnapshot> {
    return this.api.get('/search/settings', undefined, this.localErrors);
  }

  save(request: SearchSettingsSnapshot): Observable<SearchSettingsSnapshot> {
    return this.api.put('/search/settings', request, this.localErrors);
  }

  preview(request: SearchPreviewRequest): Observable<SearchPreviewResult> {
    return this.api.post('/search/preview', request, this.localErrors);
  }

  startJob(request: SearchStartJobRequest): Observable<SearchJobReceipt> {
    return this.api.post('/search/jobs', request, this.localErrors);
  }

  job(id: string): Observable<SearchJobStatus> {
    return this.api.get(`/search/jobs/${id}`, undefined, this.localErrors);
  }

  jobs(limit = 20, cursor?: string): Observable<SearchJobPage> {
    return this.api.get('/search/jobs', { limit, cursor }, this.localErrors);
  }

  cancel(id: string): Observable<SearchJobReceipt> {
    return this.api.post(`/search/jobs/${id}/cancel`, undefined, this.localErrors);
  }

  retry(id: string, request: SearchRetryJobRequest): Observable<SearchJobReceipt> {
    return this.api.post(`/search/jobs/${id}/retry`, request, this.localErrors);
  }
}
