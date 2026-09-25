import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/** One export in the person's journal (ADR-0018); the file is there while `state` is `done`. */
export interface ExportItem {
  id: string;
  list: string;
  state: 'queued' | 'running' | 'done' | 'failed';
  rowsCount?: number | null;
  truncated: boolean;
  fileName?: string | null;
  sizeBytes?: number | null;
  errorCode?: string | null;
  createdAt: string;
  finishedAt?: string | null;
  expiresAt: string;
}

/** What to export: a registry list as it stands on screen. */
export interface ExportRequest {
  list: string;
  filter?: string;
  sort?: string;
  q?: string;
  columns?: string[];
  options?: Record<string, string>;
  lang?: string;
}

/** Asynchronous exports of registry lists to xlsx and the journal of them. */
@Injectable({ providedIn: 'root' })
export class ExportsService {
  private readonly api = inject(ApiService);

  /** Queues an export; failures are the caller's to show (409 EXPORT_BUSY, 422 for a bad request). */
  request(request: ExportRequest): Observable<ExportItem> {
    return this.api.post<ExportItem>('/exports', request, { notifyError: false });
  }

  journal(): Observable<ExportItem[]> {
    return this.api.get<ExportItem[]>('/exports', undefined, { notifyError: false });
  }

  /** The finished file; the browser downloads it with the session cookie. */
  fileUrl(id: string): string {
    return `/api/v1/exports/${encodeURIComponent(id)}/file`;
  }
}
