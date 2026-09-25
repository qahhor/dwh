import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../core/services/toast.service';
import { TranslatePipe } from '../../core/services/i18n.service';
import { KeysetPager } from '../../shared/paging/keyset-pager';

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
import { SMTTabBarComponent, SMTTabItem } from '../../shared/ui-kit/components/tab-bar';
import { optionsMemo } from '../../shared/ui-kit/components/forms/radio-group';
import { I18nService } from '../../core/services/i18n.service';

export * from './audit.models';

@Component({
  selector: 'app-audit',
  standalone: true,
  imports: [
    SMTTabBarComponent, CommonModule,
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
        <smt-tab-bar
          class="audit-tabs"
          [tabs]="auditTabs()"
          [value]="activeTab"
          [smtAriaLabel]="'audit.razdely_audita' | t"
          (valueChange)="$event && setTab($event)" />
      </div>

      <!-- TAB 1: AUDIT LOGS -->
      <app-audit-logs-table
        *ngIf="activeTab === 'audit'"
        [pager]="auditPager"
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
        (selectRecord)="selectAuditRecord($event)"
      ></app-audit-logs-table>

      <!-- TAB 2: SECURITY EVENTS -->
      <app-audit-security-table
        *ngIf="activeTab === 'security'"
        [pager]="securityPager"
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
        (selectEvent)="selectSecurityEvent($event)"
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
  /** Texts of the tabs below; translated again when the language changes. */
  private readonly tabText = inject(I18nService);

  private readonly destroyRef = inject(DestroyRef);

  readonly stats = signal<AuditStats | null>(null);
  readonly statsError = signal<boolean>(false);

  readonly auditLogs = computed(() => this.auditPager.items() as AuditRecord[]);

  readonly securityEvents = computed(() => this.securityPager.items() as SecurityEventRecord[]);

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
  readonly auditTotal = this.auditPager.total;
  readonly auditHasMore = this.auditPager.canGoForward;
  readonly auditError = this.auditPager.failed;
  readonly securityTotal = this.securityPager.total;
  readonly securityHasMore = this.securityPager.canGoForward;
  readonly securityError = this.securityPager.failed;

  private readonly tabsMemo = optionsMemo<SMTTabItem<'audit' | 'security'>[]>();

  constructor(
    private api: ApiService,
    private toast: ToastService
  ) {}

  get auditCurrentPage(): number { return this.auditPager.page(); }
  get auditPageSize(): number { return this.auditPager.pageSize(); }
  get secCurrentPage(): number { return this.securityPager.page(); }
  get secPageSize(): number { return this.securityPager.pageSize(); }

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

  auditTabs(): SMTTabItem<'audit' | 'security'>[] {
    return this.tabsMemo([this.tabText.currentLang(), this.auditTotal(), this.securityTotal()], () => [
      { value: 'audit', label: this.tabText.translate('audit.change_log_count', { count: this.auditTotal() }), icon: 'database', id: 'audit-log-tab' },
      { value: 'security', label: this.tabText.translate('audit.security_events_count', { count: this.securityTotal() }), icon: 'shield', id: 'security-events-tab' },
    ]);
  }

  private startOfUtcDay(value: string): string | undefined {
    return value ? `${value}T00:00:00.000Z` : undefined;
  }

  private endOfUtcDay(value: string): string | undefined {
    return value ? `${value}T23:59:59.999Z` : undefined;
  }
}
