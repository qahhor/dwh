import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../core/services/toast.service';
import { TranslatePipe } from '../../core/services/i18n.service';
import { KeysetPager } from '../../shared/data/keyset-pager';

import {
  AuditRecord,
  SecurityEventRecord,
  AuditStats,
  AuditPage
} from './audit.models';

import { AuditStatsTilesComponent } from './components/audit-stats-tiles.component';
import { AuditLogsTableComponent } from './components/audit-logs-table.component';
import { AuditSecurityTableComponent } from './components/audit-security-table.component';
import { AuditModalsComponent } from './components/audit-modals.component';

export * from './audit.models';

@Component({
  selector: 'app-audit',
  standalone: true,
  imports: [
    CommonModule,
    TranslatePipe,
    AuditStatsTilesComponent,
    AuditLogsTableComponent,
    AuditSecurityTableComponent,
    AuditModalsComponent
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
      <app-audit-stats-tiles
        [stats]="stats()"
        [statsError]="statsError()"
        (retryStats)="loadStats()"
      ></app-audit-stats-tiles>

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
            <span>{{ 'audit.change_log_count' | t:{count: auditTotal()} }}</span>
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
            <span>{{ 'audit.security_events_count' | t:{count: securityTotal()} }}</span>
          </button>
        </div>
      </div>

      <!-- TAB 1: AUDIT LOGS -->
      <app-audit-logs-table
        *ngIf="activeTab === 'audit'"
        [auditLogs]="auditLogs()"
        [auditTotal]="auditTotal()"
        [auditHasMore]="auditHasMore()"
        [auditPageSize]="auditPageSize"
        [auditCurrentPage]="auditCurrentPage"
        [isLoading]="auditPager.loading()"
        [auditError]="auditError()"
        [tableFilter]="tableFilter"
        [eventFilter]="eventFilter"
        [rowPkFilter]="rowPkFilter"
        [auditUserFilter]="auditUserFilter"
        [auditFromFilter]="auditFromFilter"
        [auditToFilter]="auditToFilter"
        (tableFilterChange)="tableFilter = $event"
        (eventFilterChange)="eventFilter = $event"
        (rowPkFilterChange)="rowPkFilter = $event"
        (auditUserFilterChange)="auditUserFilter = $event"
        (auditFromFilterChange)="auditFromFilter = $event"
        (auditToFilterChange)="auditToFilter = $event"
        (applyFilters)="loadAuditLogs(true)"
        (resetFilters)="resetAuditFilters()"
        (retryLoad)="auditPager.retry()"
        (selectRecord)="selectAuditRecord($event)"
        (pageChange)="onAuditPageChange($event)"
        (pageSizeChange)="onAuditPageSizeChange($event)"
      ></app-audit-logs-table>

      <!-- TAB 2: SECURITY EVENTS -->
      <app-audit-security-table
        *ngIf="activeTab === 'security'"
        [securityEvents]="securityEvents()"
        [securityTotal]="securityTotal()"
        [securityHasMore]="securityHasMore()"
        [secPageSize]="secPageSize"
        [secCurrentPage]="secCurrentPage"
        [isLoading]="securityPager.loading()"
        [securityError]="securityError()"
        [secEventTypeFilter]="secEventTypeFilter"
        [secIpFilter]="secIpFilter"
        [securityUserFilter]="securityUserFilter"
        [securityFromFilter]="securityFromFilter"
        [securityToFilter]="securityToFilter"
        (secEventTypeFilterChange)="secEventTypeFilter = $event"
        (secIpFilterChange)="secIpFilter = $event"
        (securityUserFilterChange)="securityUserFilter = $event"
        (securityFromFilterChange)="securityFromFilter = $event"
        (securityToFilterChange)="securityToFilter = $event"
        (applyFilters)="loadSecurityEvents(true)"
        (resetFilters)="resetSecurityFilters()"
        (retryLoad)="securityPager.retry()"
        (selectEvent)="selectSecurityEvent($event)"
        (pageChange)="onSecurityPageChange($event)"
        (pageSizeChange)="onSecurityPageSizeChange($event)"
      ></app-audit-security-table>

      <!-- MODALS -->
      <app-audit-modals
        [selectedAudit]="selectedAudit"
        [selectedSecEvent]="selectedSecEvent"
        (closeAuditModal)="selectedAudit = null"
        (closeSecModal)="selectedSecEvent = null"
      ></app-audit-modals>
    </div>
  `,
  styleUrl: './audit.component.css'
})
export class AuditComponent implements OnInit {
  readonly stats = signal<AuditStats | null>(null);
  readonly statsError = signal<boolean>(false);

  activeTab: 'audit' | 'security' = 'audit';

  // Audit Filters & Pagination
  tableFilter = '';
  eventFilter = '';
  rowPkFilter = '';
  auditUserFilter = '';
  auditFromFilter = '';
  auditToFilter = '';
  selectedAudit: AuditRecord | null = null;

  // Security Events Filters & Pagination
  secEventTypeFilter = '';
  secIpFilter = '';
  securityUserFilter = '';
  securityFromFilter = '';
  securityToFilter = '';
  selectedSecEvent: SecurityEventRecord | null = null;

  private readonly destroyRef = inject(DestroyRef);

  /* Each list pages through its own keyset endpoint. The pager reads the
     filters at request time, cancels a superseded request and moves the page
     number only when that page arrives. */
  readonly auditPager = new KeysetPager<AuditRecord>((cursor, limit) =>
    this.api.get<AuditPage<AuditRecord>>('/audit/logs', {
      table_name: this.tableFilter || undefined,
      row_pk: this.rowPkFilter.trim() || undefined,
      event: this.eventFilter || undefined,
      user_id: this.auditUserFilter.trim() || undefined,
      from: this.startOfUtcDay(this.auditFromFilter),
      to: this.endOfUtcDay(this.auditToFilter),
      limit,
      cursor: cursor ?? undefined
    }), { destroyRef: this.destroyRef });

  readonly securityPager = new KeysetPager<SecurityEventRecord>((cursor, limit) =>
    this.api.get<AuditPage<SecurityEventRecord>>('/audit/security-events', {
      event_type: this.secEventTypeFilter || undefined,
      user_id: this.securityUserFilter.trim() || undefined,
      ip: this.secIpFilter || undefined,
      from: this.startOfUtcDay(this.securityFromFilter),
      to: this.endOfUtcDay(this.securityToFilter),
      limit,
      cursor: cursor ?? undefined
    }), { destroyRef: this.destroyRef });

  readonly auditLogs = computed(() => this.auditPager.items() as AuditRecord[]);
  readonly auditTotal = this.auditPager.total;
  readonly auditHasMore = this.auditPager.canGoForward;
  readonly auditError = this.auditPager.failed;
  get auditCurrentPage(): number { return this.auditPager.page(); }
  get auditPageSize(): number { return this.auditPager.pageSize(); }

  readonly securityEvents = computed(() => this.securityPager.items() as SecurityEventRecord[]);
  readonly securityTotal = this.securityPager.total;
  readonly securityHasMore = this.securityPager.canGoForward;
  readonly securityError = this.securityPager.failed;
  get secCurrentPage(): number { return this.securityPager.page(); }
  get secPageSize(): number { return this.securityPager.pageSize(); }

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
      this.loadAuditLogs(true);
    } else {
      this.loadSecurityEvents(true);
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
    this.statsError.set(false);
    this.api.get<AuditStats>('/audit/stats').subscribe({
      next: res => {
        this.stats.set(res);
        this.statsError.set(false);
      },
      error: () => this.statsError.set(true)
    });
  }

  loadAuditLogs(resetPagination = false) {
    if (resetPagination) this.auditPager.first();
    else this.auditPager.reload();
  }

  loadSecurityEvents(resetPagination = false) {
    if (resetPagination) this.securityPager.first();
    else this.securityPager.reload();
  }

  resetAuditFilters() {
    this.tableFilter = '';
    this.eventFilter = '';
    this.rowPkFilter = '';
    this.auditUserFilter = '';
    this.auditFromFilter = '';
    this.auditToFilter = '';
    this.loadAuditLogs(true);
  }

  resetSecurityFilters() {
    this.secEventTypeFilter = '';
    this.secIpFilter = '';
    this.securityUserFilter = '';
    this.securityFromFilter = '';
    this.securityToFilter = '';
    this.loadSecurityEvents(true);
  }

  paginatedAuditLogs(): AuditRecord[] {
    return this.auditLogs();
  }

  paginatedSecurityEvents(): SecurityEventRecord[] {
    return this.securityEvents();
  }

  onAuditPageChange(page: number) {
    this.auditPager.goTo(page);
  }

  onAuditPageSizeChange(pageSize: number) {
    this.auditPager.setPageSize(pageSize);
  }

  onSecurityPageChange(page: number) {
    this.securityPager.goTo(page);
  }

  onSecurityPageSizeChange(pageSize: number) {
    this.securityPager.setPageSize(pageSize);
  }

  private startOfUtcDay(value: string): string | undefined {
    return value ? `${value}T00:00:00.000Z` : undefined;
  }

  private endOfUtcDay(value: string): string | undefined {
    return value ? `${value}T23:59:59.999Z` : undefined;
  }

  selectAuditRecord(record: AuditRecord) {
    this.selectedAudit = record;
  }

  selectSecurityEvent(ev: SecurityEventRecord) {
    this.selectedSecEvent = ev;
  }

  getDiffKeys(record: AuditRecord): string[] {
    const oldKeys = Object.keys(record.oldRow || {});
    const newKeys = Object.keys(record.newRow || {});
    return Array.from(new Set([...oldKeys, ...newKeys, ...(record.changedColumns || [])]));
  }
}
