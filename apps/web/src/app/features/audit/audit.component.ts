import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  linkedSignal,
  OnInit,
  signal,
} from '@angular/core';

import { TranslatePipe } from '@core/services/i18n.service';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { QueryListMeta } from '@core/models/query-meta.models';
import { QueryMetaService, parseSort } from '@core/services/query-meta.service';
import { ListViewState, ListViewsApi } from '@shared/list-views/list-views';
import { TableColumnStateStore } from '@shared/ui-kit/services/table-column-state.store';
import { sortFromHeader } from '@shared/ui/registry-table-config';
import { OrderBy } from '@shared/ui-kit/components/table/table.types';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';

import { AuditRecord, SecurityEventRecord, AuditStats } from './audit.models';
import { AuditApi } from './audit.api';
import { AuditLogFilters, SecurityEventFilters } from './audit-filters';

import { AuditStatsTilesComponent } from './components/audit-stats-tiles.component';
import { AuditLogsTableComponent } from './components/audit-logs-table.component';
import { AuditSecurityTableComponent } from './components/audit-security-table.component';
import { AuditModalsComponent } from './components/audit-modals.component';
import { SMTTabBarComponent, SMTTabItem } from '@shared/ui-kit/components/tab-bar';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { I18nService } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';

export * from './audit.models';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

@Component({
  selector: 'app-audit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    UiPageHeaderComponent,
    SMTButtonComponent,
    SMTAlertComponent,
    SMTTabBarComponent,
    TranslatePipe,
    AuditStatsTilesComponent,
    AuditLogsTableComponent,
    AuditSecurityTableComponent,
    AuditModalsComponent,
  ],
  template: `
    <div class="audit-page">
      <!-- Page Header -->
      <ui-page-header [title]="'nav.audit' | t" [count]="'WORM Log'">
        <button
          smt-button
          smtVariant="secondary"
          type="button"
          [attr.aria-label]="'audit.obnovit_zhurnal_audita' | t"
          (click)="refreshAll()"
          [title]="'audit.obnovit_zhurnal' | t"
        >
          <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
          <span>{{ 'common.refresh' | t }}</span>
        </button>
      </ui-page-header>

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
          [value]="activeTab()"
          [smtAriaLabel]="'audit.razdely_audita' | t"
          (valueChange)="$event && setTab($event)"
        />
      </div>

      @if (metaError()) {
        <smt-alert smtTone="danger" data-testid="audit-meta-error">
          <span>{{ (activeTab() === 'audit' ? 'audit.load_log_error' : 'audit.load_security_error') | t }}</span>
          <button smt-button type="button" smtVariant="secondary" smtSize="sm" (click)="refreshAll()">
            {{ 'common.retry' | t }}
          </button>
        </smt-alert>
      }

      <!-- TAB 1: AUDIT LOGS -->
      @if (activeTab() === 'audit') {
        <app-audit-logs-table
          [pager]="auditPager"
          [meta]="auditMeta()"
          [views]="auditViews"
          [exportOptions]="auditFilters.exportOptions()"
          (sortChange)="onAuditSort($event)"
          [tableFilter]="auditFilters.table"
          [eventFilter]="auditFilters.event"
          [rowPkFilter]="auditFilters.rowPk"
          [auditUserFilter]="auditFilters.user"
          [auditFromFilter]="auditFilters.from"
          [auditToFilter]="auditFilters.to"
          (tableFilterChange)="auditFilters.table = $event"
          (eventFilterChange)="auditFilters.event = $event"
          (rowPkFilterChange)="auditFilters.rowPk = $event"
          (auditUserFilterChange)="auditFilters.user = $event"
          (auditFromFilterChange)="auditFilters.from = $event"
          (auditToFilterChange)="auditFilters.to = $event"
          (applyFilters)="loadAuditLogs(true)"
          (resetFilters)="resetAuditFilters()"
          (selectRecord)="selectedAudit = $event"
        ></app-audit-logs-table>
      }

      <!-- TAB 2: SECURITY EVENTS -->
      @if (activeTab() === 'security') {
        <app-audit-security-table
          [pager]="securityPager"
          [meta]="securityMeta()"
          [views]="securityViews"
          [exportOptions]="securityFilters.exportOptions()"
          (sortChange)="onSecuritySort($event)"
          [secEventTypeFilter]="securityFilters.eventType"
          [secIpFilter]="securityFilters.ip"
          [securityUserFilter]="securityFilters.user"
          [securityFromFilter]="securityFilters.from"
          [securityToFilter]="securityFilters.to"
          (secEventTypeFilterChange)="securityFilters.eventType = $event"
          (secIpFilterChange)="securityFilters.ip = $event"
          (securityUserFilterChange)="securityFilters.user = $event"
          (securityFromFilterChange)="securityFilters.from = $event"
          (securityToFilterChange)="securityFilters.to = $event"
          (applyFilters)="loadSecurityEvents(true)"
          (resetFilters)="resetSecurityFilters()"
          (selectEvent)="selectedSecEvent = $event"
        ></app-audit-security-table>
      }

      <!-- MODALS -->
      <app-audit-modals
        [selectedAudit]="selectedAudit"
        [selectedSecEvent]="selectedSecEvent"
        (closeAuditModal)="selectedAudit = null"
        (closeSecModal)="selectedSecEvent = null"
      ></app-audit-modals>
    </div>
  `,
  styleUrl: './audit.component.css',
})
export class AuditComponent implements OnInit {
  /** Texts of the tabs below; translated again when the language changes. */
  private readonly tabText = inject(I18nService);
  private readonly audit = inject(AuditApi);

