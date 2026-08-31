import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { BackupCheck, CpApiService } from '../core/cp-api.service';
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
            <h1 class="view-title">Проверка бэкапов</h1>
            <span class="count-badge">{{ checks().length }}</span>
          </div>
          <p class="view-desc">Журнал автоматических тестовых восстановлений баз данных экземпляров</p>
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

    <!-- KPI Metric Cards (Interactive) -->
    <div class="tiles">
      <div
        class="tile clickable-tile"
        [class.active-tile]="selectedFilter === 'all'"
        (click)="selectedFilter = 'all'"
        title="Все проверки"
      >
        <span class="tile-label">Всего проверок</span>
        <div style="display: flex; align-items: baseline; justify-content: space-between;">
          <span class="tile-value">{{ checks().length }}</span>
          <span class="material-symbols-outlined tile-hint-icon">history</span>
        </div>
      </div>

      <div
        class="tile clickable-tile"
        [class.active-tile]="selectedFilter === 'success'"
        (click)="selectedFilter = 'success'"
        title="Только успешные восстановления"
      >
        <span class="tile-label">Успешно восстановлено</span>
        <div style="display: flex; align-items: baseline; justify-content: space-between;">
          <span class="tile-value" style="color: var(--success);">{{ successCount() }}</span>
          <span class="material-symbols-outlined tile-hint-icon" style="color: var(--success);">check_circle</span>
        </div>
      </div>

      <div
        class="tile clickable-tile"
        [class.tile-alarm]="failureCount() > 0"
        [class.active-tile]="selectedFilter === 'failed'"
        (click)="selectedFilter = 'failed'"
        title="Только сбои"
      >
        <span class="tile-label">Сбоев при проверке</span>
        <div style="display: flex; align-items: baseline; justify-content: space-between;">
          <span class="tile-value">{{ failureCount() }}</span>
          <span class="material-symbols-outlined tile-hint-icon">{{ failureCount() > 0 ? 'error' : 'shield' }}</span>
        </div>
      </div>
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
              <th class="sortable-th" (click)="setSort('clientCode')">
                <span>Клиент</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'clientCode'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th class="sortable-th" (click)="setSort('success')">
                <span>Результат восстановления</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'success'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th class="sortable-th" (click)="setSort('durationSec')">
                <span>Длительность</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'durationSec'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th>Детали</th>
              <th class="sortable-th" (click)="setSort('verifiedAt')">
                <span>Дата и время проверки</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'verifiedAt'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
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
  `,
  styles: [`
    .clickable-tile {
      cursor: pointer;
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
      cursor: pointer;
      user-select: none;
      transition: background-color 0.1s ease;
    }
    .sortable-th:hover {
      background-color: var(--border-color);
      color: var(--text-main);
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
  `]
})
export class BackupsComponent implements OnInit {
  private api = inject(CpApiService);

  checks = signal<BackupCheck[]>([]);
  busy = signal(false);
  error = signal('');
  searchQuery = '';
  selectedFilter: 'all' | 'success' | 'failed' = 'all';

  sortCol: BackupSortCol = 'verifiedAt';
  sortDir: SortDir = 'desc';

  dt = dt;

  successCount = computed(() => this.checks().filter(c => c.success).length);
  failureCount = computed(() => this.checks().filter(c => !c.success).length);

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

  setSort(col: BackupSortCol): void {
    if (this.sortCol === col) {
      this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortCol = col;
      this.sortDir = 'asc';
    }
  }

  async load(): Promise<void> {
    this.busy.set(true);
    try {
      this.checks.set(await this.api.backupChecks());
      this.error.set('');
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось загрузить журнал проверок'));
    } finally {
      this.busy.set(false);
    }
  }
}
