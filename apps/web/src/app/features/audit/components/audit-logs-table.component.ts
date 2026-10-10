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
import { SMTInputComponent, SMTInputValue } from '@shared/ui-kit/components/forms/input';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
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
    SMTInputComponent,
    SMTControlComponent,
    TranslatePipe,
    SMTButtonComponent,
    UiServerTableComponent,
    SMTDateRangePickerComponent,
    SMTSelectComponent,
    DatePipe,
    NgClass,
  ],
  templateUrl: './audit-logs-table.component.html',
  styleUrl: './audit-logs-table.component.css',
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
      ariaLabel: this.i18n.translate('audit.logs.data_change_log'),
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
      { id: 'md_users', label: this.i18n.translate('audit.logs.table_users') },
      { id: 'ms_tasks', label: this.i18n.translate('audit.logs.table_tasks') },
      { id: 'ms_projects', label: this.i18n.translate('audit.logs.table_projects') },
      { id: 'md_roles', label: this.i18n.translate('audit.logs.table_roles') },
      { id: 'md_custom_fields', label: this.i18n.translate('audit.logs.table_custom_fields') },
    ]);
  }

  eventOptions(): SMTSelectOption<string>[] {
    return this.eventOptionsMemo([this.i18n.currentLang()], () => [
      { id: 'I', label: this.i18n.translate('audit.logs.action_insert') },
      { id: 'U', label: this.i18n.translate('audit.logs.action_update') },
      { id: 'D', label: this.i18n.translate('audit.logs.action_delete') },
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

  /** The filters are text; the kit field's value may be a number or null. */
  filterText(value: SMTInputValue): string {
    return value === null ? '' : String(value);
  }

  /** A preset, Apply or clearing sets both bounds and refetches at once. */
  onPeriodChange(range: DateRange | null): void {
    this.auditFromFilterChange.emit(range?.from ?? '');
    this.auditToFilterChange.emit(range?.to ?? '');
    this.applyFilters.emit();
  }
}
