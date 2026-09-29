import { Injectable, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { EMPTY, catchError, finalize, tap } from 'rxjs';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { AnnouncementsApi } from './announcements.api';
import {
  AnnouncementAdminRecord,
  AnnouncementBannerType,
  AnnouncementDraftPayload,
  ApiProblem,
  Confirmation,
} from './announcements.models';

export type AnnouncementStatusFilter = 'ALL' | 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

/**
 * The announcements screen's state: the list and its filters, the draft being
 * edited, and the save, publish and archive flows. Provided by the screen, so
 * it lives as long as the screen does.
 */
@Injectable()
export class AnnouncementsStore {
  private readonly announcementsApi = inject(AnnouncementsApi);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

  /** A failed read keeps the list on screen; a save or a transition updates it here without a new request. */
  readonly announcements = linkedSignal<AnnouncementAdminRecord[] | null | undefined, AnnouncementAdminRecord[]>({
    source: () => (this.list.hasValue() ? (this.list.value() ?? null) : undefined),
    computation: (records, previous) => (records === undefined ? (previous?.value ?? []) : (records ?? [])),
  });
  readonly operationError = signal<string | null>(null);
  readonly isSaving = signal(false);
  readonly isEditorOpen = signal(false);

  readonly statusFilter = signal<AnnouncementStatusFilter>('ALL');
  readonly searchQuery = signal('');
  readonly bannerType = signal<AnnouncementBannerType>('INFO');

  readonly draftTitles = signal<Record<string, string>>({ ru: '' });
  readonly draftBodies = signal<Record<string, string>>({ ru: '' });
  readonly editingId = signal<number | null>(null);

  /** Bumped to read the list again; a new value cancels a read still in flight. */
  private readonly listRevision = signal(0);

  readonly loadError = computed(() => this.list.error() !== undefined);

  readonly draftCount = computed(() => this.announcements().filter((a) => a.state === 'DRAFT').length);

  readonly publishedCount = computed(() => this.announcements().filter((a) => a.state === 'PUBLISHED').length);

  readonly archivedCount = computed(() => this.announcements().filter((a) => a.state === 'ARCHIVED').length);

  readonly activeAnnouncementId = computed(() => {
    const published = this.announcements().filter((a) => a.state === 'PUBLISHED');
    return published.length > 0 ? published[0].id : null;
  });

  readonly filteredAnnouncements = computed(() => {
    let list = this.announcements();
    const filter = this.statusFilter();
    if (filter !== 'ALL') {
      list = list.filter((a) => a.state === filter);
    }
    const q = this.searchQuery().trim().toLowerCase();
    if (q) {
      list = list.filter((a) => {
        const title = this.localizedValue(a.titleJson).toLowerCase();
        const body = this.localizedValue(a.bodyJson).toLowerCase();
        const idStr = String(a.id);
        return title.includes(q) || body.includes(q) || idStr.includes(q);
      });
    }
    return list;
  });

  /** Starts with the store, that is with the screen. */
  private readonly list = rxResource({ params: this.listRevision, stream: () => this.announcementsApi.manageable() });
  readonly isLoading = this.list.isLoading;

  private editingLockVersion: number | null = null;

  setStatusFilter(filter: AnnouncementStatusFilter): void {
    this.statusFilter.set(filter);
  }

  resetFilters(): void {
    this.statusFilter.set('ALL');
    this.searchQuery.set('');
  }

  loadAnnouncements(): void {
    this.listRevision.update((revision) => revision + 1);
  }

  canCreate(): boolean {
    return this.permissions.canCreate('platform.announcements');
  }

  canUpdate(): boolean {
    return this.permissions.canUpdate('platform.announcements');
  }

  canPublish(): boolean {
    return this.permissions.hasPermission('platform.announcements', 'publish');
  }

  canArchive(): boolean {
    return this.permissions.hasPermission('platform.announcements', 'archive');
  }

  openCreate(): void {
    this.editingId.set(null);
    this.editingLockVersion = null;
    this.draftTitles.set({ ru: '' });
    this.draftBodies.set({ ru: '' });
    this.bannerType.set('INFO');
    this.operationError.set(null);
    this.isEditorOpen.set(true);
  }

  openEdit(item: AnnouncementAdminRecord): void {
    this.editingId.set(item.id);
    this.editingLockVersion = item.lockVersion;
    const titles = { ...item.titleJson };
    const bodies = { ...item.bodyJson };
    if (!titles['ru']) titles['ru'] = '';
    if (!bodies['ru']) bodies['ru'] = '';
    this.draftTitles.set(titles);
    this.draftBodies.set(bodies);
    this.bannerType.set(item.bannerType);
    this.operationError.set(null);
    this.isEditorOpen.set(true);
  }

  closeEditor(): void {
    if (!this.isSaving()) {
      this.isEditorOpen.set(false);
    }
  }

  isDraftValid(): boolean {
    const title = this.titleRu();
    const body = this.bodyRu();
    return title.trim().length > 0 && title.length <= 10_000 && body.trim().length > 0 && body.length <= 10_000;
  }

  hasPublishableContent(item: AnnouncementAdminRecord): boolean {
    return (
      this.localizedValue(item.titleJson).trim().length > 0 && this.localizedValue(item.bodyJson).trim().length > 0
    );
  }

  saveDraft(): void {
    if (!this.isDraftValid() || this.isSaving()) {
      return;
    }
    const titleJson = trimmedValues(this.draftTitles());
    titleJson['ru'] = this.titleRu().trim();
    const bodyJson = trimmedValues(this.draftBodies());
    bodyJson['ru'] = this.bodyRu().trim();

    const payload: AnnouncementDraftPayload = {
      titleJson,
      bodyJson,
      bannerType: this.bannerType(),
      lockVersion: this.editingLockVersion,
    };
    this.isSaving.set(true);
    this.operationError.set(null);
    this.announcementsApi.save(this.editingId(), payload).subscribe({
      next: (saved) => {
        this.upsert(saved);
        this.isSaving.set(false);
        this.isEditorOpen.set(false);
        this.toast.success(
          this.editingId() === null
            ? this.uiI18n.translate('announcements.chernovik_sozdan')
            : this.uiI18n.translate('announcements.chernovik_sohranen'),
        );
      },
      error: (problem: ApiProblem) => {
        this.isSaving.set(false);
        this.handleMutationError(problem);
      },
    });
  }

  requestConfirmation(action: Confirmation['action'], announcement: AnnouncementAdminRecord): void {
    if (action === 'publish' && !this.hasPublishableContent(announcement)) {
      return;
    }
    this.operationError.set(null);
    const t = (key: string) => this.uiI18n.translate(key);
    const archive = action === 'archive';
    const title = this.localizedValue(announcement.titleJson);
    this.modal
      .confirm({
        title: t(archive ? 'announcements.arhivirovat_obyavlenie' : 'announcements.opublikovat_obyavlenie'),
        message: `«${title}»\n${t(archive ? 'announcements.obyavlenie_ischeznet_u_polzovateley_i_ostanetsya' : 'announcements.posle_publikacii_obyavlenie_uvidyat_polzovateli_')}`,
        yesLabel: t('common.confirm'),
        noLabel: t('common.cancel'),
        destructive: archive,
        action: () => this.runConfirmedAction({ action, announcement }),
      })
      .subscribe();
  }

  refreshAfterConflict(): void {
    this.operationError.set(null);
    this.isEditorOpen.set(false);
    this.loadAnnouncements();
  }

  localizedValue(values: Record<string, string> | null | undefined): string {
    if (!values) {
      return '';
    }
    const current = this.uiI18n.currentLang();
    return values[current] ?? values['ru'] ?? Object.values(values).find((value) => value?.trim().length > 0) ?? '';
  }

  private titleRu(): string {
    return this.draftTitles()['ru'] || '';
  }

  private bodyRu(): string {
    return this.draftBodies()['ru'] || '';
  }

  /**
   * Publishes or archives from the dialog. A refusal closes the dialog and is
   * shown on the page like any other save error, since a conflict needs the
   * page's "refresh" and not another try.
   */
  private runConfirmedAction(pending: Confirmation) {
    this.isSaving.set(true);
    const { announcement, action } = pending;
    return this.announcementsApi.transition(announcement.id, action, announcement.lockVersion).pipe(
      tap((saved) => {
        this.upsert(saved);
        this.toast.success(
          pending.action === 'publish'
            ? this.uiI18n.translate('announcements.obyavlenie_opublikovano')
            : this.uiI18n.translate('announcements.obyavlenie_arhivirovano'),
        );
      }),
      catchError((problem: ApiProblem) => {
        this.handleMutationError(problem);
        return EMPTY;
      }),
      finalize(() => this.isSaving.set(false)),
    );
  }

  private upsert(saved: AnnouncementAdminRecord): void {
    const records = this.announcements();
    const exists = records.some((item) => item.id === saved.id);
    this.announcements.set(exists ? records.map((item) => (item.id === saved.id ? saved : item)) : [saved, ...records]);
  }

  private handleMutationError(problem: ApiProblem): void {
    if (problem?.status === 409 || problem?.code === 'CONFLICT') {
      this.operationError.set(this.uiI18n.translate('announcements.eto_obyavlenie_uzhe_izmeneno_drugim_polzovatelem'));
      return;
    }
    this.operationError.set(
      problem?.detail || this.uiI18n.translate('announcements.ne_udalos_sohranit_izmenenie_povtorite_popytku'),
    );
  }
}

/** The filled-in languages of a draft field, trimmed; empty ones are not sent. */
function trimmedValues(values: Record<string, string>): Record<string, string> {
  const trimmed: Record<string, string> = {};
  for (const [k, v] of Object.entries(values)) {
    if (v && v.trim()) trimmed[k] = v.trim();
  }
  return trimmed;
}
