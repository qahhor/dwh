import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, Signal, TemplateRef, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, filter, interval } from 'rxjs';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { RouterLink } from '@angular/router';
import { UiBadgeComponent } from '../../../shared/ui/ui-badge.component';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { BarChartPoint, BarChartSeries, UiBarChartComponent } from '../../../shared/ui/ui-bar-chart.component';
import { UiKpiCardComponent } from '../../../shared/ui/ui-kpi-card.component';
import { UiLocalTableComponent } from '../../../shared/ui/ui-local-table.component';
import { TableConfig } from '../../../shared/ui-kit/components/table/table.types';
import { UiDashboardCardComponent } from '../../../shared/ui/ui-dashboard-card.component';
import {
  UPL_OVERVIEW_PERIODS,
  UplAttentionItem,
  UplFreshnessState,
  UplOverview,
  UplOverviewApi,
  UplOverviewPeriod,
  UplSourceFreshness
} from './overview-api';

/** How often an open, visible overview asks for fresh figures. */
const REFRESH_MS = 5 * 60 * 1000;
/** Worst first: what is late comes before what is merely due. */
const SEVERITY: Record<UplFreshnessState, number> = { overdue: 0, due: 1, never: 2, fresh: 3, adhoc: 4 };
const STATE_VARIANT: Record<UplFreshnessState, string> = {
  overdue: 'danger', due: 'warning', never: 'neutral', fresh: 'success', adhoc: 'info'
};
const STATE_KEY: Record<UplFreshnessState, string> = {
  overdue: 'upl.overview.fresh.state.overdue',
  due: 'upl.overview.fresh.state.due',
  never: 'upl.overview.fresh.state.never',
  fresh: 'upl.overview.fresh.state.fresh',
  adhoc: 'upl.overview.fresh.state.adhoc'
};

/**
 * The data overview (roadmap wave 5): what came into the warehouse over a
 * chosen period and what came of it. The page is a grid of widgets, each with
 * its own loading and failure state; it refreshes by itself while the tab is
 * visible and says when the figures were taken. It is a lazily loaded route,
 * so the start of the application does not grow with it.
 */
