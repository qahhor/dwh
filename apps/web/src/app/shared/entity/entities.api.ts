import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import type { FileValue } from '@core/services/field-values';
import type { ApiSchema } from '@core/api/api-schema';
import { BulkResult } from '../bulk/bulk';

/** Actions every entity declared on the server offers (ADR-0019). */
@Injectable({ providedIn: 'root' })
export class EntitiesApi {
  private readonly api = inject(ApiService);

  /** Deletes the chosen records of an entity; the result names the ones that failed and why. */
  bulkDelete(code: string, ids: number[]): Observable<BulkResult> {
    return this.api.post<BulkResult>(
      `/entities/${encodeURIComponent(code)}/bulk`,
      { action: 'delete', ids },
      { notifyError: false },
    );
  }

  /**
   * Uploads a file for a file or image field (ADR-0032 4.7): the files module checks its content and scans it; the
   * field then holds its id, and the save attaches it to the record.
   */
  uploadFile(file: File): Observable<FileValue> {
    const body = new FormData();
    body.append('file', file);
    return this.api.post<ApiSchema<'FileView'>>('/files/upload', body, { notifyError: false }).pipe(
      map((stored) => ({
        id: stored.id ?? '',
        name: stored.originalName,
        size: stored.sizeBytes,
        contentType: stored.mimeType,
      })),
    );
  }

  /** Where a record's file is read (ADR-0032 4.7): through the record, never the files list. */
  fileUrl(code: string, recordId: number, fileId: string): string {
    return `/api/v1/entities/${encodeURIComponent(code)}/${recordId}/files/${encodeURIComponent(fileId)}`;
  }
}
