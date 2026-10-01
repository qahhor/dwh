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
  /** Absent when the note has no text. */
  contentMd?: string;
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
  /** What the viewer may do with this note (ADR-0032 6.2). */
  actions?: string[];
}

/**
 * The note endpoints of the general entity runtime (ADR-0032 6.1: /api/v1/entities/ms.notes), typed. The screen
 * shows its own message for each failure, so none of these raises the general error toast.
 */
@Injectable({ providedIn: 'root' })
export class NotesApi {
  private readonly api = inject(ApiService);

  /** A page of the registry list ms.notes: pinned first, then the latest. */
  page(query: ListQuery, cursor: string | null = null): Observable<KeysetPage<Note>> {
    const params = { limit: PAGE_SIZE, ...(cursor ? { cursor } : {}), ...toQueryParams(query) };
    return this.api.get<KeysetPage<Note>>('/entities/ms.notes', params, { notifyError: false });
  }

  /** One note as it is now, as when a save was refused over a newer revision; the caller shows a failure. */
  get(id: number): Observable<Note> {
    return this.api.get<Note>(`/entities/ms.notes/${id}`, undefined, { notifyError: false });
  }

  /** Creates a note, or changes the one with `id` from `revision`; a 422 names the fields it rejects. */
  save(id: number | null, payload: Record<string, unknown>, revision?: number): Observable<Note> {
    return id === null
      ? this.api.post<Note>('/entities/ms.notes', payload, { notifyError: false })
      : this.api.patch<Note>(`/entities/ms.notes/${id}`, payload, { notifyError: false, ifMatch: revision });
  }

  /** Pins or unpins the note: a change of `isPinned` from the revision it was read at. */
  setPin(id: number, pinned: boolean, revision?: number): Observable<Note> {
    return this.api.patch<Note>(
      `/entities/ms.notes/${id}`,
      { isPinned: pinned },
      { notifyError: false, ifMatch: revision },
    );
  }

  /** Moves the note to the archive or back, from the revision it was read at; a stale one is refused. */
  setArchived(id: number, archived: boolean, revision?: number): Observable<Note> {
    return this.api.put<Note>(
      `/entities/ms.notes/${id}/archived`,
      { archived },
      { notifyError: false, ifMatch: revision },
    );
  }

  remove(id: number): Observable<void> {
    return this.api.delete<void>(`/entities/ms.notes/${id}`, { notifyError: false });
  }
}
