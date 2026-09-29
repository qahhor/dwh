import { Injectable, inject } from '@angular/core';
import { EMPTY, Observable, expand, reduce } from 'rxjs';
import type { KeysetPage } from '@core/models/common.models';
import { ApiService } from '@core/services/api.service';
import { AnnouncementAdminRecord, AnnouncementDraftPayload } from './announcements.models';

const MANAGE_PAGE = 200;

/** Announcement administration: every announcement, drafts, publishing and archiving. */
@Injectable({ providedIn: 'root' })
export class AnnouncementsApi {
  private readonly api = inject(ApiService);

  /**
   * Every announcement, last changed first. The server answers a page of at most 200 (plan item 3.5); the screen
   * filters and counts them by state, so the pages are read one after another until the last.
   */
  manageable(): Observable<AnnouncementAdminRecord[]> {
    const page = (cursor: string | null) =>
      this.api.get<KeysetPage<AnnouncementAdminRecord>>(
        '/announcements/manage',
        cursor ? { limit: MANAGE_PAGE, cursor } : { limit: MANAGE_PAGE },
      );
    return page(null).pipe(
      expand((current) => (current.hasMore && current.nextCursor ? page(current.nextCursor) : EMPTY)),
      reduce((all, current) => [...all, ...(current.items ?? [])], [] as AnnouncementAdminRecord[]),
    );
  }

  /** Creates a draft, or saves the one with `id`; the lock version refuses a save over a newer one. */
  save(id: number | null, draft: AnnouncementDraftPayload): Observable<AnnouncementAdminRecord> {
    return id === null
      ? this.api.post<AnnouncementAdminRecord>('/announcements', draft)
      : this.api.put<AnnouncementAdminRecord>(`/announcements/${id}`, draft);
  }

  /** Publishes or archives an announcement at the lock version the person saw. */
  transition(id: number, action: 'publish' | 'archive', lockVersion: number): Observable<AnnouncementAdminRecord> {
    return this.api.post<AnnouncementAdminRecord>(`/announcements/${id}/${action}`, { lockVersion });
  }
}
