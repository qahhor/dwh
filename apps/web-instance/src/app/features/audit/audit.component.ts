import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../core/services/toast.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../shared/ui/ui-modal.component';
import { UiPaginationComponent } from '../../shared/ui/ui-pagination.component';

export interface AuditRecord {
  id: number;
  tableName: string;
  rowPk: string;
  event: 'I' | 'U' | 'D';
  changedBy?: number;
  sessionId?: number;
  isApi: boolean;
  changedAt: string;
  changedColumns: string[];
  oldRow?: Record<string, any>;
  newRow?: Record<string, any>;
  changedByName?: string;
  changedByLogin?: string;
}

export interface SecurityEventRecord {
  id: number;
  eventType: string;
  userId?: number;
  ip: string;
  userAgent?: string;
  details: Record<string, any>;
  createdAt: string;
  userName?: string;
  userLogin?: string;
}

export interface AuditStats {
  totalAuditLogs: number;
  totalSecurityEvents: number;
  securityEventsLast24h: number;
  failedLoginsLast24h: number;
}

@Component({
  selector: 'app-audit',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    UiButtonComponent,
    UiModalComponent,
    UiPaginationComponent
  ],
  template: `
    <div class="audit-page">
      <!-- Page Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">Аудит и безопасность</h1>
          <span class="view-subtitle">Неизменяемый журнал мутаций данных и security-событий</span>
        </div>
        <div class="header-actions">
          <button class="icon-refresh-btn" (click)="refreshAll()" title="Обновить журнал">
            <span class="material-symbols-outlined">refresh</span>
          </button>
        </div>
      </div>

      <!-- Stats Cards -->
      <div class="stats-grid" *ngIf="stats() as s">
        <div class="stat-card">
          <div class="stat-icon-wrapper blue">
            <span class="material-symbols-outlined">history</span>
          </div>
          <div class="stat-info">
            <span class="stat-value">{{ s.totalAuditLogs }}</span>
            <span class="stat-label">Всего записей аудита</span>
          </div>
        </div>

        <div class="stat-card">
          <div class="stat-icon-wrapper indigo">
            <span class="material-symbols-outlined">security</span>
          </div>
          <div class="stat-info">
            <span class="stat-value">{{ s.totalSecurityEvents }}</span>
            <span class="stat-label">Событий безопасности</span>
          </div>
        </div>

        <div class="stat-card">
          <div class="stat-icon-wrapper amber">
            <span class="material-symbols-outlined">schedule</span>
          </div>
          <div class="stat-info">
            <span class="stat-value">{{ s.securityEventsLast24h }}</span>
            <span class="stat-label">Событий за 24 часа</span>
          </div>
        </div>

        <div class="stat-card">
          <div class="stat-icon-wrapper red">
            <span class="material-symbols-outlined">gpp_bad</span>
          </div>
          <div class="stat-info">
            <span class="stat-value">{{ s.failedLoginsLast24h }}</span>
            <span class="stat-label">Неудачных входов / блокировок</span>
          </div>
        </div>
      </div>

      <!-- Tabs Navigation -->
      <div class="tabs-nav-bar">
        <div class="tab-buttons">
          <button
            class="nav-tab-btn"
            [class.active]="activeTab === 'audit'"
            (click)="setTab('audit')"
          >
            <span class="material-symbols-outlined">database</span>
            Журнал изменений данных
            <span class="tab-counter">{{ auditLogs().length }}</span>
          </button>
          <button
            class="nav-tab-btn"
            [class.active]="activeTab === 'security'"
            (click)="setTab('security')"
          >
            <span class="material-symbols-outlined">shield</span>
            События безопасности
            <span class="tab-counter">{{ securityEvents().length }}</span>
          </button>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 1: AUDIT LOGS -->
      <!-- =================================================================== -->
      <div class="tab-content" *ngIf="activeTab === 'audit'">
        <!-- Filter Toolbar -->
        <div class="filter-toolbar">
          <div class="filter-group">
            <select class="filter-select" [(ngModel)]="tableFilter" (change)="loadAuditLogs()">
              <option value="">Все таблицы</option>
              <option value="md_users">Пользователи (md_users)</option>
              <option value="ms_tasks">Задачи (ms_tasks)</option>
              <option value="ms_projects">Проекты (ms_projects)</option>
              <option value="md_roles">Роли и права (md_roles)</option>
              <option value="md_custom_fields">Динамические поля (md_custom_fields)</option>
            </select>

            <select class="filter-select" [(ngModel)]="eventFilter" (change)="loadAuditLogs()">
              <option value="">Все действия</option>
              <option value="I">Создание (INSERT)</option>
              <option value="U">Изменение (UPDATE)</option>
              <option value="D">Удаление (DELETE)</option>
            </select>
          </div>
        </div>

        <!-- Audit Table -->
        <div class="table-container">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 70px;">ID</th>
                <th>Таблица</th>
                <th>PK</th>
                <th>Действие</th>
                <th>Кто изменил</th>
                <th>Канал</th>
                <th>Дата и время</th>
                <th style="width: 80px; text-align: right;">Diff</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let item of paginatedAuditLogs()" (click)="selectAuditRecord(item)" class="clickable-row">
                <td class="tabular-nums font-mono text-muted">#{{ item.id }}</td>
                <td>
                  <span class="table-tag font-mono">{{ item.tableName }}</span>
                </td>
                <td>
                  <span class="pk-pill font-mono">{{ item.rowPk }}</span>
                </td>
                <td>
                  <span class="event-badge" [ngClass]="getEventBadgeClass(item.event)">
                    {{ getEventName(item.event) }}
                  </span>
                </td>
                <td>
                  <div class="user-cell" *ngIf="item.changedByName">
                    <span class="user-name">{{ item.changedByName }}</span>
                    <span class="user-sub text-muted text-xs">&#64;{{ item.changedByLogin }}</span>
                  </div>
                  <span *ngIf="!item.changedByName" class="text-muted">Система</span>
                </td>
                <td>
                  <span class="channel-pill" [class.api-pill]="item.isApi">
                    <span class="material-symbols-outlined">{{ item.isApi ? 'terminal' : 'web' }}</span>
                    {{ item.isApi ? 'REST API' : 'Web UI' }}
                  </span>
                </td>
                <td>
                  <span class="date-cell tabular-nums">{{ item.changedAt | date:'dd.MM.yyyy HH:mm:ss' }}</span>
                </td>
                <td style="text-align: right;">
                  <button class="diff-btn" title="Просмотр изменений">
                    <span class="material-symbols-outlined">difference</span>
                  </button>
                </td>
              </tr>

              <tr *ngIf="auditLogs().length === 0 && !isLoading()">
                <td colspan="8" class="empty-state-cell">
                  <div class="empty-state-box">
                    <span class="material-symbols-outlined empty-icon">history_toggle_off</span>
                    <h3>Записей аудита не найдено</h3>
                    <p>Попробуйте сбросить выбранные фильтры</p>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <ui-pagination
          *ngIf="auditLogs().length > 0"
          [totalItems]="auditLogs().length"
          [pageSize]="auditPageSize"
          [currentPage]="auditCurrentPage"
          (pageChange)="auditCurrentPage = $event"
          (pageSizeChange)="auditPageSize = $event; auditCurrentPage = 1"
        ></ui-pagination>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 2: SECURITY EVENTS -->
      <!-- =================================================================== -->
      <div class="tab-content" *ngIf="activeTab === 'security'">
        <!-- Filter Toolbar -->
        <div class="filter-toolbar">
          <div class="filter-group">
            <select class="filter-select" [(ngModel)]="secEventTypeFilter" (change)="loadSecurityEvents()">
              <option value="">Все события</option>
              <option value="LOGIN_SUCCESS">Успешный вход (LOGIN_SUCCESS)</option>
              <option value="LOGIN_FAILED">Ошибка входа (LOGIN_FAILED)</option>
              <option value="LOGIN_LOCKED">Блокировка brute-force (LOGIN_LOCKED)</option>
              <option value="IP_RATE_LIMITED">Rate Limit IP (IP_RATE_LIMITED)</option>
              <option value="PASSWORD_CHANGED">Смена пароля (PASSWORD_CHANGED)</option>
              <option value="API_TOKEN_CREATED">Выпуск API токена</option>
            </select>

            <div class="search-box">
              <span class="material-symbols-outlined search-icon">search</span>
              <input
                type="text"
                class="search-input"
                placeholder="Поиск по IP..."
                [(ngModel)]="secIpFilter"
                (keyup.enter)="loadSecurityEvents()"
              />
            </div>
          </div>
        </div>

        <!-- Security Events Table -->
        <div class="table-container">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 70px;">ID</th>
                <th>Событие</th>
                <th>Пользователь</th>
                <th>IP-адрес</th>
                <th>User-Agent / Устройство</th>
                <th>Дата и время</th>
                <th style="width: 80px; text-align: right;">Детали</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let item of paginatedSecurityEvents()" (click)="selectSecurityEvent(item)" class="clickable-row">
                <td class="tabular-nums font-mono text-muted">#{{ item.id }}</td>
                <td>
                  <span class="sec-event-badge" [ngClass]="getSecurityEventBadgeClass(item.eventType)">
                    <span class="material-symbols-outlined">{{ getSecurityEventIcon(item.eventType) }}</span>
                    {{ item.eventType }}
                  </span>
                </td>
                <td>
                  <div class="user-cell" *ngIf="item.userName">
                    <span class="user-name">{{ item.userName }}</span>
                    <span class="user-sub text-muted text-xs">&#64;{{ item.userLogin }}</span>
                  </div>
                  <span *ngIf="!item.userName" class="text-muted">{{ item.details['login'] || 'Гость' }}</span>
                </td>
                <td>
                  <span class="ip-pill font-mono">{{ item.ip }}</span>
                </td>
                <td>
                  <span class="ua-cell text-muted text-xs" [title]="item.userAgent || ''">
                    {{ formatUserAgent(item.userAgent) }}
                  </span>
                </td>
                <td>
                  <span class="date-cell tabular-nums">{{ item.createdAt | date:'dd.MM.yyyy HH:mm:ss' }}</span>
                </td>
                <td style="text-align: right;">
                  <button class="diff-btn" title="Просмотр деталей">
                    <span class="material-symbols-outlined">info</span>
                  </button>
                </td>
              </tr>

              <tr *ngIf="securityEvents().length === 0 && !isLoading()">
                <td colspan="7" class="empty-state-cell">
                  <div class="empty-state-box">
                    <span class="material-symbols-outlined empty-icon">verified_user</span>
                    <h3>Событий безопасности не найдено</h3>
                    <p>Все подозрительные события и входы фиксируются здесь</p>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <ui-pagination
          *ngIf="securityEvents().length > 0"
          [totalItems]="securityEvents().length"
          [pageSize]="secPageSize"
          [currentPage]="secCurrentPage"
          (pageChange)="secCurrentPage = $event"
          (pageSizeChange)="secPageSize = $event; secCurrentPage = 1"
        ></ui-pagination>
      </div>

      <!-- =================================================================== -->
      <!-- MODAL: AUDIT DIFF VIEWER -->
      <!-- =================================================================== -->
      <ui-modal
        [isOpen]="selectedAudit !== null"
        title="Детали изменения записи (Visual Diff)"
        size="lg"
        (close)="selectedAudit = null"
      >
        <div body *ngIf="selectedAudit as audit" class="diff-modal-body">
          <div class="diff-meta-grid">
            <div class="meta-item">
              <span class="meta-label">Таблица:</span>
              <span class="meta-val font-mono">{{ audit.tableName }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">PK:</span>
              <span class="meta-val font-mono">{{ audit.rowPk }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">Действие:</span>
              <span class="event-badge" [ngClass]="getEventBadgeClass(audit.event)">{{ getEventName(audit.event) }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">Автор:</span>
              <span class="meta-val">{{ audit.changedByName ? audit.changedByName + ' (@' + audit.changedByLogin + ')' : 'Система' }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">Дата:</span>
              <span class="meta-val">{{ audit.changedAt | date:'dd.MM.yyyy HH:mm:ss' }}</span>
            </div>
          </div>

          <!-- Diff Table -->
          <div class="diff-section-title">Сравнение полей (Diff):</div>
          <div class="diff-table-box" *ngIf="getDiffKeys(audit).length > 0; else noDiff">
            <table class="diff-table">
              <thead>
                <tr>
                  <th style="width: 25%;">Поле</th>
                  <th style="width: 37.5%;">Предыдущее значение</th>
                  <th style="width: 37.5%;">Новое значение</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let key of getDiffKeys(audit)">
                  <td class="font-mono field-name">{{ key }}</td>
                  <td class="diff-cell old-cell">
                    <pre class="diff-val">{{ formatValue(audit.oldRow?.[key]) }}</pre>
                  </td>
                  <td class="diff-cell new-cell">
                    <pre class="diff-val">{{ formatValue(audit.newRow?.[key]) }}</pre>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <ng-template #noDiff>
            <div class="no-diff-msg">Нет подробных данных diff для этой операции</div>
          </ng-template>
        </div>
        <div footer class="modal-footer-actions">
          <ui-button variant="secondary" (click)="selectedAudit = null">Закрыть</ui-button>
        </div>
      </ui-modal>

      <!-- =================================================================== -->
      <!-- MODAL: SECURITY EVENT DETAILS -->
      <!-- =================================================================== -->
      <ui-modal
        [isOpen]="selectedSecEvent !== null"
        title="Событие безопасности"
        size="md"
        (close)="selectedSecEvent = null"
      >
        <div body *ngIf="selectedSecEvent as ev" class="sec-modal-body">
          <div class="diff-meta-grid">
            <div class="meta-item">
              <span class="meta-label">Тип события:</span>
              <span class="sec-event-badge" [ngClass]="getSecurityEventBadgeClass(ev.eventType)">
                {{ ev.eventType }}
              </span>
            </div>
            <div class="meta-item">
              <span class="meta-label">IP:</span>
              <span class="meta-val font-mono">{{ ev.ip }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">Пользователь:</span>
              <span class="meta-val">{{ ev.userName ? ev.userName + ' (@' + ev.userLogin + ')' : (ev.details['login'] || '—') }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">Дата:</span>
              <span class="meta-val">{{ ev.createdAt | date:'dd.MM.yyyy HH:mm:ss' }}</span>
            </div>
            <div class="meta-item full-width" *ngIf="ev.userAgent">
              <span class="meta-label">User-Agent:</span>
              <span class="meta-val text-xs font-mono">{{ ev.userAgent }}</span>
            </div>
          </div>

          <div class="diff-section-title">Параметры события (JSON):</div>
          <pre class="json-details-viewer">{{ ev.details | json }}</pre>
        </div>
        <div footer class="modal-footer-actions">
          <ui-button variant="secondary" (click)="selectedSecEvent = null">Закрыть</ui-button>
        </div>
      </ui-modal>
    </div>
  `,
  styles: [`
    .audit-page {
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
      flex-direction: column;
      gap: 4px;
    }

    .view-title {
      font-size: 24px;
      font-weight: 700;
      color: var(--text-primary, #f1f5f9);
      margin: 0;
    }

    .view-subtitle {
      font-size: 13px;
      color: #94a3b8;
    }

    /* Stats Grid */
    .stats-grid {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 16px;
    }

    @media (max-width: 1024px) {
      .stats-grid {
        grid-template-columns: repeat(2, 1fr);
      }
    }

    @media (max-width: 640px) {
      .stats-grid {
        grid-template-columns: 1fr;
      }
    }

    .stat-card {
      background: var(--bg-card, #1e293b);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 12px;
      padding: 16px;
      display: flex;
      align-items: center;
      gap: 14px;
    }

    .stat-icon-wrapper {
      width: 44px;
      height: 44px;
      border-radius: 10px;
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .stat-icon-wrapper.blue { background: rgba(56, 189, 248, 0.15); color: #38bdf8; }
    .stat-icon-wrapper.indigo { background: rgba(99, 102, 241, 0.15); color: #818cf8; }
    .stat-icon-wrapper.amber { background: rgba(245, 158, 11, 0.15); color: #f59e0b; }
    .stat-icon-wrapper.red { background: rgba(239, 68, 68, 0.15); color: #ef4444; }

    .stat-info {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .stat-value {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-primary, #f1f5f9);
    }

    .stat-label {
      font-size: 12px;
      color: #94a3b8;
    }

    /* Tabs */
    .tabs-nav-bar {
      display: flex;
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);
    }

    .tab-buttons {
      display: flex;
      gap: 4px;
    }

    .nav-tab-btn {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 10px 16px;
      border: none;
      background: transparent;
      color: #94a3b8;
      font-size: 14px;
      font-weight: 500;
      cursor: pointer;
      border-bottom: 2px solid transparent;
      transition: all 0.15s ease;
    }

    .nav-tab-btn .material-symbols-outlined {
      font-size: 18px;
    }

    .nav-tab-btn.active {
      color: var(--color-primary, #818cf8);
      border-bottom-color: var(--color-primary, #818cf8);
    }

    .tab-counter {
      padding: 1px 6px;
      border-radius: 10px;
      font-size: 11px;
      font-weight: 600;
      background: rgba(255, 255, 255, 0.06);
    }

    .nav-tab-btn.active .tab-counter {
      background: rgba(99, 102, 241, 0.15);
      color: #818cf8;
    }

    /* Filter Toolbar */
    .filter-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 16px;
      flex-wrap: wrap;
    }

    .filter-group {
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
    }

    .filter-select {
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 8px;
      padding: 7px 12px;
      color: var(--text-primary, #f1f5f9);
      font-size: 13px;
      outline: none;
    }

    .filter-select option {
      background: #1e293b;
      color: #f1f5f9;
    }

    .search-box {
      display: flex;
      align-items: center;
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 8px;
      padding: 0 10px;
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
    }

    .icon-refresh-btn {
      width: 36px;
      height: 36px;
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

    .clickable-row {
      cursor: pointer;
      transition: background 0.15s ease;
    }

    .clickable-row:hover td {
      background: rgba(255, 255, 255, 0.03);
    }

    .table-tag {
      padding: 2px 6px;
      border-radius: 4px;
      background: rgba(255, 255, 255, 0.05);
      color: #cbd5e1;
      font-size: 12px;
    }

    .pk-pill {
      font-size: 12px;
      color: #94a3b8;
    }

    .event-badge {
      font-size: 11px;
      font-weight: 700;
      padding: 2px 8px;
      border-radius: 4px;
      text-transform: uppercase;
    }

    .event-badge.insert { background: rgba(52, 211, 153, 0.15); color: #34d399; }
    .event-badge.update { background: rgba(96, 165, 250, 0.15); color: #60a5fa; }
    .event-badge.delete { background: rgba(248, 113, 113, 0.15); color: #f87171; }

    .sec-event-badge {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 11px;
      font-weight: 600;
      padding: 2px 8px;
      border-radius: 4px;
    }

    .sec-event-badge .material-symbols-outlined {
      font-size: 14px;
    }

    .sec-event-badge.success { background: rgba(52, 211, 153, 0.15); color: #34d399; }
    .sec-event-badge.warning { background: rgba(251, 191, 36, 0.15); color: #fbbf24; }
    .sec-event-badge.danger { background: rgba(248, 113, 113, 0.15); color: #f87171; }
    .sec-event-badge.info { background: rgba(99, 102, 241, 0.15); color: #818cf8; }

    .user-cell {
      display: flex;
      flex-direction: column;
    }

    .user-name {
      font-weight: 500;
      color: var(--text-primary, #f1f5f9);
    }

    .channel-pill {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 11px;
      color: #94a3b8;
    }

    .channel-pill .material-symbols-outlined {
      font-size: 14px;
    }

    .channel-pill.api-pill {
      color: #38bdf8;
    }

    .ip-pill {
      font-size: 12px;
      padding: 2px 6px;
      border-radius: 4px;
      background: rgba(255, 255, 255, 0.04);
      color: #cbd5e1;
    }

    .ua-cell {
      max-width: 200px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      display: block;
    }

    .diff-btn {
      width: 28px;
      height: 28px;
      border-radius: 4px;
      border: none;
      background: rgba(255, 255, 255, 0.04);
      color: #94a3b8;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
    }

    .diff-btn:hover {
      background: rgba(99, 102, 241, 0.15);
      color: #818cf8;
    }

    .diff-btn .material-symbols-outlined {
      font-size: 16px;
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

    /* Modal Styles */
    .diff-meta-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 10px 16px;
      background: rgba(255, 255, 255, 0.03);
      padding: 12px 16px;
      border-radius: 8px;
      margin-bottom: 16px;
    }

    .meta-item {
      display: flex;
      align-items: baseline;
      gap: 8px;
    }

    .meta-item.full-width {
      grid-column: span 2;
    }

    .meta-label {
      font-size: 12px;
      color: #64748b;
    }

    .meta-val {
      font-size: 13px;
      color: var(--text-primary, #f1f5f9);
    }

    .diff-section-title {
      font-size: 13px;
      font-weight: 600;
      color: #94a3b8;
      margin-bottom: 8px;
    }

    .diff-table-box {
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 8px;
      overflow: hidden;
    }

    .diff-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 12px;
    }

    .diff-table th {
      padding: 8px 12px;
      background: rgba(255, 255, 255, 0.03);
      color: #94a3b8;
      font-weight: 600;
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);
      text-align: left;
    }

    .diff-table td {
      padding: 8px 12px;
      border-bottom: 1px solid rgba(255, 255, 255, 0.04);
      vertical-align: top;
    }

    .field-name {
      color: #818cf8;
      font-weight: 500;
    }

    .diff-cell.old-cell {
      background: rgba(239, 68, 68, 0.05);
      color: #f87171;
    }

    .diff-cell.new-cell {
      background: rgba(52, 211, 153, 0.05);
      color: #34d399;
    }

    .diff-val {
      margin: 0;
      font-family: inherit;
      white-space: pre-wrap;
      word-break: break-all;
    }

    .no-diff-msg {
      padding: 24px;
      text-align: center;
      color: #64748b;
      font-size: 13px;
    }

    .json-details-viewer {
      background: rgba(0, 0, 0, 0.3);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 8px;
      padding: 12px;
      font-size: 12px;
      color: #cbd5e1;
      overflow-x: auto;
      max-height: 250px;
    }

    .modal-footer-actions {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 8px;
    }
  `]
})
export class AuditComponent implements OnInit {
  readonly auditLogs = signal<AuditRecord[]>([]);
  readonly securityEvents = signal<SecurityEventRecord[]>([]);
  readonly stats = signal<AuditStats | null>(null);
  readonly isLoading = signal<boolean>(false);

