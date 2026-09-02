import { ChangeDetectionStrategy, Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { A11yModule } from '@angular/cdk/a11y';
import { ApiService } from '../../core/services/api.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../shared/ui/ui-modal.component';

export type AnnouncementState = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type AnnouncementBannerType = 'INFO' | 'WARNING' | 'CRITICAL';

export interface AnnouncementAdminRecord {
  id: number;
  titleJson: Record<string, string>;
  bodyJson: Record<string, string>;
  bannerType: AnnouncementBannerType;
  state: AnnouncementState;
  createdBy: number | null;
  createdAt: string;
  modifiedAt: string;
  publishedAt: string | null;
  archivedAt: string | null;
  lockVersion: number;
}

interface AnnouncementDraftPayload {
  titleJson: Record<string, string>;
  bodyJson: Record<string, string>;
  bannerType: AnnouncementBannerType;
  lockVersion: number | null;
}

interface Confirmation {
  action: 'publish' | 'archive';
  announcement: AnnouncementAdminRecord;
}

interface ApiProblem {
  status?: number;
  code?: string;
  detail?: string;
}

@Component({
  selector: 'app-announcements',
  standalone: true,
  imports: [CommonModule, FormsModule, A11yModule, UiButtonComponent, UiModalComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="announcements-page" aria-labelledby="announcements-title">
      <header class="view-header">
        <div>
          <p class="eyebrow">Локальные сообщения</p>
          <h1 id="announcements-title">Объявления</h1>
          <p class="subtitle">Публикуйте важные сообщения пользователям этой установки SmartupCMS.</p>
        </div>
        <div class="header-actions">
          <ui-button
            variant="secondary"
            icon="refresh"
            [loading]="isLoading()"
            ariaLabel="Обновить список объявлений"
            (onClick)="loadAnnouncements()"
          >Обновить</ui-button>
          <ui-button
            *ngIf="canCreate()"
            icon="add"
            ariaLabel="Создать объявление"
            (onClick)="openCreate()"
          >Создать</ui-button>
        </div>
      </header>

      <div *ngIf="operationError()" class="inline-alert" role="alert">
        <span class="material-symbols-outlined" aria-hidden="true">sync_problem</span>
        <div>
          <strong>Изменения не сохранены</strong>
          <p>{{ operationError() }}</p>
        </div>
        <ui-button variant="secondary" size="sm" ariaLabel="Обновить список объявлений" (onClick)="refreshAfterConflict()">
          Обновить список
        </ui-button>
      </div>

      <div *ngIf="isLoading() && announcements().length === 0" class="state-panel" aria-busy="true" aria-live="polite">
        <span class="spinner" aria-hidden="true"></span>
        <span>Загружаем объявления…</span>
      </div>

      <div *ngIf="loadError() && !isLoading()" class="state-panel error-state" role="alert" data-testid="announcements-load-error">
        <span class="material-symbols-outlined" aria-hidden="true">cloud_off</span>
        <div>
          <h2>Не удалось загрузить объявления</h2>
          <p>Проверьте соединение с сервером и повторите запрос.</p>
        </div>
        <ui-button variant="secondary" ariaLabel="Повторить загрузку объявлений" (onClick)="loadAnnouncements()">Повторить</ui-button>
      </div>

      <div *ngIf="!isLoading() && !loadError() && announcements().length === 0" class="state-panel empty-state" data-testid="announcements-empty">
        <span class="material-symbols-outlined" aria-hidden="true">campaign</span>
        <div>
          <h2>Объявлений пока нет</h2>
          <p>Создайте черновик, проверьте текст и опубликуйте его для пользователей.</p>
        </div>
        <ui-button *ngIf="canCreate()" variant="secondary" icon="add" (onClick)="openCreate()">Создать черновик</ui-button>
      </div>

      <div *ngIf="announcements().length > 0" class="announcement-list" aria-live="polite">
        <article *ngFor="let item of announcements(); trackBy: trackById" class="announcement-card" [attr.data-state]="item.state">
          <div class="card-marker" [class]="'card-marker marker-' + item.bannerType.toLowerCase()" aria-hidden="true"></div>
          <div class="card-main">
            <div class="card-heading">
              <div>
                <div class="card-meta">
                  <span class="state-badge" [class]="'state-badge state-' + item.state.toLowerCase()">{{ stateLabel(item.state) }}</span>
                  <span class="type-label">{{ bannerLabel(item.bannerType) }}</span>
                  <span>№{{ item.id }}</span>
                </div>
                <h2>{{ localizedValue(item.titleJson) || 'Без заголовка' }}</h2>
              </div>
              <time [attr.datetime]="item.modifiedAt">{{ item.modifiedAt | date:'dd.MM.yyyy, HH:mm' }}</time>
            </div>
            <p class="announcement-body">{{ localizedValue(item.bodyJson) || 'Текст не заполнен' }}</p>
            <div class="card-actions">
              <button
                *ngIf="item.state === 'DRAFT' && canUpdate()"
                type="button"
                class="text-action edit-action"
                (click)="openEdit(item)"
              >
                <span class="material-symbols-outlined" aria-hidden="true">edit</span>
                Редактировать
              </button>
              <button
                *ngIf="item.state === 'DRAFT' && canPublish()"
                type="button"
                class="text-action publish-action"
                [disabled]="!hasPublishableContent(item)"
                [attr.aria-describedby]="!hasPublishableContent(item) ? 'invalid-draft-' + item.id : null"
                (click)="requestConfirmation('publish', item)"
              >
                <span class="material-symbols-outlined" aria-hidden="true">publish</span>
                Опубликовать
              </button>
              <span *ngIf="item.state === 'DRAFT' && !hasPublishableContent(item)" class="invalid-hint" [id]="'invalid-draft-' + item.id">
                Заполните RU-заголовок и текст
              </span>
              <button
                *ngIf="item.state === 'PUBLISHED' && canArchive()"
                type="button"
                class="text-action archive-action"
                (click)="requestConfirmation('archive', item)"
              >
                <span class="material-symbols-outlined" aria-hidden="true">archive</span>
                Архивировать
              </button>
            </div>
          </div>
        </article>
      </div>

      <ui-modal
        [isOpen]="isEditorOpen()"
        [title]="editingId === null ? 'Новое объявление' : 'Редактирование объявления'"
        size="lg"
        [hasFooter]="false"
        (close)="closeEditor()"
      >
        <form body id="announcement-editor" class="editor-form" (ngSubmit)="saveDraft()" novalidate>
          <div class="field-group">
            <label for="announcement-title-ru">Заголовок (RU) <span aria-hidden="true">*</span></label>
            <input
              id="announcement-title-ru"
              name="announcementTitleRu"
              type="text"
              maxlength="10000"
              required
              cdkFocusInitial
              [(ngModel)]="titleRu"
              [attr.aria-invalid]="titleRu.trim().length === 0"
              aria-describedby="announcement-title-hint"
            />
            <span id="announcement-title-hint" class="field-hint">Коротко опишите главное сообщение.</span>
          </div>
          <div class="field-group">
            <label for="announcement-body-ru">Текст объявления (RU) <span aria-hidden="true">*</span></label>
            <textarea
              id="announcement-body-ru"
              name="announcementBodyRu"
              rows="7"
              maxlength="10000"
              required
              [(ngModel)]="bodyRu"
              [attr.aria-invalid]="bodyRu.trim().length === 0"
              aria-describedby="announcement-body-hint"
            ></textarea>
            <span id="announcement-body-hint" class="field-hint">До 10 000 символов. Текст увидят все пользователи установки.</span>
          </div>
          <div class="field-group">
            <label for="announcement-banner-type">Уровень сообщения</label>
            <select id="announcement-banner-type" name="announcementBannerType" [(ngModel)]="bannerType">
              <option value="INFO">Информация</option>
              <option value="WARNING">Предупреждение</option>
              <option value="CRITICAL">Критическое</option>
            </select>
          </div>
          <div class="form-actions">
            <ui-button variant="secondary" (onClick)="closeEditor()">Отмена</ui-button>
            <button
              type="submit"
              class="primary-button"
              data-testid="save-draft"
              [disabled]="!isDraftValid() || isSaving()"
              [attr.aria-busy]="isSaving()"
            >{{ isSaving() ? 'Сохранение…' : 'Сохранить черновик' }}</button>
          </div>
        </form>
      </ui-modal>

      <ui-modal
        [isOpen]="confirmation() !== null"
        [title]="confirmationTitle()"
        size="sm"
        [dismissible]="!isSaving()"
        (close)="confirmation.set(null)"
      >
        <div body *ngIf="confirmation() as pending" class="confirmation-copy">
          <p *ngIf="pending.action === 'publish'">После публикации объявление увидят пользователи. Редактирование станет недоступно.</p>
          <p *ngIf="pending.action === 'archive'">Объявление исчезнет у пользователей и останется в локальном архиве.</p>
        </div>
        <div footer class="modal-actions">
          <ui-button variant="secondary" [disabled]="isSaving()" (onClick)="confirmation.set(null)">Отмена</ui-button>
          <ui-button
            [variant]="confirmation()?.action === 'archive' ? 'danger' : 'primary'"
            [loading]="isSaving()"
            (onClick)="executeConfirmedAction()"
          >Подтвердить</ui-button>
        </div>
      </ui-modal>
    </section>
  `,
  styles: [`
    :host { display: block; }
    .announcements-page { display: flex; flex-direction: column; gap: 18px; max-width: 1180px; margin: 0 auto; }
    .view-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
    .eyebrow { margin: 0 0 4px; color: var(--primary); font-size: 12px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
    h1 { margin: 0; color: var(--text-main); font-size: 26px; line-height: 1.2; }
    .subtitle { margin: 8px 0 0; color: var(--text-muted); font-size: 13px; line-height: 1.5; }
    .header-actions { display: flex; gap: 8px; }
    .announcement-list { display: flex; flex-direction: column; gap: 10px; }
    .announcement-card { position: relative; display: flex; overflow: hidden; background: var(--bg-surface); border: 1px solid var(--border-color); border-radius: var(--radius-lg); }
    .card-marker { flex: 0 0 4px; background: var(--info); }
    .marker-warning { background: var(--warning); }
    .marker-critical { background: var(--danger); }
    .card-main { flex: 1; min-width: 0; padding: 16px 18px; }
    .card-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
    .card-heading h2 { margin: 7px 0 0; color: var(--text-main); font-size: 16px; }
    .card-heading time { flex: 0 0 auto; color: var(--text-muted); font-size: 11px; }
    .card-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; color: var(--text-muted); font-size: 11px; }
    .state-badge { padding: 3px 8px; border-radius: 999px; font-weight: 700; }
    .state-draft { color: var(--warning); background: var(--warning-bg); }
    .state-published { color: var(--success); background: var(--success-bg); }
    .state-archived { color: var(--text-muted); background: var(--bg-hover); }
    .type-label { font-weight: 600; text-transform: uppercase; }
    .announcement-body { margin: 10px 0 14px; color: var(--text-muted); font-size: 13px; line-height: 1.55; white-space: pre-line; }
    .card-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding-top: 12px; border-top: 1px solid var(--border-color); }
    .text-action { display: inline-flex; align-items: center; gap: 5px; padding: 4px 7px; border: 0; border-radius: var(--radius-sm); background: transparent; color: var(--primary); font: inherit; font-size: 12px; font-weight: 600; cursor: pointer; }
    .text-action:hover:not(:disabled) { background: var(--bg-hover); }
    .text-action:focus-visible, .primary-button:focus-visible, input:focus-visible, textarea:focus-visible, select:focus-visible { outline: 2px solid var(--focus-ring, var(--primary)); outline-offset: 2px; }
    .text-action:disabled { color: var(--text-muted); cursor: not-allowed; opacity: .6; }
    .text-action .material-symbols-outlined { font-size: 17px; }
    .archive-action { color: var(--danger); }
    .invalid-hint { color: var(--text-muted); font-size: 11px; }
    .state-panel, .inline-alert { display: flex; align-items: center; gap: 14px; padding: 22px; background: var(--bg-surface); border: 1px solid var(--border-color); border-radius: var(--radius-lg); color: var(--text-muted); }
    .state-panel { justify-content: center; min-height: 180px; text-align: left; }
    .state-panel h2, .inline-alert strong { margin: 0; color: var(--text-main); font-size: 15px; }
    .state-panel p, .inline-alert p { margin: 4px 0 0; font-size: 12px; line-height: 1.45; }
    .empty-state { flex-direction: column; text-align: center; }
    .empty-state > .material-symbols-outlined { color: var(--primary); font-size: 38px; }
    .error-state > .material-symbols-outlined, .inline-alert > .material-symbols-outlined { color: var(--danger); font-size: 28px; }
    .error-state ui-button, .inline-alert ui-button { margin-left: auto; }
    .inline-alert { padding: 14px 16px; border-color: color-mix(in srgb, var(--danger) 35%, var(--border-color)); }
    .spinner { width: 20px; height: 20px; border: 2px solid var(--border-color); border-top-color: var(--primary); border-radius: 50%; animation: spin .7s linear infinite; }
    .editor-form { display: flex; flex-direction: column; gap: 16px; }
    .field-group { display: flex; flex-direction: column; gap: 6px; }
    .field-group label { color: var(--text-main); font-size: 12px; font-weight: 600; }
    .field-group input, .field-group textarea, .field-group select { width: 100%; box-sizing: border-box; border: 1px solid var(--border-color); border-radius: var(--radius-sm); background: var(--bg-app); color: var(--text-main); font: inherit; font-size: 13px; padding: 9px 11px; }
    .field-group textarea { resize: vertical; min-height: 140px; line-height: 1.5; }
    .field-hint { color: var(--text-muted); font-size: 11px; }
    .form-actions, .modal-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; }
    .form-actions { margin: 4px -18px -18px; padding: 12px 18px; border-top: 1px solid var(--border-color); }
    .primary-button { height: 34px; padding: 6px 14px; border: 0; border-radius: var(--radius-sm); background: var(--primary); color: var(--text-inverse); font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; }
    .primary-button:disabled { cursor: not-allowed; opacity: .5; }
    .confirmation-copy { color: var(--text-muted); font-size: 13px; line-height: 1.5; }
    .confirmation-copy p { margin: 0; }
    @keyframes spin { to { transform: rotate(360deg); } }
    @media (max-width: 680px) { .view-header { flex-direction: column; } .header-actions { width: 100%; } .card-heading { flex-direction: column; gap: 6px; } .card-main { padding: 14px; } .state-panel, .inline-alert { align-items: flex-start; flex-wrap: wrap; } .error-state ui-button, .inline-alert ui-button { margin-left: 42px; } }
    @media (prefers-reduced-motion: reduce) { .spinner { animation-duration: 1.5s; } }
  `]
})
export class AnnouncementsComponent implements OnInit {
  readonly announcements = signal<AnnouncementAdminRecord[]>([]);
  readonly isLoading = signal(true);
  readonly loadError = signal(false);
  readonly operationError = signal<string | null>(null);
  readonly isSaving = signal(false);
  readonly isEditorOpen = signal(false);
  readonly confirmation = signal<Confirmation | null>(null);

  editingId: number | null = null;
  editingLockVersion: number | null = null;
  titleRu = '';
  bodyRu = '';
  bannerType: AnnouncementBannerType = 'INFO';

  constructor(
    private readonly api: ApiService,
    private readonly permissions: PermissionService,
    private readonly toast: ToastService
  ) {}

  ngOnInit(): void {
    this.loadAnnouncements();
  }

  loadAnnouncements(): void {
    this.isLoading.set(true);
    this.loadError.set(false);
    this.api.get<AnnouncementAdminRecord[]>('/announcements/manage').subscribe({
      next: records => {
        this.announcements.set(records ?? []);
        this.isLoading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.isLoading.set(false);
      }
    });
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
    this.editingId = null;
    this.editingLockVersion = null;
    this.titleRu = '';
    this.bodyRu = '';
    this.bannerType = 'INFO';
    this.operationError.set(null);
    this.isEditorOpen.set(true);
  }

  openEdit(item: AnnouncementAdminRecord): void {
    this.editingId = item.id;
    this.editingLockVersion = item.lockVersion;
    this.titleRu = this.localizedValue(item.titleJson);
    this.bodyRu = this.localizedValue(item.bodyJson);
    this.bannerType = item.bannerType;
    this.operationError.set(null);
    this.isEditorOpen.set(true);
  }

  closeEditor(): void {
    if (!this.isSaving()) {
      this.isEditorOpen.set(false);
    }
  }

  isDraftValid(): boolean {
    return this.titleRu.trim().length > 0
      && this.titleRu.length <= 10_000
      && this.bodyRu.trim().length > 0
      && this.bodyRu.length <= 10_000;
  }

  hasPublishableContent(item: AnnouncementAdminRecord): boolean {
    return this.localizedValue(item.titleJson).trim().length > 0
      && this.localizedValue(item.bodyJson).trim().length > 0;
  }

  saveDraft(): void {
    if (!this.isDraftValid() || this.isSaving()) {
      return;
    }
    const payload: AnnouncementDraftPayload = {
      titleJson: { ru: this.titleRu.trim() },
      bodyJson: { ru: this.bodyRu.trim() },
      bannerType: this.bannerType,
      lockVersion: this.editingLockVersion
    };
    this.isSaving.set(true);
    this.operationError.set(null);
    const request = this.editingId === null
      ? this.api.post<AnnouncementAdminRecord>('/announcements', payload)
      : this.api.put<AnnouncementAdminRecord>(`/announcements/${this.editingId}`, payload);
    request.subscribe({
      next: saved => {
        this.upsert(saved);
        this.isSaving.set(false);
        this.isEditorOpen.set(false);
        this.toast.success(this.editingId === null ? 'Черновик создан' : 'Черновик сохранён');
      },
      error: (problem: ApiProblem) => {
        this.isSaving.set(false);
        this.handleMutationError(problem);
      }
    });
  }

  requestConfirmation(action: Confirmation['action'], announcement: AnnouncementAdminRecord): void {
    if (action === 'publish' && !this.hasPublishableContent(announcement)) {
      return;
    }
    this.operationError.set(null);
    this.confirmation.set({ action, announcement });
  }

  confirmationTitle(): string {
    return this.confirmation()?.action === 'archive'
      ? 'Архивировать объявление?'
      : 'Опубликовать объявление?';
  }

  executeConfirmedAction(): void {
    const pending = this.confirmation();
    if (!pending || this.isSaving()) {
      return;
    }
    this.isSaving.set(true);
    const path = `/announcements/${pending.announcement.id}/${pending.action}`;
    this.api.post<AnnouncementAdminRecord>(path, { lockVersion: pending.announcement.lockVersion }).subscribe({
      next: saved => {
        this.upsert(saved);
        this.isSaving.set(false);
        this.confirmation.set(null);
        this.toast.success(pending.action === 'publish' ? 'Объявление опубликовано' : 'Объявление архивировано');
      },
      error: (problem: ApiProblem) => {
        this.isSaving.set(false);
        this.confirmation.set(null);
        this.handleMutationError(problem);
      }
    });
  }

  refreshAfterConflict(): void {
    this.operationError.set(null);
    this.isEditorOpen.set(false);
    this.confirmation.set(null);
    this.loadAnnouncements();
  }

  localizedValue(values: Record<string, string> | null | undefined): string {
    if (!values) {
      return '';
    }
    return values['ru'] ?? Object.values(values).find(value => value?.trim().length > 0) ?? '';
  }

  stateLabel(state: AnnouncementState): string {
    return ({ DRAFT: 'Черновик', PUBLISHED: 'Опубликовано', ARCHIVED: 'Архив' } as const)[state];
  }

  bannerLabel(type: AnnouncementBannerType): string {
    return ({ INFO: 'Информация', WARNING: 'Предупреждение', CRITICAL: 'Критическое' } as const)[type];
  }

  trackById(_index: number, item: AnnouncementAdminRecord): number {
    return item.id;
  }

  private upsert(saved: AnnouncementAdminRecord): void {
    const records = this.announcements();
    const exists = records.some(item => item.id === saved.id);
    this.announcements.set(exists
      ? records.map(item => item.id === saved.id ? saved : item)
      : [saved, ...records]);
  }

  private handleMutationError(problem: ApiProblem): void {
    if (problem?.status === 409 || problem?.code === 'CONFLICT') {
      this.operationError.set('Это объявление уже изменено другим пользователем. Обновите список и повторите действие.');
      return;
    }
    this.operationError.set(problem?.detail || 'Не удалось сохранить изменение. Повторите попытку.');
  }
}
