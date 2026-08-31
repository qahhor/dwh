import { Component, OnDestroy, OnInit, computed, inject, signal, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CpApiService, FleetItem } from '../core/cp-api.service';
import { ago, dt, errorText } from '../core/format';
import { UiPaginationComponent } from '../shared/ui-pagination.component';

type SortColumn = 'instanceId' | 'clientName' | 'environment' | 'url' | 'appVersion' | 'schemaVersion' | 'lastHeartbeatAt' | 'health' | 'licenseStatus';
type SortDirection = 'asc' | 'desc';

@Component({
  selector: 'cp-fleet',
  standalone: true,
  imports: [CommonModule, FormsModule, UiPaginationComponent],
  template: `
    <!-- Page Header -->
    <div class="view-header">
      <div class="header-left">
        <h1 class="view-title">Флот экземпляров</h1>
        <span class="count-badge">{{ total() }}</span>
      </div>

      <div class="header-right">
        <button type="button" class="btn btn-secondary" (click)="load()" [disabled]="busy()" [attr.aria-busy]="busy()">
          <span class="material-symbols-outlined" [class.spin-icon]="busy()" aria-hidden="true">refresh</span>
          <span>Обновить</span>
        </button>
        <button type="button" class="btn btn-primary" (click)="openRegisterModal()">
          <span class="material-symbols-outlined" aria-hidden="true">add</span>
          <span>Новый экземпляр</span>
        </button>
      </div>
    </div>

    <!-- Error Alert -->
    <div *ngIf="error()" class="alert alert-error" role="alert">
      <span class="material-symbols-outlined">error</span>
      <span>{{ error() }}</span>
    </div>

    <!-- KPI Metric Cards -->
    <div class="tiles">
      <div
        class="tile clickable-tile"
        [class.selected-tile]="selectedFilter === 'all'"
        (click)="selectedFilter = 'all'"
        title="Показать все экземпляры"
      >
        <span class="tile-label">Всего экземпляров</span>
        <span class="tile-value">{{ total() }}</span>
      </div>

      <div
        class="tile clickable-tile"
        [class.tile-alarm]="problems() > 0"
        [class.selected-tile]="selectedFilter === 'DOWN'"
        (click)="selectedFilter = 'DOWN'"
        title="Показать только недоступные экземпляры"
      >
        <span class="tile-label">Требуют внимания</span>
        <span class="tile-value" [style.color]="problems() > 0 ? 'var(--danger)' : 'inherit'">{{ problems() }}</span>
      </div>

      <div
        class="tile clickable-tile"
        [class.selected-tile]="selectedFilter === 'ACTIVE_LICENSE'"
        (click)="selectedFilter = 'ACTIVE_LICENSE'"
        title="Показать активные лицензии"
      >
        <span class="tile-label">Активные лицензии</span>
        <span class="tile-value">{{ activeLicensesCount() }}</span>
      </div>

      <div class="tile">
        <span class="tile-label">Обновлено</span>
        <span class="tile-value tile-value-sm">{{ refreshedAt() }}</span>
      </div>
    </div>

    <!-- Filter & Search Toolbar (Single Unified Row like Portal) -->
    <div class="toolbar">
      <div class="search-field">
        <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
        <label class="sr-only" for="fleet-search">Поиск по названию или коду</label>
        <input
          id="fleet-search"
          name="fleetSearch"
          type="text"
          class="search-input"
          placeholder="Поиск по названию или коду..."
          [(ngModel)]="searchQuery"
          (ngModelChange)="currentPage = 1"
        />
        <button
          *ngIf="searchQuery"
          type="button"
          class="btn-icon"
          style="position: absolute; right: 4px; padding: 2px;"
          (click)="searchQuery = ''; currentPage = 1"
          title="Очистить"
        >
          <span class="material-symbols-outlined" style="font-size: 16px;">close</span>
        </button>
      </div>

      <div class="status-tabs" role="group" aria-label="Фильтр флота">
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedFilter === 'all'"
          (click)="selectedFilter = 'all'; currentPage = 1"
        >
          Все ({{ total() }})
        </button>
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedFilter === 'UP'"
          (click)="selectedFilter = 'UP'; currentPage = 1"
        >
          <span class="status-tab-dot" style="background-color: var(--success);"></span>
          На связи ({{ upCount() }})
        </button>
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedFilter === 'DOWN'"
          (click)="selectedFilter = 'DOWN'; currentPage = 1"
        >
          <span class="status-tab-dot" style="background-color: var(--danger);"></span>
          Недоступны ({{ problems() }})
        </button>
        <button
          type="button"
          class="status-tab"
          [class.active]="selectedFilter === 'ACTIVE_LICENSE'"
          (click)="selectedFilter = 'ACTIVE_LICENSE'; currentPage = 1"
        >
          <span class="status-tab-dot" style="background-color: var(--primary);"></span>
          Активные ({{ activeLicensesCount() }})
        </button>
      </div>

      <div class="filter-dropdowns" style="margin-left: auto;">
        <select
          class="form-select"
          style="height: 32px; padding: 4px 10px; font-size: 12px;"
          [(ngModel)]="selectedEnv"
          (ngModelChange)="currentPage = 1"
        >
          <option value="ALL">Все контуры</option>
          <option value="production">Production</option>
          <option value="staging">Staging</option>
          <option value="dev">Development</option>
        </select>
      </div>
    </div>

    <!-- Data Table Card -->
    <div class="card">
      <div class="table-scroll" role="region" aria-label="Таблица флота экземпляров" tabindex="0">
        <table aria-label="Флот экземпляров">
          <thead>
            <tr>
              <th class="sortable-th" (click)="setSort('instanceId')" style="width: 50px;">
                <span>ID</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'instanceId'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th class="sortable-th" (click)="setSort('clientName')">
                <span>Клиент</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'clientName'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th class="sortable-th" (click)="setSort('environment')">
                <span>Контур</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'environment'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th>Адрес</th>
              <th class="sortable-th" (click)="setSort('appVersion')">
                <span>Версия</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'appVersion'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th>Схема</th>
              <th class="sortable-th" (click)="setSort('lastHeartbeatAt')">
                <span>Последний heartbeat</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'lastHeartbeatAt'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th class="sortable-th" (click)="setSort('health')">
                <span>Связь</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'health'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th class="sortable-th" (click)="setSort('licenseStatus')">
                <span>Лицензия</span>
                <span class="material-symbols-outlined sort-icon" *ngIf="sortCol === 'licenseStatus'">
                  {{ sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                </span>
              </th>
              <th style="text-align: right; width: 90px;">Действия</th>
            </tr>
          </thead>
          <tbody>
            <tr
              *ngFor="let i of pagedItems(); trackBy: trackByInstance"
              class="clickable-row"
              (click)="openDrawer(i)"
            >
              <td class="tabular-nums mono" style="font-size: 12px; color: var(--text-muted);">#{{ i.instanceId }}</td>
              <td>
                <div style="font-weight: 600; color: var(--text-main);">{{ i.clientName }}</div>
                <div class="sub mono" style="font-size: 11px; color: var(--text-muted); margin-top: 2px;">
                  {{ i.clientCode }} · профиль {{ i.resourceProfile }}
                </div>
              </td>
              <td>
                <span class="badge badge-neutral" style="text-transform: capitalize;">{{ i.environment }}</span>
              </td>
              <td class="mono" style="font-size: 12px;" (click)="$event.stopPropagation()">
                <a [href]="i.url" target="_blank" rel="noopener" class="url-link">
                  {{ i.url }}
                  <span class="material-symbols-outlined" style="font-size: 13px;">open_in_new</span>
                </a>
              </td>
              <td class="tabular-nums mono" style="font-size: 12px;">{{ i.appVersion ?? '—' }}</td>
              <td class="tabular-nums mono" style="font-size: 12px;">{{ i.schemaVersion ?? '—' }}</td>
              <td>
                <div>{{ ago(i.lastHeartbeatAt) }}</div>
                <div *ngIf="i.lastHeartbeatAt" style="font-size: 11px; color: var(--text-muted); margin-top: 2px;">
                  {{ dt(i.lastHeartbeatAt) }}
                </div>
              </td>
              <td>
                <span class="badge" [ngClass]="badgeClass(i)">
                  {{ healthText(i) }}
                </span>
              </td>
              <td>
                <span class="badge" [ngClass]="{
                  'badge-success': i.licenseStatus === 'ACTIVE',
                  'badge-danger': i.licenseStatus === 'SUSPENDED',
                  'badge-warning': i.licenseStatus === 'READ_ONLY' || i.licenseStatus === 'PENDING_ACTIVATION'
                }">
                  {{ i.licenseStatus || 'ACTIVE' }}
                </span>
              </td>
              <td style="text-align: right;" (click)="$event.stopPropagation()">
                <div style="display: inline-flex; align-items: center; gap: 4px;">
                  <!-- Toggle Status Button (Icon only with Tooltip) -->
                  <button
                    *ngIf="i.licenseStatus === 'SUSPENDED'"
                    type="button"
                    class="btn-icon"
                    style="color: var(--success);"
                    (click)="toggleStatus(i, 'ACTIVE')"
                    [disabled]="busy()"
                    title="Активировать обслуживание"
                  >
                    <span class="material-symbols-outlined" style="font-size: 20px;">play_circle</span>
                  </button>
                  <button
                    *ngIf="i.licenseStatus !== 'SUSPENDED'"
                    type="button"
                    class="btn-icon"
                    style="color: var(--danger);"
                    (click)="toggleStatus(i, 'SUSPENDED')"
                    [disabled]="busy()"
                    title="Приостановить обслуживание"
                  >
                    <span class="material-symbols-outlined" style="font-size: 20px;">pause_circle</span>
                  </button>

                  <!-- View Details (Eye icon) -->
                  <button
                    type="button"
                    class="btn-icon"
                    (click)="openDrawer(i)"
                    title="Просмотреть карточку"
                  >
                    <span class="material-symbols-outlined" style="font-size: 20px;">visibility</span>
                  </button>
                </div>
              </td>
            </tr>
            <tr *ngIf="sortedAndFilteredItems().length === 0 && !busy()">
              <td colspan="10" class="empty">
                <div style="display: flex; flex-direction: column; align-items: center; gap: 8px;">
                  <span class="material-symbols-outlined" style="font-size: 36px; color: var(--text-light);">search_off</span>
                  <span>Экземпляров по заданным фильтрам не найдено.</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Bottom Pagination Bar (100% Portal Parity) -->
      <ui-pagination
        [totalItems]="sortedAndFilteredItems().length"
        [pageSize]="pageSize"
        [currentPage]="currentPage"
        (pageChange)="currentPage = $event"
        (pageSizeChange)="pageSize = $event; currentPage = 1"
      ></ui-pagination>
    </div>

    <!-- SLIDE-OVER DRAWER: Детальная карточка инстанса -->
    <div class="drawer-backdrop" *ngIf="selectedInstance" (click)="closeDrawer()"></div>
    <aside class="drawer-panel" *ngIf="selectedInstance" role="dialog" aria-modal="true" aria-label="Карточка инстанса">
      <div class="drawer-header">
        <div>
          <div style="display: flex; align-items: center; gap: 8px;">
            <h2 class="drawer-title">{{ selectedInstance.clientName }}</h2>
            <span class="badge badge-neutral mono">{{ selectedInstance.clientCode }}</span>
          </div>
          <p class="drawer-subtitle">ID экземпляра: #{{ selectedInstance.instanceId }} · Контур {{ selectedInstance.environment }}</p>
        </div>
        <button type="button" class="btn-icon" (click)="closeDrawer()" aria-label="Закрыть шторку">
          <span class="material-symbols-outlined">close</span>
        </button>
      </div>

      <div class="drawer-body">
        <!-- Status Summary Section -->
        <div class="drawer-section">
          <h3 class="section-title">Состояние и лицензия</h3>
          <div class="drawer-grid">
            <div class="drawer-info-block">
              <span class="info-label">Сетевая связь (Heartbeat)</span>
              <span class="badge" [ngClass]="badgeClass(selectedInstance)" style="margin-top: 4px;">
                {{ healthText(selectedInstance) }}
              </span>
            </div>
            <div class="drawer-info-block">
              <span class="info-label">Статус лицензии</span>
              <span class="badge" [ngClass]="{
                'badge-success': selectedInstance.licenseStatus === 'ACTIVE',
                'badge-danger': selectedInstance.licenseStatus === 'SUSPENDED',
                'badge-warning': selectedInstance.licenseStatus === 'READ_ONLY' || selectedInstance.licenseStatus === 'PENDING_ACTIVATION'
              }" style="margin-top: 4px;">
                {{ selectedInstance.licenseStatus || 'ACTIVE' }}
              </span>
            </div>
          </div>
        </div>

        <!-- Network & Endpoint Section -->
        <div class="drawer-section">
          <h3 class="section-title">Точка доступа и адрес</h3>
          <div class="drawer-info-block">
            <span class="info-label">URL-адрес сервиса</span>
            <div style="display: flex; align-items: center; gap: 8px; margin-top: 4px;">
              <code class="mono url-box">{{ selectedInstance.url }}</code>
              <button type="button" class="btn btn-secondary btn-sm" (click)="copyText(selectedInstance.url)">
                <span class="material-symbols-outlined" style="font-size: 15px;">{{ copied() ? 'check' : 'content_copy' }}</span>
                <span>{{ copied() ? 'Скопировано' : 'Копировать' }}</span>
              </button>
              <a [href]="selectedInstance.url" target="_blank" rel="noopener" class="btn btn-secondary btn-sm">
                <span class="material-symbols-outlined" style="font-size: 15px;">open_in_new</span>
              </a>
            </div>
          </div>
        </div>

        <!-- Versions & Runtime Section -->
        <div class="drawer-section">
          <h3 class="section-title">Версионирование</h3>
          <div class="drawer-grid">
            <div class="drawer-info-block">
              <span class="info-label">Версия приложения</span>
              <span class="info-value mono">{{ selectedInstance.appVersion || '—' }}</span>
            </div>
            <div class="drawer-info-block">
              <span class="info-label">Версия схемы БД (Flyway)</span>
              <span class="info-value mono">{{ selectedInstance.schemaVersion || '—' }}</span>
            </div>
            <div class="drawer-info-block">
              <span class="info-label">Профиль ресурсов</span>
              <span class="info-value">Профиль {{ selectedInstance.resourceProfile }}</span>
            </div>
            <div class="drawer-info-block">
              <span class="info-label">Последний пинг</span>
              <span class="info-value">{{ ago(selectedInstance.lastHeartbeatAt) }}</span>
            </div>
          </div>
        </div>

        <!-- Action Section -->
        <div class="drawer-section">
          <h3 class="section-title">Управление жизненным циклом</h3>
          <div style="display: flex; gap: 10px; margin-top: 8px;">
            <button
              *ngIf="selectedInstance.licenseStatus === 'SUSPENDED'"
              type="button"
              class="btn btn-primary"
              (click)="toggleStatus(selectedInstance, 'ACTIVE')"
              [disabled]="busy()"
            >
              <span class="material-symbols-outlined">play_arrow</span>
              <span>Активировать экземпляр</span>
            </button>
            <button
              *ngIf="selectedInstance.licenseStatus !== 'SUSPENDED'"
              type="button"
              class="btn btn-danger"
              (click)="toggleStatus(selectedInstance, 'SUSPENDED')"
              [disabled]="busy()"
            >
              <span class="material-symbols-outlined">pause</span>
              <span>Приостановить обслуживание</span>
            </button>
          </div>
        </div>
      </div>
    </aside>
  `,
  styles: [`
    .clickable-tile {
      cursor: pointer;
      user-select: none;
      transition: all 0.15s ease;
    }
    .clickable-tile:hover {
      border-color: var(--primary);
      transform: translateY(-2px);
    }
    .clickable-tile.selected-tile {
      border-color: var(--primary);
      box-shadow: 0 0 0 2px var(--primary-subtle);
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

    .clickable-row {
      cursor: pointer;
    }

    .url-link {
      color: var(--primary);
      text-decoration: none;
      display: inline-flex;
      align-items: center;
      gap: 3px;
    }
    .url-link:hover {
      text-decoration: underline;
    }

    .spin-icon {
      animation: spin 1s linear infinite;
    }
    @keyframes spin {
      from { transform: rotate(0deg); }
      to { transform: rotate(360deg); }
    }

    /* Slide-Over Drawer */
    .drawer-backdrop {
      position: fixed;
      inset: 0;
      background-color: rgba(15, 23, 42, 0.5);
      backdrop-filter: blur(2px);
      z-index: 1200;
      animation: fadeIn 0.2s ease;
    }

    .drawer-panel {
      position: fixed;
      top: 0;
      right: 0;
      bottom: 0;
      width: 480px;
      max-width: 90vw;
      background-color: var(--bg-surface);
      border-left: 1px solid var(--border-color);
      box-shadow: var(--shadow-overlay);
      z-index: 1300;
      display: flex;
      flex-direction: column;
      animation: slideIn 0.25s cubic-bezier(0.16, 1, 0.3, 1);
    }

    @keyframes fadeIn {
      from { opacity: 0; }
      to { opacity: 1; }
    }

    @keyframes slideIn {
      from { transform: translateX(100%); }
      to { transform: translateX(0); }
    }

    .drawer-header {
      padding: 18px 22px;
      border-bottom: 1px solid var(--border-color);
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
    }
    .drawer-title {
      font-size: 17px;
      font-weight: 700;
      color: var(--text-main);
    }
    .drawer-subtitle {
      font-size: 12px;
      color: var(--text-muted);
      margin-top: 3px;
    }

    .drawer-body {
      flex: 1;
      overflow-y: auto;
      padding: 22px;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }

    .drawer-section {
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding-bottom: 16px;
      border-bottom: 1px solid var(--border-subtle);
    }
    .drawer-section:last-child {
      border-bottom: none;
      padding-bottom: 0;
    }

    .section-title {
      font-size: 12px;
      text-transform: uppercase;
      font-weight: 700;
      letter-spacing: 0.5px;
      color: var(--text-muted);
    }

    .drawer-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
    }

    .drawer-info-block {
      display: flex;
      flex-direction: column;
      gap: 3px;
    }
    .info-label {
      font-size: 11px;
      color: var(--text-light);
      font-weight: 500;
    }
    .info-value {
      font-size: 13px;
      color: var(--text-main);
      font-weight: 600;
    }

    .url-box {
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      padding: 6px 10px;
      border-radius: var(--radius-sm);
      font-size: 12px;
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  `]
})
export class FleetComponent implements OnInit, OnDestroy {
  private api = inject(CpApiService);

