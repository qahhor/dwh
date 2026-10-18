import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { fieldLabel, type QueryFieldMeta, type QueryListMeta } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { RefLookups } from '@shared/lookups/ref-lookup';
import { type BarChartPoint, type BarChartSeries, UiBarChartComponent } from '@shared/ui/ui-bar-chart.component';
import { UiReportPlotComponent } from '@shared/ui/ui-report-plot.component';
import { reportChartProjection } from './report-chart-projection';
import { UiKpiCardComponent } from '@shared/ui/ui-kpi-card.component';
import { type ReportChart, type ReportResult, type ReportRow, drillFilter, measureLabel } from './entity-reports';
import { ReportWords } from './report-words';
import { SMTReportPivotComponent } from './smt-report-pivot.component';

/** The colours of the stacks of a chart, theme tokens in a fixed order. */
const SERIES_COLORS = ['var(--primary)', 'var(--success)', 'var(--warning)', 'var(--info)', 'var(--danger)'];

/** More stacks than colours: the last one adds up the rest. */
const MAX_SERIES = SERIES_COLORS.length;
const REST = '__rest__';

/** One column of the table: a group's field or a measure. */
interface ReportColumn {
  label: string;
  numeric: boolean;
}

/**
 * A report's answer on screen (ADR-0032 10.2): one figure per measure when nothing is grouped, bars of the first
 * measure — stacked by the second grouping when there is one — and the table of every group and measure. The chart is
 * an image with a name, its figures in a table for screen readers (`ui-bar-chart`); the visible table carries the same
 * figures with column headers, so nothing is told by colour alone. Group values are read in words: the server's label
 * of a choice, a list item, a user or a record, else a choice by its label, a reference by its name, a date bucket by its
 * period. With `drillCode` each row links to the list of its records under the same filter (drill-down).
 */
@Component({
  selector: 'smt-entity-report',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    SMTReportPivotComponent,
    TranslatePipe,
    UiBarChartComponent,
    UiKpiCardComponent,
    UiReportPlotComponent,
  ],
  host: { class: 'smt-entity-report' },
  templateUrl: './smt-entity-report.component.html',
  styleUrl: './smt-entity-report.component.css',
})
export class SMTEntityReportComponent {
  private readonly i18n = inject(I18nService);

  readonly result = input.required<ReportResult | null>();
  /** The list's metadata: the words of its fields and values. */
  readonly meta = input.required<QueryListMeta | null>();
  /** What the report shows, for the accessible name of its chart and table. */
  readonly caption = input.required<string>();

  readonly chart = input<ReportChart>('table');
  /** The table under the chart; a widget shows only its chart. */
  readonly showTable = input(true);
  /**
   * The entity whose records a row opens (drill-down), with the report's filter; none — the rows open nothing, as on a
   * dashboard or for a prepared dataset, whose fields the list does not have.
   */
  readonly drillCode = input<string | null>(null);
  readonly drillBase = input<readonly unknown[]>([]);

  /** The filter of each row's records as the list link carries it; null where the list cannot say it exactly. */
  readonly drills = computed<(string | null)[]>(() => {
    const result = this.result();
    const meta = this.meta();
    if (!result || !meta || !this.drillCode()) return [];
    return result.rows.map((_row, index) => {
      const filter = drillFilter(result, index, meta, this.drillBase());
      return filter ? JSON.stringify(filter) : null;
    });
  });

  /** Each row's groups in words, for the name of its link. */
  readonly rowLabels = computed(() => (this.result()?.rows ?? []).map((row) => this.rowText(row)));

  /** The groups the person chose, without the currency the platform adds to money. */
  readonly groupCount = computed(() => (this.result()?.groups ?? []).length);

  /** The chart's own name, apart from the table's: a screen reader meets two tables with the same figures. */
  readonly chartCaption = computed(
    () =>
      `${this.caption()} — ${this.i18n.translate(this.chart() === 'line' ? 'ui.report.chart_line' : this.chart() === 'horizontalBar' ? 'ui.report.chart_horizontal_bar' : 'ui.report.chart_bar')}`,
  );

  readonly showKpi = computed(
    () => this.chart() === 'kpi' || (!this.additionalChart() && this.chosenGroups().length === 0),
  );
  readonly additionalChart = computed<'line' | 'horizontalBar' | null>(() =>
    this.chart() === 'line' ? 'line' : this.chart() === 'horizontalBar' ? 'horizontalBar' : null,
  );
  readonly projection = computed(() => {
    const kind = this.additionalChart();
    return kind ? reportChartProjection(this.result(), this.meta(), kind) : null;
  });
  readonly plotPoints = computed(() =>
    (this.projection() ?? []).map((point) => ({
      ...point,
      label: this.rowText(this.result()!.rows[point.row]),
      text: this.number(point.value),
    })),
  );

