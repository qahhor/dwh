import { Component, computed, EventEmitter, inject, input, Input, Output, Signal, signal, TemplateRef, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../shared/ui-kit/components/forms/input';
import { SMTButtonComponent } from '../../../shared/ui-kit/components/button';
import { UiServerTableComponent } from '../../../shared/ui/ui-server-table.component';
import { DateRange, SMTDateRangePickerComponent } from '../../../shared/ui-kit/components/forms/date-picker';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { KeysetPager } from '../../../shared/paging/keyset-pager';
import { OrderBy, TableConfig } from '../../../shared/ui-kit/components/table/table.types';
import { QueryListMeta } from '../../../core/models/query-meta.models';
import { ListViewState } from '../../../shared/list-views/list-views';
import { registryTableConfig } from '../../../shared/ui/registry-table-config';
import { AuditRecord } from '../audit.models';
import { SMTSelectComponent, SMTSelectOption } from '../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../shared/ui-kit/components/forms/radio-group/radio-options';

@Component({
  selector: 'app-audit-logs-table',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    SMTInputComponent,
    SMTInputValueAccessor,
    TranslatePipe,
    SMTButtonComponent,
    UiServerTableComponent,
    SMTDateRangePickerComponent,
    SMTSelectComponent
  ],
  template: `
    <div id="audit-log-panel" class="tab-content" role="tabpanel" aria-labelledby="audit-log-tab">
      <!-- Filter Toolbar -->
      <div class="filter-toolbar">
        <div class="filter-group">
          <label class="sr-only" for="audit-table-filter">{{ 'audit.filtr_zhurnala_po_tablice' | t }}</label>
          <smt-select smtTriggerId="audit-table-filter" class="filter-select" [options]="tableOptions()"
            [placeholder]="'audit.vse_tablicy' | t" [emptyLabel]="'audit.vse_tablicy' | t"
            [value]="tableFilter || null" (valueChange)="tableFilterChange.emit($event ?? ''); applyFilters.emit()" />

          <label class="sr-only" for="audit-event-filter">{{ 'audit.filtr_zhurnala_po_deystviyu' | t }}</label>
          <smt-select smtTriggerId="audit-event-filter" class="filter-select" [options]="eventOptions()"
            [placeholder]="'audit.vse_deystviya' | t" [emptyLabel]="'audit.vse_deystviya' | t"
            [value]="eventFilter || null" (valueChange)="eventFilterChange.emit($event ?? ''); applyFilters.emit()" />

          <div class="compact-filter">
            <label for="audit-row-pk-filter">{{ 'audit.row_pk' | t }}</label>
            <smt-input smtFieldId="audit-row-pk-filter" name="auditRowPkFilter" type="text" smtSize="sm"
              [ngModel]="rowPkFilter" (ngModelChange)="rowPkFilterChange.emit($event)" (keyup.enter)="applyFilters.emit()" />
          </div>

          <div class="compact-filter compact-filter-narrow">
            <label for="audit-user-filter">{{ 'audit.user_id' | t }}</label>
            <smt-input smtFieldId="audit-user-filter" name="auditUserFilter" type="text" inputmode="numeric" smtSize="sm"
              smtPattern="[0-9]*" [ngModel]="auditUserFilter" (ngModelChange)="auditUserFilterChange.emit($event)" (keyup.enter)="applyFilters.emit()" />
          </div>

          <div class="compact-filter compact-filter-period">
            <span class="compact-filter-label" aria-hidden="true">{{ 'audit.period_utc' | t }}</span>
            <smt-date-range-picker data-testid="audit-period-filter" [smtAriaLabel]="'audit.period_utc' | t"
              [value]="period()" (valueChange)="onPeriodChange($event)" />
          </div>

          <button smt-button type="button" id="audit-apply-filters" smtVariant="primary" smtSize="sm" smtIcon="filter_alt"
            (click)="applyFilters.emit()">{{ 'audit.apply_filters' | t }}</button>
          <button smt-button type="button" id="audit-reset-filters" smtVariant="ghost" smtSize="sm" smtIcon="filter_alt_off"
            (click)="resetFilters.emit()">{{ 'audit.reset_filters' | t }}</button>
        </div>
      </div>

      <!-- The region keeps the page's scroll landmark; the table inside names itself. -->
      <div class="table-container" role="region" [attr.aria-label]="'audit.tablica_zhurnala_izmeneniy' | t" [attr.aria-busy]="pager.loading()">
        @if (tableConfig(); as config) {
          <ui-server-table
            [pager]="pager"
            [config]="config"
            [views]="views()"
            [filterMeta]="meta()"
            [exportable]="true"
            [exportOptions]="exportOptions()"
            [lockedColumns]="['id', 'diff']"
            [loadingLabel]="'audit.loading_log' | t"
            [errorLabel]="'audit.load_log_error' | t"
            errorId="audit-load-error"
            [emptyTemplate]="emptyState()"
            (sortChange)="sortChange.emit($event)" />
        }
      </div>

      <ng-template #idCell let-item><span class="tabular-nums font-mono text-muted">#{{ item.id }}</span></ng-template>
      <ng-template #tableCell let-item><span class="table-tag font-mono">{{ item.tableName }}</span></ng-template>
      <ng-template #pkCell let-item><span class="pk-pill font-mono">{{ item.rowPk }}</span></ng-template>
      <ng-template #eventCell let-item>
        <span class="event-badge" [ngClass]="getEventBadgeClass(item.event)">{{ getEventName(item.event) }}</span>
      </ng-template>
      <ng-template #userCell let-item>
        <div class="user-cell" *ngIf="item.changedByName">
          <span class="user-name">{{ item.changedByName }}</span>
          <span class="user-sub text-muted text-xs">&#64;{{ item.changedByLogin }}</span>
        </div>
        <span *ngIf="!item.changedByName" class="text-muted">{{ 'audit.sistema' | t }}</span>
      </ng-template>
      <ng-template #channelCell let-item>
        <span class="channel-pill" [class.api-pill]="item.isApi">
          <span class="material-symbols-outlined" aria-hidden="true">{{ item.isApi ? 'terminal' : 'web' }}</span>
          {{ item.isApi ? 'REST API' : 'Web UI' }}
        </span>
      </ng-template>
      <ng-template #dateCell let-item><span class="date-cell tabular-nums">{{ item.changedAt | date:'dd.MM.yyyy HH:mm:ss' }}</span></ng-template>
      <ng-template #diffCell let-item>
        <button type="button" class="diff-btn" [attr.aria-label]="'audit.view_change_number' | t:{id: item.id}" [title]="'audit.prosmotr_izmeneniy' | t" (click)="selectRecord.emit(item)">
          <span class="material-symbols-outlined" aria-hidden="true">difference</span>
        </button>
      </ng-template>
      <ng-template #emptyStateTpl>
        <div class="empty-state-box">
          <span class="material-symbols-outlined empty-icon" aria-hidden="true">history_toggle_off</span>
          <h3>{{ 'audit.zapisey_audita_ne_naydeno' | t }}</h3>
          <p>{{ 'audit.poprobuyte_sbrosit_vybrannye_filtry' | t }}</p>
        </div>
      </ng-template>
    </div>
  `,
  styles: [`
    /* The card that holds the table, its pagination and any load error. */
    .table-container {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 12px;
      min-width: 0;
    }

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
      width: 240px;
      max-width: 100%;
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

    .compact-filter-period {
      min-width: 220px;
    }

    .compact-filter label,
    .compact-filter-label {
      color: var(--text-light);
      font-size: 11px;
      font-weight: 600;
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

    .table-tag {
      background: var(--bg-hover);
      padding: 2px 6px;
      border-radius: 4px;
      font-size: 12px;
      color: var(--text-main);
      border: 1px solid var(--border-color);
    }

    .pk-pill {
      font-weight: 600;
      color: var(--text-main);
    }

    .event-badge {
      display: inline-flex;
      align-items: center;
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.5px;
    }

    .event-badge.insert {
      background: var(--success-bg);
      color: var(--success-text);
    }

    .event-badge.update {
      background: var(--info-bg);
      color: var(--info-text);
    }

    .event-badge.delete {
      background: var(--danger-bg);
      color: var(--danger-text);
    }

    .user-cell {
      display: flex;
      flex-direction: column;
    }

    .user-name {
      font-weight: 500;
    }

    .channel-pill {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      color: var(--text-light);
    }

    .channel-pill .material-symbols-outlined {
      font-size: 14px;
    }

    .api-pill {
      color: var(--primary-text);
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

    .empty-state-box {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 16px;
      text-align: center;
      color: var(--text-light);
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
export class AuditLogsTableComponent {
  private readonly i18n = inject(I18nService);

  readonly meta = input<QueryListMeta | null>(null);
  readonly views = input<ListViewState | null>(null);
  /** The filters on screen, so an export matches the list shown. */
  readonly exportOptions = input<Record<string, string> | null>(null);

  readonly emptyState = viewChild.required<TemplateRef<unknown>>('emptyStateTpl');
  private readonly idCell = viewChild.required<TemplateRef<unknown>>('idCell');
  private readonly tableCell = viewChild.required<TemplateRef<unknown>>('tableCell');
  private readonly pkCell = viewChild.required<TemplateRef<unknown>>('pkCell');
  private readonly eventCell = viewChild.required<TemplateRef<unknown>>('eventCell');
  private readonly userCell = viewChild.required<TemplateRef<unknown>>('userCell');
  private readonly channelCell = viewChild.required<TemplateRef<unknown>>('channelCell');
  private readonly dateCell = viewChild.required<TemplateRef<unknown>>('dateCell');
  private readonly diffCell = viewChild.required<TemplateRef<unknown>>('diffCell');

  private readonly periodFrom = signal('');
  private readonly periodTo = signal('');

  /** The two UTC day bounds as one period; none set is "any period". */
  readonly period = computed<DateRange | null>(() => {
    const from = this.periodFrom();
    const to = this.periodTo();
    return from || to ? { from: from || null, to: to || null } : null;
  });

  /** Registry columns (`audit.logs`) with the screen's cells, plus the diff button, which is not a field. */
  readonly tableConfig = computed<TableConfig<AuditRecord> | null>(() => {
    const meta = this.meta();
    if (!meta) return null;
    const header = (value: string) => ({ type: 'primitive' as const, value });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    // Every row is its own grid, so tracks are fixed or shares of the width, never content-sized.
    const share = 'max(140px, calc((100% - 700px) / 2))';
    const base = registryTableConfig<AuditRecord>(meta, {
      translate: key => this.i18n.translate(key),
      trackBy: (_index, item) => item.id,
      ariaLabel: this.i18n.translate('audit.zhurnal_izmeneniy_dannyh'),
      sort: this.views()?.sort() ?? null,
      cells: {
        id: cell(this.idCell), tableName: cell(this.tableCell), rowPk: cell(this.pkCell), event: cell(this.eventCell),
        changedByName: cell(this.userCell), isApi: cell(this.channelCell), changedAt: cell(this.dateCell)
      },
      widths: { id: '90px', tableName: share, rowPk: '110px', event: '120px', changedByName: share, isApi: '120px', changedAt: '170px' },
      align: { id: 'left' }
    });
    return {
      ...base,
      layout: 'fit',
      columns: {
        ...base.columns,
        diff: { key: 'diff', header: header('Diff'), content: cell(this.diffCell), width: '90px', align: 'right' }
      },
      columnsOrder: [...base.columnsOrder, 'diff']
    };
  });

  @Input({ required: true }) pager!: KeysetPager<AuditRecord>;

  @Input() tableFilter = '';
  @Input() eventFilter = '';
  @Input() rowPkFilter = '';
  @Input() auditUserFilter = '';

  @Output() tableFilterChange = new EventEmitter<string>();
  @Output() eventFilterChange = new EventEmitter<string>();
  @Output() rowPkFilterChange = new EventEmitter<string>();
  @Output() auditUserFilterChange = new EventEmitter<string>();
  @Output() auditFromFilterChange = new EventEmitter<string>();
  @Output() auditToFilterChange = new EventEmitter<string>();

  @Output() applyFilters = new EventEmitter<void>();
  @Output() resetFilters = new EventEmitter<void>();
  @Output() selectRecord = new EventEmitter<AuditRecord>();
  @Output() sortChange = new EventEmitter<{ column: string; sortBy: OrderBy } | undefined>();

  private readonly tableOptionsMemo = optionsMemo<SMTSelectOption<string>[]>();

  private readonly eventOptionsMemo = optionsMemo<SMTSelectOption<string>[]>();

  tableOptions(): SMTSelectOption<string>[] {
    return this.tableOptionsMemo([this.i18n.currentLang()], () => [
      { id: 'md_users', label: this.i18n.translate('audit.polzovateli_md_users') },
      { id: 'ms_tasks', label: this.i18n.translate('audit.zadachi_ms_tasks') },
      { id: 'ms_projects', label: this.i18n.translate('audit.proekty_ms_projects') },
      { id: 'md_roles', label: this.i18n.translate('audit.roli_i_prava_md_roles') },
      { id: 'md_custom_fields', label: this.i18n.translate('audit.dinamicheskie_polya_md_custom_fields') },
    ]);
  }

  eventOptions(): SMTSelectOption<string>[] {
    return this.eventOptionsMemo([this.i18n.currentLang()], () => [
      { id: 'I', label: this.i18n.translate('audit.sozdanie_insert') },
      { id: 'U', label: this.i18n.translate('audit.izmenenie_update') },
      { id: 'D', label: this.i18n.translate('audit.udalenie_delete') },
    ]);
  }

  @Input() set auditFromFilter(value: string) {
    this.periodFrom.set(value ?? '');
  }
  get auditFromFilter(): string {
    return this.periodFrom();
  }
  @Input() set auditToFilter(value: string) {
    this.periodTo.set(value ?? '');
  }
  get auditToFilter(): string {
    return this.periodTo();
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

  /** A preset, Apply or clearing sets both bounds and refetches at once. */
  onPeriodChange(range: DateRange | null): void {
    this.auditFromFilterChange.emit(range?.from ?? '');
    this.auditToFilterChange.emit(range?.to ?? '');
    this.applyFilters.emit();
  }
}
