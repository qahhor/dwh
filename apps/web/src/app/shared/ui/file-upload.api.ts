import { HttpClient, HttpEventType } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, filter, map } from 'rxjs';

/** What the upload endpoint answers: the stored file. */
export interface UploadedFile {
  id: string;
  originalName?: string;
  sizeBytes?: number;
  mimeType?: string;
  createdAt?: string;
}

/** One step of an upload: the share sent so far, or the stored file once the server has answered. */
export type UploadStep =
  | { readonly kind: 'progress'; readonly percent: number }
  | { readonly kind: 'done'; readonly file: Partial<UploadedFile> };

/**
 * Uploads to the files module with progress events. ApiService reports no progress, so this service asks
 * HttpClient itself; the raw HTTP error reaches the caller, which shows it on the file's own row.
 */
@Injectable({ providedIn: 'root' })
export class FileUploadApi {
  private readonly http = inject(HttpClient);

  upload(file: File): Observable<UploadStep> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http
      .post<UploadedFile>('/api/v1/files/upload', formData, {
        reportProgress: true,
        observe: 'events',
        withCredentials: true,
      })
      .pipe(
        map((event): UploadStep | null => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            return { kind: 'progress', percent: Math.round((100 * event.loaded) / event.total) };
          }
          if (event.type === HttpEventType.Response) {
            return { kind: 'done', file: event.body ?? {} };
          }
          return null;
        }),
        filter((step): step is UploadStep => step !== null),
      );
  }
}
