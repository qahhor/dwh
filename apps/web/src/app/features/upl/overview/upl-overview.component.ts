import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  ResourceRef,
  Signal,
  TemplateRef,
  computed,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, filter, interval, map, of } from 'rxjs';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { RouterLink } from '@angular/router';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { BarChartPoint, BarChartSeries, UiBarChartComponent } from '@shared/ui/ui-bar-chart.component';
import { UiKpiCardComponent } from '@shared/ui/ui-kpi-card.component';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { UiDashboardCardComponent } from '@shared/ui/ui-dashboard-card.component';
import {
  UPL_OVERVIEW_PERIODS,
  UplAttentionItem,
  UplFreshnessState,
  UplOverview,
  UplOverviewApi,
  UplOverviewPeriod,
  UplSourceFreshness,
} from './overview.api';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { TBadgeVariant } from '@shared/ui-kit/components/badge/badge.component';

/**
 * What a load leaves on screen. A failure is a value, so it never throws out of the resource, and it
 * keeps the figures of the load before: a failed refresh leaves them on screen.
 */
interface OverviewLoad {
  overview: UplOverview | null;
  failed: boolean;
}

/** How often an open, visible overview asks for fresh figures. */
const REFRESH_MS = 5 * 60 * 1000;
/** Worst first: what is late comes before what is merely due. */
const SEVERITY: Record<UplFreshnessState, number> = { overdue: 0, due: 1, never: 2, fresh: 3, adhoc: 4 };
const STATE_VARIANT: Record<UplFreshnessState, TBadgeVariant> = {
  overdue: 'error',
  due: 'warning',
  never: 'gray',
  fresh: 'success',
  adhoc: 'blue',
};
const STATE_KEY: Record<UplFreshnessState, string> = {
  overdue: 'upl.overview.fresh.state.overdue',
  due: 'upl.overview.fresh.state.due',
  never: 'upl.overview.fresh.state.never',
  fresh: 'upl.overview.fresh.state.fresh',
  adhoc: 'upl.overview.fresh.state.adhoc',
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
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTRadioGroupComponent,
    DatePipe,
    RouterLink,
    TranslatePipe,
    SMTBadgeComponent,
    SMTButtonComponent,
    UiDashboardCardComponent,
    UiLocalTableComponent,
    UiKpiCardComponent,
    UiBarChartComponent,
  ],
  templateUrl: './upl-overview.component.html',
  styleUrl: './upl-overview.component.css',
})
export class UplOverviewComponent implements OnInit {
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);

  private readonly api = inject(UplOverviewApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly i18n = inject(I18nService);

  private readonly sourceCell = viewChild.required<TemplateRef<unknown>>('sourceCell');
  private readonly stateCell = viewChild.required<TemplateRef<unknown>>('stateCell');
  private readonly lastCell = viewChild.required<TemplateRef<unknown>>('lastCell');
  private readonly dueCell = viewChild.required<TemplateRef<unknown>>('dueCell');

  readonly days = signal<UplOverviewPeriod>(30);
  /** The figures on screen: none while another period loads, the last ones while a refresh is on its way or fails. */
  readonly data = computed<UplOverview | null>(() => this.overview.value()?.overview ?? null);
  readonly loading = computed(() => this.overview.isLoading());
  readonly failed = computed(() => !this.loading() && this.overview.value()?.failed === true);

  readonly chartSeries = computed<BarChartSeries[]>(() => [
    { key: 'applied', label: this.i18n.translate('upl.overview.totals.applied'), color: 'var(--success)' },
    { key: 'other', label: this.i18n.translate('upl.overview.daily.other'), color: 'var(--primary)' },
    { key: 'rejected', label: this.i18n.translate('upl.overview.totals.rejected'), color: 'var(--danger)' },
  ]);

  /** Every day of the period as a bar, labelled day.month. */
  readonly chartPoints = computed<BarChartPoint[]>(() =>
    (this.data()?.daily ?? []).map((day) => ({
      label: day.day.slice(8, 10) + '.' + day.day.slice(5, 7),
      values: { applied: day.applied, other: day.other, rejected: day.rejected },
    })),
  );

  /** Sources worst first, by name within a state; a header click sorts them otherwise. */
  readonly freshnessRows = computed(() =>
    [...(this.data()?.freshness ?? [])].sort(
      (a, b) => SEVERITY[a.state] - SEVERITY[b.state] || a.name.localeCompare(b.name),
    ),
  );

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
        due: { header: header('upl.overview.fresh.col.due'), content: cell(this.dueCell), width: '150px' },
      },
      columnsOrder: ['source', 'state', 'last', 'due'],
    };
  });

  readonly periods = UPL_OVERVIEW_PERIODS;
  /** A new period cancels the answer still on its way, so the latest one wins. */
  private readonly overview: ResourceRef<OverviewLoad | undefined> = rxResource({
    params: () => this.days(),
    stream: ({ params: days }) => {
      const shown: UplOverview | null = untracked(this.data);
      return this.api.get(days).pipe(
        map((overview): OverviewLoad => ({ overview, failed: false })),
        catchError(() => of<OverviewLoad>({ overview: shown, failed: true })),
      );
    },
  });

  readonly freshnessSort = {
    source: (f: UplSourceFreshness) => f.name,
    state: (f: UplSourceFreshness) => SEVERITY[f.state],
    last: (f: UplSourceFreshness) => f.lastPeriodTo,
    due: (f: UplSourceFreshness) => f.dueBy,
  };

  private readonly periodMemo = optionsMemo<SMTRadioOption<UplOverviewPeriod>[]>();

  ngOnInit(): void {
    interval(REFRESH_MS)
      .pipe(
        filter(() => typeof document === 'undefined' || document.visibilityState === 'visible'),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.load());
  }

  stateText(f: UplSourceFreshness): string {
    return this.i18n.translate(STATE_KEY[f.state]);
  }

  stateVariant(f: UplSourceFreshness): TBadgeVariant {
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
        return this.i18n.translate('upl.overview.attention.overdue', {
          source: item.sourceName,
          period: day(item.periodTo),
          days: item.daysLate ?? 0,
        });
      case 'rejected':
        return this.i18n.translate('upl.overview.attention.rejected', {
          source: item.sourceName,
          file: item.fileName ?? '',
        });
      default:
        return this.i18n.translate('upl.overview.attention.waiting', {
          source: item.sourceName,
          file: item.fileName ?? '',
        });
    }
  }

  attentionAction(item: UplAttentionItem): string {
    return this.i18n.translate(
      item.kind === 'overdue' ? 'upl.overview.attention.open_source' : 'upl.overview.attention.open_upload',
    );
  }

  /** An overdue source opens the upload form with it chosen; an upload opens its own card. */
  attentionLink(_item: UplAttentionItem): string[] {
    return ['/upl/packages'];
  }

  attentionQuery(item: UplAttentionItem): Record<string, string> {
    return item.packageId ? { open: item.packageId } : { source: String(item.sourceId) };
  }

  choose(period: UplOverviewPeriod): void {
    this.days.set(period);
  }

  /** Asks for the figures of the chosen period again, keeping the ones on screen meanwhile. */
  load(): void {
    this.overview.reload();
  }

  periodOptions(): SMTRadioOption<UplOverviewPeriod>[] {
    return this.periodMemo([this.optionText.currentLang()], () =>
      this.periods.map((period) => ({
        value: period,
        label: this.optionText.translate('upl.overview.days', { n: period }),
      })),
    );
  }
}
