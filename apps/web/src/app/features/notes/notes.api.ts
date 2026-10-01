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
  /** What a change of the record names in If-Match (plan item 3.6). */
  revision?: number;
  /** In the archive (ADR-0032 5.4): out of the list until the archive is shown, still read by id. */
  archived?: boolean;
  archivedAt?: string | null;
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

  /** One note as it is now, as when a save was refused over a newer revision; the caller shows a failure. */
  get(id: number): Observable<Note> {
    return this.api.get<Note>(`/notes/${id}`, undefined, { notifyError: false });
  }

  /** Creates a note, or updates the one with `id`; a 422 names the fields it rejects. */
  save(id: number | null, payload: Record<string, unknown>, revision?: number): Observable<Note> {
    return id === null
      ? this.api.post<Note>('/notes', payload, { notifyError: false })
      : this.api.put<Note>(`/notes/${id}`, payload, { notifyError: false, ifMatch: revision });
  }

  /** Pins or unpins the note: the request states the result, so a repeat leaves the same note. */
  setPin(id: number, pinned: boolean): Observable<Note> {
    return this.api.put<Note>(`/notes/${id}/pin`, { pinned }, { notifyError: false });
  }

  /** Moves the note to the archive or back, from the revision it was read at; a stale one is refused. */
  setArchived(id: number, archived: boolean, revision?: number): Observable<Note> {
    return this.api.put<Note>(`/notes/${id}/archived`, { archived }, { notifyError: false, ifMatch: revision });
  }

  remove(id: number): Observable<void> {
    return this.api.delete<void>(`/notes/${id}`, { notifyError: false });
  }
}
