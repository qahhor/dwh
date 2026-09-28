import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { KeysetPage } from '@core/models/common.models';
import { ListQuery } from '@core/models/query-meta.models';
import { ApiService } from '@core/services/api.service';
import { toQueryParams } from '@core/services/query-meta.service';

/** The note entity (MsNoteEntity on the server): its form, rules, list and the viewer's actions. */
export const NOTE_ENTITY = 'ms.notes';

const PAGE_SIZE = 50;

export interface Note {
  id: number;
  title: string;
  contentMd: string;
  color: string;
  isPinned: boolean;
  /** Custom field values by attribute name. */
  attributes: Record<string, unknown>;
  createdBy: number;
  createdAt: string;
  modifiedAt: string;
}

/**
 * The note endpoints, typed. The screen shows its own message for each failure, so none of these
 * raises the general error toast.
 */
@Injectable({ providedIn: 'root' })
export class NotesApi {
  private readonly api = inject(ApiService);

  /** A page of the registry list ms.notes: pinned first, then the latest. */
  page(query: ListQuery, cursor: string | null = null): Observable<KeysetPage<Note>> {
    const params = { limit: PAGE_SIZE, ...(cursor ? { cursor } : {}), ...toQueryParams(query) };
    return this.api.get<KeysetPage<Note>>('/notes', params, { notifyError: false });
  }

  /** Creates a note, or updates the one with `id`; a 422 names the fields it rejects. */
  save(id: number | null, payload: Record<string, unknown>): Observable<Note> {
    return id === null
      ? this.api.post<Note>('/notes', payload, { notifyError: false })
      : this.api.put<Note>(`/notes/${id}`, payload, { notifyError: false });
  }

  /** Pins an unpinned note and unpins a pinned one. */
  togglePin(id: number): Observable<Note> {
    return this.api.post<Note>(`/notes/${id}/pin`, {}, { notifyError: false });
  }

  remove(id: number): Observable<void> {
    return this.api.delete<void>(`/notes/${id}`, { notifyError: false });
  }
}
