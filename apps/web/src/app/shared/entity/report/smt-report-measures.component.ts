import { ChangeDetectionStrategy, Component, computed, effect, inject, input, model } from '@angular/core';
import { fieldLabel, type QueryFieldMeta, type QueryListMeta } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { MAX_MEASURES, REPORT_OPS, emptyReport, measurable, type ReportMeasure, type ReportOp } from './entity-reports';
import { countable, type ReportShare } from './report-analytics';

/**
 * The measures of a report in the builder (ADR-0032 10.2; ADR-0044): up to four, each a count, a count of a field's
 * different values, or a sum, average, minimum or maximum of a number or of money; a count or a sum may show its share of
 * the total — of its row or its column in a pivot. A list without numbers keeps the counts.
 */
@Component({
  selector: 'smt-report-measures',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTButtonComponent, SMTControlComponent, SMTSelectComponent, TranslatePipe],
  host: { class: 'smt-report-measures' },
  templateUrl: './smt-report-measures.component.html',
  styleUrl: './smt-report-measures.component.css',
})
export class SMTReportMeasuresComponent {
  private readonly i18n = inject(I18nService);

  readonly meta = input.required<QueryListMeta>();
  readonly ids = input.required<string>();

  /** A pivot offers the shares of a row and of a column too. */
  readonly pivot = input(false);
  /** A comparison of periods shows no shares. */
  readonly compared = input(false);
  /** A saved report is shown as it is: nothing changes. */
  readonly locked = input(false);
  /** The metadata is read: the counts are kept only once it says there are no numbers. */
  readonly ready = input(true);

  readonly measures = model.required<ReportMeasure[]>();

  readonly measureOptions = computed<SMTSelectOption<string>[]>(() =>
    this.options(this.meta().fields.filter(measurable)),
  );
  readonly distinctOptions = computed<SMTSelectOption<string>[]>(() =>
    this.options(this.meta().fields.filter(countable)),
  );
  readonly opOptions = computed<SMTSelectOption<ReportOp>[]>(() => {
    this.i18n.currentLang();
    const numbers = this.measureOptions().length > 0;
    return REPORT_OPS.map((op) => ({
      id: op,
      label: this.i18n.translate(`ui.report.op.${op}`),
      disabled: op !== 'count' && op !== 'count_distinct' && !numbers,
    }));
  });
  readonly shareOptions = computed<SMTSelectOption<ReportShare>[]>(() => {
    this.i18n.currentLang();
    const shares: ReportShare[] = this.pivot() ? ['total', 'row', 'column'] : ['total'];
    return shares.map((share) => ({ id: share, label: this.i18n.translate(`ui.report.share_of.${share}`) }));
  });

  readonly maxMeasures = MAX_MEASURES;

  constructor() {
    // A measure of a number needs a number field; a list without one keeps the count only.
    effect(() => {
      if (
        !this.locked() &&
        this.ready() &&
        this.measureOptions().length === 0 &&
        this.measures().some((measure) => measure.op !== 'count' && measure.op !== 'count_distinct')
      ) {
        this.measures.set(emptyReport().measures);
      }
    });
    // A share of a row or a column needs a pivot; a comparison has no shares.
    effect(() => {
      const pivot = this.pivot();
      const compared = this.compared();
      const measures = this.measures();
      if (measures.some((measure) => measure.share && (compared || (!pivot && measure.share !== 'total')))) {
        this.measures.set(
          measures.map((measure) =>
            measure.share && (compared || (!pivot && measure.share !== 'total'))
              ? { op: measure.op, ...(measure.field ? { field: measure.field } : {}) }
              : measure,
          ),
        );
      }
    });
  }

  /** Whether the measure may show a share: a count or a sum, outside a comparison. */
  shared(measure: ReportMeasure): boolean {
    return !this.compared() && (measure.op === 'count' || measure.op === 'sum');
  }

  fieldOptions(measure: ReportMeasure): SMTSelectOption<string>[] {
    return measure.op === 'count_distinct' ? this.distinctOptions() : this.measureOptions();
  }

  setOp(index: number, op: ReportOp | null): void {
    if (this.locked()) return;
    const chosen = op ?? 'count';
    this.update(index, (measure) => {
      if (chosen === 'count') return { op: 'count', ...(measure.share ? { share: measure.share } : {}) };
      const options = chosen === 'count_distinct' ? this.distinctOptions() : this.measureOptions();
      const field = options.some((option) => option.id === measure.field) ? measure.field : options[0]?.id;
      const share = chosen === 'sum' && measure.share ? { share: measure.share } : {};
      return { op: chosen, ...(field ? { field } : {}), ...share };
    });
  }

  setField(index: number, field: string | null): void {
    if (this.locked()) return;
    this.update(index, (measure) => ({ ...measure, field: field ?? measure.field }));
  }

  setShare(index: number, share: ReportShare | null): void {
    if (this.locked()) return;
    this.update(index, (measure) => {
      const next: ReportMeasure = { op: measure.op, ...(measure.field ? { field: measure.field } : {}) };
      return share ? { ...next, share } : next;
    });
  }

  add(): void {
    if (this.locked()) return;
    const first = this.measureOptions()[0]?.id;
    this.measures.update((measures) => [...measures, first ? { op: 'sum', field: first } : { op: 'count' }]);
  }

  remove(index: number): void {
    if (this.locked()) return;
    this.measures.update((measures) => measures.filter((_measure, at) => at !== index));
  }

  private update(index: number, change: (measure: ReportMeasure) => ReportMeasure): void {
    this.measures.update((measures) => measures.map((measure, at) => (at === index ? change(measure) : measure)));
  }

  private options(fields: QueryFieldMeta[]): SMTSelectOption<string>[] {
    this.i18n.currentLang();
    return fields.map((field) => ({ id: field.key, label: fieldLabel(field, (key) => this.i18n.translate(key)) }));
  }
}
