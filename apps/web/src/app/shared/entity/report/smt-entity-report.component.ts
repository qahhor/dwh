import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { fieldLabel, type QueryFieldMeta, type QueryListMeta } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { RefLookups } from '@shared/lookups/ref-lookup';
import { type BarChartPoint, type BarChartSeries, UiBarChartComponent } from '@shared/ui/ui-bar-chart.component';
import { UiReportPlotComponent } from '@shared/ui/ui-report-plot.component';
import { reportChartProjection } from './report-chart-projection';
import { UiKpiCardComponent } from '@shared/ui/ui-kpi-card.component';
import { type ReportChart, type ReportResult, groupText, measureLabel } from './entity-reports';

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
 * figures with column headers, so nothing is told by colour alone. Group values are read in words: a choice by its
 * label, a reference by its name, a date bucket by its period.
 */
@Component({
  selector: 'smt-entity-report',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, UiBarChartComponent, UiKpiCardComponent, UiReportPlotComponent],
  host: { class: 'smt-entity-report' },
  template: `
    @if (result(); as result) {
      @if (result.rows.length === 0) {
        <p class="report-empty" data-testid="report-empty">{{ 'ui.report.no_rows' | t }}</p>
      } @else {
        @if (showKpi()) {
          <div class="report-kpis" data-testid="report-kpis">
            @for (kpi of kpis(); track $index) {
              <ui-kpi-card [label]="kpi.label" [value]="kpi.value" goodWhen="neutral" />
            }
          </div>
        } @else if (chart() === 'bar' && !tableFallback()) {
          <ui-bar-chart
            data-testid="report-chart"
            [series]="series()"
            [points]="points()"
            [caption]="chartCaption()"
            [axisLabel]="axisLabel()"
          />
        }
        @if (additionalChart(); as kind) {
          @if (!tableFallback()) {
            <ui-report-plot [kind]="kind" [points]="plotPoints()" [caption]="chartCaption()" />
          }
        }
        @if (tableFallback()) {
          <p class="report-note" role="note" data-testid="report-chart-fallback">
            {{ 'ui.report.chart_table_fallback' | t }}
          </p>
        }
        @if (showTable() || tableFallback() || additionalChart()) {
          <div class="report-table-wrap" tabindex="0" role="region" [attr.aria-label]="caption()">
            <table class="report-table" data-testid="report-table">
              <caption class="sr-only">
                {{
                  caption()
                }}
              </caption>
              <thead>
                <tr>
                  @for (column of columns(); track $index) {
                    <th scope="col" [class.num]="column.numeric">{{ column.label }}</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (row of cells(); track $index) {
                  <tr>
                    @for (cell of row; track $index; let first = $first) {
                      @if (first && groupCount() > 0) {
                        <th scope="row">{{ cell.text }}</th>
                      } @else {
                        <td [class.num]="cell.numeric">{{ cell.text }}</td>
                      }
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
        @if (result.truncated) {
          <p class="report-note" role="note">{{ 'ui.report.truncated' | t: { n: result.rows.length } }}</p>
        }
      }
    }
  `,
  styleUrl: './smt-entity-report.component.css',
})
export class SMTEntityReportComponent {
  private readonly i18n = inject(I18nService);
  private readonly refLookups = inject(RefLookups);

  readonly result = input.required<ReportResult | null>();
  /** The list's metadata: the words of its fields and values. */
  readonly meta = input.required<QueryListMeta | null>();
  /** What the report shows, for the accessible name of its chart and table. */
  readonly caption = input.required<string>();
  readonly chart = input<ReportChart>('table');
  /** The table under the chart; a widget shows only its chart. */
  readonly showTable = input(true);

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
      label: this.rowText(this.result()!.rows[point.row].groups),
      text: this.number(point.value),
    })),
  );

  readonly kpis = computed(() => {
    const result = this.result();
    const meta = this.meta();
    if (!result || result.rows.length === 0) return [];
    const translate = (key: string) => this.i18n.translate(key);
    return result.rows.flatMap((row) =>
      result.measures.map((measure, index) => {
        const suffix = this.rowText(row.groups);
        const label = measureLabel(measure, meta, translate);
        return { label: suffix ? `${label} (${suffix})` : label, value: row.values[index] ?? '—' };
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
      ...row.groups.map((value, index) => ({ text: this.groupWord(value, index), numeric: false })),
      ...row.values.map((value) => ({ text: this.number(value), numeric: true })),
    ]);
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
        key === REST ? this.i18n.translate('ui.report.others') : this.groupWord(this.stackGroups().get(key), stack),
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
      const label = this.groupWord(row.groups[axis], axis);
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

  /** The raw keys in order of appearance; the remaining additive series share a reserved key. */
  private readonly stackKeys = computed(() => {
    const keys = [...this.stackGroups().keys()];
    return keys.length <= MAX_SERIES ? keys : [...keys.slice(0, MAX_SERIES - 1), REST];
  });

  private groupKey(value: string | number | boolean | null | undefined): string {
    return 'group:' + JSON.stringify(value ?? null);
  }

  private stackKey(name: string): string {
    return this.stackKeys().includes(name) ? name : REST;
  }

  private field(key: string): QueryFieldMeta | undefined {
    return this.meta()?.fields.find((field) => field.key === key);
  }

  private groupWord(value: string | number | boolean | null | undefined, index: number): string {
    const group = this.result()?.groups[index];
    if (!group) return '';
    this.i18n.currentLang();
    return groupText(value ?? null, this.field(group.field), group.trunc, {
      translate: (key) => this.i18n.translate(key),
      date: (iso, options) => this.i18n.formatDate(iso, options),
      refName: (field, key) => (field.ref ? this.refLookups.name(field.ref, key) : null),
    });
  }

  private rowText(groups: (string | number | boolean | null)[]): string {
    return groups.map((value, index) => this.groupWord(value, index)).join(' · ');
  }

  private number(value: number | null): string {
    return value === null ? '—' : this.i18n.formatNumber(Number(value));
  }
}
