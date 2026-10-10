import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { catchError, debounceTime, map, of, switchMap } from 'rxjs';
import { fieldLabel, type QueryFieldMeta, type QueryListMeta } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { filterDsl, readFilterDsl } from '@core/services/query-meta.service';
import type { ListViewState } from '@shared/list-views/list-views';
import { problemText } from '@shared/ui/problem-text';
import { UiFilterBarComponent } from '@shared/ui/ui-filter-bar.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTRadioGroupComponent, type SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import {
  MAX_GROUPS,
  MAX_MEASURES,
  REPORT_OPS,
  REPORT_TRUNCS,
  type ReportChart,
  type ReportGroup,
  type ReportMeasure,
  type ReportOp,
  type ReportResult,
  type ReportState,
  type ReportTrunc,
  EntityReportsApi,
  dated,
  emptyReport,
  groupable,
  measurable,
} from './entity-reports';
import { SMTEntityReportSavedComponent } from './smt-entity-report-saved.component';
import { SMTEntityReportComponent } from './smt-entity-report.component';

let nextBuilderId = 0;

/** What the report view shows: its answer, or why there is none. */
interface ReportRun {
  result: ReportResult | null;
  error: string;
  loading: boolean;
}

/**
 * The report tab of an entity's list (ADR-0032 10.2; plan 10/10, item 5.8): a report without code — up to two
 * groupings (a choice, a yes/no, a reference or a date by day, week, month, quarter or year), up to four measures
 * (count, or sum, average, minimum and maximum of a number or of money) and the list's own filter — drawn as a table,
 * bars or one figure as soon as it changes. The server builds the query from the list's declaration, under the
 * viewer's scope and field rights; the person saves what they built as a report, or as a widget of their dashboard.
 */
@Component({
  selector: 'smt-entity-report-builder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTButtonComponent,
    SMTControlComponent,
    SMTEntityReportComponent,
    SMTEntityReportSavedComponent,
    SMTRadioGroupComponent,
    SMTSelectComponent,
    TranslatePipe,
    UiFilterBarComponent,
  ],
  host: { class: 'smt-entity-report-builder' },
  template: `
    <section class="builder" [attr.aria-labelledby]="ids + '-title'" data-testid="report-builder">
      <h2 class="sr-only" [id]="ids + '-title'">{{ 'ui.report.title' | t }}</h2>
      @if (canSave()) {
        <smt-entity-report-saved [listCode]="meta().code" [state]="state()" (opened)="open($event)" />
      }
      <ui-filter-bar
        [meta]="meta()"
        [conditions]="views().filter()"
        [match]="views().match()"
        (conditionsChange)="views().setFilter($event)"
        (filterChange)="views().setFilter($event.conditions, $event.match)"
      />
      <div class="builder-controls">
        @for (slot of groupSlots(); track $index; let i = $index) {
          <smt-control class="builder-field" [smtLabel]="(i === 0 ? 'ui.report.group_by' : 'ui.report.then_by') | t">
            <smt-select
              [smtTriggerId]="ids + '-group-' + i"
              [options]="groupOptions()"
              [placeholder]="'ui.report.no_grouping' | t"
              [value]="slot?.field ?? null"
              (valueChange)="setGroup(i, $event)"
            />
          </smt-control>
          @if (slot && isDated(slot.field)) {
            <smt-control class="builder-field" [smtLabel]="'ui.report.bucket' | t">
              <smt-select
                [smtTriggerId]="ids + '-trunc-' + i"
                [options]="truncOptions()"
                [allowClear]="false"
                [value]="slot.trunc ?? 'month'"
                (valueChange)="setTrunc(i, $event)"
              />
            </smt-control>
          }
        }
      </div>
      <div class="builder-controls">
        @for (measure of measures(); track $index; let i = $index) {
          <fieldset class="builder-measure" [attr.data-testid]="'report-measure-' + i">
            <legend class="sr-only">{{ 'ui.report.measure_n' | t: { n: i + 1 } }}</legend>
            <smt-control class="builder-field" [smtLabel]="'ui.report.measure' | t">
              <smt-select
                [smtTriggerId]="ids + '-op-' + i"
                [options]="opOptions()"
                [allowClear]="false"
                [value]="measure.op"
                (valueChange)="setOp(i, $event)"
              />
            </smt-control>
            @if (measure.op !== 'count') {
              <smt-control class="builder-field" [smtLabel]="'ui.report.of_field' | t">
                <smt-select
                  [smtTriggerId]="ids + '-field-' + i"
                  [options]="measureOptions()"
                  [allowClear]="false"
                  [value]="measure.field ?? null"
                  (valueChange)="setMeasureField(i, $event)"
                />
              </smt-control>
            }
            @if (measures().length > 1) {
              <button
                smt-button
                type="button"
                smtVariant="ghost"
                smtSize="sm"
                smtIcon="close"
                smtIconOnly
                [attr.aria-label]="'ui.report.remove_measure' | t: { n: i + 1 }"
                (click)="removeMeasure(i)"
              ></button>
            }
          </fieldset>
        }
        <button
          smt-button
          type="button"
          smtVariant="secondary"
          smtSize="sm"
          smtIcon="add"
          data-testid="report-add-measure"
          [disabled]="measures().length >= maxMeasures"
          (click)="addMeasure()"
        >
          {{ 'ui.report.add_measure' | t }}
        </button>
      </div>
      <smt-radio-group
        smtAppearance="segmented"
        smtOrientation="horizontal"
        [options]="chartOptions()"
        [value]="effectiveChart()"
        [smtAriaLabel]="'ui.report.chart' | t"
        (valueChange)="chart.set($event ?? 'table')"
      />
      <div class="builder-result" aria-live="polite" [attr.aria-busy]="run().loading || null">
        @if (run().error) {
          <p class="builder-error" role="alert" data-testid="report-error">{{ run().error }}</p>
        } @else {
          <smt-entity-report [result]="run().result" [meta]="meta()" [chart]="effectiveChart()" [caption]="caption()" />
        }
      </div>
    </section>
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .builder {
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .builder-controls {
        display: flex;
        flex-wrap: wrap;
        align-items: flex-end;
        gap: 12px;
      }
      .builder-field {
        display: flex;
        flex-direction: column;
        gap: 4px;
        min-width: 180px;
      }
      .builder-measure {
        display: flex;
        align-items: flex-end;
        gap: 8px;
        margin: 0;
        padding: 0;
        border: 0;
      }
      .builder-error {
        margin: 0;
        color: var(--danger-text, var(--danger));
      }
    `,
  ],
})
export class SMTEntityReportBuilderComponent {
  private readonly i18n = inject(I18nService);
  private readonly reports = inject(EntityReportsApi);

