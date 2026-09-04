import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiFileUploadComponent } from '../../shared/ui/ui-file-upload.component';
import { UiModalComponent } from '../../shared/ui/ui-modal.component';
import { UiPaginationComponent } from '../../shared/ui/ui-pagination.component';
import { TaskFile } from '../../core/models/task.models';
import { TranslatePipe, I18nService } from '../../core/services/i18n.service';

export interface FileDetail {
  id: string;
  sha256: string;
  originalName: string;
  sizeBytes: number;
  mimeType: string;
  storageBucket: string;
  storageKey: string;
  createdAt: string;
  createdBy?: number;
  creatorName?: string;
  creatorLogin?: string;
}

export interface StorageStats {
  companyQuotaBytes: number;
  companyUsedBytes: number;
  companyAvailableBytes: number;
  userQuotaBytes: number;
  userUsedBytes: number;
  userAvailableBytes: number;
  totalFilesCount: number;
  userFilesCount: number;
}

@Component({
  selector: 'app-files',
  standalone: true,
  imports: [
    TranslatePipe,
    CommonModule,
    FormsModule,
    UiButtonComponent,
    UiFileUploadComponent,
    UiModalComponent,
    UiPaginationComponent
  ],
  template: `
    <div class="files-page">
      <!-- Page Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'files.faylovoe_hranilische' | t }}</h1>
          <span class="count-badge">{{ files().length }}</span>
        </div>
        <div class="header-right">
          <ui-button variant="primary" icon="cloud_upload" (onClick)="isUploadModalOpen.set(true)">
            {{ 'files.zagruzit_fayl' | t }}
          </ui-button>
        </div>
      </div>

      <!-- Storage Quotas & Metrics Dashboard -->
      <div class="storage-metrics-grid" *ngIf="stats() as s">
        <!-- Company Quota Card -->
        <div class="metric-card company-card">
          <div class="metric-header">
            <div class="metric-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">corporate_fare</span>
              <span class="card-title">{{ 'files.diskovoe_prostranstvo_kompanii' | t }}</span>
            </div>
            <span class="percent-badge" [class.danger]="getCompanyPercent(s) >= 90" [class.warning]="getCompanyPercent(s) >= 75 && getCompanyPercent(s) < 90">
              {{ getCompanyPercent(s) }}%
            </span>
          </div>

          <div class="metric-body">
            <div class="metric-values">
              <span class="used-val">{{ formatBytes(s.companyUsedBytes) }}</span>
              <span class="sep-val">{{ 'files.iz' | t }}</span>
              <span class="quota-val">{{ formatBytes(s.companyQuotaBytes) }}</span>
            </div>

            <div
              class="progress-bar-track"
              role="progressbar"
              [attr.aria-label]="'files.ispolzovanie_hranilischa_kompanii' | t"
              aria-valuemin="0"
              aria-valuemax="100"
              [attr.aria-valuenow]="getCompanyPercent(s)"
            >
              <div
                class="progress-bar-fill"
                [style.width.%]="getCompanyPercent(s)"
                [class.danger-fill]="getCompanyPercent(s) >= 90"
                [class.warning-fill]="getCompanyPercent(s) >= 75 && getCompanyPercent(s) < 90"
              ></div>
            </div>

            <div class="metric-footer">
              <span>{{ 'files.available_space' | t:{size: formatBytes(s.companyAvailableBytes)} }}</span>
              <span>{{ 'files.total_files_count' | t:{count: s.totalFilesCount} }}</span>
            </div>
          </div>
        </div>

        <!-- User Personal Quota Card -->
        <div class="metric-card user-card">
          <div class="metric-header">
            <div class="metric-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">person</span>
              <span class="card-title">{{ 'files.moya_personalnaya_kvota' | t }}</span>
            </div>
            <span class="percent-badge user-badge" [class.danger]="getUserPercent(s) >= 90" [class.warning]="getUserPercent(s) >= 75 && getUserPercent(s) < 90">
              {{ getUserPercent(s) }}%
            </span>
          </div>

          <div class="metric-body">
            <div class="metric-values">
              <span class="used-val">{{ formatBytes(s.userUsedBytes) }}</span>
              <span class="sep-val">{{ 'files.iz' | t }}</span>
              <span class="quota-val">{{ formatBytes(s.userQuotaBytes) }}</span>
            </div>

            <div
              class="progress-bar-track"
              role="progressbar"
              [attr.aria-label]="'files.ispolzovanie_personalnoy_kvoty' | t"
              aria-valuemin="0"
              aria-valuemax="100"
              [attr.aria-valuenow]="getUserPercent(s)"
            >
              <div
                class="progress-bar-fill user-fill"
                [style.width.%]="getUserPercent(s)"
                [class.danger-fill]="getUserPercent(s) >= 90"
                [class.warning-fill]="getUserPercent(s) >= 75 && getUserPercent(s) < 90"
              ></div>
            </div>

            <div class="metric-footer">
              <span>{{ 'files.available_space' | t:{size: formatBytes(s.userAvailableBytes)} }}</span>
              <span>{{ 'files.my_files_count' | t:{count: s.userFilesCount} }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Filters & Search Toolbar -->
      <div class="filter-toolbar">
        <div class="toolbar-left">
          <!-- Scope Tabs -->
          <div class="scope-tabs" role="group" [attr.aria-label]="'files.oblast_faylov' | t">
            <button
              type="button"
              class="tab-btn"
              [class.active]="scope === 'all'"
              [attr.aria-pressed]="scope === 'all'"
              (click)="setScope('all')"
            >
              <span class="material-symbols-outlined" aria-hidden="true">folder_shared</span>
              {{ 'files.vse_fayly_kompanii' | t }}
            </button>
            <button
              type="button"
              class="tab-btn"
              [class.active]="scope === 'mine'"
              [attr.aria-pressed]="scope === 'mine'"
              (click)="setScope('mine')"
            >
              <span class="material-symbols-outlined" aria-hidden="true">person</span>
              {{ 'files.moi_fayly' | t }}
            </button>
          </div>

          <!-- Search Input -->
          <div class="search-box">
            <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
            <label class="sr-only" for="file-search">{{ 'files.poisk_faylov' | t }}</label>
            <input
              id="file-search"
              name="fileSearch"
              type="text"
              class="search-input"
              [placeholder]="'files.poisk_faylov_po_imeni' | t"
              [(ngModel)]="searchQuery"
              (keyup.enter)="loadFiles()"
            />
            <button type="button" class="clear-btn" [attr.aria-label]="'files.ochistit_poisk_faylov' | t" *ngIf="searchQuery" (click)="searchQuery = ''; loadFiles()">
              <span class="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          </div>
        </div>

        <div class="toolbar-right">
          <button type="button" class="icon-refresh-btn" [attr.aria-label]="'files.obnovit_spisok_faylov' | t" (click)="refreshAll()" [title]="'announcements.obnovit_spisok' | t">
            <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
          </button>
        </div>
      </div>

      <!-- Files Table -->
      <div class="table-container" role="region" [attr.aria-label]="'files.tablica_faylov' | t" tabindex="0" [attr.aria-busy]="isLoading()">
        <table class="data-table" [attr.aria-label]="'files.spisok_faylov' | t">
          <thead>
            <tr>
              <th style="width: 48px;"></th>
              <th>{{ 'files.imya_fayla' | t }}</th>
              <th>{{ 'files.razmer' | t }}</th>
              <th>{{ 'files.tip_mime' | t }}</th>
              <th>{{ 'files.zagruzil' | t }}</th>
              <th>{{ 'files.data_zagruzki' | t }}</th>
              <th style="width: 100px; text-align: right;">{{ 'common.actions' | t }}</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let file of paginatedFiles()">
              <!-- Icon -->
              <td>
                <div class="file-icon-wrapper" [ngClass]="getFileCategory(file.mimeType, file.originalName)">
                  <span class="material-symbols-outlined" aria-hidden="true">{{ getFileIcon(file.mimeType, file.originalName) }}</span>
                </div>
              </td>

              <!-- File Name -->
              <td>
                <button type="button" class="file-name-cell" (click)="downloadFile(file)" [attr.aria-label]="'files.download_named' | t:{name: file.originalName}" [title]="'files.skachat_fayl' | t">
                  <span class="primary-name">{{ file.originalName }}</span>
                  <span class="sha-sub text-muted text-xs font-mono">{{ file.sha256.substring(0, 12) }}...</span>
                </button>
              </td>

              <!-- Size -->
              <td>
                <span class="size-pill font-mono">{{ formatBytes(file.sizeBytes) }}</span>
              </td>

              <!-- MIME Type -->
              <td>
                <span class="mime-badge">{{ file.mimeType }}</span>
              </td>

              <!-- Creator -->
              <td>
                <div class="creator-cell" *ngIf="file.creatorName">
                  <span class="creator-name">{{ file.creatorName }}</span>
                  <span class="creator-login text-muted text-xs">&#64;{{ file.creatorLogin }}</span>
                </div>
                <span *ngIf="!file.creatorName" class="text-muted">—</span>
              </td>

              <!-- Date -->
              <td>
                <span class="date-cell tabular-nums">{{ file.createdAt | date:'dd.MM.yyyy HH:mm' }}</span>
              </td>

              <!-- Actions -->
              <td style="text-align: right;">
                <div class="row-actions">
                  <button type="button" class="action-btn download-btn" [attr.aria-label]="'files.download_named' | t:{name: file.originalName}" (click)="downloadFile(file)" [title]="'files.skachat' | t">
                    <span class="material-symbols-outlined" aria-hidden="true">download</span>
                  </button>
                  <button
                    *ngIf="canDeleteFile(file)"
                    type="button"
                    class="action-btn delete-btn"
                    [attr.aria-label]="'files.delete_named' | t:{name: file.originalName}"
                    (click)="confirmDeleteFile(file)"
                    [title]="'common.delete' | t"
                  >
                    <span class="material-symbols-outlined" aria-hidden="true">delete</span>
                  </button>
                </div>
              </td>
            </tr>

            <!-- Empty State -->
            <tr *ngIf="files().length === 0 && !isLoading()">
              <td colspan="7" class="empty-state-cell">
                <div class="empty-state-box">
                  <span class="material-symbols-outlined empty-icon" aria-hidden="true">folder_open</span>
                  <h3>{{ 'files.fayly_ne_naydeny' | t }}</h3>
                  <p>{{ 'files.zagruzite_pervyy_fayl_s_pomoschyu_knopki_zagruzi' | t }}</p>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Universal Pagination -->
      <ui-pagination
        *ngIf="files().length > 0"
        [totalItems]="files().length"
        [pageSize]="pageSize"
        [currentPage]="currentPage"
        (pageChange)="currentPage = $event"
        (pageSizeChange)="pageSize = $event; currentPage = 1"
      ></ui-pagination>

      <!-- Upload Modal -->
      <ui-modal
        [isOpen]="isUploadModalOpen()"
        [title]="'files.zagruzka_faylov_v_hranilische' | t"
        size="md"
        (close)="isUploadModalOpen.set(false)"
      >
        <div body class="upload-modal-body">
          <ui-file-upload
            [files]="uploadedBatch()"
            [canUpload]="true"
            [canDelete]="true"
            (fileAttached)="onBatchFileUploaded($event)"
            (fileRemoved)="onBatchFileRemoved($event)"
          ></ui-file-upload>
        </div>
        <div footer class="modal-footer-actions">
          <ui-button variant="secondary" (onClick)="closeUploadModal()">{{ 'audit.zakryt' | t }}</ui-button>
        </div>
      </ui-modal>

      <!-- Confirm Delete Modal -->
      <ui-modal
        [isOpen]="fileToDelete !== null"
        [title]="'files.podtverzhdenie_udaleniya' | t"
        size="sm"
        (close)="fileToDelete = null"
      >
        <div body *ngIf="fileToDelete">
          <p>{{ 'files.vy_deystvitelno_hotite_udalit_fayl' | t }} <strong>{{ fileToDelete.originalName }}</strong>?</p>
          <p class="text-muted text-xs">{{ 'files.quota_will_be_released' | t:{size: formatBytes(fileToDelete.sizeBytes)} }}</p>
        </div>
        <div footer class="modal-footer-actions">
          <ui-button variant="secondary" (onClick)="fileToDelete = null">{{ 'common.cancel' | t }}</ui-button>
          <ui-button variant="danger" (onClick)="executeDeleteFile()">{{ 'common.delete' | t }}</ui-button>
        </div>
      </ui-modal>
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

    .file-count-badge {
      padding: 2px 8px;
      border-radius: 12px;
      font-size: 12px;
      font-weight: 600;
      background: var(--primary-subtle);
      color: var(--primary-text);
    }

    /* Storage Metrics Cards */
    .storage-metrics-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
    }

    @media (max-width: 768px) {
      .storage-metrics-grid {
        grid-template-columns: 1fr;
      }
    }

    .metric-card {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 16px 20px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .metric-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .metric-title-group {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .card-icon {
      font-size: 20px;
      color: var(--primary-text);
    }

    .card-title {
      font-size: 14px;
      font-weight: 600;
      color: var(--text-main);
    }

    .percent-badge {
      font-size: 12px;
      font-weight: 700;
      padding: 2px 8px;
      border-radius: 6px;
      background: var(--primary-subtle);
      color: var(--primary-text);
    }

    .percent-badge.warning { background: var(--warning-bg); color: var(--warning); }
    .percent-badge.danger { background: var(--danger-bg); color: var(--danger); }

    .user-badge {
      background: var(--info-bg);
      color: var(--info);
    }

    .metric-body {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .metric-values {
      display: flex;
      align-items: baseline;
      gap: 6px;
    }

    .used-val {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-main);
    }

    .sep-val {
      font-size: 12px;
      color: var(--text-light);
    }

    .quota-val {
      font-size: 14px;
      font-weight: 600;
      color: var(--text-muted);
    }

    .progress-bar-track {
      height: 8px;
      background: var(--bg-active);
      border-radius: 4px;
      overflow: hidden;
    }

    .progress-bar-fill {
      height: 100%;
      background: linear-gradient(90deg, var(--primary), var(--primary-hover));
      border-radius: 4px;
      transition: width 0.3s ease;
    }

    .progress-bar-fill.user-fill {
      background: var(--info);
    }

    .progress-bar-fill.warning-fill {
      background: var(--warning);
    }

    .progress-bar-fill.danger-fill {
      background: var(--danger);
    }

    .metric-footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      font-size: 12px;
      color: var(--text-light);
    }

    /* Filter Toolbar */
    .filter-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
    }

    .toolbar-left {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }

    .scope-tabs {
      display: flex;
      background: var(--bg-hover);
      border-radius: 8px;
      padding: 3px;
      gap: 2px;
    }

    .tab-btn {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 6px 12px;
      border-radius: 6px;
      border: none;
      background: transparent;
      color: var(--text-light);
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .tab-btn .material-symbols-outlined {
      font-size: 16px;
    }

    .tab-btn.active {
      background: var(--bg-surface);
      color: var(--text-main);
      box-shadow: var(--shadow-sm);
    }

    .search-box {
      display: flex;
      align-items: center;
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 0 10px;
      min-width: 260px;
    }

    .search-icon {
      font-size: 18px;
      color: var(--text-light);
    }

    .search-input {
      background: transparent;
      border: none;
      outline: none;
      color: var(--text-main);
      padding: 7px 8px;
      font-size: 13px;
      width: 100%;
    }

    .clear-btn {
      border: none;
      background: transparent;
      color: var(--text-light);
      cursor: pointer;
      display: flex;
      align-items: center;
    }

    .clear-btn .material-symbols-outlined {
      font-size: 16px;
    }

    .icon-refresh-btn {
      width: 34px;
      height: 34px;
      border-radius: 8px;
      border: 1px solid var(--border-color);
      background: var(--bg-surface);
      color: var(--text-light);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .icon-refresh-btn:hover {
      background: var(--bg-hover);
      color: var(--text-main);
    }

    /* Table */
    .table-container {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      overflow-x: auto;
    }

    .data-table {
      width: 100%;
      border-collapse: collapse;
      text-align: left;
      font-size: 13px;
    }

    .data-table th {
      padding: 12px 16px;
      background: var(--bg-hover);
      color: var(--text-muted);
      font-weight: 600;
      border-bottom: 1px solid var(--border-color);
      white-space: nowrap;
    }

    .data-table td {
      padding: 12px 16px;
      border-bottom: 1px solid var(--border-subtle);
      color: var(--text-main);
      vertical-align: middle;
    }

    .data-table tr:hover td {
      background: var(--bg-hover);
    }

    .file-icon-wrapper {
      width: 32px;
      height: 32px;
      border-radius: 6px;
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .file-icon-wrapper.image { background: var(--info-bg); color: var(--info); }
    .file-icon-wrapper.pdf { background: var(--danger-bg); color: var(--danger); }
    .file-icon-wrapper.doc { background: var(--info-bg); color: var(--info); }
    .file-icon-wrapper.sheet { background: var(--success-bg); color: var(--success); }
    .file-icon-wrapper.archive { background: var(--warning-bg); color: var(--warning); }
    .file-icon-wrapper.other { background: var(--bg-hover); color: var(--text-light); }

    .file-name-cell {
      width: 100%;
      border: 0;
      background: transparent;
      color: inherit;
      cursor: pointer;
      display: flex;
      flex-direction: column;
      font: inherit;
      padding: 0;
      text-align: left;
    }

    .primary-name {
      font-weight: 500;
      color: var(--text-main);
    }

    .file-name-cell:hover .primary-name {
      color: var(--primary-text);
      text-decoration: underline;
    }

    .size-pill {
      font-size: 12px;
      color: var(--text-light);
    }

    .mime-badge {
      font-size: 11px;
      padding: 2px 6px;
      border-radius: 4px;
      background: var(--bg-hover);
      color: var(--text-muted);
    }

    .creator-cell {
      display: flex;
      flex-direction: column;
    }

    .creator-name {
      font-size: 13px;
      color: var(--text-main);
    }

    .date-cell {
      font-size: 12px;
      color: var(--text-light);
    }

    .row-actions {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 6px;
    }

    .action-btn {
      width: 28px;
      height: 28px;
      border-radius: 4px;
      border: none;
      background: transparent;
      color: var(--text-light);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .action-btn .material-symbols-outlined {
      font-size: 18px;
    }

    .action-btn.download-btn:hover {
      background: var(--primary-subtle);
      color: var(--primary-text);
    }

    .action-btn.delete-btn:hover {
      background: var(--danger-bg);
      color: var(--danger);
    }

    .empty-state-cell {
      padding: 48px !important;
      text-align: center;
    }

    .empty-state-box {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
    }

    .empty-icon {
      font-size: 48px;
      color: var(--text-light);
    }

    .empty-state-box h3 {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }

    .empty-state-box p {
      font-size: 13px;
      color: var(--text-light);
      margin: 0;
    }

    .modal-footer-actions {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 8px;
    }
  `]
})
export class FilesComponent implements OnInit {
  private readonly uiI18n = inject(I18nService);
  readonly files = signal<FileDetail[]>([]);
  readonly stats = signal<StorageStats | null>(null);
  readonly isLoading = signal<boolean>(false);
  readonly isUploadModalOpen = signal<boolean>(false);
  readonly uploadedBatch = signal<TaskFile[]>([]);

