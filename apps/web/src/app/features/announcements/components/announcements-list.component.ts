import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { AnnouncementAdminRecord, AnnouncementBannerType, AnnouncementState } from '../announcements.models';

@Component({
  selector: 'app-announcements-list',
  imports: [TranslatePipe, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="announcement-list" aria-live="polite">
      @for (item of items(); track trackById($index, item)) {
        <article class="announcement-card" [attr.data-state]="item.state">
          <div
            class="card-marker"
            [class]="'card-marker marker-' + item.bannerType.toLowerCase()"
            aria-hidden="true"
          ></div>
          <div class="card-main">
            <div class="card-heading">
              <div>
                <div class="card-meta">
                  <span class="state-badge" [class]="'state-badge state-' + item.state.toLowerCase()">
                    <span class="material-symbols-outlined badge-icon" aria-hidden="true">
                      {{
                        item.state === 'PUBLISHED' ? 'check_circle' : item.state === 'DRAFT' ? 'edit_note' : 'archive'
                      }}
                    </span>
                    {{ stateLabel(item.state) }}
                  </span>
                  <span class="type-badge" [class]="'type-badge type-' + item.bannerType.toLowerCase()">
                    <span class="material-symbols-outlined badge-icon" aria-hidden="true">
                      {{
                        item.bannerType === 'CRITICAL' ? 'error' : item.bannerType === 'WARNING' ? 'warning' : 'info'
                      }}
                    </span>
                    {{ bannerLabel(item.bannerType) }}
                  </span>
                  @if (item.id === activeId()) {
                    <span class="active-badge">
                      <span class="material-symbols-outlined badge-icon" aria-hidden="true">sensors</span>
                      {{ 'announcements.list.active' | t }}
                    </span>
                  }
                  <span class="id-tag">№{{ item.id }}</span>
                </div>
                <h2>{{ localizedValue(item.titleJson) || ('announcements.without_title' | t) }}</h2>
              </div>
              <div class="card-timestamps">
                @if (item.publishedAt) {
                  <span class="meta-time">
                    {{ 'announcements.list.published_at' | t }} {{ item.publishedAt | date: 'dd.MM.yyyy, HH:mm' }}
                  </span>
                }
                @if (item.archivedAt) {
                  <span class="meta-time">
                    {{ 'announcements.list.archived_at' | t }} {{ item.archivedAt | date: 'dd.MM.yyyy, HH:mm' }}
                  </span>
                }
                <time [attr.datetime]="item.modifiedAt">{{ item.modifiedAt | date: 'dd.MM.yyyy, HH:mm' }}</time>
              </div>
            </div>
            <p class="announcement-body">{{ localizedValue(item.bodyJson) || ('announcements.empty_body' | t) }}</p>
            <div class="card-actions">
              @if (item.state === 'DRAFT' && canUpdate()) {
                <button type="button" class="text-action edit-action" (click)="edit.emit(item)">
                  <span class="material-symbols-outlined" aria-hidden="true">edit</span>
                  {{ 'common.edit' | t }}
                </button>
              }
              @if (item.state === 'DRAFT' && canPublish()) {
                <button
                  type="button"
                  class="text-action publish-action"
                  [disabled]="!hasPublishableContent(item)"
                  [attr.aria-describedby]="!hasPublishableContent(item) ? 'invalid-draft-' + item.id : null"
                  (click)="publish.emit(item)"
                >
                  <span class="material-symbols-outlined" aria-hidden="true">publish</span>
                  {{ 'announcements.list.publish' | t }}
                </button>
              }
              @if (item.state === 'DRAFT' && !hasPublishableContent(item)) {
                <span class="invalid-hint" [id]="'invalid-draft-' + item.id">
                  {{ 'announcements.list.fill_ru_title_and_text' | t }}
                </span>
              }
              @if (item.state === 'PUBLISHED' && canArchive()) {
                <button type="button" class="text-action archive-action" (click)="archive.emit(item)">
                  <span class="material-symbols-outlined" aria-hidden="true">archive</span>
                  {{ 'announcements.list.archive' | t }}
                </button>
              }
            </div>
          </div>
        </article>
      }
    </div>
  `,
  styleUrl: './announcements-list.component.css',
})
export class AnnouncementsListComponent {
  private readonly uiI18n = inject(I18nService);

  readonly items = input.required<AnnouncementAdminRecord[]>();
  readonly activeId = input<number | null>(null);
  readonly canUpdate = input<boolean>(false);
  readonly canPublish = input<boolean>(false);
  readonly canArchive = input<boolean>(false);

  readonly edit = output<AnnouncementAdminRecord>();
  readonly publish = output<AnnouncementAdminRecord>();
  readonly archive = output<AnnouncementAdminRecord>();

  localizedValue(values: Record<string, string> | null | undefined): string {
    if (!values) return '';
    const current = this.uiI18n.currentLang();
    return values[current] ?? values['ru'] ?? Object.values(values).find((value) => value?.trim().length > 0) ?? '';
  }

  stateLabel(state: AnnouncementState): string {
    return (
      {
        DRAFT: this.uiI18n.translate('announcements.list.draft'),
        PUBLISHED: this.uiI18n.translate('announcements.list.published'),
        ARCHIVED: this.uiI18n.translate('projects.common.archive'),
      } as const
    )[state];
  }

  bannerLabel(type: AnnouncementBannerType): string {
    return (
      {
        INFO: this.uiI18n.translate('announcements.common.level_info'),
        WARNING: this.uiI18n.translate('announcements.common.level_warning'),
        CRITICAL: this.uiI18n.translate('announcements.common.level_critical'),
      } as const
    )[type];
  }

  hasPublishableContent(item: AnnouncementAdminRecord): boolean {
    return (
      this.localizedValue(item.titleJson).trim().length > 0 && this.localizedValue(item.bodyJson).trim().length > 0
    );
  }

  trackById(_index: number, item: AnnouncementAdminRecord): number {
    return item.id;
  }
}
