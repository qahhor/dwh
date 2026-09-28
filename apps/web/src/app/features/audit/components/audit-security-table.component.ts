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
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { UiServerTableComponent } from '@shared/ui/ui-server-table.component';
import { DateRange, SMTDateRangePickerComponent } from '@shared/ui-kit/components/forms/date-picker';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { OrderBy, TableConfig } from '@shared/ui-kit/components/table/table.types';
import { QueryListMeta } from '@core/models/query-meta.models';
import { ListViewState } from '@shared/list-views/list-views';
import { registryTableConfig } from '@shared/ui/registry-table-config';
import { SecurityEventRecord } from '../audit.models';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';

@Component({
  selector: 'app-audit-security-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    TranslatePipe,
    SMTButtonComponent,
    UiServerTableComponent,
    SMTDateRangePickerComponent,
    SMTSelectComponent,
    DatePipe,
    NgClass,
  ],
  templateUrl: './audit-security-table.component.html',
  styleUrl: './audit-security-table.component.css',
})
export class AuditSecurityTableComponent {
  private readonly i18n = inject(I18nService);

  readonly pager = input.required<KeysetPager<SecurityEventRecord>>();

  readonly meta = input<QueryListMeta | null>(null);
  readonly views = input<ListViewState | null>(null);
  /** The filters on screen, so an export matches the list shown. */
  readonly exportOptions = input<Record<string, string> | null>(null);

  readonly secEventTypeFilter = input('');
  readonly secIpFilter = input('');
  readonly securityUserFilter = input('');

  readonly securityFromFilter = input<string>('');
  readonly securityToFilter = input<string>('');

  readonly secEventTypeFilterChange = output<string>();
  readonly secIpFilterChange = output<string>();
  readonly securityUserFilterChange = output<string>();
  readonly securityFromFilterChange = output<string>();
  readonly securityToFilterChange = output<string>();

  readonly applyFilters = output<void>();
  readonly resetFilters = output<void>();
  readonly sortChange = output<
    | {
        column: string;
        sortBy: OrderBy;
      }
    | undefined
  >();
  readonly selectEvent = output<SecurityEventRecord>();

  readonly emptyState = viewChild.required<TemplateRef<unknown>>('emptyStateTpl');
  private readonly idCell = viewChild.required<TemplateRef<unknown>>('idCell');
  private readonly eventCell = viewChild.required<TemplateRef<unknown>>('eventCell');
  private readonly userCell = viewChild.required<TemplateRef<unknown>>('userCell');
  private readonly ipCell = viewChild.required<TemplateRef<unknown>>('ipCell');
  private readonly agentCell = viewChild.required<TemplateRef<unknown>>('agentCell');
  private readonly dateCell = viewChild.required<TemplateRef<unknown>>('dateCell');
  private readonly detailsCell = viewChild.required<TemplateRef<unknown>>('detailsCell');

  /** The two UTC day bounds as one period; none set is "any period". */
  readonly period = computed<DateRange | null>(() => {
    const from = this.periodFrom();
    const to = this.periodTo();
    return from || to ? { from: from || null, to: to || null } : null;
  });

  /** Registry columns (`audit.security_events`) with the screen's cells, plus the details button. */
  readonly tableConfig = computed<TableConfig<SecurityEventRecord> | null>(() => {
    const meta = this.meta();
    if (!meta) return null;
    const header = (value: string) => ({ type: 'primitive' as const, value });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    // Every row is its own grid, so tracks are fixed or shares of the width, never content-sized.
    const share = 'max(140px, calc((100% - 680px) / 2))';
    const base = registryTableConfig<SecurityEventRecord>(meta, {
      translate: (key) => this.i18n.translate(key),
      trackBy: (_index, item) => item.id,
      ariaLabel: this.i18n.translate('audit.sobytiya_bezopasnosti'),
      sort: this.views()?.sort() ?? null,
      cells: {
        id: cell(this.idCell),
        eventType: cell(this.eventCell),
        userName: cell(this.userCell),
        ip: cell(this.ipCell),
        userAgent: cell(this.agentCell),
        createdAt: cell(this.dateCell),
      },
      widths: { id: '90px', eventType: '190px', userName: share, ip: '140px', userAgent: share, createdAt: '160px' },
      align: { id: 'left' },
    });
    return {
      ...base,
      layout: 'fit',
      columns: {
        ...base.columns,
        details: {
          key: 'details',
          header: header(this.i18n.translate('audit.detali')),
          content: cell(this.detailsCell),
          width: '100px',
          align: 'right',
        },
      },
      columnsOrder: [...base.columnsOrder, 'details'],
    };
  });

  private readonly periodFrom = computed(() => this.securityFromFilter() ?? '');
  private readonly periodTo = computed(() => this.securityToFilter() ?? '');

  private readonly eventTypeMemo = optionsMemo<SMTSelectOption<string>[]>();

  eventTypeOptions(): SMTSelectOption<string>[] {
    return this.eventTypeMemo([this.i18n.currentLang()], () => [
      { id: 'LOGIN_SUCCESS', label: this.i18n.translate('audit.uspeshnyy_vhod_login_success') },
      { id: 'LOGIN_FAILED', label: this.i18n.translate('audit.oshibka_vhoda_login_failed') },
      { id: 'LOGIN_LOCKED', label: this.i18n.translate('audit.blokirovka_brute_force_login_locked') },
      { id: 'IP_RATE_LIMITED', label: 'Rate Limit IP (IP_RATE_LIMITED)' },
      { id: 'PASSWORD_CHANGED', label: this.i18n.translate('audit.smena_parolya_password_changed') },
      { id: 'API_TOKEN_CREATED', label: this.i18n.translate('audit.vypusk_api_tokena') },
    ]);
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

  /** The filters are text; the kit field's value may be a number or null. */
  filterText(value: SMTInputValue): string {
    return value === null ? '' : String(value);
  }

  /** A preset, Apply or clearing sets both bounds and refetches at once. */
  onPeriodChange(range: DateRange | null): void {
    this.securityFromFilterChange.emit(range?.from ?? '');
    this.securityToFilterChange.emit(range?.to ?? '');
    this.applyFilters.emit();
  }
}