  scope: 'all' | 'mine' = 'all';
  searchQuery = '';
  currentPage = 1;
  pageSize = 15;

  fileToDelete: FileDetail | null = null;

  constructor(
    private api: ApiService,
    private permService: PermissionService,
    private toast: ToastService
  ) {}

  ngOnInit() {
    this.refreshAll();
  }

  refreshAll() {
    this.loadStats();
    this.loadFiles();
  }

  loadStats() {
    this.api.get<StorageStats>('/files/storage/stats').subscribe({
      next: res => this.stats.set(res),
      error: () => {}
    });
  }

  loadFiles() {
    this.isLoading.set(true);
    this.api.get<FileDetail[]>('/files', {
      scope: this.scope,
      q: this.searchQuery,
      limit: 100
    }).subscribe({
      next: res => {
        this.files.set(res || []);
        this.isLoading.set(false);
      },
      error: () => {
        this.isLoading.set(false);
      }
    });
  }

  setScope(scope: 'all' | 'mine') {
    this.scope = scope;
    this.currentPage = 1;
    this.loadFiles();
  }

  paginatedFiles(): FileDetail[] {
    const list = this.files();
    const start = (this.currentPage - 1) * this.pageSize;
    return list.slice(start, start + this.pageSize);
  }

  getCompanyPercent(s: StorageStats): number {
    if (!s.companyQuotaBytes || s.companyQuotaBytes === 0) return 0;
    return Math.min(100, Math.round((s.companyUsedBytes / s.companyQuotaBytes) * 100));
  }

