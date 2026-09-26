import { Component, computed, EventEmitter, inject, Input, Output, Signal, signal, TemplateRef, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../shared/ui-kit/components/forms/input';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiServerTableComponent } from '../../../shared/ui/ui-server-table.component';
import { DateRange, SMTDateRangePickerComponent } from '../../../shared/ui-kit/components/forms/date-picker';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { KeysetPager } from '../../../shared/paging/keyset-pager';
import { TableConfig } from '../../../shared/ui-kit/components/table/table.types';
import { SecurityEventRecord } from '../audit.models';
import { SMTSelectComponent, SMTSelectOption } from '../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../shared/ui-kit/components/forms/radio-group/radio-options';

@Component({
  selector: 'app-audit-security-table',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    SMTInputComponent,
    SMTInputValueAccessor,
    TranslatePipe,
    UiButtonComponent,
    UiServerTableComponent,
    SMTDateRangePickerComponent,
    SMTSelectComponent
  ],
  template: `
    <div id="security-events-panel" class="tab-content" role="tabpanel" aria-labelledby="security-events-tab">
      <!-- Filter Toolbar -->
      <div class="filter-toolbar">
        <div class="filter-group">
          <label class="sr-only" for="security-event-filter">{{ 'audit.filtr_sobytiy_bezopasnosti' | t }}</label>
          <smt-select smtTriggerId="security-event-filter" class="filter-select" [options]="eventTypeOptions()"
            [placeholder]="'audit.vse_sobytiya' | t" [emptyLabel]="'audit.vse_sobytiya' | t"
            [value]="secEventTypeFilter || null"
            (valueChange)="secEventTypeFilterChange.emit($event ?? ''); applyFilters.emit()" />

          <div class="search-box">
            <label class="sr-only" for="security-ip-search">{{ 'audit.poisk_sobytiy_po_ip_adresu' | t }}</label>
            <smt-input
              smtFieldId="security-ip-search"
              name="securityIpSearch"
              type="text"
              smtIcon="search"
              smtSize="sm"
              [placeholder]="'audit.poisk_po_ip' | t"
              [ngModel]="secIpFilter"
              (ngModelChange)="secIpFilterChange.emit($event)"
              (keyup.enter)="applyFilters.emit()" />
          </div>

          <div class="compact-filter compact-filter-narrow">
            <label for="security-user-filter">{{ 'audit.user_id' | t }}</label>
            <smt-input smtFieldId="security-user-filter" name="securityUserFilter" type="text" inputmode="numeric" smtSize="sm"
              smtPattern="[0-9]*" [ngModel]="securityUserFilter" (ngModelChange)="securityUserFilterChange.emit($event)" (keyup.enter)="applyFilters.emit()" />
          </div>

          <div class="compact-filter compact-filter-period">
            <span class="compact-filter-label" aria-hidden="true">{{ 'audit.period_utc' | t }}</span>
            <smt-date-range-picker data-testid="security-period-filter" [smtAriaLabel]="'audit.period_utc' | t"
              [value]="period()" (valueChange)="onPeriodChange($event)" />
          </div>

          <ui-button id="security-apply-filters" variant="primary" size="sm" icon="filter_alt"
            (onClick)="applyFilters.emit()">{{ 'audit.apply_filters' | t }}</ui-button>
          <ui-button id="security-reset-filters" variant="ghost" size="sm" icon="filter_alt_off"
            (onClick)="resetFilters.emit()">{{ 'audit.reset_filters' | t }}</ui-button>
        </div>
      </div>

      <div class="table-container" role="region" [attr.aria-label]="'audit.tablica_sobytiy_bezopasnosti' | t" [attr.aria-busy]="pager.loading()">
        <ui-server-table
          [pager]="pager"
          [config]="tableConfig()"
          [loadingLabel]="'audit.loading_security' | t"
          [errorLabel]="'audit.load_security_error' | t"
          errorId="security-load-error"
          [emptyTemplate]="emptyState()" />
      </div>

      <ng-template #idCell let-item><span class="tabular-nums font-mono text-muted">#{{ item.id }}</span></ng-template>
      <ng-template #eventCell let-item>
        <span class="sec-event-badge" [ngClass]="getSecurityEventBadgeClass(item.eventType)">
          <span class="material-symbols-outlined" aria-hidden="true">{{ getSecurityEventIcon(item.eventType) }}</span>
          {{ item.eventType }}
        </span>
      </ng-template>
      <ng-template #userCell let-item>
        <div class="user-cell" *ngIf="item.userName">
          <span class="user-name">{{ item.userName }}</span>
          <span class="user-sub text-muted text-xs">&#64;{{ item.userLogin }}</span>
        </div>
        <span *ngIf="!item.userName" class="text-muted">{{ item.details['login'] || ('common.guest' | t) }}</span>
      </ng-template>
      <ng-template #ipCell let-item><span class="ip-pill font-mono">{{ item.ip }}</span></ng-template>
      <ng-template #agentCell let-item>
        <span class="ua-cell text-muted text-xs" [title]="item.userAgent || ''">{{ formatUserAgent(item.userAgent) }}</span>
      </ng-template>
      <ng-template #dateCell let-item><span class="date-cell tabular-nums">{{ item.createdAt | date:'dd.MM.yyyy HH:mm:ss' }}</span></ng-template>
      <ng-template #detailsCell let-item>
        <button type="button" class="diff-btn" [attr.aria-label]="'audit.view_security_event_number' | t:{id: item.id}" [title]="'audit.prosmotr_detaley' | t" (click)="selectEvent.emit(item)">
          <span class="material-symbols-outlined" aria-hidden="true">info</span>
        </button>
      </ng-template>
      <ng-template #emptyStateTpl>
        <div class="empty-state-box">
          <span class="material-symbols-outlined empty-icon" aria-hidden="true">verified_user</span>
          <h3>{{ 'audit.sobytiy_bezopasnosti_ne_naydeno' | t }}</h3>
          <p>{{ 'audit.vse_podozritelnye_sobytiya_i_vhody_fiksiruyutsya' | t }}</p>
        </div>
      </ng-template>
    </div>
  `,
  styleUrl: './audit-security-table.component.css'
})
export class AuditSecurityTableComponent {
  private readonly i18n = inject(I18nService);