@Component({
  selector: 'app-upl-overview',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, RouterLink, TranslatePipe, UiBadgeComponent, UiButtonComponent, UiDashboardCardComponent, UiLocalTableComponent, UiKpiCardComponent, UiBarChartComponent],
  template: `
    <section class="overview" aria-labelledby="overview-title">
      <header class="overview__head">
        <div>
          <h1 id="overview-title" class="overview__title">{{ 'upl.overview.title' | t }}</h1>
          <p class="overview__subtitle">{{ 'upl.overview.subtitle' | t }}</p>
        </div>
        <div class="overview__controls">
          <div class="overview__periods" role="group" [attr.aria-label]="'upl.overview.period' | t">
            @for (period of periods; track period) {
              <button type="button" class="overview__period" data-testid="overview-period"
                [class.overview__period--active]="days() === period" [attr.aria-pressed]="days() === period"
                (click)="choose(period)">
                {{ 'upl.overview.days' | t: { n: period } }}
              </button>
            }
          </div>
          <ui-button variant="secondary" size="sm" icon="refresh" data-testid="overview-refresh" (onClick)="load()">
            {{ 'common.refresh' | t }}
          </ui-button>
        </div>
      </header>
      @if (data(); as current) {
        <p class="overview__stamp" data-testid="overview-stamp">
          {{ 'upl.overview.taken_at' | t: { time: (current.generatedAt | date: 'dd.MM.yyyy HH:mm') ?? '' } }}
        </p>
      }

      <div class="overview__grid">
        <ui-dashboard-card
          data-testid="overview-totals"
          [title]="'upl.overview.totals.title' | t"
          [subtitle]="'upl.overview.days_long' | t: { n: days() }"
          [loading]="loading() && !data()"
          [failed]="failed()"
          [empty]="data()?.totals?.uploads === 0"
          [emptyText]="'upl.overview.totals.empty' | t"
          (retry)="load()">
          @if (data(); as current) {
            <div class="overview__tiles" data-testid="overview-kpis">
              <ui-kpi-card
                [label]="'upl.overview.totals.uploads' | t"
                [value]="current.totals.uploads"
                [previous]="current.previous.uploads"
                goodWhen="neutral" />
              <ui-kpi-card
                [label]="'upl.overview.totals.applied' | t"
                [value]="current.totals.applied"
                [previous]="current.previous.applied" />
              <ui-kpi-card
                [label]="'upl.overview.totals.verified' | t"
                [value]="current.totals.verified"
                [previous]="current.previous.verified"
                goodWhen="down" />
              <ui-kpi-card
                [label]="'upl.overview.totals.rejected' | t"
                [value]="current.totals.rejected"
                [previous]="current.previous.rejected"
                goodWhen="down" />
              <ui-kpi-card
                [label]="'upl.overview.totals.rows' | t"
                [value]="current.totals.rowsApplied"
                [previous]="current.previous.rowsApplied" />
            </div>
          }
        </ui-dashboard-card>

        <ui-dashboard-card
          class="overview__wide"
          data-testid="overview-daily"
          [title]="'upl.overview.daily.title' | t"
          [subtitle]="'upl.overview.days_long' | t: { n: days() }"
          [loading]="loading() && !data()"
          [failed]="failed()"
          [empty]="data()?.totals?.uploads === 0"
          [emptyText]="'upl.overview.totals.empty' | t"
          (retry)="load()">
          <ui-bar-chart [series]="chartSeries()" [points]="chartPoints()" [caption]="'upl.overview.daily.caption' | t: { n: days() }"
            [axisLabel]="'upl.overview.daily.day' | t" />
        </ui-dashboard-card>

        <ui-dashboard-card
          data-testid="overview-attention"
          [title]="'upl.overview.attention.title' | t"
          [loading]="loading() && !data()"
          [failed]="failed()"
          [empty]="data()?.attention?.length === 0"
          [emptyText]="'upl.overview.attention.empty' | t"
          (retry)="load()">
          <ul class="overview__attention">
            @for (item of data()?.attention ?? []; track $index) {
              <li class="overview__attention-item" [class]="'overview__attention-item overview__attention-item--' + item.kind">
                <span class="material-symbols-outlined" aria-hidden="true">{{ attentionIcon(item) }}</span>
                <span class="overview__attention-text">{{ attentionText(item) }}</span>
                <a class="overview__attention-link" data-testid="overview-attention-link"
                  [routerLink]="attentionLink(item)" [queryParams]="attentionQuery(item)">{{ attentionAction(item) }}</a>
              </li>
            }
          </ul>
        </ui-dashboard-card>

        <ui-dashboard-card
          class="overview__wide"
          data-testid="overview-freshness"
          [title]="'upl.overview.fresh.title' | t"
          [subtitle]="'upl.overview.fresh.subtitle' | t"
          [loading]="loading() && !data()"
          [failed]="failed()"
          [empty]="data()?.freshness?.length === 0"
          [emptyText]="'upl.overview.fresh.empty' | t"
          (retry)="load()">
          <ui-local-table [rows]="freshnessRows()" [config]="freshnessConfig()" [sortValues]="freshnessSort" />
        </ui-dashboard-card>
      </div>
    </section>

    <ng-template #sourceCell let-f>
      <a class="overview__source" [routerLink]="['/upl/sources', f.sourceId]">{{ f.name }}</a>
      <span class="overview__code">{{ f.code }}</span>
    </ng-template>
    <ng-template #stateCell let-f><ui-badge [variant]="stateVariant(f)" [dot]="true">{{ stateText(f) }}</ui-badge></ng-template>
    <ng-template #lastCell let-f>{{ f.lastPeriodTo ? (f.lastPeriodTo | date: 'dd.MM.yyyy') : '—' }}</ng-template>
    <ng-template #dueCell let-f>{{ f.dueBy ? (f.dueBy | date: 'dd.MM.yyyy') : '—' }}</ng-template>
  `,
  styles: [`
    .overview { display: flex; flex-direction: column; gap: 12px; padding: 24px; min-width: 0; }
    .overview__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
    .overview__title { margin: 0; font-size: 20px; font-weight: 700; color: var(--text-main); }
    .overview__subtitle { margin: 4px 0 0; font-size: 13px; color: var(--text-muted); }
    .overview__controls { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .overview__periods { display: inline-flex; border: 1px solid var(--border-color); border-radius: var(--radius-sm); overflow: hidden; }
    .overview__period {
      border: 0; background: var(--bg-surface); color: var(--text-main); padding: 6px 12px; font: inherit; font-size: 12px; cursor: pointer;
    }
    .overview__period + .overview__period { border-left: 1px solid var(--border-color); }
    .overview__period--active { background: var(--primary); color: var(--on-primary); }
    .overview__period:focus-visible { outline: 2px solid var(--focus-ring, var(--primary)); outline-offset: -2px; }
    .overview__stamp { margin: 0; font-size: 12px; color: var(--text-muted); }
    .overview__grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 340px), 1fr)); gap: 16px; }
    .overview__tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 12px; margin: 0; }
    .overview__tile dt { font-size: 12px; color: var(--text-muted); }
    .overview__wide { grid-column: 1 / -1; }
    .overview__attention { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
    .overview__attention-item { display: grid; grid-template-columns: auto 1fr auto; gap: 8px; align-items: start; font-size: 13px; }
    .overview__attention-item .material-symbols-outlined { font-size: 18px; }
    .overview__attention-item--overdue .material-symbols-outlined { color: var(--danger-text); }
    .overview__attention-item--rejected .material-symbols-outlined { color: var(--warning-text, var(--danger-text)); }
    .overview__attention-item--waiting .material-symbols-outlined { color: var(--primary-text, var(--primary)); }
    .overview__attention-text { color: var(--text-main); overflow-wrap: anywhere; }
    .overview__attention-link { color: var(--primary-text, var(--primary)); white-space: nowrap; }
    .overview__source { color: var(--primary-text, var(--primary)); font-weight: 500; }
    .overview__code { display: block; font-size: 12px; color: var(--text-muted); font-family: var(--font-mono, monospace); }
    .overview__tile dd { margin: 4px 0 0; font-size: 22px; font-weight: 700; color: var(--text-main); font-variant-numeric: tabular-nums; }
  `]
})
export class UplOverviewComponent implements OnInit {
  private readonly api = inject(UplOverviewApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly i18n = inject(I18nService);

  readonly periods = UPL_OVERVIEW_PERIODS;
  readonly days = signal<UplOverviewPeriod>(30);
  readonly data = signal<UplOverview | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);
  private request?: Subscription;