  private readonly destroyRef = inject(DestroyRef);
  private readonly queryMeta = inject(QueryMetaService);

  /** Field metadata of the two lists (`audit.logs`, `audit.security_events`), roadmap item 50. */
  readonly auditMeta = signal<QueryListMeta | null>(null);
  readonly securityMeta = signal<QueryListMeta | null>(null);
  readonly metaError = signal(false);
  /** A failed read keeps the last summary on screen next to its error. */
  readonly stats = linkedSignal<AuditStats | undefined, AuditStats | null>({
    source: () => (this.statsResource.hasValue() ? this.statsResource.value() : undefined),
    computation: (stats, previous) => stats ?? previous?.value ?? null,
  });

  /** A signal, so a tab chosen from code (not only by a click) redraws the screen. */
  readonly activeTab = signal<'audit' | 'security'>('audit');

  /** Bumped to read the summary again; a new value cancels a read still in flight. */
  private readonly statsRevision = signal(0);

  readonly statsError = computed(() => this.statsResource.error() !== undefined);

  readonly auditLogs = computed(() => this.auditPager.items() as AuditRecord[]);

  readonly securityEvents = computed(() => this.securityPager.items() as SecurityEventRecord[]);

  private readonly statsResource = rxResource({ params: this.statsRevision, stream: () => this.audit.stats() });

  /** Sort, filter and columns of each list; saved views keep them under a name. */
  readonly auditViews = new ListViewState('audit.logs', inject(ListViewsApi), {
    defaultSort: () => {
      const meta = this.auditMeta();
      return meta ? parseSort(meta.defaultSort) : null;
    },
    onApply: () => this.auditPager.first(),
    columnsStore: inject(TableColumnStateStore),
  });
  readonly securityViews = new ListViewState('audit.security_events', inject(ListViewsApi), {
    defaultSort: () => {
      const meta = this.securityMeta();
      return meta ? parseSort(meta.defaultSort) : null;
    },
    onApply: () => this.securityPager.first(),
    columnsStore: inject(TableColumnStateStore),
  });

  /** The screen's own filters of each list; the pagers read them at request time. */
  readonly auditFilters = new AuditLogFilters();
  readonly securityFilters = new SecurityEventFilters();
  selectedAudit: AuditRecord | null = null;
  selectedSecEvent: SecurityEventRecord | null = null;

  /* Each list pages through its own keyset endpoint. The pager reads the
     filters at request time, cancels a superseded request and moves the page
     number only when that page arrives. */
  readonly auditPager = new KeysetPager<AuditRecord>(
    (cursor, limit) =>
      this.audit.logs(
        this.auditFilters.flat(),
        { sort: this.auditViews.sort(), conditions: this.auditViews.filter(), match: this.auditViews.match() },
        cursor,
        limit,
      ),
    { destroyRef: this.destroyRef },
  );