  activeTab: 'audit' | 'security' = 'audit';

  // Audit Filters & Pagination
  tableFilter = '';
  eventFilter = '';
  auditCurrentPage = 1;
  auditPageSize = 20;
  selectedAudit: AuditRecord | null = null;

  // Security Events Filters & Pagination
  secEventTypeFilter = '';
  secIpFilter = '';
  secCurrentPage = 1;
  secPageSize = 20;
  selectedSecEvent: SecurityEventRecord | null = null;

  constructor(
    private api: ApiService,
    private toast: ToastService
  ) {}

  ngOnInit() {
    this.refreshAll();
  }

  refreshAll() {
    this.loadStats();
    if (this.activeTab === 'audit') {
      this.loadAuditLogs();
    } else {
      this.loadSecurityEvents();
    }
  }

  setTab(tab: 'audit' | 'security') {
    this.activeTab = tab;
    if (tab === 'audit' && this.auditLogs().length === 0) {
      this.loadAuditLogs();
    } else if (tab === 'security' && this.securityEvents().length === 0) {
      this.loadSecurityEvents();
    }
  }

  loadStats() {
    this.api.get<AuditStats>('/audit/stats').subscribe({
      next: res => this.stats.set(res),
      error: () => {}
    });
  }

  loadAuditLogs() {
    this.isLoading.set(true);
    this.api.get<AuditRecord[]>('/audit/logs', {
      table_name: this.tableFilter || undefined,
      event: this.eventFilter || undefined,
      limit: 100
    }).subscribe({
      next: res => {
        this.auditLogs.set(res || []);
        this.isLoading.set(false);
      },
      error: () => this.isLoading.set(false)
    });
  }