  private readonly sourceCell = viewChild.required<TemplateRef<unknown>>('sourceCell');
  private readonly stateCell = viewChild.required<TemplateRef<unknown>>('stateCell');
  private readonly lastCell = viewChild.required<TemplateRef<unknown>>('lastCell');
  private readonly dueCell = viewChild.required<TemplateRef<unknown>>('dueCell');

  readonly chartSeries = computed<BarChartSeries[]>(() => [
    { key: 'applied', label: this.i18n.translate('upl.overview.totals.applied'), color: 'var(--success)' },
    { key: 'other', label: this.i18n.translate('upl.overview.daily.other'), color: 'var(--primary)' },
    { key: 'rejected', label: this.i18n.translate('upl.overview.totals.rejected'), color: 'var(--danger)' }
  ]);

  /** Every day of the period as a bar, labelled day.month. */
  readonly chartPoints = computed<BarChartPoint[]>(() => (this.data()?.daily ?? []).map(day => ({
    label: day.day.slice(8, 10) + '.' + day.day.slice(5, 7),
    values: { applied: day.applied, other: day.other, rejected: day.rejected }
  })));

  /** Sources worst first, by name within a state; a header click sorts them otherwise. */
  readonly freshnessRows = computed(() => [...(this.data()?.freshness ?? [])]
    .sort((a, b) => SEVERITY[a.state] - SEVERITY[b.state] || a.name.localeCompare(b.name)));

