import { Component, OnInit, signal } from '@angular/core';
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
          <h1 class="view-title">Файловое хранилище</h1>
          <span class="count-badge">{{ files().length }}</span>
        </div>
        <div class="header-right">
          <ui-button variant="primary" icon="cloud_upload" (onClick)="isUploadModalOpen.set(true)">
            Загрузить файл
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
              <span class="card-title">Дисковое пространство компании</span>
            </div>
            <span class="percent-badge" [class.danger]="getCompanyPercent(s) >= 90" [class.warning]="getCompanyPercent(s) >= 75 && getCompanyPercent(s) < 90">
              {{ getCompanyPercent(s) }}%
            </span>
          </div>

          <div class="metric-body">
            <div class="metric-values">
              <span class="used-val">{{ formatBytes(s.companyUsedBytes) }}</span>
              <span class="sep-val">из</span>
              <span class="quota-val">{{ formatBytes(s.companyQuotaBytes) }}</span>
            </div>

            <div
              class="progress-bar-track"
              role="progressbar"
              aria-label="Использование хранилища компании"
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
              <span>Свободно: {{ formatBytes(s.companyAvailableBytes) }}</span>
              <span>Всего файлов: {{ s.totalFilesCount }}</span>
            </div>
          </div>
        </div>

        <!-- User Personal Quota Card -->
        <div class="metric-card user-card">
          <div class="metric-header">
            <div class="metric-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">person</span>
              <span class="card-title">Моя персональная квота</span>
            </div>
            <span class="percent-badge user-badge" [class.danger]="getUserPercent(s) >= 90" [class.warning]="getUserPercent(s) >= 75 && getUserPercent(s) < 90">
              {{ getUserPercent(s) }}%
            </span>
          </div>

          <div class="metric-body">
            <div class="metric-values">
              <span class="used-val">{{ formatBytes(s.userUsedBytes) }}</span>
              <span class="sep-val">из</span>
              <span class="quota-val">{{ formatBytes(s.userQuotaBytes) }}</span>
            </div>

            <div
              class="progress-bar-track"
              role="progressbar"
              aria-label="Использование персональной квоты"
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
              <span>Свободно: {{ formatBytes(s.userAvailableBytes) }}</span>
              <span>Моих файлов: {{ s.userFilesCount }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Filters & Search Toolbar -->
      <div class="filter-toolbar">
        <div class="toolbar-left">
          <!-- Scope Tabs -->
          <div class="scope-tabs" role="group" aria-label="Область файлов">
            <button
              type="button"
              class="tab-btn"
              [class.active]="scope === 'all'"
              [attr.aria-pressed]="scope === 'all'"
              (click)="setScope('all')"
            >
              <span class="material-symbols-outlined" aria-hidden="true">folder_shared</span>
              Все файлы компании
            </button>
            <button
              type="button"
              class="tab-btn"
              [class.active]="scope === 'mine'"
              [attr.aria-pressed]="scope === 'mine'"
              (click)="setScope('mine')"
            >
              <span class="material-symbols-outlined" aria-hidden="true">person</span>
              Мои файлы
            </button>
          </div>

          <!-- Search Input -->
          <div class="search-box">
            <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
            <label class="sr-only" for="file-search">Поиск файлов</label>
            <input
              id="file-search"
              name="fileSearch"
              type="text"
              class="search-input"
              placeholder="Поиск файлов по имени..."
              [(ngModel)]="searchQuery"
              (keyup.enter)="loadFiles()"
            />
            <button type="button" class="clear-btn" aria-label="Очистить поиск файлов" *ngIf="searchQuery" (click)="searchQuery = ''; loadFiles()">
              <span class="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          </div>
        </div>

        <div class="toolbar-right">
          <button type="button" class="icon-refresh-btn" aria-label="Обновить список файлов" (click)="refreshAll()" title="Обновить список">
            <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
          </button>
        </div>
      </div>

      <!-- Files Table -->
      <div class="table-container" role="region" aria-label="Таблица файлов" tabindex="0" [attr.aria-busy]="isLoading()">
        <table class="data-table" aria-label="Список файлов">
          <thead>
            <tr>
              <th style="width: 48px;"></th>
              <th>Имя файла</th>
              <th>Размер</th>
              <th>Тип MIME</th>
              <th>Загрузил</th>
              <th>Дата загрузки</th>
              <th style="width: 100px; text-align: right;">Действия</th>
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
                <button type="button" class="file-name-cell" (click)="downloadFile(file)" [attr.aria-label]="'Скачать файл ' + file.originalName" title="Скачать файл">
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
                  <button type="button" class="action-btn download-btn" [attr.aria-label]="'Скачать файл ' + file.originalName" (click)="downloadFile(file)" title="Скачать">
                    <span class="material-symbols-outlined" aria-hidden="true">download</span>
                  </button>
                  <button
                    *ngIf="canDeleteFile(file)"
                    type="button"
                    class="action-btn delete-btn"
                    [attr.aria-label]="'Удалить файл ' + file.originalName"
                    (click)="confirmDeleteFile(file)"
                    title="Удалить"
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
                  <h3>Файлы не найдены</h3>
                  <p>Загрузите первый файл с помощью кнопки «Загрузить файл»</p>
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
        title="Загрузка файлов в хранилище"
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
          <ui-button variant="secondary" (onClick)="closeUploadModal()">Закрыть</ui-button>
        </div>
      </ui-modal>

      <!-- Confirm Delete Modal -->
      <ui-modal
        [isOpen]="fileToDelete !== null"
        title="Подтверждение удаления"
        size="sm"
        (close)="fileToDelete = null"
      >
        <div body *ngIf="fileToDelete">
          <p>Вы действительно хотите удалить файл <strong>{{ fileToDelete.originalName }}</strong>?</p>
          <p class="text-muted text-xs">Занятый объем ({{ formatBytes(fileToDelete.sizeBytes) }}) будет освобожден в вашей квоте.</p>
        </div>
        <div footer class="modal-footer-actions">
          <ui-button variant="secondary" (onClick)="fileToDelete = null">Отмена</ui-button>
          <ui-button variant="danger" (onClick)="executeDeleteFile()">Удалить</ui-button>
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
      color: var(--text-primary, #f1f5f9);
      margin: 0;
    }

    .file-count-badge {
      padding: 2px 8px;
      border-radius: 12px;
      font-size: 12px;
      font-weight: 600;
      background: rgba(99, 102, 241, 0.15);
      color: var(--color-primary, #818cf8);
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
      background: var(--bg-card, #1e293b);
      border: 1px solid rgba(255, 255, 255, 0.08);
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
      color: var(--color-primary, #818cf8);
    }

    .card-title {
      font-size: 14px;
      font-weight: 600;
      color: var(--text-primary, #f1f5f9);
    }

    .percent-badge {
      font-size: 12px;
      font-weight: 700;
      padding: 2px 8px;
      border-radius: 6px;
      background: rgba(99, 102, 241, 0.15);
      color: #818cf8;
    }

    .percent-badge.warning { background: rgba(245, 158, 11, 0.15); color: #f59e0b; }
    .percent-badge.danger { background: rgba(239, 68, 68, 0.15); color: #ef4444; }

    .user-badge {
      background: rgba(14, 165, 233, 0.15);
      color: #38bdf8;
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
      color: var(--text-primary, #f1f5f9);
    }

    .sep-val {
      font-size: 12px;
      color: #64748b;
    }

    .quota-val {
      font-size: 14px;
      font-weight: 600;
      color: #94a3b8;
    }

    .progress-bar-track {
      height: 8px;
      background: rgba(255, 255, 255, 0.08);
      border-radius: 4px;
      overflow: hidden;
    }

    .progress-bar-fill {
      height: 100%;
      background: linear-gradient(90deg, #6366f1, #818cf8);
      border-radius: 4px;
      transition: width 0.3s ease;
    }

    .progress-bar-fill.user-fill {
      background: linear-gradient(90deg, #0ea5e9, #38bdf8);
    }

    .progress-bar-fill.warning-fill {
      background: linear-gradient(90deg, #f59e0b, #fbbf24);
    }

    .progress-bar-fill.danger-fill {
      background: linear-gradient(90deg, #ef4444, #f87171);
    }

    .metric-footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      font-size: 12px;
      color: #94a3b8;
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
      background: rgba(255, 255, 255, 0.05);
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
      color: #94a3b8;
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .tab-btn .material-symbols-outlined {
      font-size: 16px;
    }

    .tab-btn.active {
      background: var(--bg-card, #1e293b);
      color: var(--text-primary, #f1f5f9);
      box-shadow: 0 1px 3px rgba(0,0,0,0.2);
    }

    .search-box {
      display: flex;
      align-items: center;
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 8px;
      padding: 0 10px;
      min-width: 260px;
    }

    .search-icon {
      font-size: 18px;
      color: #94a3b8;
    }

    .search-input {
      background: transparent;
      border: none;
      outline: none;
      color: var(--text-primary, #f1f5f9);
      padding: 7px 8px;
      font-size: 13px;
      width: 100%;
    }

    .clear-btn {
      border: none;
      background: transparent;
      color: #94a3b8;
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
      border: 1px solid rgba(255, 255, 255, 0.08);
      background: rgba(255, 255, 255, 0.04);
      color: #94a3b8;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .icon-refresh-btn:hover {
      background: rgba(255, 255, 255, 0.08);
      color: var(--text-primary, #f1f5f9);
    }

    /* Table */
    .table-container {
      background: var(--bg-card, #1e293b);
      border: 1px solid rgba(255, 255, 255, 0.08);
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
      background: rgba(255, 255, 255, 0.02);
      color: #94a3b8;
      font-weight: 600;
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);
      white-space: nowrap;
    }

    .data-table td {
      padding: 12px 16px;
      border-bottom: 1px solid rgba(255, 255, 255, 0.04);
      color: var(--text-primary, #f1f5f9);
      vertical-align: middle;
    }

    .data-table tr:hover td {
      background: rgba(255, 255, 255, 0.02);
    }

    .file-icon-wrapper {
      width: 32px;
      height: 32px;
      border-radius: 6px;
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .file-icon-wrapper.image { background: rgba(56, 189, 248, 0.15); color: #38bdf8; }
    .file-icon-wrapper.pdf { background: rgba(248, 113, 113, 0.15); color: #f87171; }
    .file-icon-wrapper.doc { background: rgba(96, 165, 250, 0.15); color: #60a5fa; }
    .file-icon-wrapper.sheet { background: rgba(52, 211, 153, 0.15); color: #34d399; }
    .file-icon-wrapper.archive { background: rgba(251, 191, 36, 0.15); color: #fbbf24; }
    .file-icon-wrapper.other { background: rgba(148, 163, 184, 0.15); color: #94a3b8; }

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
      color: var(--text-primary, #f1f5f9);
    }

    .file-name-cell:hover .primary-name {
      color: var(--color-primary, #818cf8);
      text-decoration: underline;
    }

    .size-pill {
      font-size: 12px;
      color: #94a3b8;
    }

    .mime-badge {
      font-size: 11px;
      padding: 2px 6px;
      border-radius: 4px;
      background: rgba(255, 255, 255, 0.05);
      color: #94a3b8;
    }

    .creator-cell {
      display: flex;
      flex-direction: column;
    }

    .creator-name {
      font-size: 13px;
      color: var(--text-primary, #f1f5f9);
    }

    .date-cell {
      font-size: 12px;
      color: #94a3b8;
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
      color: #94a3b8;
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
      background: rgba(99, 102, 241, 0.15);
      color: #818cf8;
    }

    .action-btn.delete-btn:hover {
      background: rgba(239, 68, 68, 0.15);
      color: #ef4444;
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
      color: #475569;
    }

    .empty-state-box h3 {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-primary, #f1f5f9);
      margin: 0;
    }

    .empty-state-box p {
      font-size: 13px;
      color: #64748b;
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
        this.toast.success(`Файл «${f.originalName}» удален`);
        this.fileToDelete = null;
        this.refreshAll();
      },
      error: err => {
        this.toast.error(err.error?.message || 'Не удалось удалить файл');
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
