import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { Observable, catchError, debounceTime, map, of, switchMap } from 'rxjs';
import { fieldLabel, type QueryListMeta, type QueryCondition, type QueryMatch } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { filterDsl, readFilterDsl } from '@core/services/query-meta.service';
import type { ListViewState } from '@shared/list-views/list-views';
import { problemText } from '@shared/ui/problem-text';
import { UiFilterBarComponent } from '@shared/ui/ui-filter-bar.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTRadioGroupComponent } from '@shared/ui-kit/components/forms/radio-group';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import {
  MAX_GROUPS,
  REPORT_TRUNCS,
  type ReportChart,
  type ReportGroup,
  type ReportMeasure,
  type ReportResult,
  type ReportSort,
  type ReportState,
  type ReportTrunc,
  type SavedReport,
  EntityReportsApi,
  dated,
  emptyReport,
  groupable,
} from './entity-reports';
import {
  completeCompare,
  pendingParameters,
  reportShape,
  type ParameterValue,
  type ReportCompare,
  type ReportParameter,
} from './report-analytics';
import { SMTReportMeasuresComponent } from './smt-report-measures.component';
import { SMTReportParametersComponent } from './smt-report-parameters.component';
import { SMTReportParametersEditorComponent } from './smt-report-parameters-editor.component';
import { SMTReportShapeComponent } from './smt-report-shape.component';
import { chartOptions, parseSort, reportOrder, sortOptions, sortValue, topOptions } from './report-order';
import { ReportSourcesStore } from './report-sources.store';
import { ReportExportComponent } from './report-export.component';
import type { ReportExportSource } from './report-export.api';
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
 * bars or one figure as soon as it changes, its groups ordered and cut to a top when asked. The server builds the query
 * under the viewer's scope and field rights; the person saves it as a report or a widget of their dashboard. A table row
 * opens its records under the same filter (drill-down), unless the report reads a prepared dataset.
 */
@Component({
  selector: 'smt-entity-report-builder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTButtonComponent,
    SMTControlComponent,
    SMTEntityReportComponent,
    SMTEntityReportSavedComponent,
    SMTReportMeasuresComponent,
    SMTReportParametersComponent,
    SMTReportParametersEditorComponent,
    SMTReportShapeComponent,
    ReportExportComponent,
    SMTRadioGroupComponent,
    SMTSelectComponent,
    TranslatePipe,
    UiFilterBarComponent,
  ],
  providers: [ReportSourcesStore],
  host: { class: 'smt-entity-report-builder' },
  templateUrl: './smt-entity-report-builder.component.html',
  styleUrl: './smt-entity-report-builder.component.css',
})
export class SMTEntityReportBuilderComponent {
  readonly sources = inject(ReportSourcesStore);
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
  /** A saved report to open once the person's reports are read: the reports catalog opens one this way. */
  readonly openId = input<number | null>(null);

  private readonly savedReports = viewChild(SMTEntityReportSavedComponent);

  readonly source = signal<SavedReport | null>(null);

  readonly groupBy = signal<ReportGroup[]>([]);
  readonly measures = signal<ReportMeasure[]>(emptyReport().measures);
  /** Bars by default: one figure until something is grouped. */
  readonly chart = signal<ReportChart>('bar');
  /** The order of the groups; null — by their values. */
  readonly sort = signal<ReportSort | null>(null);
  /** The top of the groups kept; null — every group. */
  readonly limit = signal<number | null>(null);
  /** The analytic shape (ADR-0044): a pivot, totals, two periods compared. */
  readonly pivot = signal(false);
  readonly totals = signal(false);
  readonly compare = signal<ReportCompare | null>(null);
  /** The parameters the report asks its reader for, as the author declares them. */
  readonly parameters = signal<ReportParameter[]>([]);
  /** The values a reader applied to an open saved report; null — not yet applied. */
  readonly parameterValues = signal<Record<string, ParameterValue> | null>(null);

  readonly run = signal<ReportRun>({ result: null, error: '', loading: false });

  readonly refresh = signal(0);
  readonly completedRequest = signal('');
  private readonly projectedFilter = signal<QueryCondition[]>([]);
  private readonly projectedMatch = signal<QueryMatch>('all');