  loadSecurityEvents() {
    this.isLoading.set(true);
    this.api.get<SecurityEventRecord[]>('/audit/security-events', {
      event_type: this.secEventTypeFilter || undefined,
      ip: this.secIpFilter || undefined,
      limit: 100
    }).subscribe({
      next: res => {
        this.securityEvents.set(res || []);
        this.isLoading.set(false);
      },
      error: () => this.isLoading.set(false)
    });
  }

  paginatedAuditLogs(): AuditRecord[] {
    const start = (this.auditCurrentPage - 1) * this.auditPageSize;
    return this.auditLogs().slice(start, start + this.auditPageSize);
  }

  paginatedSecurityEvents(): SecurityEventRecord[] {
    const start = (this.secCurrentPage - 1) * this.secPageSize;
    return this.securityEvents().slice(start, start + this.secPageSize);
  }

  selectAuditRecord(record: AuditRecord) {
    this.selectedAudit = record;
  }

  selectSecurityEvent(ev: SecurityEventRecord) {
    this.selectedSecEvent = ev;
  }

  getEventName(event: string): string {
    switch (event) {
      case 'I': return 'INSERT';
      case 'U': return 'UPDATE';
      case 'D': return 'DELETE';
      default: return event;
    }
  }

  getEventBadgeClass(event: string): string {
    switch (event) {
      case 'I': return 'insert';
      case 'U': return 'update';
      case 'D': return 'delete';
      default: return '';
    }
  }

