import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../core/services/toast.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../shared/ui/ui-modal.component';
import { UiPaginationComponent } from '../../shared/ui/ui-pagination.component';
import { TranslatePipe } from '../../core/services/i18n.service';

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
    TranslatePipe,
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
          <h1 class="view-title">{{ 'nav.audit' | t }}</h1>
          <span class="count-badge">WORM Log</span>
        </div>
        <div class="header-right">
          <button type="button" class="btn btn-secondary" [attr.aria-label]="'audit.obnovit_zhurnal_audita' | t" (click)="refreshAll()" [title]="'audit.obnovit_zhurnal' | t">
            <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
            <span>{{ 'common.refresh' | t }}</span>
          </button>
        </div>
      </div>

      <!-- Stats Cards -->
      <div class="tiles" *ngIf="stats() as s">
        <div class="tile">
          <div class="tile-header">
            <span class="tile-label">{{ 'audit.vsego_zapisey_audita' | t }}</span>
            <span class="material-symbols-outlined" style="color: var(--primary);">history</span>
          </div>
          <div class="tile-value">{{ s.totalAuditLogs }}</div>
          <div class="tile-meta" style="color: var(--text-muted); font-size: 11px;">{{ 'audit.neizmenyaemyy_zhurnal' | t }}</div>
        </div>

        <div class="tile">
          <div class="tile-header">
            <span class="tile-label">{{ 'audit.sobytiy_bezopasnosti' | t }}</span>
            <span class="material-symbols-outlined" style="color: var(--info);">security</span>
          </div>
          <div class="tile-value">{{ s.totalSecurityEvents }}</div>
          <div class="tile-meta" style="color: var(--text-muted); font-size: 11px;">{{ 'audit.vse_tipy_sobytiy' | t }}</div>
        </div>

        <div class="tile">
          <div class="tile-header">
            <span class="tile-label">{{ 'audit.sobytiy_za_24_chasa' | t }}</span>
            <span class="material-symbols-outlined" style="color: var(--warning);">schedule</span>
          </div>
          <div class="tile-value">{{ s.securityEventsLast24h }}</div>
          <div class="tile-meta" style="color: var(--text-muted); font-size: 11px;">{{ 'audit.sutochnaya_aktivnost' | t }}</div>
        </div>

        <div class="tile" [class.tile-alarm]="s.failedLoginsLast24h > 0">
          <div class="tile-header">
            <span class="tile-label">{{ 'audit.neudachnyh_vhodov_blokirovok' | t }}</span>
            <span class="material-symbols-outlined" [style.color]="s.failedLoginsLast24h > 0 ? 'var(--danger)' : 'var(--success)'">
              {{ s.failedLoginsLast24h > 0 ? 'gpp_bad' : 'verified_user' }}
            </span>
          </div>
          <div class="tile-value" [style.color]="s.failedLoginsLast24h > 0 ? 'var(--danger)' : 'var(--text-main)'">
            {{ s.failedLoginsLast24h }}
          </div>
          <div class="tile-meta" [style.color]="s.failedLoginsLast24h > 0 ? 'var(--danger)' : 'var(--success)'" style="font-size: 11px; font-weight: 600;">
            {{ (s.failedLoginsLast24h > 0 ? 'audit.trebuet_vnimaniya' : 'audit.anomaliy_ne_obnaruzheno') | t }}
          </div>
        </div>
      </div>

      <!-- Tabs Navigation -->
      <div class="toolbar">
        <div class="status-tabs" role="tablist" [attr.aria-label]="'audit.razdely_audita' | t">
          <button
            id="audit-log-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'audit'"
            [attr.aria-selected]="activeTab === 'audit'"
            aria-controls="audit-log-panel"
            (click)="setTab('audit')"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">database</span>
            <span>{{ 'audit.change_log_count' | t:{count: auditLogs().length} }}</span>
          </button>
          <button
            id="security-events-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'security'"
            [attr.aria-selected]="activeTab === 'security'"
            aria-controls="security-events-panel"
            (click)="setTab('security')"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">shield</span>
            <span>{{ 'audit.security_events_count' | t:{count: securityEvents().length} }}</span>
          </button>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 1: AUDIT LOGS -->
      <!-- =================================================================== -->
      <div id="audit-log-panel" class="tab-content" role="tabpanel" aria-labelledby="audit-log-tab" *ngIf="activeTab === 'audit'">
        <!-- Filter Toolbar -->
        <div class="filter-toolbar">
          <div class="filter-group">
            <label class="sr-only" for="audit-table-filter">{{ 'audit.filtr_zhurnala_po_tablice' | t }}</label>
            <select id="audit-table-filter" name="auditTableFilter" class="filter-select" [(ngModel)]="tableFilter" (change)="loadAuditLogs()">
              <option value="">{{ 'audit.vse_tablicy' | t }}</option>
              <option value="md_users">{{ 'audit.polzovateli_md_users' | t }}</option>
              <option value="ms_tasks">{{ 'audit.zadachi_ms_tasks' | t }}</option>
              <option value="ms_projects">{{ 'audit.proekty_ms_projects' | t }}</option>
              <option value="md_roles">{{ 'audit.roli_i_prava_md_roles' | t }}</option>
              <option value="md_custom_fields">{{ 'audit.dinamicheskie_polya_md_custom_fields' | t }}</option>
            </select>

            <label class="sr-only" for="audit-event-filter">{{ 'audit.filtr_zhurnala_po_deystviyu' | t }}</label>
            <select id="audit-event-filter" name="auditEventFilter" class="filter-select" [(ngModel)]="eventFilter" (change)="loadAuditLogs()">
              <option value="">{{ 'audit.vse_deystviya' | t }}</option>
              <option value="I">{{ 'audit.sozdanie_insert' | t }}</option>
              <option value="U">{{ 'audit.izmenenie_update' | t }}</option>
              <option value="D">{{ 'audit.udalenie_delete' | t }}</option>
            </select>
          </div>
        </div>

        <!-- Audit Table -->
        <div class="table-container" role="region" [attr.aria-label]="'audit.tablica_zhurnala_izmeneniy' | t" tabindex="0" [attr.aria-busy]="isLoading()">
          <table class="data-table" [attr.aria-label]="'audit.zhurnal_izmeneniy_dannyh' | t">
            <thead>
              <tr>
                <th style="width: 70px;">ID</th>
                <th>{{ 'audit.tablica' | t }}</th>
                <th>PK</th>
                <th>{{ 'audit.deystvie' | t }}</th>
                <th>{{ 'audit.kto_izmenil' | t }}</th>
                <th>{{ 'audit.kanal' | t }}</th>
                <th>{{ 'audit.data_i_vremya' | t }}</th>
                <th style="width: 80px; text-align: right;">Diff</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let item of paginatedAuditLogs()">
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
                  <span *ngIf="!item.changedByName" class="text-muted">{{ 'audit.sistema' | t }}</span>
                </td>
                <td>
                  <span class="channel-pill" [class.api-pill]="item.isApi">
                    <span class="material-symbols-outlined" aria-hidden="true">{{ item.isApi ? 'terminal' : 'web' }}</span>
                    {{ item.isApi ? 'REST API' : 'Web UI' }}
                  </span>
                </td>
                <td>
                  <span class="date-cell tabular-nums">{{ item.changedAt | date:'dd.MM.yyyy HH:mm:ss' }}</span>
                </td>
                <td style="text-align: right;">
                  <button type="button" class="diff-btn" [attr.aria-label]="'audit.view_change_number' | t:{id: item.id}" [title]="'audit.prosmotr_izmeneniy' | t" (click)="selectAuditRecord(item)">
                    <span class="material-symbols-outlined" aria-hidden="true">difference</span>
                  </button>
                </td>
              </tr>

              <tr *ngIf="auditLogs().length === 0 && !isLoading()">
                <td colspan="8" class="empty-state-cell">
                  <div class="empty-state-box">
                    <span class="material-symbols-outlined empty-icon" aria-hidden="true">history_toggle_off</span>
                    <h3>{{ 'audit.zapisey_audita_ne_naydeno' | t }}</h3>
                    <p>{{ 'audit.poprobuyte_sbrosit_vybrannye_filtry' | t }}</p>
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
      <div id="security-events-panel" class="tab-content" role="tabpanel" aria-labelledby="security-events-tab" *ngIf="activeTab === 'security'">
        <!-- Filter Toolbar -->
        <div class="filter-toolbar">
          <div class="filter-group">
            <label class="sr-only" for="security-event-filter">{{ 'audit.filtr_sobytiy_bezopasnosti' | t }}</label>
            <select id="security-event-filter" name="securityEventFilter" class="filter-select" [(ngModel)]="secEventTypeFilter" (change)="loadSecurityEvents()">
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
                [(ngModel)]="secIpFilter"
                (keyup.enter)="loadSecurityEvents()"
              />
            </div>
          </div>
        </div>

        <!-- Security Events Table -->
        <div class="table-container" role="region" [attr.aria-label]="'audit.tablica_sobytiy_bezopasnosti' | t" tabindex="0" [attr.aria-busy]="isLoading()">
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
              <tr *ngFor="let item of paginatedSecurityEvents()">
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
                  <button type="button" class="diff-btn" [attr.aria-label]="'audit.view_security_event_number' | t:{id: item.id}" [title]="'audit.prosmotr_detaley' | t" (click)="selectSecurityEvent(item)">
                    <span class="material-symbols-outlined" aria-hidden="true">info</span>
                  </button>
                </td>
              </tr>

              <tr *ngIf="securityEvents().length === 0 && !isLoading()">
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
        [title]="'audit.detali_izmeneniya_zapisi_visual_diff' | t"
        size="lg"
        (close)="selectedAudit = null"
      >
        <div body *ngIf="selectedAudit as audit" class="diff-modal-body">
          <div class="diff-meta-grid">
            <div class="meta-item">
              <span class="meta-label">{{ 'audit.tablica.6f39b76' | t }}</span>
              <span class="meta-val font-mono">{{ audit.tableName }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">PK:</span>
              <span class="meta-val font-mono">{{ audit.rowPk }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">{{ 'audit.deystvie.7b79e9f' | t }}</span>
              <span class="event-badge" [ngClass]="getEventBadgeClass(audit.event)">{{ getEventName(audit.event) }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">{{ 'audit.avtor' | t }}</span>
              <span class="meta-val">{{ audit.changedByName ? audit.changedByName + ' (@' + audit.changedByLogin + ')' : ('common.system' | t) }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">{{ 'audit.data' | t }}</span>
              <span class="meta-val">{{ audit.changedAt | date:'dd.MM.yyyy HH:mm:ss' }}</span>
            </div>
          </div>

          <!-- Diff Table -->
          <div class="diff-section-title">{{ 'audit.sravnenie_poley_diff' | t }}</div>
          <div class="diff-table-box" role="region" [attr.aria-label]="'audit.sravnenie_izmenennyh_poley' | t" tabindex="0" *ngIf="getDiffKeys(audit).length > 0; else noDiff">
            <table class="diff-table" [attr.aria-label]="'audit.sravnenie_znacheniy_do_i_posle_izmeneniya' | t">
              <thead>
                <tr>
                  <th style="width: 25%;">{{ 'audit.pole' | t }}</th>
                  <th style="width: 37.5%;">{{ 'audit.predyduschee_znachenie' | t }}</th>
                  <th style="width: 37.5%;">{{ 'audit.novoe_znachenie' | t }}</th>
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
            <div class="no-diff-msg">{{ 'audit.net_podrobnyh_dannyh_diff_dlya_etoy_operacii' | t }}</div>
          </ng-template>
        </div>
        <div footer class="modal-footer-actions">
          <ui-button variant="secondary" (onClick)="selectedAudit = null">{{ 'audit.zakryt' | t }}</ui-button>
        </div>
      </ui-modal>

      <!-- =================================================================== -->
      <!-- MODAL: SECURITY EVENT DETAILS -->
      <!-- =================================================================== -->
      <ui-modal
        [isOpen]="selectedSecEvent !== null"
        [title]="'audit.sobytie_bezopasnosti' | t"
        size="md"
        (close)="selectedSecEvent = null"
      >
        <div body *ngIf="selectedSecEvent as ev" class="sec-modal-body">
          <div class="diff-meta-grid">
            <div class="meta-item">
              <span class="meta-label">{{ 'audit.tip_sobytiya' | t }}</span>
              <span class="sec-event-badge" [ngClass]="getSecurityEventBadgeClass(ev.eventType)">
                {{ ev.eventType }}
              </span>
            </div>
            <div class="meta-item">
              <span class="meta-label">IP:</span>
              <span class="meta-val font-mono">{{ ev.ip }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">{{ 'audit.polzovatel.a7d134d' | t }}</span>
              <span class="meta-val">{{ ev.userName ? ev.userName + ' (@' + ev.userLogin + ')' : (ev.details['login'] || '—') }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">{{ 'audit.data' | t }}</span>
              <span class="meta-val">{{ ev.createdAt | date:'dd.MM.yyyy HH:mm:ss' }}</span>
            </div>
            <div class="meta-item full-width" *ngIf="ev.userAgent">
              <span class="meta-label">User-Agent:</span>
              <span class="meta-val text-xs font-mono">{{ ev.userAgent }}</span>
            </div>
          </div>

          <div class="diff-section-title">{{ 'audit.parametry_sobytiya_json' | t }}</div>
          <pre class="json-details-viewer">{{ ev.details | json }}</pre>
        </div>
        <div footer class="modal-footer-actions">
          <ui-button variant="secondary" (onClick)="selectedSecEvent = null">{{ 'audit.zakryt' | t }}</ui-button>
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
      color: var(--text-main);
      margin: 0;
    }

    .view-subtitle {
      font-size: 13px;
      color: var(--text-light);
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
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
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

    .stat-icon-wrapper.blue { background: var(--info-bg); color: var(--info); }
    .stat-icon-wrapper.indigo { background: var(--primary-subtle); color: var(--primary-text); }
    .stat-icon-wrapper.amber { background: var(--warning-bg); color: var(--warning); }
    .stat-icon-wrapper.red { background: var(--danger-bg); color: var(--danger); }

    .stat-info {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .stat-value {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-main);
    }

    .stat-label {
      font-size: 12px;
      color: var(--text-light);
    }

    /* Tabs */
    .tabs-nav-bar {
      display: flex;
      border-bottom: 1px solid var(--border-color);
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
      color: var(--text-light);
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
      color: var(--primary-text);
      border-bottom-color: var(--primary);
    }

    .tab-counter {
      padding: 1px 6px;
      border-radius: 10px;
      font-size: 11px;
      font-weight: 600;
      background: var(--bg-hover);
    }

    .nav-tab-btn.active .tab-counter {
      background: var(--primary-subtle);
      color: var(--primary-text);
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
      display: flex;
      align-items: center;
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 0 10px;
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
    }

    .icon-refresh-btn {
      width: 36px;
      height: 36px;
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

    .table-tag {
      padding: 2px 6px;
      border-radius: 4px;
      background: var(--bg-hover);
      color: var(--text-muted);
      font-size: 12px;
    }

    .pk-pill {
      font-size: 12px;
      color: var(--text-light);
    }

    .event-badge {
      font-size: 11px;
      font-weight: 700;
      padding: 2px 8px;
      border-radius: 4px;
      text-transform: uppercase;
    }

    .event-badge.insert { background: var(--success-bg); color: var(--success); }
    .event-badge.update { background: var(--info-bg); color: var(--info); }
    .event-badge.delete { background: var(--danger-bg); color: var(--danger); }

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

    .sec-event-badge.success { background: var(--success-bg); color: var(--success); }
    .sec-event-badge.warning { background: var(--warning-bg); color: var(--warning); }
    .sec-event-badge.danger { background: var(--danger-bg); color: var(--danger); }
    .sec-event-badge.info { background: var(--info-bg); color: var(--info); }

    .user-cell {
      display: flex;
      flex-direction: column;
    }

    .user-name {
      font-weight: 500;
      color: var(--text-main);
    }

    .channel-pill {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 11px;
      color: var(--text-light);
    }

    .channel-pill .material-symbols-outlined {
      font-size: 14px;
    }

    .channel-pill.api-pill {
      color: var(--info);
    }

    .ip-pill {
      font-size: 12px;
      padding: 2px 6px;
      border-radius: 4px;
      background: var(--bg-hover);
      color: var(--text-muted);
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
      background: var(--bg-hover);
      color: var(--text-light);
      display: inline-flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
    }

    .diff-btn:hover {
      background: var(--primary-subtle);
      color: var(--primary-text);
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

    /* Modal Styles */
    .diff-meta-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 10px 16px;
      background: var(--bg-hover);
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
      color: var(--text-light);
    }

    .meta-val {
      font-size: 13px;
      color: var(--text-main);
    }

    .diff-section-title {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-muted);
      margin-bottom: 8px;
    }

    .diff-table-box {
      border: 1px solid var(--border-color);
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
      background: var(--bg-hover);
      color: var(--text-muted);
      font-weight: 600;
      border-bottom: 1px solid var(--border-color);
      text-align: left;
    }

    .diff-table td {
      padding: 8px 12px;
      border-bottom: 1px solid var(--border-subtle);
      vertical-align: top;
    }

    .field-name {
      color: var(--primary-text);
      font-weight: 500;
    }

    .diff-cell.old-cell {
      background: var(--danger-bg);
      color: var(--danger);
    }

    .diff-cell.new-cell {
      background: var(--success-bg);
      color: var(--success);
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
      color: var(--text-light);
      font-size: 13px;
    }

    .json-details-viewer {
      background: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 12px;
      font-size: 12px;
      color: var(--text-main);
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