  readonly freshnessSort = {
    source: (f: UplSourceFreshness) => f.name,
    state: (f: UplSourceFreshness) => SEVERITY[f.state],
    last: (f: UplSourceFreshness) => f.lastPeriodTo,
    due: (f: UplSourceFreshness) => f.dueBy
  };

  readonly freshnessConfig = computed<TableConfig<UplSourceFreshness>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, f) => f.sourceId,
      ariaLabel: this.i18n.translate('upl.overview.fresh.title'),
      layout: 'fit',
      columns: {
        source: { header: header('upl.overview.fresh.col.source'), content: cell(this.sourceCell) },
        state: { header: header('upl.overview.fresh.col.state'), content: cell(this.stateCell), width: '170px' },
        last: { header: header('upl.overview.fresh.col.last'), content: cell(this.lastCell), width: '170px' },
        due: { header: header('upl.overview.fresh.col.due'), content: cell(this.dueCell), width: '150px' }
      },
      columnsOrder: ['source', 'state', 'last', 'due']
    };
  });

  ngOnInit(): void {
    this.load();
    interval(REFRESH_MS).pipe(
      filter(() => typeof document === 'undefined' || document.visibilityState === 'visible'),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(() => this.load());
  }

  stateText(f: UplSourceFreshness): string {
    return this.i18n.translate(STATE_KEY[f.state]);
  }

  stateVariant(f: UplSourceFreshness): string {
    return STATE_VARIANT[f.state];
  }

  attentionIcon(item: UplAttentionItem): string {
    return item.kind === 'overdue' ? 'schedule' : item.kind === 'rejected' ? 'report' : 'hourglass_top';
  }

  /** What is wrong, in one sentence with the source and the file or period. */
  attentionText(item: UplAttentionItem): string {
    const day = (value?: string | null) => (value ? value.split('-').reverse().join('.') : '');
    switch (item.kind) {
      case 'overdue':
        return this.i18n.translate('upl.overview.attention.overdue',
          { source: item.sourceName, period: day(item.periodTo), days: item.daysLate ?? 0 });
      case 'rejected':
        return this.i18n.translate('upl.overview.attention.rejected', { source: item.sourceName, file: item.fileName ?? '' });
      default:
        return this.i18n.translate('upl.overview.attention.waiting', { source: item.sourceName, file: item.fileName ?? '' });
    }
  }

  attentionAction(item: UplAttentionItem): string {
    return this.i18n.translate(item.kind === 'overdue' ? 'upl.overview.attention.open_source' : 'upl.overview.attention.open_upload');
  }

  /** An overdue source opens the upload form with it chosen; an upload opens its own card. */
  attentionLink(_item: UplAttentionItem): string[] {
    return ['/upl/packages'];
  }

  attentionQuery(item: UplAttentionItem): Record<string, string> {
    return item.packageId ? { open: item.packageId } : { source: String(item.sourceId) };
  }

  choose(period: UplOverviewPeriod): void {
    if (period === this.days()) return;
    this.days.set(period);
    this.data.set(null);
    this.load();
  }

  /** The latest answer wins: switching the period cancels the request still on its way. */
  load(): void {
    this.request?.unsubscribe();
    this.loading.set(true);
    this.failed.set(false);
    this.request = this.api.get(this.days()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: overview => {
        this.data.set(overview);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.failed.set(true);
      }
    });
  }
}