  /**
   * One card per figure; with a comparison one per measure — its current figure with the change against the earlier
   * one (ADR-0044).
   */
  readonly kpis = computed(() => {
    const result = this.result();
    const meta = this.meta();
    if (!result || result.rows.length === 0) return [];
    const translate = (key: string) => this.i18n.translate(key);
    const compared = !!result.comparison;
    return result.rows.flatMap((row) =>
      result.measures.flatMap((measure, index) => {
        if (compared && measure.period !== 'current') return [];
        const suffix = this.rowText(row);
        const label = measureLabel({ ...measure, period: undefined }, meta, translate);
        const previous = compared ? (row.values[index + 1] ?? null) : null;
        return [
          {
            label: suffix ? `${label} (${suffix})` : label,
            value: (measure.share ? this.words.percent(row.values[index]) : row.values[index]) ?? '—',
            previous: compared && row.values[index] !== null ? previous : null,
            note: compared ? translate('ui.report.period.previous') + ': ' + this.number(previous) : '',
          },
        ];
      }),
    );
  });

  readonly columns = computed<ReportColumn[]>(() => {
    const result = this.result();
    const meta = this.meta();
    if (!result) return [];
    const translate = (key: string) => this.i18n.translate(key);
    return [
      ...result.groups.map((group) => {
        const field = this.field(group.field);
        const label = field ? fieldLabel(field, translate) : group.field;
        return {
          label: group.implicit ? this.i18n.translate('ui.report.currency') : label,
          numeric: false,
        };
      }),
      ...result.measures.map((measure) => ({ label: measureLabel(measure, meta, translate), numeric: true })),
    ];
  });

  readonly cells = computed(() => {
    const result = this.result();
    if (!result) return [];
    return result.rows.map((row) => [
      ...row.groups.map((value, index) => ({
        text: this.groupWord(value, index, row.labels?.[index]),
        numeric: false,
      })),
      ...row.values.map((value, index) => ({ text: this.figure(result, index, value), numeric: true })),
    ]);
  });

  /** The totals under the table: "Total" in the first grouping's column, the currency of money kept (ADR-0044). */
  readonly totalCells = computed(() => {
    const result = this.result();
    if (!result || result.pivot) return [];
    const total = this.i18n.translate('ui.report.total');
    return (result.totals ?? []).map((row) => {
      let named = false;
      return [
        ...row.groups.map((value, index) => {
          if (value === null && !named && !result.groups[index].implicit) {
            named = true;
            return { text: total, numeric: false };
          }
          return { text: value === null ? '' : this.groupWord(value, index, row.labels?.[index]), numeric: false };
        }),
        ...row.values.map((value, index) => ({ text: this.figure(result, index, value), numeric: true })),
      ];
    });
  });

  readonly axisLabel = computed(() => {
    const first = this.chosenGroups()[0];
    const field = first ? this.field(first.group.field) : undefined;
    return field ? fieldLabel(field, (key) => this.i18n.translate(key)) : '';
  });

  /**
   * The stacks: one per value of the second grouping (or of the currency of money), more than five added into the
   * last; a single stack of the first measure otherwise.
   */
  readonly series = computed<BarChartSeries[]>(() => {
    const result = this.result();
    if (!result) return [];
    const stack = this.stackIndex();
    if (stack < 0) {
      return [
        {
          key: 'value',
          label: measureLabel(result.measures[0], this.meta(), (key) => this.i18n.translate(key)),
          color: SERIES_COLORS[0],
        },
      ];
    }
    return this.stackKeys().map((key, index) => ({
      key,
      label:
        key === REST
          ? this.i18n.translate('ui.report.others')
          : this.groupWord(this.stackGroups().get(key), stack, this.stackLabels().get(key)),
      color: SERIES_COLORS[index],
    }));
  });

