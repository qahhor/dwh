import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import { AnnouncementAdminRecord, AnnouncementDraftPayload } from './announcements.models';

/** Announcement administration: every announcement, drafts, publishing and archiving. */
@Injectable({ providedIn: 'root' })
export class AnnouncementsApi {
  private readonly api = inject(ApiService);

  manageable(): Observable<AnnouncementAdminRecord[]> {
    return this.api.get<AnnouncementAdminRecord[]>('/announcements/manage');
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
