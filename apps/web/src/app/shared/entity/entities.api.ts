import { Injectable, inject } from '@angular/core';
import { EMPTY, Observable, expand, map, reduce } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import type { FileValue } from '@core/services/field-values';
import type { ApiSchema } from '@core/api/api-schema';
import type { KeysetPage } from '@core/models/common.models';
import type { ListQuery } from '@core/models/query-meta.models';
import { toQueryParams } from '@core/services/query-meta.service';
import { BulkResult } from '../bulk/bulk';

/**
 * A record of any entity on the general runtime (ADR-0032 6.2): the system properties, the viewer's fields by key,
 * custom fields in `attributes`, and the actions the viewer may take on this record now.
 */
export interface EntityRecord {
  id: number;
  /** What a change of the record names in If-Match (ADR-0024). */
  revision?: number;
  /** In the archive (ADR-0032 5.4): out of the list until the archive is shown, still read by id. */
  archived?: boolean;
  attributes?: Record<string, unknown>;
  /** What this viewer may do with this record: `update`, `archive`, `delete`, an action's code. */
  actions?: string[];
  /** Resolved relation labels (ADR-0032 4.6; plan 10/10, item 5.4). */
  labels?: Record<string, unknown>;
  [key: string]: unknown;
}

/**
 * What every entity declared on the server offers (ADR-0019, ADR-0032 6.1): its records through the general runtime
 * `/api/v1/entities/{code}`, bulk actions, and the files of its file fields. A screen shows its own message for each
 * failure, so none of these raises the general error toast.
 */
@Injectable({ providedIn: 'root' })
export class EntitiesApi {
  private readonly api = inject(ApiService);

  /** A page of the entity's list: its search, filter and sort (ADR-0016), from `cursor`. */
  page(code: string, query: ListQuery, cursor: string | null, limit: number): Observable<KeysetPage<EntityRecord>> {
    const params = { limit, ...(cursor ? { cursor } : {}), ...toQueryParams(query) };
    return this.api.get<KeysetPage<EntityRecord>>(path(code), params, { notifyError: false });
  }

  /**
   * Every record of a short list — a reference entity holds at most 500 items (ADR-0032 4.5) — in its default order,
   * page after page of the largest size.
   */
  all(code: string, query: ListQuery = {}): Observable<EntityRecord[]> {
    const page = (cursor: string | null) => this.page(code, query, cursor, ALL_PAGE);
    return page(null).pipe(
      expand((current) => (current.hasMore && current.nextCursor ? page(current.nextCursor) : EMPTY)),
      reduce((items, current) => items.concat(current.items), [] as EntityRecord[]),
    );
  }

  /** One record as it is now; a record out of the viewer's scope is a 404, like one that does not exist. */
  get(code: string, id: number): Observable<EntityRecord> {
    return this.api.get<EntityRecord>(path(code, id), undefined, { notifyError: false });
  }

  /** Creates a record; a 422 names the fields it rejects. */
  create(code: string, body: Record<string, unknown>): Observable<EntityRecord> {
    return this.api.post<EntityRecord>(path(code), body, { notifyError: false });
  }

  /** Changes the record from `revision`: a stale one is refused with 409, none with 428 (ADR-0024). */
  patch(
    code: string,
    id: number,
    body: Record<string, unknown>,
    revision: number | undefined,
  ): Observable<EntityRecord> {
    return this.api.patch<EntityRecord>(path(code, id), body, { notifyError: false, ifMatch: revision });
  }

  /** Deletes the record, when the entity declares deleting. */
  remove(code: string, id: number): Observable<void> {
    return this.api.delete<void>(path(code, id), { notifyError: false });
  }

  /** Moves the record to the archive or back, from `revision` (ADR-0032 5.4). */
  setArchived(code: string, id: number, archived: boolean, revision: number | undefined): Observable<EntityRecord> {
    return this.api.put<EntityRecord>(
      `${path(code, id)}/archived`,
      { archived },
      { notifyError: false, ifMatch: revision },
    );
  }

  /** Runs a record's action from `revision` (ADR-0032 6.7); `params` is the action's body. */
  action(
    code: string,
    id: number,
    action: string,
    revision: number | undefined,
    params: Record<string, unknown> = {},
  ): Observable<EntityRecord> {
    return this.api.post<EntityRecord>(`${path(code, id)}/actions/${encodeURIComponent(action)}`, params, {
      notifyError: false,
      ifMatch: revision,
    });
  }

  /** Deletes the chosen records of an entity; the result names the ones that failed and why. */
  bulkDelete(code: string, ids: number[]): Observable<BulkResult> {
    return this.bulk(code, 'delete', ids);
  }

  /** Moves the chosen records of an archivable entity to the archive (ADR-0032 5.4). */
  bulkArchive(code: string, ids: number[]): Observable<BulkResult> {
    return this.bulk(code, 'archive', ids);
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

  private bulk(code: string, action: string, ids: number[]): Observable<BulkResult> {
    return this.api.post<BulkResult>(`${path(code)}/bulk`, { action, ids }, { notifyError: false });
  }
}

/** The largest page of a list (ADR-0016). */
const ALL_PAGE = 200;

function path(code: string, id?: number): string {
  const base = `/entities/${encodeURIComponent(code)}`;
  return id === undefined ? base : `${base}/${id}`;
}