  readonly points = computed<BarChartPoint[]>(() => {
    const result = this.result();
    if (!result) return [];
    const stack = this.stackIndex();
    const axis = this.chosenGroups()[0]?.index ?? 0;
    const points = new Map<string, BarChartPoint>();
    for (const row of result.rows) {
      const label = this.groupWord(row.groups[axis], axis, row.labels?.[axis]);
      const axisKey = this.groupKey(row.groups[axis]);
      const point = points.get(axisKey) ?? { label, values: {} };
      const key = stack < 0 ? 'value' : this.stackKey(this.groupKey(row.groups[stack]));
      point.values[key] = (point.values[key] ?? 0) + Number(row.values[0] ?? 0);
      points.set(axisKey, point);
    }
    return [...points.values()];
  });

  /** Only additive measures may combine the remaining series. */
  readonly tableFallback = computed(() => {
    const result = this.result();
    if (this.additionalChart()) return this.projection() === null;
    if (!result || this.chart() !== 'bar' || this.showKpi()) return false;
    if (result.groups.length > 2) return true;
    if (
      result.groups.some(
        (group, index) =>
          (group.implicit || this.field(group.field)?.format === 'currency') &&
          new Set(result.rows.map((row) => this.groupKey(row.groups[index]))).size > 1,
      )
    ) {
      return true;
    }
    if (result.rows.some((row) => row.values[0] == null || !Number.isFinite(row.values[0]) || row.values[0]! < 0)) {
      return true;
    }
    const op = result.measures[0]?.op;
    if (op === 'sum' || op === 'count') return false;
    const stacks = this.stackGroups().size;
    if (stacks > MAX_SERIES) return true;
    const axis = this.chosenGroups()[0]?.index ?? 0;
    const axes = new Set(result.rows.map((row) => this.groupKey(row.groups[axis]))).size;
    return stacks > 1 && axes * stacks !== result.rows.length;
  });

  private readonly chosenGroups = computed(() =>
    (this.result()?.groups ?? []).map((group, index) => ({ group, index })).filter((item) => !item.group.implicit),
  );

  /** The column the bars stack by: the second grouping, else the currency of money; -1 for none. */
  private readonly stackIndex = computed(() => {
    const groups = this.result()?.groups ?? [];
    const axis = this.chosenGroups()[0]?.index ?? -1;
    return groups.findIndex((_group, index) => index !== axis);
  });

  /** Original keys identify groups; translated names are only labels. */
  private readonly stackGroups = computed(() => {
    const result = this.result();
    const stack = this.stackIndex();
    const groups = new Map<string, string | number | boolean | null>();
    if (result && stack >= 0) {
      for (const row of result.rows) {
        const value = row.groups[stack];
        groups.set(this.groupKey(value), value);
      }
    }
    return groups;
  });

  /** The server's words of the stacks' values, by their keys. */
  private readonly stackLabels = computed(() => {
    const result = this.result();
    const stack = this.stackIndex();
    const labels = new Map<string, string>();
    if (result && stack >= 0) {
      for (const row of result.rows) {
        const label = row.labels?.[stack];
        if (label) labels.set(this.groupKey(row.groups[stack]), label);
      }
    }
    return labels;
  });

  /** The raw keys in order of appearance; the remaining additive series share a reserved key. */
  private readonly stackKeys = computed(() => {
    const keys = [...this.stackGroups().keys()];
    return keys.length <= MAX_SERIES ? keys : [...keys.slice(0, MAX_SERIES - 1), REST];
  });

  private readonly words = new ReportWords(this.i18n, inject(RefLookups));

  /** A period as its first and last day. */
  period(from: string, to: string): string {
    const options: Intl.DateTimeFormatOptions = { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' };
    return `${this.i18n.formatDate(from, options)} — ${this.i18n.formatDate(to, options)}`;
  }

  private groupKey(value: string | number | boolean | null | undefined): string {
    return 'group:' + JSON.stringify(value ?? null);
  }

  private stackKey(name: string): string {
    return this.stackKeys().includes(name) ? name : REST;
  }

  private field(key: string): QueryFieldMeta | undefined {
    return this.words.field(this.meta(), key);
  }

  private groupWord(value: string | number | boolean | null | undefined, index: number, label?: string | null): string {
    return this.words.group(this.result(), this.meta(), value, index, label);
  }

  private rowText(row: ReportRow): string {
    return this.words.row(this.result(), this.meta(), row);
  }

  private number(value: number | null): string {
    return this.words.number(value);
  }

  /** A measure's figure: a share or a change in percent with its sign, any other in the language's format. */
  private figure(result: ReportResult, index: number, value: number | null): string {
    const measure = result.measures[index];
    return measure?.share || measure?.period === 'change_percent' ? this.words.percent(value) : this.number(value);
  }
}
