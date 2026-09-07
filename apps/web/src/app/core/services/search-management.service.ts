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
  SearchStartJobRequest
} from '../models/search-management.models';
import { ApiService } from './api.service';

@Injectable({ providedIn: 'root' })
export class SearchManagementService {
  private readonly api = inject(ApiService);
  private readonly localErrors = { notifyError: false } as const;

  status(): Observable<SearchManagementStatus> {
    return this.api.get('/search/status', undefined, this.localErrors);
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
