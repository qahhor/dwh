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
import {
  fieldLabel,
  type QueryFieldMeta,
  type QueryListMeta,
  type QueryCondition,
  type QueryMatch,
} from '@core/models/query-meta.models';
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
  type SavedReport,
  EntityReportsApi,
  dated,
  emptyReport,
  groupable,
  measurable,
} from './entity-reports';
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

  private readonly savedReports = viewChild(SMTEntityReportSavedComponent);

  readonly source = signal<SavedReport | null>(null);

  readonly groupBy = signal<ReportGroup[]>([]);
  readonly measures = signal<ReportMeasure[]>(emptyReport().measures);
  /** Bars by default: one figure until something is grouped. */
  readonly chart = signal<ReportChart>('bar');

  readonly run = signal<ReportRun>({ result: null, error: '', loading: false });

  readonly refresh = signal(0);
  readonly completedRequest = signal('');
  private readonly projectedFilter = signal<QueryCondition[]>([]);
  private readonly projectedMatch = signal<QueryMatch>('all');

  readonly reportFilter = computed(() => (this.sources.dataset() ? this.projectedFilter() : this.views().filter()));
  readonly reportMatch = computed(() => (this.sources.dataset() ? this.projectedMatch() : this.views().match()));
  readonly reportMeta = computed(() =>
    this.sources.dataset() ? (this.sources.projected() ?? { ...this.meta(), fields: [] }) : this.meta(),
  );
  readonly displayResult = computed(() =>
    JSON.stringify(this.request()) === this.completedRequest() ? this.run().result : null,
  );

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
    filter: filterDsl(this.reportFilter(), this.reportMatch(), true),
    chart: this.effectiveChart(),
    ...(this.sources.dataset() ? { dataset: this.sources.dataset()! } : {}),
  }));

  readonly groupOptions = computed<SMTSelectOption<string>[]>(() =>
    this.options(this.reportMeta().fields.filter(groupable)),
  );
  readonly measureOptions = computed<SMTSelectOption<string>[]>(() =>
    this.options(this.reportMeta().fields.filter(measurable)),
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
      {
        value: 'line',
        label: this.i18n.translate('ui.report.chart_line'),
        icon: 'show_chart',
        disabled:
          this.groupBy().length !== 1 || !this.isDated(this.groupBy()[0]?.field ?? '') || this.measures().length !== 1,
      },
      {
        value: 'horizontalBar',
        label: this.i18n.translate('ui.report.chart_horizontal_bar'),
        icon: 'bar_chart',
        disabled:
          this.groupBy().length !== 1 || this.isDated(this.groupBy()[0]?.field ?? '') || this.measures().length !== 1,
      },
      { value: 'kpi', label: this.i18n.translate('ui.report.chart_kpi'), icon: 'pin', disabled: grouped },
    ];
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
      ? { kind: 'saved', code: asked.code, id: asked.source }
      : { kind: 'query', code: asked.code, state: { ...this.state(), filter: asked.filter } };
  });

  readonly ids = `report-builder-${nextBuilderId++}`;
  readonly maxMeasures = MAX_MEASURES;

  constructor() {
    effect(() => {
      const code = this.code();
      untracked(() => this.sources.configure(code));
    });
    toObservable(this.request)
      .pipe(
        debounceTime(250),
        switchMap((asked) => {
          if (asked.metadataLoading || asked.metadataError)
            return of<ReportRun>({
              result: null,
              loading: asked.metadataLoading,
              error: asked.metadataError,
            });
          this.run.update((current) => ({ ...current, loading: true }));
          const response =
            asked.source !== null ? this.reports.saved(asked.code, asked.source) : this.reports.run(asked.code, asked);
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
    // A measure of a number needs a number field; a list without one keeps the count only.
    effect(() => {
      if (
        !this.source() &&
        !this.sources.loading() &&
        !this.sources.error() &&
        this.measureOptions().length === 0 &&
        this.measures().some((measure) => measure.op !== 'count')
      ) {
        this.measures.set(emptyReport().measures);
      }
    });
  }

  canLeaveRecordPage(): Observable<boolean> {
    return this.savedReports()?.canLeaveRecordPage() ?? of(true);
  }
  openSaved(report: SavedReport): void {
    this.open(report.state);
    this.source.set(report);
    this.refresh.update((value) => value + 1);
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

  setOp(index: number, op: ReportOp | null): void {
    if (this.source()) return;
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
    if (this.source()) return;
    this.measures.update((measures) =>
      measures.map((measure, at) => (at === index ? { ...measure, field: field ?? measure.field } : measure)),
    );
  }

  addMeasure(): void {
    if (this.source()) return;
    const first = this.measureOptions()[0]?.id;
    this.measures.update((measures) => [...measures, first ? { op: 'sum', field: first } : { op: 'count' }]);
  }

  removeMeasure(index: number): void {
    if (this.source()) return;
    this.measures.update((measures) => measures.filter((_measure, at) => at !== index));
  }

  /** Shows a saved report: its grouping, measures and chart, and its filter in the list's filter bar. */
  open(state: ReportState): void {
    this.source.set(null);
    this.sources.select(state.dataset ?? null);
    this.groupBy.set(state.groupBy ?? []);
    this.measures.set(state.measures?.length ? state.measures : emptyReport().measures);
    this.chart.set(state.chart ?? 'bar');
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
  }

  setFilter(conditions: QueryCondition[], match?: QueryMatch): void {
    if (this.source()) return;
    if (this.sources.dataset()) {
      this.projectedFilter.set(conditions);
      if (match) this.projectedMatch.set(match);
    } else this.views().setFilter(conditions, match);
  }

  private options(fields: QueryFieldMeta[]): SMTSelectOption<string>[] {
    this.i18n.currentLang();
    return fields.map((field) => ({ id: field.key, label: fieldLabel(field, (key) => this.i18n.translate(key)) }));
  }
}
