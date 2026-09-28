import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { KeysetPage } from '../../core/models/common.models';
import { ListQuery } from '../../core/models/query-meta.models';
import { ApiService } from '../../core/services/api.service';
import { toQueryParams } from '../../core/services/query-meta.service';
import { FileDetail, StorageStats } from './files.models';

/** The file store: the list by scope, the storage counters and deleting a file. */
@Injectable({ providedIn: 'root' })
export class FilesApi {
  private readonly api = inject(ApiService);

  /** A page of the files in a scope (mine, all); the screen shows a failed page itself. */
  page(scope: string, query: ListQuery, cursor: string | null, limit: number): Observable<KeysetPage<FileDetail>> {
    return this.api.get<KeysetPage<FileDetail>>(
      '/files',
      { scope, limit, ...(cursor ? { cursor } : {}), ...toQueryParams(query) },
      { notifyError: false },
    );
  }

  storageStats(): Observable<StorageStats> {
    return this.api.get<StorageStats>('/files/storage/stats');
  }

  /** Deletes a file; `quiet` leaves the failure to the caller (a confirmation that shows it). */
  remove(id: string, quiet = false): Observable<unknown> {
    return this.api.delete(`/files/${id}`, quiet ? { notifyError: false } : {});
  }
}
