import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { BackupCheck, CpApiService, InstanceBackupReport } from '../core/cp-api.service';
import { dt, errorText } from '../core/format';

type BackupSortCol = 'clientCode' | 'success' | 'durationSec' | 'verifiedAt';
type SortDir = 'asc' | 'desc';

@Component({
  selector: 'cp-backups',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <!-- Page Header -->
    <div class="view-header">
      <div class="header-left">
        <div>
          <div style="display: flex; align-items: center; gap: 10px;">
            <h1 class="view-title">Резервные копии</h1>
            <span class="count-badge">{{ reports().length }}</span>
          </div>
          <p class="view-desc">Артефакты резервного копирования и отдельный журнал тестовых восстановлений</p>
        </div>
      </div>

      <div class="header-right">
        <button type="button" class="btn btn-secondary" (click)="load()" [disabled]="busy()" [attr.aria-busy]="busy()">
          <span class="material-symbols-outlined" [class.spin-icon]="busy()" aria-hidden="true">refresh</span>
          <span>{{ busy() ? 'Обновляем…' : 'Обновить' }}</span>
        </button>
      </div>
    </div>

    <!-- Error Alert -->
    <div *ngIf="error()" class="alert alert-error" role="alert">
      <span class="material-symbols-outlined">error</span>
      <span>{{ error() }}</span>
    </div>

    <section class="artifact-section" data-testid="backup-artifact-reports" aria-labelledby="artifact-heading">
      <div class="section-header">
        <div>
          <h2 id="artifact-heading" class="section-heading">Артефакты резервных копий</h2>
          <p class="section-copy">
            «Загружен» подтверждает только приём артефакта. Готовность к восстановлению подтверждает статус «Проверен».
          </p>
        </div>
        <div class="artifact-summary" aria-label="Сводка статусов артефактов">
          <span class="badge badge-warning">Загружено: {{ uploadedReportCount() }}</span>
          <span class="badge badge-success">Проверено: {{ verifiedReportCount() }}</span>
          <span class="badge badge-danger">Ошибок: {{ failedReportCount() }}</span>
        </div>
      </div>

      <div class="card">
        <div class="table-scroll" role="region" aria-label="Таблица артефактов резервных копий" tabindex="0">
          <table aria-label="Артефакты резервных копий">
            <thead>
              <tr>
                <th>Клиент</th>
                <th>Экземпляр</th>
                <th>Состояние артефакта</th>
                <th>Длительность</th>
                <th>Контрольная сумма</th>
                <th>Завершён</th>
                <th>Получен</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let report of reports(); trackBy: trackByBackupId">
                <td>
                  <span class="mono badge badge-neutral artifact-client">{{ report.clientCode }}</span>
                </td>
                <td class="tabular-nums mono artifact-secondary">#{{ report.instanceId }}</td>
                <td>
                  <span class="badge" [ngClass]="artifactStatusClass(report.artifactStatus)">
                    {{ artifactStatusLabel(report.artifactStatus) }}
                  </span>
                  <div *ngIf="report.reasonCode" class="artifact-reason">
                    Код причины: <span class="mono">{{ report.reasonCode }}</span>
                  </div>
                </td>
                <td class="tabular-nums mono artifact-secondary">{{ report.durationSec }} сек</td>
                <td class="mono artifact-secondary">{{ checksumFingerprint(report.checksumSha256) }}</td>
                <td class="artifact-secondary">{{ dt(report.completedAt) }}</td>
                <td class="artifact-secondary">{{ dt(report.receivedAt) }}</td>
              </tr>
              <tr *ngIf="reports().length === 0 && !busy()">
                <td colspan="7" class="empty">
                  <div class="empty-stack">
                    <span class="material-symbols-outlined empty-icon" aria-hidden="true">inventory_2</span>
                    <span>Отчёты об артефактах ещё не поступали.</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <section data-testid="restore-verification-checks" aria-labelledby="restore-heading">
      <div class="section-header restore-header">
        <div>
          <h2 id="restore-heading" class="section-heading">Тестовые восстановления</h2>
          <p class="section-copy">Независимая проверка возможности восстановить базу данных из резервной копии.</p>
        </div>
      </div>

    <!-- KPI Metric Cards (Interactive) -->
    <div class="tiles">
      <button
        type="button"
        class="tile clickable-tile"
        [class.active-tile]="selectedFilter === 'all'"
        [attr.aria-pressed]="selectedFilter === 'all'"
        (click)="selectedFilter = 'all'"
        title="Все проверки"
      >
        <span class="tile-label">Всего проверок</span>
        <div style="display: flex; align-items: baseline; justify-content: space-between;">
          <span class="tile-value">{{ checks().length }}</span>
          <span class="material-symbols-outlined tile-hint-icon">history</span>
        </div>
      </button>

      <button
        type="button"
        class="tile clickable-tile"
        [class.active-tile]="selectedFilter === 'success'"
        [attr.aria-pressed]="selectedFilter === 'success'"
        (click)="selectedFilter = 'success'"
        title="Только успешные восстановления"
      >
        <span class="tile-label">Успешно восстановлено</span>
        <div style="display: flex; align-items: baseline; justify-content: space-between;">
          <span class="tile-value" style="color: var(--success);">{{ successCount() }}</span>
          <span class="material-symbols-outlined tile-hint-icon" style="color: var(--success);">check_circle</span>
        </div>
      </button>

      <button
        type="button"
        class="tile clickable-tile"
        [class.tile-alarm]="failureCount() > 0"
        [class.active-tile]="selectedFilter === 'failed'"
        [attr.aria-pressed]="selectedFilter === 'failed'"
        (click)="selectedFilter = 'failed'"
        title="Только сбои"
      >
        <span class="tile-label">Сбоев при проверке</span>
        <div style="display: flex; align-items: baseline; justify-content: space-between;">
          <span class="tile-value">{{ failureCount() }}</span>
          <span class="material-symbols-outlined tile-hint-icon">{{ failureCount() > 0 ? 'error' : 'shield' }}</span>
        </div>
      </button>
    </div>

    <!-- Toolbar -->
    <div class="toolbar">
      <div class="search-field">
        <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
        <label class="sr-only" for="backup-search">Поиск по коду клиента</label>
        <input
          id="backup-search"
          name="backupSearch"
          type="text"
          class="search-input"
          placeholder="Поиск по коду клиента..."
          [(ngModel)]="searchQuery"
        />
        <button
          *ngIf="searchQuery"
          type="button"
          class="btn-icon"
          style="position: absolute; right: 6px;"
          (click)="searchQuery = ''"
          title="Очистить"
        >
          <span class="material-symbols-outlined" style="font-size: 16px;">close</span>
        </button>
      </div>

      <div class="status-tabs" role="group" aria-label="Фильтр бэкапов">
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedFilter === 'all'"
          (click)="selectedFilter = 'all'"
        >
          Все ({{ checks().length }})
        </button>
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedFilter === 'success'"
          (click)="selectedFilter = 'success'"
        >
          <span class="status-tab-dot" style="background-color: var(--success);"></span>
          Успешные ({{ successCount() }})
        </button>
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedFilter === 'failed'"
          (click)="selectedFilter = 'failed'"
        >
          <span class="status-tab-dot" style="background-color: var(--danger);"></span>
          Сбои ({{ failureCount() }})
        </button>
      </div>
    </div>

    <!-- Data Table Card -->
    <div class="card">
      <div class="table-scroll" role="region" aria-label="Таблица проверок бэкапов" tabindex="0">
        <table aria-label="Проверки бэкапов">
          <thead>
            <tr>
              <th class="sortable-th" [attr.aria-sort]="sortAria('clientCode')">
                <button type="button" class="sort-button" (click)="setSort('clientCode')">
                  <span>Клиент</span>
                  <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'clientCode'" aria-hidden="true">
                    {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                  </span>
                </button>
              </th>
              <th class="sortable-th" [attr.aria-sort]="sortAria('success')">
                <button type="button" class="sort-button" (click)="setSort('success')">
                  <span>Результат восстановления</span>
                  <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'success'" aria-hidden="true">
                    {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                  </span>
                </button>
              </th>
              <th class="sortable-th" [attr.aria-sort]="sortAria('durationSec')">
                <button type="button" class="sort-button" (click)="setSort('durationSec')">
                  <span>Длительность</span>
                  <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'durationSec'" aria-hidden="true">
                    {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                  </span>
                </button>
              </th>
              <th>Детали</th>
              <th class="sortable-th" [attr.aria-sort]="sortAria('verifiedAt')">
                <button type="button" class="sort-button" (click)="setSort('verifiedAt')">
                  <span>Дата и время проверки</span>
                  <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'verifiedAt'" aria-hidden="true">
                    {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                  </span>
                </button>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let b of sortedAndFilteredChecks(); trackBy: trackById">
              <td>
                <span class="mono badge badge-neutral" style="font-size: 12px; font-weight: 600;">{{ b.clientCode }}</span>
              </td>
              <td>
                <span class="badge" [ngClass]="b.success ? 'badge-success' : 'badge-danger'">
                  {{ b.success ? 'Успешно восстановлен' : 'Ошибка восстановления' }}
                </span>
              </td>
              <td class="tabular-nums mono" style="font-size: 12px;">{{ b.durationSec }} сек</td>
              <td style="font-size: 12px; color: var(--text-muted);">{{ b.details || '—' }}</td>
              <td style="font-size: 12px; color: var(--text-muted);">{{ dt(b.verifiedAt) }}</td>
            </tr>
            <tr *ngIf="sortedAndFilteredChecks().length === 0 && !busy()">
              <td colspan="5" class="empty">
                <div style="display: flex; flex-direction: column; align-items: center; gap: 8px;">
                  <span class="material-symbols-outlined" style="font-size: 36px; color: var(--text-light);">cloud_done</span>
                  <span>Отчетов о проверке бэкапов не найдено.</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    </section>
  `,
  styles: [`
    .artifact-section {
      margin-bottom: 32px;
    }
    .section-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 16px;
      margin: 0 0 12px;
    }
    .restore-header {
      margin-top: 4px;
    }
    .section-heading {
      margin: 0;
      color: var(--text-main);
      font-size: 17px;
      font-weight: 700;
    }
    .section-copy {
      max-width: 760px;
      margin: 4px 0 0;
      color: var(--text-muted);
      font-size: 12px;
      line-height: 1.5;
    }
    .artifact-summary {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 6px;
    }
    .artifact-client {
      font-size: 12px;
      font-weight: 600;
    }
    .artifact-secondary {
      color: var(--text-muted);
      font-size: 12px;
      white-space: nowrap;
    }
    .artifact-reason {
      margin-top: 5px;
      color: var(--danger);
      font-size: 11px;
    }
    .empty-stack {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
    }
    .empty-icon {
      color: var(--text-light);
      font-size: 36px;
    }
    .clickable-tile {
      appearance: none;
      width: 100%;
      cursor: pointer;
      color: inherit;
      font: inherit;
      text-align: left;
      user-select: none;
    }
    .clickable-tile:hover {
      border-color: var(--primary);
      transform: translateY(-2px);
    }
    .clickable-tile.active-tile {
      border-color: var(--primary);
      background-color: var(--primary-subtle);
      box-shadow: 0 0 0 2px var(--primary);
    }
    .tile-hint-icon {
      font-size: 20px;
      color: var(--text-light);
    }
    .active-tile .tile-hint-icon {
      color: var(--primary);
    }

    .sortable-th {
      transition: background-color 0.1s ease;
    }
    .sortable-th:has(.sort-button:hover),
    .sortable-th:has(.sort-button:focus-visible) {
      background-color: var(--border-color);
      color: var(--text-main);
    }
    .sort-button {
      appearance: none;
      display: inline-flex;
      align-items: center;
      width: 100%;
      padding: 0;
      border: 0;
      background: transparent;
      color: inherit;
      cursor: pointer;
      font: inherit;
      font-weight: inherit;
      text-align: left;
    }
    .sort-icon {
      font-size: 14px;
      margin-left: 4px;
      vertical-align: text-bottom;
    }

    .spin-icon {
      animation: spin 1s linear infinite;
    }
    @keyframes spin {
      from { transform: rotate(0deg); }
      to { transform: rotate(360deg); }
    }
    @media (max-width: 760px) {
      .section-header {
        flex-direction: column;
      }
      .artifact-summary {
        justify-content: flex-start;
      }
    }
  `]
})
export class BackupsComponent implements OnInit {
  private api = inject(CpApiService);

  checks = signal<BackupCheck[]>([]);
  reports = signal<InstanceBackupReport[]>([]);
  busy = signal(false);
  error = signal('');
  searchQuery = '';
  selectedFilter: 'all' | 'success' | 'failed' = 'all';

  sortCol: BackupSortCol = 'verifiedAt';
  sortDir: SortDir = 'desc';

  dt = dt;

  successCount = computed(() => this.checks().filter(c => c.success).length);
  failureCount = computed(() => this.checks().filter(c => !c.success).length);
  uploadedReportCount = computed(() => this.reports().filter(
    report => report.artifactStatus === 'UPLOADED').length);
  verifiedReportCount = computed(() => this.reports().filter(
    report => report.artifactStatus === 'VERIFIED').length);
  failedReportCount = computed(() => this.reports().filter(
    report => report.artifactStatus === 'FAILED').length);

  sortedAndFilteredChecks(): BackupCheck[] {
    const q = this.searchQuery.trim().toLowerCase();
    const filtered = this.checks().filter(c => {
      const matchesText = !q || (c.clientCode && c.clientCode.toLowerCase().includes(q));
      if (!matchesText) return false;

      if (this.selectedFilter === 'success') return c.success;
      if (this.selectedFilter === 'failed') return !c.success;

      return true;
    });

    return filtered.sort((a, b) => {
      let valA: string | number | boolean = '';
      let valB: string | number | boolean = '';

      switch (this.sortCol) {
        case 'clientCode': valA = a.clientCode; valB = b.clientCode; break;
        case 'success': valA = a.success ? 1 : 0; valB = b.success ? 1 : 0; break;
        case 'durationSec': valA = a.durationSec; valB = b.durationSec; break;
        case 'verifiedAt': valA = a.verifiedAt; valB = b.verifiedAt; break;
      }

      if (valA === valB) return 0;
      const comp = valA > valB ? 1 : -1;
      return this.sortDir === 'asc' ? comp : -comp;
    });
  }

  ngOnInit(): void {
    void this.load();
  }

  trackById(index: number, item: BackupCheck): number {
    return item.id;
  }

  trackByBackupId(index: number, item: InstanceBackupReport): string {
    return item.backupId;
  }

  artifactStatusLabel(status: InstanceBackupReport['artifactStatus']): string {
    switch (status) {
      case 'UPLOADED': return 'Загружен, не проверен';
      case 'VERIFIED': return 'Проверен';
      case 'FAILED': return 'Ошибка загрузки';
    }
  }

  artifactStatusClass(status: InstanceBackupReport['artifactStatus']): string {
    switch (status) {
      case 'UPLOADED': return 'badge-warning';
      case 'VERIFIED': return 'badge-success';
      case 'FAILED': return 'badge-danger';
    }
  }

  checksumFingerprint(checksum: string | null): string {
    if (!checksum) return '—';
    return checksum.length > 24
      ? `${checksum.slice(0, 12)}…${checksum.slice(-8)}`
      : checksum;
  }

  setSort(col: BackupSortCol): void {
    if (this.sortCol === col) {
      this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortCol = col;
      this.sortDir = 'asc';
    }
  }

  sortAria(col: BackupSortCol): 'ascending' | 'descending' | null {
    if (this.sortCol !== col) return null;
    return this.sortDir === 'asc' ? 'ascending' : 'descending';
  }

  async load(): Promise<void> {
    this.busy.set(true);
    try {
      const [reports, checks] = await Promise.all([
        this.api.backupReports(),
        this.api.backupChecks()
      ]);
      this.reports.set(reports);
      this.checks.set(checks);
      this.error.set('');
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось загрузить данные о резервных копиях'));
    } finally {
      this.busy.set(false);
    }
  }
}