  /** The order and the top as asked: only for a grouped report, and an order by a measure the report has. */
  readonly order = computed(() => reportOrder(this.groupBy().length, this.measures(), this.sort(), this.limit()));
  readonly reportFilter = computed(() => (this.sources.dataset() ? this.projectedFilter() : this.views().filter()));
  readonly reportMatch = computed(() => (this.sources.dataset() ? this.projectedMatch() : this.views().match()));
  readonly reportMeta = computed(() =>
    this.sources.dataset() ? (this.sources.meta() ?? { ...this.meta(), fields: [] }) : this.meta(),
  );
  readonly displayResult = computed(() =>
    JSON.stringify(this.request()) === this.completedRequest() ? this.run().result : null,
  );

  /** The grouping slots: the chosen ones, and one more empty while there is room. */
  readonly groupSlots = computed<(ReportGroup | null)[]>(() => {
    const groups = this.groupBy();
    return groups.length < MAX_GROUPS ? [...groups, null] : groups;
  });

  /** A comparison asks for both its days when its period is a range of the report's own. */
  readonly effectiveCompare = computed(() => completeCompare(this.compare()));
  /** The parameters of the open saved report, and whether the reader still has to fill a required one. */
  readonly sourceParameters = computed(() => this.source()?.state.parameters ?? []);
  readonly parametersPending = computed(() => pendingParameters(this.sourceParameters(), this.parameterValues()));

  /** A chart that fits the report: bars need a grouping, one figure has none; a pivot and a comparison are tables. */
  readonly effectiveChart = computed<ReportChart>(() => {
    const grouped = this.groupBy().length > 0;
    if (this.pivot()) return 'table';
    if (this.effectiveCompare()) return grouped ? 'table' : 'kpi';
    const chart = this.chart();
    if (chart === 'bar' && !grouped) return 'kpi';
    if (chart === 'kpi' && grouped) return 'bar';
    return chart;
  });

  /** What is on screen, as a saved report keeps it. */
  readonly state = computed<ReportState>(() => ({
    groupBy: this.groupBy(),
    measures: this.measures(),
    filter: filterDsl(this.reportFilter(), this.reportMatch(), true),
    chart: this.effectiveChart(),
    ...(this.sources.dataset() ? { dataset: this.sources.dataset()! } : {}),
    ...this.order(),
    ...this.shape(),
    ...(this.parameters().length ? { parameters: this.parameters() } : {}),
  }));

  /** The pivot, totals and comparison only when the report has them, as the server keeps them. */
  readonly shape = computed(() =>
    reportShape(this.pivot(), this.totals(), this.effectiveCompare(), this.groupBy().length),
  );

  readonly sortOptions = computed(() => {
    this.i18n.currentLang();
    return sortOptions(this.measures(), this.reportMeta(), (key, params) => this.i18n.translate(key, params));
  });
  readonly sortValue = computed(() => sortValue(this.order().sort));
  readonly topOptions = computed(() => {
    this.i18n.currentLang();
    return topOptions((key, params) => this.i18n.translate(key, params));
  });