  getUserPercent(s: StorageStats): number {
    if (!s.userQuotaBytes || s.userQuotaBytes === 0) return 0;
    return Math.min(100, Math.round((s.userUsedBytes / s.userQuotaBytes) * 100));
  }

  downloadFile(file: FileDetail) {
    window.open(`/api/v1/files/${file.id}/download`, '_blank');
  }

  canDeleteFile(file: FileDetail): boolean {
    return this.permService.hasPermission('platform.files', 'delete') ||
           this.permService.hasPermission('platform.files', 'manage_quotas');
  }

  confirmDeleteFile(file: FileDetail) {
    this.fileToDelete = file;
  }

  executeDeleteFile() {
    if (!this.fileToDelete) return;
    const f = this.fileToDelete;
    this.api.delete(`/files/${f.id}`).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('files.deleted_named', { name: f.originalName }));
        this.fileToDelete = null;
        this.refreshAll();
      },
      error: err => {
        this.toast.error(err.error?.message || this.uiI18n.translate('files.ne_udalos_udalit_fayl'));
      }
    });
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

  getFileCategory(mimeType?: string, fileName?: string): string {
    const mime = (mimeType || '').toLowerCase();
    const name = (fileName || '').toLowerCase();

    if (mime.startsWith('image/') || /\.(png|jpe?g|gif|webp|svg)$/.test(name)) return 'image';
    if (mime === 'application/pdf' || name.endsWith('.pdf')) return 'pdf';
    if (/\.(docx?|odt|rtf|txt|md)$/.test(name)) return 'doc';
    if (/\.(xlsx?|csv|ods)$/.test(name)) return 'sheet';
    if (/\.(zip|tar|gz|rar|7z)$/.test(name)) return 'archive';
    return 'other';
  }

  getFileIcon(mimeType?: string, fileName?: string): string {
    const cat = this.getFileCategory(mimeType, fileName);
    switch (cat) {
      case 'image': return 'image';
      case 'pdf': return 'picture_as_pdf';
      case 'doc': return 'description';
      case 'sheet': return 'table_chart';
      case 'archive': return 'folder_zip';
      default: return 'attach_file';
    }
  }

  formatBytes(bytes: number): string {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }
}
