import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  Signal,
  TemplateRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, filter, interval } from 'rxjs';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { RouterLink } from '@angular/router';
import { UiBadgeComponent } from '@shared/ui/ui-badge.component';
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
} from './overview-api';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';

/** How often an open, visible overview asks for fresh figures. */
const REFRESH_MS = 5 * 60 * 1000;
/** Worst first: what is late comes before what is merely due. */
const SEVERITY: Record<UplFreshnessState, number> = { overdue: 0, due: 1, never: 2, fresh: 3, adhoc: 4 };
const STATE_VARIANT: Record<UplFreshnessState, string> = {
  overdue: 'danger',
  due: 'warning',
  never: 'neutral',
  fresh: 'success',
  adhoc: 'info',
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
    UiBadgeComponent,
    SMTButtonComponent,
    UiDashboardCardComponent,
    UiLocalTableComponent,
    UiKpiCardComponent,
    UiBarChartComponent,
  ],
  templateUrl: './upl-overview.component.html',
  styles: [
    `
      .overview {
        display: flex;
        flex-direction: column;
        gap: 12px;
        padding: 24px;
        min-width: 0;
      }
      .overview__head {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 12px;
        flex-wrap: wrap;
      }
      .overview__title {
        margin: 0;
        font-size: 20px;
        font-weight: 700;
        color: var(--text-main);
      }
      .overview__subtitle {
        margin: 4px 0 0;
        font-size: 13px;
        color: var(--text-muted);
      }
      .overview__controls {
        display: flex;
        align-items: center;
        gap: 10px;
        flex-wrap: wrap;
      }
      .overview__stamp {
        margin: 0;
        font-size: 12px;
        color: var(--text-muted);
      }
      .overview__grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(min(100%, 340px), 1fr));
        gap: 16px;
      }
      .overview__tiles {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
        gap: 12px;
        margin: 0;
      }
      .overview__tile dt {
        font-size: 12px;
        color: var(--text-muted);
      }
      .overview__wide {
        grid-column: 1 / -1;
      }
      .overview__attention {
        list-style: none;
        margin: 0;
        padding: 0;
        display: grid;
        gap: 8px;
      }
      .overview__attention-item {
        display: grid;
        grid-template-columns: auto 1fr auto;
        gap: 8px;
        align-items: start;
        font-size: 13px;
      }
      .overview__attention-item .material-symbols-outlined {
        font-size: 18px;
      }
      .overview__attention-item--overdue .material-symbols-outlined {
        color: var(--danger-text);
      }
      .overview__attention-item--rejected .material-symbols-outlined {
        color: var(--warning-text, var(--danger-text));
      }
      .overview__attention-item--waiting .material-symbols-outlined {
        color: var(--primary-text, var(--primary));
      }
      .overview__attention-text {
        color: var(--text-main);
        overflow-wrap: anywhere;
      }
      .overview__attention-link {
        color: var(--primary-text, var(--primary));
        white-space: nowrap;
      }
      .overview__source {
        color: var(--primary-text, var(--primary));
        font-weight: 500;
      }
      .overview__code {
        display: block;
        font-size: 12px;
        color: var(--text-muted);
        font-family: var(--font-mono, monospace);
      }
      .overview__tile dd {
        margin: 4px 0 0;
        font-size: 22px;
        font-weight: 700;
        color: var(--text-main);
        font-variant-numeric: tabular-nums;
      }
    `,
  ],
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
  readonly data = signal<UplOverview | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);

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
  private request?: Subscription;

  readonly freshnessSort = {
    source: (f: UplSourceFreshness) => f.name,
    state: (f: UplSourceFreshness) => SEVERITY[f.state],
    last: (f: UplSourceFreshness) => f.lastPeriodTo,
    due: (f: UplSourceFreshness) => f.dueBy,
  };

  private readonly periodMemo = optionsMemo<SMTRadioOption<UplOverviewPeriod>[]>();

  ngOnInit(): void {
    this.load();
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
    this.request = this.api
      .get(this.days())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (overview) => {
          this.data.set(overview);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.failed.set(true);
        },
      });
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