  /** The entity whose list is reported. */
  readonly code = input.required<string>();
  readonly meta = input.required<QueryListMeta>();
  /** The list's views: the report takes the filter on screen. */
  readonly views = input.required<ListViewState>();
  /** The title of the list, the start of the report's accessible name. */
  readonly title = input('');
  /** The person may save reports of this list. */
  readonly canSave = input(false);

  readonly groupBy = signal<ReportGroup[]>([]);
  readonly measures = signal<ReportMeasure[]>(emptyReport().measures);
  /** Bars by default: one figure until something is grouped. */
  readonly chart = signal<ReportChart>('bar');

  readonly run = signal<ReportRun>({ result: null, error: '', loading: false });

  /** The grouping slots: the chosen ones, and one more empty while there is room. */
  readonly groupSlots = computed<(ReportGroup | null)[]>(() => {
    const groups = this.groupBy();
    return groups.length < MAX_GROUPS ? [...groups, null] : groups;
  });

  /** A chart that fits the report: bars need a grouping, one figure has none. */
  readonly effectiveChart = computed<ReportChart>(() => {
    const grouped = this.groupBy().length > 0;
    const chart = this.chart();
    if (chart === 'bar' && !grouped) return 'kpi';
    if (chart === 'kpi' && grouped) return 'bar';
    return chart;
  });

  /** What is on screen, as a saved report keeps it. */
  readonly state = computed<ReportState>(() => ({
    groupBy: this.groupBy(),
    measures: this.measures(),
    filter: filterDsl(this.views().filter(), this.views().match(), true),
    chart: this.effectiveChart(),
  }));

