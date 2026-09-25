import { Component, DestroyRef, OnDestroy, OnInit, signal, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { finalize, Observable, Subscription, tap, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { TaskFile } from '../../core/models/task.models';
import { TranslatePipe, I18nService } from '../../core/services/i18n.service';
import { FileDetail, StorageStats } from './files.models';
import { KeysetPage } from '../../core/models/common.models';
import { QueryListMeta } from '../../core/models/query-meta.models';
import { QueryMetaService, parseSort, toQueryParams } from '../../core/services/query-meta.service';
import { KeysetPager } from '../../shared/paging/keyset-pager';
import { ListViewState, ListViewsApi } from '../../shared/list-views/list-views';
import { TableColumnStateStore } from '../../shared/ui-kit/services/table-column-state.store';
import { sortFromHeader } from '../../shared/ui/registry-table-config';
import { OrderBy } from '../../shared/ui-kit/components/table/table.types';
import { FilesMetricsCardsComponent } from './components/files-metrics-cards.component';
import { FilesToolbarComponent } from './components/files-toolbar.component';
import { FilesTableComponent } from './components/files-table.component';
import { FilesModalsComponent } from './components/files-modals.component';
import { SMTModalService } from '../../shared/ui-kit/components/modal';
import { problemText } from '../../shared/ui/problem-text';
import { SMTFilePreviewService } from '../../shared/ui-kit/components/file-preview';

export type { FileDetail, StorageStats } from './files.models';

function formatBytes(bytes: number): string {
  if (!bytes || bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

@Component({
  selector: 'app-files',
  standalone: true,
  imports: [
    CommonModule,
    UiButtonComponent,
    TranslatePipe,
    FilesMetricsCardsComponent,
    FilesToolbarComponent,
    FilesTableComponent,
    FilesModalsComponent
  ],
  template: `
    <div class="files-page">
      <!-- Page Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'files.faylovoe_hranilische' | t }}</h1>
          <span class="count-badge" data-testid="files-count">{{ pager.total() }}</span>
        </div>
        <div class="header-right">
          <ui-button variant="primary" icon="cloud_upload" (onClick)="isUploadModalOpen.set(true)">
            {{ 'files.zagruzit_fayl' | t }}
          </ui-button>
        </div>
      </div>

      <!-- Storage Quotas & Metrics Dashboard -->
      <app-files-metrics-cards [stats]="stats()"></app-files-metrics-cards>

      <!-- Filters & Search Toolbar -->
      <app-files-toolbar
        [scope]="scope"
        [searchQuery]="searchQuery"
        (scopeChange)="setScope($event)"
        (searchQueryChange)="onSearchQueryChange($event)"
        (search)="searchFiles()"
        (clear)="onClearSearch()"
        (refresh)="refreshAll()"
      ></app-files-toolbar>

      <!-- Files Table & Pagination -->
      @if (metaError()) {
        <div class="alert alert-error" role="alert" data-testid="files-meta-error">
          <span>{{ 'files.list_load_error' | t }}</span>
          <ui-button variant="secondary" size="sm" (onClick)="loadFiles()">{{ 'common.retry' | t }}</ui-button>
        </div>
      }
      <app-files-table
        [pager]="pager"
        [meta]="meta()"
        [views]="views"
        [exportSearch]="searchQuery"
        [exportOptions]="exportOptions()"
        [isDeleting]="isDeleting()"
        [canDeleteFn]="canDeleteFileBound"
        (download)="downloadFile($event)"
        (preview)="previewFile($event)"
        (delete)="confirmDeleteFile($event)"
        (sortChange)="onSort($event)"
      ></app-files-table>

      <!-- Modals (Upload & Delete Confirmation) -->
      <app-files-modals
        [isUploadModalOpen]="isUploadModalOpen()"
        [uploadedBatch]="uploadedBatch()"
        (closeUpload)="closeUploadModal()"
        (batchFileUploaded)="onBatchFileUploaded($event)"
        (batchFileRemoved)="onBatchFileRemoved($event)"
      ></app-files-modals>
    </div>
  `,
  styles: [`
    .files-page {
      display: flex;
      flex-direction: column;
      gap: 20px;
      padding: 0;
      max-width: 1400px;
      margin: 0 auto;
    }

    .view-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .header-left {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .view-title {
      font-size: 24px;
      font-weight: 700;
      color: var(--text-main);
      margin: 0;
    }

    .count-badge {
      padding: 2px 8px;
      border-radius: 12px;
      font-size: 12px;
      font-weight: 600;
      background: var(--primary-subtle);
      color: var(--primary-text);
    }
  `]
})
export class FilesComponent implements OnInit, OnDestroy {
  private readonly preview = inject(SMTFilePreviewService);
  private readonly uiI18n = inject(I18nService);
  private readonly auth = inject(AuthService);
  private readonly queryMeta = inject(QueryMetaService);
  private readonly destroyRef = inject(DestroyRef);
  private statsRequest?: Subscription;
  private destroyed = false;
  /** Field metadata of the list (`query-meta/mf.files`). */
  readonly meta = signal<QueryListMeta | null>(null);
  readonly metaError = signal(false);
  readonly views = new ListViewState('mf.files', inject(ListViewsApi), {
    defaultSort: () => {
      const meta = this.meta();
      return meta ? parseSort(meta.defaultSort) : null;
    },
    onApply: () => this.pager.first(),
    columnsStore: inject(TableColumnStateStore)
  });
  /** Every request carries the scope, the search box, the sort and the filter; only the latest answer lands. */
  readonly pager = new KeysetPager<FileDetail>(
    (cursor, limit) => this.api.get<KeysetPage<FileDetail>>('/files', {
      scope: this.scope,
      limit,
      ...(cursor ? { cursor } : {}),
      ...toQueryParams({ sort: this.views.sort(), conditions: this.views.filter(), search: this.searchQuery })
    }, { notifyError: false }),
    { pageSize: 15, destroyRef: this.destroyRef }
  );
  readonly files = this.pager.items;
  readonly stats = signal<StorageStats | null>(null);
  readonly isLoading = this.pager.loading;
  readonly isUploadModalOpen = signal<boolean>(false);
  readonly uploadedBatch = signal<TaskFile[]>([]);
  readonly isDeleting = signal(false);

  scope: 'all' | 'mine' = 'all';
  private exportScope: { scope: string } = { scope: 'all' };

  /** The scope as an export option; the same object while the scope stays, so the button is not re-rendered. */
  exportOptions(): Record<string, string> {
    if (this.exportScope.scope !== this.scope) this.exportScope = { scope: this.scope };
    return this.exportScope;
  }
  searchQuery = '';

  readonly canDeleteFileBound = (file: FileDetail) => this.canDeleteFile(file);

  private readonly modal = inject(SMTModalService);

  constructor(
    private api: ApiService,
    private permService: PermissionService,
    private toast: ToastService
  ) {}

  ngOnInit() {
    this.refreshAll();
  }

  ngOnDestroy() {
    this.destroyed = true;
    this.pager.cancel();
    this.statsRequest?.unsubscribe();
  }

  refreshAll() {
    this.loadStats();
    this.loadFiles();
  }

  loadStats() {
    if (this.destroyed) return;
    this.statsRequest?.unsubscribe();
    this.statsRequest = this.api.get<StorageStats>('/files/storage/stats').subscribe({
      next: res => this.stats.set(res),
      error: () => {}
    });
  }

  /** The list's metadata once, then the first page with the current scope, search, sort and filter. */
  loadFiles() {
    if (this.destroyed) return;
    if (this.meta()) {
      this.pager.first();
      return;
    }
    this.metaError.set(false);
    this.queryMeta.get('mf.files').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: meta => {
        this.meta.set(meta);
        this.views.load().subscribe(() => this.pager.first());
      },
      error: () => this.metaError.set(true)
    });
  }

  searchFiles() {
    this.loadFiles();
  }

  setScope(scope: 'all' | 'mine') {
    this.scope = scope;
    this.loadFiles();
  }

  onSearchQueryChange(query: string) {
    this.searchQuery = query;
  }

  onClearSearch() {
    this.searchQuery = '';
    this.searchFiles();
  }

  /** A header click sorts the whole list on the server. */
  onSort(event: { column: string; sortBy: OrderBy } | undefined) {
    if (!this.meta()) return;
    this.views.setSort(sortFromHeader(event));
    this.pager.first();
  }

  /** Shows the images of the page in the preview, starting at this one. */
  previewFile(file: FileDetail) {
    const previews = this.files().map(item => ({ name: item.originalName, mimeType: item.mimeType, url: `/api/v1/files/${item.id}/download`, item }));
    const chosen = previews.find(preview => preview.item === file);
    if (chosen) this.preview.open(previews, chosen, preview => this.downloadFile((preview as typeof chosen).item));
  }

  downloadFile(file: FileDetail) {
    window.open(`/api/v1/files/${file.id}/download`, '_blank');
  }

  canDeleteFile(file: FileDetail): boolean {
    return this.permService.hasPermission('platform.files', 'delete') &&
      (this.permService.hasPermission('platform.files', 'manage_quotas') ||
        (file.createdBy != null && file.createdBy === this.auth.currentUser()?.id));
  }

  /**
   * Asks before deleting. The dialog stays open while the file is deleted and
   * shows the server's reason if it fails, so the person can retry or keep it.
   */
  confirmDeleteFile(file: FileDetail) {
    if (this.isDeleting() || !this.canDeleteFile(file)) return;
    this.modal.confirm({
      title: this.uiI18n.translate('files.podtverzhdenie_udaleniya'),
      message: `${this.uiI18n.translate('files.delete_file_question', { name: file.originalName })}\n${this.uiI18n.translate('files.quota_will_be_released', { size: formatBytes(file.sizeBytes) })}`,
      yesLabel: this.uiI18n.translate('common.delete'),
      noLabel: this.uiI18n.translate('common.cancel'),
      destructive: true,
      action: () => this.deleteFile(file),
      actionError: problemText
    }).subscribe();
  }

  /** Rights are checked again at the moment of deleting: they may have changed while the dialog was open. */
  private deleteFile(file: FileDetail): Observable<unknown> {
    if (!this.canDeleteFile(file)) {
      return throwError(() => ({ detail: this.uiI18n.translate('files.delete_not_allowed') }));
    }
    this.isDeleting.set(true);
    return this.api.delete(`/files/${file.id}`, { notifyError: false }).pipe(
      tap(() => {
        this.toast.success(this.uiI18n.translate('files.deleted_named', { name: file.originalName }));
        this.refreshAll();
      }),
      finalize(() => this.isDeleting.set(false))
    );
  }

  onBatchFileUploaded(taskFile: TaskFile) {
    this.uploadedBatch.update(list => [...list, taskFile]);
    this.refreshAll();
  }

  onBatchFileRemoved(taskFile: TaskFile) {
    this.api.delete(`/files/${taskFile.fileId}`).subscribe({
      next: () => {
        this.uploadedBatch.update(list => list.filter(f => f.fileId !== taskFile.fileId));
        this.refreshAll();
      }
    });
  }

  closeUploadModal() {
    this.isUploadModalOpen.set(false);
    this.uploadedBatch.set([]);
    this.refreshAll();
  }

  formatBytes(bytes: number): string {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }
}