  items = signal<FleetItem[]>([]);
  timeout = signal(10);
  busy = signal(false);
  error = signal('');
  refreshedAt = signal('—');

  searchQuery = '';
  selectedFilter: 'all' | 'UP' | 'DOWN' | 'ACTIVE_LICENSE' = 'all';
  selectedEnv = 'ALL';

  sortCol: SortColumn = 'instanceId';
  sortDir: SortDirection = 'desc';

  currentPage = 1;
  pageSize = 10;

  selectedInstance: FleetItem | null = null;
  copied = signal(false);

  private timer?: ReturnType<typeof setInterval>;

  ago = ago;
  dt = dt;

  total = computed(() => this.items().length);
  problems = computed(() => this.items().filter(i => i.health !== 'UP').length);
  upCount = computed(() => this.items().filter(i => i.health === 'UP').length);
  activeLicensesCount = computed(() => this.items().filter(i => (i.licenseStatus || 'ACTIVE') === 'ACTIVE').length);

  @HostListener('window:keydown.escape')
  handleEscape(): void {
    if (this.selectedInstance) {
      this.closeDrawer();
    }
  }

  sortedAndFilteredItems(): FleetItem[] {
    const q = this.searchQuery.trim().toLowerCase();
    const filtered = this.items().filter(i => {
      const matchesText = !q
        || (i.clientName && i.clientName.toLowerCase().includes(q))
        || (i.clientCode && i.clientCode.toLowerCase().includes(q))
        || (i.url && i.url.toLowerCase().includes(q));

      if (!matchesText) return false;

      if (this.selectedFilter === 'UP') return i.health === 'UP';
      if (this.selectedFilter === 'DOWN') return i.health !== 'UP';
      if (this.selectedFilter === 'ACTIVE_LICENSE') return (i.licenseStatus || 'ACTIVE') === 'ACTIVE';

      if (this.selectedEnv !== 'ALL' && i.environment !== this.selectedEnv) return false;

      return true;
    });

    return filtered.sort((a, b) => {
      let valA: string | number | null = null;
      let valB: string | number | null = null;

      switch (this.sortCol) {
        case 'instanceId': valA = a.instanceId; valB = b.instanceId; break;
        case 'clientName': valA = a.clientName; valB = b.clientName; break;
        case 'environment': valA = a.environment; valB = b.environment; break;
        case 'url': valA = a.url; valB = b.url; break;
        case 'appVersion': valA = a.appVersion ?? ''; valB = b.appVersion ?? ''; break;
        case 'schemaVersion': valA = a.schemaVersion ?? ''; valB = b.schemaVersion ?? ''; break;
        case 'lastHeartbeatAt': valA = a.lastHeartbeatAt ?? ''; valB = b.lastHeartbeatAt ?? ''; break;
        case 'health': valA = a.health; valB = b.health; break;
        case 'licenseStatus': valA = a.licenseStatus ?? 'ACTIVE'; valB = b.licenseStatus ?? 'ACTIVE'; break;
      }

      if (valA === valB) return 0;
      if (valA === null || valA === '') return 1;
      if (valB === null || valB === '') return -1;

      const comp = valA > valB ? 1 : -1;
      return this.sortDir === 'asc' ? comp : -comp;
    });
  }