  readonly groupOptions = computed<SMTSelectOption<string>[]>(() => this.options(this.meta().fields.filter(groupable)));
  readonly measureOptions = computed<SMTSelectOption<string>[]>(() =>
    this.options(this.meta().fields.filter(measurable)),
  );
  readonly truncOptions = computed<SMTSelectOption<ReportTrunc>[]>(() => {
    this.i18n.currentLang();
    return REPORT_TRUNCS.map((trunc) => ({ id: trunc, label: this.i18n.translate(`ui.report.trunc.${trunc}`) }));
  });
  readonly opOptions = computed<SMTSelectOption<ReportOp>[]>(() => {
    this.i18n.currentLang();
    const numbers = this.measureOptions().length > 0;
    return REPORT_OPS.map((op) => ({
      id: op,
      label: this.i18n.translate(`ui.report.op.${op}`),
      disabled: op !== 'count' && !numbers,
    }));
  });
  readonly chartOptions = computed<SMTRadioOption<ReportChart>[]>(() => {
    this.i18n.currentLang();
    const grouped = this.groupBy().length > 0;
    return [
      { value: 'table', label: this.i18n.translate('ui.report.chart_table'), icon: 'table' },
      { value: 'bar', label: this.i18n.translate('ui.report.chart_bar'), icon: 'bar_chart', disabled: !grouped },
      { value: 'kpi', label: this.i18n.translate('ui.report.chart_kpi'), icon: 'pin', disabled: grouped },
    ];
  });

  readonly caption = computed(() => this.i18n.translate('ui.report.caption', { list: this.title() }));

  readonly ids = `report-builder-${nextBuilderId++}`;
  readonly maxMeasures = MAX_MEASURES;

  constructor() {
    const request = computed(() => ({
      code: this.code(),
      groupBy: this.groupBy(),
      measures: this.measures(),
      filter: filterDsl(this.views().filter(), this.views().match()),
    }));
    toObservable(request)
      .pipe(
        debounceTime(250),
        switchMap((asked) => {
          this.run.update((current) => ({ ...current, loading: true }));
          return this.reports.run(asked.code, asked).pipe(
            map((result): ReportRun => ({ result, error: '', loading: false })),
            catchError((failure: unknown) =>
              of<ReportRun>({
                result: null,
                error: problemText(failure) || this.i18n.translate('ui.report.failed'),
                loading: false,
              }),
            ),
          );
        }),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((run) => this.run.set(run));
    // A measure of a number needs a number field; a list without one keeps the count only.
    effect(() => {
      if (this.measureOptions().length === 0 && this.measures().some((measure) => measure.op !== 'count')) {
        this.measures.set(emptyReport().measures);
      }
    });
  }

  isDated(key: string): boolean {
    return dated(this.meta().fields.find((field) => field.key === key));
  }

  setGroup(index: number, key: string | null): void {
    const groups = [...this.groupBy()];
    if (!key) {
      groups.splice(index, 1);
    } else {
      const group: ReportGroup = this.isDated(key) ? { field: key, trunc: 'month' } : { field: key };
      groups[index] = group;
    }
    this.groupBy.set(groups.filter((group, at) => groups.findIndex((other) => other.field === group.field) === at));
  }

  setTrunc(index: number, trunc: ReportTrunc | null): void {
    this.groupBy.update((groups) =>
      groups.map((group, at) => (at === index ? { ...group, trunc: trunc ?? 'month' } : group)),
    );
  }

  setOp(index: number, op: ReportOp | null): void {
    const chosen = op ?? 'count';
    const first = this.measureOptions()[0]?.id;
    this.measures.update((measures) =>
      measures.map((measure, at) => {
        if (at !== index) return measure;
        if (chosen === 'count') return { op: 'count' };
        return { op: chosen, field: measure.field ?? first };
      }),
    );
  }

  setMeasureField(index: number, field: string | null): void {
    this.measures.update((measures) =>
      measures.map((measure, at) => (at === index ? { ...measure, field: field ?? measure.field } : measure)),
    );
  }

  addMeasure(): void {
    const first = this.measureOptions()[0]?.id;
    this.measures.update((measures) => [...measures, first ? { op: 'sum', field: first } : { op: 'count' }]);
  }

  removeMeasure(index: number): void {
    this.measures.update((measures) => measures.filter((_measure, at) => at !== index));
  }

  /** Shows a saved report: its grouping, measures and chart, and its filter in the list's filter bar. */
  open(state: ReportState): void {
    this.groupBy.set(state.groupBy ?? []);
    this.measures.set(state.measures?.length ? state.measures : emptyReport().measures);
    this.chart.set(state.chart ?? 'bar');
    const saved = readFilterDsl(state.filter ?? []);
    this.views().setFilter(saved.conditions, saved.match);
  }

  private options(fields: QueryFieldMeta[]): SMTSelectOption<string>[] {
    this.i18n.currentLang();
    return fields.map((field) => ({ id: field.key, label: fieldLabel(field, (key) => this.i18n.translate(key)) }));
  }
}
