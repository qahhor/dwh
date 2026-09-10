import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiPaginationComponent } from '../../../shared/ui/ui-pagination.component';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { SecurityEventRecord } from '../audit.models';

@Component({
  selector: 'app-audit-security-table',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
    UiButtonComponent,
    UiPaginationComponent
  ],
  template: `
    <div id="security-events-panel" class="tab-content" role="tabpanel" aria-labelledby="security-events-tab">
      <!-- Filter Toolbar -->
      <div class="filter-toolbar">
        <div class="filter-group">
          <label class="sr-only" for="security-event-filter">{{ 'audit.filtr_sobytiy_bezopasnosti' | t }}</label>
          <select id="security-event-filter" name="securityEventFilter" class="filter-select"
            [ngModel]="secEventTypeFilter" (ngModelChange)="secEventTypeFilterChange.emit($event); applyFilters.emit()">
            <option value="">{{ 'audit.vse_sobytiya' | t }}</option>
            <option value="LOGIN_SUCCESS">{{ 'audit.uspeshnyy_vhod_login_success' | t }}</option>
            <option value="LOGIN_FAILED">{{ 'audit.oshibka_vhoda_login_failed' | t }}</option>
            <option value="LOGIN_LOCKED">{{ 'audit.blokirovka_brute_force_login_locked' | t }}</option>
            <option value="IP_RATE_LIMITED">Rate Limit IP (IP_RATE_LIMITED)</option>
            <option value="PASSWORD_CHANGED">{{ 'audit.smena_parolya_password_changed' | t }}</option>
            <option value="API_TOKEN_CREATED">{{ 'audit.vypusk_api_tokena' | t }}</option>
          </select>

          <div class="search-box">
            <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
            <label class="sr-only" for="security-ip-search">{{ 'audit.poisk_sobytiy_po_ip_adresu' | t }}</label>
            <input
              id="security-ip-search"
              name="securityIpSearch"
              type="text"
              class="search-input"
              [placeholder]="'audit.poisk_po_ip' | t"
              [ngModel]="secIpFilter"
              (ngModelChange)="secIpFilterChange.emit($event)"
              (keyup.enter)="applyFilters.emit()"
            />
          </div>

          <div class="compact-filter compact-filter-narrow">
            <label for="security-user-filter">{{ 'audit.user_id' | t }}</label>
            <input id="security-user-filter" name="securityUserFilter" class="filter-input" type="text" inputmode="numeric"
              pattern="[0-9]*" [ngModel]="securityUserFilter" (ngModelChange)="securityUserFilterChange.emit($event)" (keyup.enter)="applyFilters.emit()" />
          </div>

          <div class="compact-filter">
            <label for="security-from-filter">{{ 'audit.date_from_utc' | t }}</label>
            <input id="security-from-filter" name="securityFromFilter" class="filter-input" type="date"
              [ngModel]="securityFromFilter" (ngModelChange)="securityFromFilterChange.emit($event)" />
          </div>

          <div class="compact-filter">
            <label for="security-to-filter">{{ 'audit.date_to_utc' | t }}</label>
            <input id="security-to-filter" name="securityToFilter" class="filter-input" type="date"
              [ngModel]="securityToFilter" (ngModelChange)="securityToFilterChange.emit($event)" />
          </div>

          <ui-button id="security-apply-filters" variant="primary" size="sm" icon="filter_alt"
            (onClick)="applyFilters.emit()">{{ 'audit.apply_filters' | t }}</ui-button>
          <ui-button id="security-reset-filters" variant="ghost" size="sm" icon="filter_alt_off"
            (onClick)="resetFilters.emit()">{{ 'audit.reset_filters' | t }}</ui-button>
        </div>
      </div>

      <div id="security-load-error" class="inline-feedback" role="alert" *ngIf="securityError">
        <span class="material-symbols-outlined" aria-hidden="true">error</span>
        <span>{{ 'audit.load_security_error' | t }}</span>
        <ui-button variant="secondary" size="sm" icon="refresh" (onClick)="retryLoad.emit()">
          {{ 'audit.retry' | t }}
        </ui-button>
      </div>

      <!-- Security Events Table -->
      <div class="table-container" role="region" [attr.aria-label]="'audit.tablica_sobytiy_bezopasnosti' | t" tabindex="0" [attr.aria-busy]="isLoading">
        <table class="data-table" [attr.aria-label]="'audit.sobytiya_bezopasnosti' | t">
          <thead>
            <tr>
              <th style="width: 70px;">ID</th>
              <th>{{ 'audit.sobytie' | t }}</th>
              <th>{{ 'audit.polzovatel' | t }}</th>
              <th>{{ 'audit.ip_adres' | t }}</th>
              <th>{{ 'audit.user_agent_ustroystvo' | t }}</th>
              <th>{{ 'audit.data_i_vremya' | t }}</th>
              <th style="width: 80px; text-align: right;">{{ 'audit.detali' | t }}</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngIf="isLoading && securityEvents.length === 0">
              <td colspan="7" class="loading-state-cell" role="status">{{ 'audit.loading_security' | t }}</td>
            </tr>
            <tr *ngFor="let item of securityEvents">
              <td class="tabular-nums font-mono text-muted">#{{ item.id }}</td>
              <td>
                <span class="sec-event-badge" [ngClass]="getSecurityEventBadgeClass(item.eventType)">
                  <span class="material-symbols-outlined" aria-hidden="true">{{ getSecurityEventIcon(item.eventType) }}</span>
                  {{ item.eventType }}
                </span>
              </td>
              <td>
                <div class="user-cell" *ngIf="item.userName">
                  <span class="user-name">{{ item.userName }}</span>
                  <span class="user-sub text-muted text-xs">&#64;{{ item.userLogin }}</span>
                </div>
                <span *ngIf="!item.userName" class="text-muted">{{ item.details['login'] || ('common.guest' | t) }}</span>
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
                <button type="button" class="diff-btn" [attr.aria-label]="'audit.view_security_event_number' | t:{id: item.id}" [title]="'audit.prosmotr_detaley' | t" (click)="selectEvent.emit(item)">
                  <span class="material-symbols-outlined" aria-hidden="true">info</span>
                </button>
              </td>
            </tr>

            <tr *ngIf="securityEvents.length === 0 && !isLoading && !securityError">
              <td colspan="7" class="empty-state-cell">
                <div class="empty-state-box">
                  <span class="material-symbols-outlined empty-icon" aria-hidden="true">verified_user</span>
                  <h3>{{ 'audit.sobytiy_bezopasnosti_ne_naydeno' | t }}</h3>
                  <p>{{ 'audit.vse_podozritelnye_sobytiya_i_vhody_fiksiruyutsya' | t }}</p>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <ui-pagination
        *ngIf="securityTotal > 0"
        [totalItems]="securityTotal"
        [pageSize]="secPageSize"
        [currentPage]="secCurrentPage"
        [cursorMode]="true"
        [hasNextPage]="securityHasMore"
        (pageChange)="pageChange.emit($event)"
        (pageSizeChange)="pageSizeChange.emit($event)"
      ></ui-pagination>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
    }

    .tab-content {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .filter-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 4px;
      flex-wrap: wrap;
    }

    .filter-group {
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
    }

    .filter-select {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 7px 12px;
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }

    .filter-select option {
      background: var(--bg-surface);
      color: var(--text-main);
    }

    .search-box {
      position: relative;
      display: flex;
      align-items: center;
    }

    .search-icon {
      position: absolute;
      left: 10px;
      color: var(--text-light);
      font-size: 18px;
      pointer-events: none;
    }

    .search-input {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 6px 10px 6px 34px;
      color: var(--text-main);
      font-size: 13px;
      outline: none;
      width: 160px;
    }

    .compact-filter {
      display: flex;
      flex-direction: column;
      gap: 3px;
      min-width: 142px;
    }

    .compact-filter-narrow {
      min-width: 92px;
      width: 110px;
    }

    .compact-filter label {
      color: var(--text-light);
      font-size: 11px;
      font-weight: 600;
    }

    .filter-input {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 6px 10px;
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }

    .filter-input:focus, .filter-select:focus, .search-input:focus {
      border-color: var(--primary);
    }

    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      margin: -1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      border: 0;
    }

    .inline-feedback {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 14px;
      border-radius: 8px;
      font-size: 13px;
      background: var(--danger-bg);
      color: var(--danger);
      border: 1px solid var(--danger);
      margin-top: 4px;
    }

    .table-container {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      overflow-x: auto;
    }

    .table-container:focus-visible {
      outline: 2px solid var(--primary);
      outline-offset: -2px;
    }

    .data-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
      text-align: left;
    }

    .data-table th {
      padding: 12px 16px;
      background: var(--bg-hover);
      color: var(--text-muted);
      font-weight: 600;
      font-size: 12px;
      border-bottom: 1px solid var(--border-color);
      white-space: nowrap;
    }

    .data-table td {
      padding: 12px 16px;
      border-bottom: 1px solid var(--border-subtle);
      color: var(--text-main);
      vertical-align: middle;
    }

    .data-table tr:last-child td {
      border-bottom: none;
    }

    .tabular-nums {
      font-variant-numeric: tabular-nums;
    }

    .font-mono {
      font-family: monospace;
    }

    .text-muted {
      color: var(--text-light);
    }

    .text-xs {
      font-size: 11px;
    }

    .ip-pill {
      background: var(--bg-hover);
      padding: 2px 6px;
      border-radius: 4px;
      font-size: 12px;
      color: var(--text-main);
    }

    .sec-event-badge {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      padding: 3px 8px;
      border-radius: 6px;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.3px;
    }

    .sec-event-badge .material-symbols-outlined {
      font-size: 14px;
    }

    .sec-event-badge.success {
      background: var(--success-bg);
      color: var(--success);
    }

    .sec-event-badge.danger {
      background: var(--danger-bg);
      color: var(--danger);
    }

    .sec-event-badge.warning {
      background: var(--warning-bg);
      color: var(--warning);
    }

    .sec-event-badge.info {
      background: var(--info-bg);
      color: var(--info);
    }

    .user-cell {
      display: flex;
      flex-direction: column;
    }

    .user-name {
      font-weight: 500;
    }

    .ua-cell {
      max-width: 200px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      display: inline-block;
    }

    .date-cell {
      white-space: nowrap;
      color: var(--text-light);
    }

    .diff-btn {
      background: transparent;
      border: 1px solid var(--border-color);
      color: var(--text-light);
      width: 32px;
      height: 32px;
      border-radius: 6px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .diff-btn:hover {
      background: var(--bg-hover);
      color: var(--primary-text);
      border-color: var(--primary);
    }

    .diff-btn .material-symbols-outlined {
      font-size: 18px;
    }

    .loading-state-cell, .empty-state-cell {
      padding: 48px !important;
      text-align: center;
      color: var(--text-light);
    }

    .empty-state-box {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
    }

    .empty-state-box .empty-icon {
      font-size: 48px;
      color: var(--text-light);
      opacity: 0.5;
    }

    .empty-state-box h3 {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }

    .empty-state-box p {
      font-size: 13px;
      margin: 0;
    }
  `]
})
export class AuditSecurityTableComponent {
  @Input() securityEvents: SecurityEventRecord[] = [];
  @Input() securityTotal = 0;
  @Input() securityHasMore = false;
  @Input() secPageSize = 20;
  @Input() secCurrentPage = 1;
  @Input() isLoading = false;
  @Input() securityError = false;

  @Input() secEventTypeFilter = '';
  @Input() secIpFilter = '';
  @Input() securityUserFilter = '';
  @Input() securityFromFilter = '';
  @Input() securityToFilter = '';

  @Output() secEventTypeFilterChange = new EventEmitter<string>();
  @Output() secIpFilterChange = new EventEmitter<string>();
  @Output() securityUserFilterChange = new EventEmitter<string>();
  @Output() securityFromFilterChange = new EventEmitter<string>();
  @Output() securityToFilterChange = new EventEmitter<string>();

  @Output() applyFilters = new EventEmitter<void>();
  @Output() resetFilters = new EventEmitter<void>();
  @Output() retryLoad = new EventEmitter<void>();
  @Output() selectEvent = new EventEmitter<SecurityEventRecord>();
  @Output() pageChange = new EventEmitter<number>();
  @Output() pageSizeChange = new EventEmitter<number>();

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
}