  pagedItems(): FleetItem[] {
    const list = this.sortedAndFilteredItems();
    const start = (this.currentPage - 1) * this.pageSize;
    return list.slice(start, start + this.pageSize);
  }

  ngOnInit(): void {
    void this.load();
    this.timer = setInterval(() => void this.load(), 30_000);
  }

  ngOnDestroy(): void {
    if (this.timer) clearInterval(this.timer);
  }

  trackByInstance(index: number, item: FleetItem): number {
    return item.instanceId;
  }

  setSort(col: SortColumn): void {
    if (this.sortCol === col) {
      this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortCol = col;
      this.sortDir = 'asc';
    }
  }

  openDrawer(item: FleetItem): void {
    this.selectedInstance = item;
    document.body.classList.add('modal-open');
  }

  closeDrawer(): void {
    this.selectedInstance = null;
    document.body.classList.remove('modal-open');
  }

  openRegisterModal(): void {
    // Navigate or trigger clients tab
    window.location.href = '/clients';
  }

  async copyText(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    } catch {
      // ignore
    }
  }

  async load(): Promise<void> {
    this.busy.set(true);
    try {
      const resp = await this.api.fleet();
      this.items.set(resp.items);
      this.timeout.set(resp.heartbeatTimeoutMinutes);
      this.error.set('');
      this.refreshedAt.set(new Date().toLocaleTimeString());

      if (this.selectedInstance) {
        const updated = resp.items.find(i => i.instanceId === this.selectedInstance?.instanceId);
        if (updated) this.selectedInstance = updated;
      }
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось загрузить состояние флота'));
    } finally {
      this.busy.set(false);
    }
  }

  async toggleStatus(item: FleetItem, newStatus: string): Promise<void> {
    if (!confirm(`Вы действительно хотите изменить статус лицензии для ${item.clientName} на ${newStatus}?`)) {
      return;
    }
    this.busy.set(true);
    try {
      await this.api.updateInstanceStatus(item.instanceId, newStatus);
      await this.load();
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось обновить статус экземпляра'));
    } finally {
      this.busy.set(false);
    }
  }

  badgeClass(i: FleetItem): string {
    switch (i.health) {
      case 'UP': return 'badge-success';
      case 'DOWN': return 'badge-danger';
      case 'NEVER': return 'badge-neutral';
      default: return 'badge-neutral';
    }
  }

  healthText(i: FleetItem): string {
    switch (i.health) {
      case 'UP': return 'на связи';
      case 'DOWN': return 'недоступен';
      case 'NEVER': return 'нет связи';
      default: return i.health;
    }
  }
}