  readonly securityPager = new KeysetPager<SecurityEventRecord>(
    (cursor, limit) =>
      this.audit.securityEvents(
        this.securityFilters.flat(),
        { sort: this.securityViews.sort(), conditions: this.securityViews.filter(), match: this.securityViews.match() },
        cursor,
        limit,
      ),
    { destroyRef: this.destroyRef },
  );
  readonly auditTotal = this.auditPager.total;
  readonly securityTotal = this.securityPager.total;

  private readonly tabsMemo = optionsMemo<SMTTabItem<'audit' | 'security'>[]>();

  /** The summary loads by itself; the list waits for its metadata. */
  ngOnInit() {
    this.loadAuditLogs(true);
  }

  refreshAll() {
    this.loadStats();
    if (this.activeTab() === 'audit') {
      this.loadAuditLogs(true);
    } else {
      this.loadSecurityEvents(true);
    }
  }

  setTab(tab: 'audit' | 'security') {
    this.activeTab.set(tab);
    if (tab === 'audit' && (!this.auditMeta() || this.auditLogs().length === 0)) {
      this.loadAuditLogs();
    } else if (tab === 'security' && (!this.securityMeta() || this.securityEvents().length === 0)) {
      this.loadSecurityEvents();
    }
  }

  loadStats() {
    this.statsRevision.update((revision) => revision + 1);
  }

  /** The first page of the audit log; its metadata comes first, once. */
  loadAuditLogs(resetPagination = false) {
    if (!this.auditMeta()) {
      this.loadMeta('audit.logs', this.auditMeta, this.auditViews, this.auditPager);
      return;
    }
    if (resetPagination) this.auditPager.first();
    else this.auditPager.reload();
  }

  /** The first page of the security events; their metadata comes first, once. */
  loadSecurityEvents(resetPagination = false) {
    if (!this.securityMeta()) {
      this.loadMeta('audit.security_events', this.securityMeta, this.securityViews, this.securityPager);
      return;
    }
    if (resetPagination) this.securityPager.first();
    else this.securityPager.reload();
  }

  /** A header click sorts the whole list on the server. */
  onAuditSort(event: { column: string; sortBy: OrderBy } | undefined) {
    if (!this.auditMeta()) return;
    this.auditViews.setSort(sortFromHeader(event));
    this.auditPager.first();
  }

  onSecuritySort(event: { column: string; sortBy: OrderBy } | undefined) {
    if (!this.securityMeta()) return;
    this.securityViews.setSort(sortFromHeader(event));
    this.securityPager.first();
  }

  resetAuditFilters() {
    this.auditFilters.reset();
    this.loadAuditLogs(true);
  }

  resetSecurityFilters() {
    this.securityFilters.reset();
    this.loadSecurityEvents(true);
  }

  getDiffKeys(record: AuditRecord): string[] {
    const oldKeys = Object.keys(record.oldRow || {});
    const newKeys = Object.keys(record.newRow || {});
    return Array.from(new Set([...oldKeys, ...newKeys, ...(record.changedColumns || [])]));
  }

  auditTabs(): SMTTabItem<'audit' | 'security'>[] {
    const auditCount = tabCount(this.auditPager);
    const securityCount = tabCount(this.securityPager);
    return this.tabsMemo([this.tabText.currentLang(), auditCount, securityCount], () => [
      {
        value: 'audit',
        label: this.tabText.translate('audit.change_log_count', { count: auditCount }),
        icon: 'database',
        id: 'audit-log-tab',
      },
      {
        value: 'security',
        label: this.tabText.translate('audit.security_events_count', { count: securityCount }),
        icon: 'shield',
        id: 'security-events-tab',
      },
    ]);
  }

  private loadMeta(
    code: string,
    meta: { set(value: QueryListMeta): void },
    views: ListViewState,
    pager: { first(): void },
  ) {
    this.metaError.set(false);
    this.queryMeta
      .get(code)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (loaded) => {
          meta.set(loaded);
          views.load().subscribe(() => pager.first());
        },
        error: () => this.metaError.set(true),
      });
  }
}

/**
 * The count a tab shows: nothing before the first answer (an unloaded list is not an empty one), and "≈ N" when
 * the server gives the planner's estimate instead of a count (plan 10/10, item 3.5).
 */
function tabCount(pager: KeysetPager<unknown>): string {
  if (!pager.loaded()) return '…';
  return pager.totalExact() ? String(pager.total()) : `≈ ${pager.total()}`;
}
