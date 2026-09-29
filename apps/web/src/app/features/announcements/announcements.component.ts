import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { TranslatePipe } from '@core/services/i18n.service';

import {
  AnnouncementState,
  AnnouncementBannerType,
  AnnouncementAdminRecord,
  AnnouncementDraftPayload,
  Confirmation,
  ApiProblem,
} from './announcements.models';

import { AnnouncementsToolbarComponent } from './components/announcements-toolbar.component';
import { AnnouncementsListComponent } from './components/announcements-list.component';
import { AnnouncementsModalsComponent } from './components/announcements-modals.component';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { AnnouncementsStore } from './announcements.store';

export type {
  AnnouncementState,
  AnnouncementBannerType,
  AnnouncementAdminRecord,
  AnnouncementDraftPayload,
  Confirmation,
  ApiProblem,
};

@Component({
  selector: 'app-announcements',
  imports: [
    UiPageHeaderComponent,
    TranslatePipe,
    SMTButtonComponent,
    AnnouncementsToolbarComponent,
    AnnouncementsListComponent,
    AnnouncementsModalsComponent,
  ],
  providers: [AnnouncementsStore],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './announcements.component.html',
  styleUrl: './announcements.component.css',
})
export class AnnouncementsComponent {
  /** State and flows of the screen; the template reads it directly. The store loads the list itself. */
  readonly store = inject(AnnouncementsStore);
}