  readonly groupOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    const translate = (key: string) => this.i18n.translate(key);
    return this.reportMeta()
      .fields.filter(groupable)
      .map((field) => ({ id: field.key, label: fieldLabel(field, translate) }));
  });
  readonly truncOptions = computed<SMTSelectOption<ReportTrunc>[]>(() => {
    this.i18n.currentLang();
    return REPORT_TRUNCS.map((trunc) => ({ id: trunc, label: this.i18n.translate(`ui.report.trunc.${trunc}`) }));
  });
  readonly chartOptions = computed(() => {
    this.i18n.currentLang();
    const first = this.groupBy()[0]?.field;
    return chartOptions(
      this.groupBy().length,
      first !== undefined && this.isDated(first),
      this.measures().length,
      (key) => this.i18n.translate(key),
    );
  });

  readonly caption = computed(() => this.i18n.translate('ui.report.caption', { list: this.title() }));
  readonly request = computed(() => ({
    code: this.code(),
    dataset: this.sources.dataset() ?? undefined,
    metadataLoading: this.sources.loading(),
    metadataError: this.sources.error(),
    groupBy: this.groupBy(),
    measures: this.measures(),
    filter: filterDsl(this.reportFilter(), this.reportMatch()),
    ...this.order(),
    ...this.shape(),
    parameters: this.source() ? this.parameterValues() : null,
    pending: this.parametersPending(),
    source: this.source()?.id ?? null,
    revision: this.source()?.lockVersion ?? null,
    refresh: this.refresh(),
  }));
  readonly exportSource = computed<ReportExportSource | null>(() => {
    const asked = this.request();
    if (
      !this.run().result ||
      this.run().loading ||
      this.run().error ||
      JSON.stringify(asked) !== this.completedRequest()
    )
      return null;
    return asked.source !== null
      ? {
          kind: 'saved',
          code: asked.code,
          id: asked.source,
          ...(asked.parameters ? { parameters: asked.parameters } : {}),
        }
      : { kind: 'query', code: asked.code, state: { ...this.state(), filter: asked.filter } };
  });

  readonly ids = `report-builder-${nextBuilderId++}`;

  constructor() {
    effect(() => {
      const code = this.code();
      untracked(() => this.sources.configure(code));
    });
    toObservable(this.request)
      .pipe(
        debounceTime(250),
        switchMap((asked) => {
          if (asked.metadataLoading || asked.metadataError || asked.pending)
            return of<ReportRun>({
              result: null,
              loading: asked.metadataLoading,
              error: asked.metadataError,
            });
          this.run.update((current) => ({ ...current, loading: true }));
          const response =
            asked.source === null
              ? this.reports.run(asked.code, asked)
              : asked.parameters
                ? this.reports.saved(asked.code, asked.source, asked.parameters)
                : this.reports.saved(asked.code, asked.source);
          return response.pipe(
            map((result): ReportRun => {
              this.completedRequest.set(JSON.stringify(asked));
              return { result, error: '', loading: false };
            }),
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
  }

  canLeaveRecordPage(): Observable<boolean> {
    return this.savedReports()?.canLeaveRecordPage() ?? of(true);
  }
  openSaved(report: SavedReport): void {
    this.open(report.state);
    this.source.set(report);
    this.parameterValues.set(null);
    this.refresh.update((value) => value + 1);
  }

  /** Runs the open saved report with the values its reader applied (ADR-0044). */
  applyParameters(values: Record<string, ParameterValue>): void {
    this.parameterValues.set(values);
  }
  detachSource(): void {
    this.source.set(null);
  }

  isDated(key: string): boolean {
    return dated(this.reportMeta().fields.find((field) => field.key === key));
  }

  setGroup(index: number, key: string | null): void {
    if (this.source()) return;
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
    if (this.source()) return;
    this.groupBy.update((groups) =>
      groups.map((group, at) => (at === index ? { ...group, trunc: trunc ?? 'month' } : group)),
    );
  }

  /** Orders the groups as the option names it: `group`, `label:asc`, `measure:0:desc`. */
  setSort(value: string | null): void {
    if (!this.source()) this.sort.set(parseSort(value));
  }

  setLimit(value: number | null): void {
    if (!this.source()) this.limit.set(value && value > 0 ? value : null);
  }

  /** Shows a saved report: its grouping, measures and chart, and its filter in the list's filter bar. */
  open(state: ReportState): void {
    this.source.set(null);
    this.sources.select(state.dataset ?? null);
    this.groupBy.set(state.groupBy ?? []);
    this.measures.set(state.measures?.length ? state.measures : emptyReport().measures);
    this.chart.set(state.chart ?? 'bar');
    this.sort.set(state.sort ?? null);
    this.limit.set(state.limit ?? null);
    this.pivot.set(state.pivot === true);
    this.totals.set(state.totals === true);
    this.compare.set(state.compare ?? null);
    this.parameters.set(state.parameters ?? []);
    const saved = readFilterDsl(state.filter ?? []);
    this.setFilter(saved.conditions, saved.match);
  }

  selectDataset(dataset: string | null): void {
    if (this.source()) return;
    if (dataset) {
      this.projectedFilter.set([...this.views().filter()]);
      this.projectedMatch.set(this.views().match());
    }
    this.sources.select(dataset || null);
    this.groupBy.set([]);
    this.measures.set(emptyReport().measures);
    this.pivot.set(false);
    this.compare.set(null);
    this.parameters.set([]);
  }

  setFilter(conditions: QueryCondition[], match?: QueryMatch): void {
    if (this.source()) return;
    if (this.sources.dataset()) {
      this.projectedFilter.set(conditions);
      if (match) this.projectedMatch.set(match);
    } else this.views().setFilter(conditions, match);
  }
}
