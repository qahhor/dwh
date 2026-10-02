import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { fieldLabel, type QueryFieldMeta, type QueryListMeta } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { RefLookups } from '@shared/lookups/ref-lookup';
import { type BarChartPoint, type BarChartSeries, UiBarChartComponent } from '@shared/ui/ui-bar-chart.component';
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
  imports: [TranslatePipe, UiBarChartComponent, UiKpiCardComponent],
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
        } @else if (chart() === 'bar') {
          <ui-bar-chart
            data-testid="report-chart"
            [series]="series()"
            [points]="points()"
            [caption]="chartCaption()"
            [axisLabel]="axisLabel()"
          />
        }
        @if (showTable()) {
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
  styles: [
    `
      :host {
        display: flex;
        flex-direction: column;
        gap: 12px;
        min-width: 0;
      }
      .report-kpis {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
        gap: 12px;
      }
      .report-table-wrap {
        overflow-x: auto;
        max-height: 420px;
        border: 1px solid var(--border-color);
        border-radius: 8px;
      }
      .report-table-wrap:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }
      .report-table {
        width: 100%;
        border-collapse: collapse;
        font-size: 13px;
      }
      .report-table th,
      .report-table td {
        padding: 6px 10px;
        border-bottom: 1px solid var(--border-subtle);
        text-align: left;
        white-space: nowrap;
      }
      .report-table thead th {
        position: sticky;
        top: 0;
        background: var(--bg-surface);
        color: var(--text-muted);
        font-weight: 600;
      }
      .report-table tbody th {
        font-weight: 500;
      }
      .report-table .num {
        text-align: right;
        font-variant-numeric: tabular-nums;
      }
      .report-empty,
      .report-note {
        margin: 0;
        color: var(--text-muted);
        font-size: 13px;
      }
    `,
  ],
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
  readonly chartCaption = computed(() => `${this.caption()} — ${this.i18n.translate('ui.report.chart_bar')}`);

  readonly showKpi = computed(() => this.chart() === 'kpi' || this.chosenGroups().length === 0);

  readonly kpis = computed(() => {
    const result = this.result();
    const meta = this.meta();
    if (!result || result.rows.length === 0) return [];
    const translate = (key: string) => this.i18n.translate(key);
    return result.rows.flatMap((row) =>
      result.measures.map((measure, index) => {
        const suffix = this.rowText(row.groups);
        const label = measureLabel(measure, meta, translate);
        return { label: suffix ? `${label} (${suffix})` : label, value: row.values[index] ?? 0 };
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
      label: key === REST ? this.i18n.translate('ui.report.others') : key,
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
      const point = points.get(label) ?? { label, values: {} };
      const key = stack < 0 ? 'value' : this.stackKey(this.groupWord(row.groups[stack], stack));
      point.values[key] = (point.values[key] ?? 0) + Number(row.values[0] ?? 0);
      points.set(label, point);
    }
    return [...points.values()];
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

  /** The stack names in order of appearance; past {@link MAX_SERIES} the rest share one stack. */
  private readonly stackKeys = computed(() => {
    const result = this.result();
    const stack = this.stackIndex();
    if (!result || stack < 0) return [];
    const names: string[] = [];
    for (const row of result.rows) {
      const name = this.groupWord(row.groups[stack], stack);
      if (!names.includes(name)) names.push(name);
    }
    return names.length <= MAX_SERIES ? names : [...names.slice(0, MAX_SERIES - 1), REST];
  });

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