  getSecurityEventBadgeClass(type: string): string {
    if (type.includes('SUCCESS')) return 'success';
    if (type.includes('LOCKED') || type.includes('FAILED') || type.includes('RATE_LIMITED')) return 'danger';
    if (type.includes('CHANGED') || type.includes('RESET')) return 'warning';
    return 'info';
  }

  getSecurityEventIcon(type: string): string {
    if (type.includes('SUCCESS')) return 'check_circle';
    if (type.includes('LOCKED') || type.includes('RATE_LIMITED')) return 'block';
    if (type.includes('FAILED')) return 'error';
    if (type.includes('PASSWORD')) return 'key';
    if (type.includes('TOKEN')) return 'token';
    return 'info';
  }

  formatUserAgent(ua?: string): string {
    if (!ua) return '—';
    if (ua.includes('Postman')) return 'Postman API Client';
    if (ua.includes('PowerShell') || ua.includes('curl')) return ua;
    if (ua.includes('Chrome')) return 'Google Chrome';
    if (ua.includes('Firefox')) return 'Mozilla Firefox';
    if (ua.includes('Safari')) return 'Apple Safari';
    return ua.length > 30 ? ua.substring(0, 30) + '...' : ua;
  }

  getDiffKeys(record: AuditRecord): string[] {
    const oldKeys = Object.keys(record.oldRow || {});
    const newKeys = Object.keys(record.newRow || {});
    const all = Array.from(new Set([...oldKeys, ...newKeys, ...(record.changedColumns || [])]));
    return all.filter(k => k !== 'password_hash'); // Hide sensitive hashes
  }

  formatValue(val: any): string {
    if (val === undefined || val === null) return '—';
    if (typeof val === 'object') return JSON.stringify(val, null, 2);
    return String(val);
  }
}