  readonly emptyState = viewChild.required<TemplateRef<unknown>>('emptyStateTpl');
  private readonly idCell = viewChild.required<TemplateRef<unknown>>('idCell');
  private readonly eventCell = viewChild.required<TemplateRef<unknown>>('eventCell');
  private readonly userCell = viewChild.required<TemplateRef<unknown>>('userCell');
  private readonly ipCell = viewChild.required<TemplateRef<unknown>>('ipCell');
  private readonly agentCell = viewChild.required<TemplateRef<unknown>>('agentCell');
  private readonly dateCell = viewChild.required<TemplateRef<unknown>>('dateCell');
  private readonly detailsCell = viewChild.required<TemplateRef<unknown>>('detailsCell');

  private readonly periodFrom = signal('');
  private readonly periodTo = signal('');

  /** The two UTC day bounds as one period; none set is "any period". */
  readonly period = computed<DateRange | null>(() => {
    const from = this.periodFrom();
    const to = this.periodTo();
    return from || to ? { from: from || null, to: to || null } : null;
  });

  readonly tableConfig = computed<TableConfig<SecurityEventRecord>>(() => {
    const header = (value: string) => ({ type: 'primitive' as const, value });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    // Every row is its own grid, so tracks are fixed or shares of the width, never content-sized.
    const share = 'max(140px, calc((100% - 680px) / 2))';
    return {
      trackBy: (_index, item) => item.id,
      layout: 'fit',
      ariaLabel: this.i18n.translate('audit.sobytiya_bezopasnosti'),
      columnsOrder: ['id', 'event', 'user', 'ip', 'agent', 'date', 'details'],
      columns: {
        id: { header: header('ID'), content: cell(this.idCell), width: '90px' },
        event: { header: header(this.i18n.translate('audit.sobytie')), content: cell(this.eventCell), width: '190px' },
        user: { header: header(this.i18n.translate('audit.polzovatel')), content: cell(this.userCell), width: share },
        ip: { header: header(this.i18n.translate('audit.ip_adres')), content: cell(this.ipCell), width: '140px' },
        agent: { header: header(this.i18n.translate('audit.user_agent_ustroystvo')), content: cell(this.agentCell), width: share },
        date: { header: header(this.i18n.translate('audit.data_i_vremya')), content: cell(this.dateCell), width: '160px' },
        details: { header: header(this.i18n.translate('audit.detali')), content: cell(this.detailsCell), width: '100px', align: 'right' },
      },
    };
  });

  private readonly eventTypeMemo = optionsMemo<SMTSelectOption<string>[]>();

  @Input({ required: true }) pager!: KeysetPager<SecurityEventRecord>;

  @Input() secEventTypeFilter = '';
  @Input() secIpFilter = '';
  @Input() securityUserFilter = '';

  @Output() secEventTypeFilterChange = new EventEmitter<string>();
  @Output() secIpFilterChange = new EventEmitter<string>();
  @Output() securityUserFilterChange = new EventEmitter<string>();
  @Output() securityFromFilterChange = new EventEmitter<string>();
  @Output() securityToFilterChange = new EventEmitter<string>();

  @Output() applyFilters = new EventEmitter<void>();
  @Output() resetFilters = new EventEmitter<void>();
  @Output() selectEvent = new EventEmitter<SecurityEventRecord>();

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

  @Input() set securityFromFilter(value: string) {
    this.periodFrom.set(value ?? '');
  }
  get securityFromFilter(): string {
    return this.periodFrom();
  }
  @Input() set securityToFilter(value: string) {
    this.periodTo.set(value ?? '');
  }
  get securityToFilter(): string {
    return this.periodTo();
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

  /** A preset, Apply or clearing sets both bounds and refetches at once. */
  onPeriodChange(range: DateRange | null): void {
    this.securityFromFilterChange.emit(range?.from ?? '');
    this.securityToFilterChange.emit(range?.to ?? '');
    this.applyFilters.emit();
  }
}
