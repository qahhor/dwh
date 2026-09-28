import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  Signal,
  TemplateRef,
  viewChild,
  output,
} from '@angular/core';
import { NgClass, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { UiServerTableComponent } from '@shared/ui/ui-server-table.component';
import { DateRange, SMTDateRangePickerComponent } from '@shared/ui-kit/components/forms/date-picker';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { OrderBy, TableConfig } from '@shared/ui-kit/components/table/table.types';
import { QueryListMeta } from '@core/models/query-meta.models';
import { ListViewState } from '@shared/list-views/list-views';
import { registryTableConfig } from '@shared/ui/registry-table-config';
import { AuditRecord } from '../audit.models';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';

@Component({
  selector: 'app-audit-logs-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    SMTInputComponent,
    SMTInputValueAccessor,
    TranslatePipe,
    SMTButtonComponent,
    UiServerTableComponent,
    SMTDateRangePickerComponent,
    SMTSelectComponent,
    DatePipe,
    NgClass,
  ],
  templateUrl: './audit-logs-table.component.html',
  styles: [
    `
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
    `,
  ],
})
export class AuditLogsTableComponent {
  private readonly i18n = inject(I18nService);

  readonly pager = input.required<KeysetPager<AuditRecord>>();

  readonly meta = input<QueryListMeta | null>(null);
  readonly views = input<ListViewState | null>(null);
  /** The filters on screen, so an export matches the list shown. */
  readonly exportOptions = input<Record<string, string> | null>(null);

  readonly tableFilter = input('');
  readonly eventFilter = input('');
  readonly rowPkFilter = input('');
  readonly auditUserFilter = input('');

  readonly auditFromFilter = input<string>('');
  readonly auditToFilter = input<string>('');

  readonly tableFilterChange = output<string>();
  readonly eventFilterChange = output<string>();
  readonly rowPkFilterChange = output<string>();
  readonly auditUserFilterChange = output<string>();
  readonly auditFromFilterChange = output<string>();
  readonly auditToFilterChange = output<string>();

  readonly applyFilters = output<void>();
  readonly resetFilters = output<void>();
  readonly selectRecord = output<AuditRecord>();
  readonly sortChange = output<
    | {
        column: string;
        sortBy: OrderBy;
      }
    | undefined
  >();

  readonly emptyState = viewChild.required<TemplateRef<unknown>>('emptyStateTpl');
  private readonly idCell = viewChild.required<TemplateRef<unknown>>('idCell');
  private readonly tableCell = viewChild.required<TemplateRef<unknown>>('tableCell');
  private readonly pkCell = viewChild.required<TemplateRef<unknown>>('pkCell');
  private readonly eventCell = viewChild.required<TemplateRef<unknown>>('eventCell');
  private readonly userCell = viewChild.required<TemplateRef<unknown>>('userCell');
  private readonly channelCell = viewChild.required<TemplateRef<unknown>>('channelCell');
  private readonly dateCell = viewChild.required<TemplateRef<unknown>>('dateCell');
  private readonly diffCell = viewChild.required<TemplateRef<unknown>>('diffCell');

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
      translate: (key) => this.i18n.translate(key),
      trackBy: (_index, item) => item.id,
      ariaLabel: this.i18n.translate('audit.zhurnal_izmeneniy_dannyh'),
      sort: this.views()?.sort() ?? null,
      cells: {
        id: cell(this.idCell),
        tableName: cell(this.tableCell),
        rowPk: cell(this.pkCell),
        event: cell(this.eventCell),
        changedByName: cell(this.userCell),
        isApi: cell(this.channelCell),
        changedAt: cell(this.dateCell),
      },
      widths: {
        id: '90px',
        tableName: share,
        rowPk: '110px',
        event: '120px',
        changedByName: share,
        isApi: '120px',
        changedAt: '170px',
      },
      align: { id: 'left' },
    });
    return {
      ...base,
      layout: 'fit',
      columns: {
        ...base.columns,
        diff: { key: 'diff', header: header('Diff'), content: cell(this.diffCell), width: '90px', align: 'right' },
      },
      columnsOrder: [...base.columnsOrder, 'diff'],
    };
  });

  private readonly periodFrom = computed(() => this.auditFromFilter() ?? '');
  private readonly periodTo = computed(() => this.auditToFilter() ?? '');

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

  getEventName(event: string): string {
    switch (event) {
      case 'I':
        return 'INSERT';
      case 'U':
        return 'UPDATE';
      case 'D':
        return 'DELETE';
      default:
        return event;
    }
  }

  getEventBadgeClass(event: string): string {
    switch (event) {
      case 'I':
        return 'insert';
      case 'U':
        return 'update';
      case 'D':
        return 'delete';
      default:
        return '';
    }
  }

  /** A preset, Apply or clearing sets both bounds and refetches at once. */
  onPeriodChange(range: DateRange | null): void {
    this.auditFromFilterChange.emit(range?.from ?? '');
    this.auditToFilterChange.emit(range?.to ?? '');
    this.applyFilters.emit();
  }
}
