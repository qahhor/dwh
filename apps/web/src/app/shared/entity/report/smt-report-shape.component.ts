import { ChangeDetectionStrategy, Component, computed, effect, inject, input, model } from '@angular/core';
import { fieldLabel, type QueryListMeta } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTDateRangePickerComponent, type DateRange } from '@shared/ui-kit/components/forms/date-picker';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import type { ReportGroup } from './entity-reports';
import {
  COMPARE_AGAINST,
  COMPARE_PERIODS,
  comparable,
  type CompareAgainst,
  type ComparePeriod,
  type ReportCompare,
} from './report-analytics';

/**
 * The analytic shape of a report in the builder (ADR-0044): a pivot of its two groupings (rows, then columns) with the
 * totals of rows, columns and the whole; the totals of a flat report; a comparison of the current period — this month,
 * quarter or year, the year to date or a range — with the period before or the same period a year earlier, by a date
 * field that is not a grouping. A pivot and a comparison exclude each other.
 */
@Component({
  selector: 'smt-report-shape',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTCheckboxComponent, SMTControlComponent, SMTDateRangePickerComponent, SMTSelectComponent, TranslatePipe],
  host: { class: 'smt-report-shape' },
  templateUrl: './smt-report-shape.component.html',
  styleUrl: './smt-report-shape.component.css',
})
export class SMTReportShapeComponent {
  private readonly i18n = inject(I18nService);

  readonly meta = input.required<QueryListMeta>();
  readonly groupBy = input.required<ReportGroup[]>();
  readonly ids = input.required<string>();

  readonly locked = input(false);

  readonly pivot = model(false);
  readonly totals = model(false);
  readonly compare = model<ReportCompare | null>(null);

  readonly canPivot = computed(() => this.groupBy().length === 2 && !this.compare());

  readonly fieldOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    const grouped = new Set(this.groupBy().map((group) => group.field));
    return this.meta()
      .fields.filter((field) => comparable(field) && !grouped.has(field.key))
      .map((field) => ({ id: field.key, label: fieldLabel(field, (key) => this.i18n.translate(key)) }));
  });
  readonly periodOptions = computed<SMTSelectOption<ComparePeriod>[]>(() => {
    this.i18n.currentLang();
    return COMPARE_PERIODS.map((period) => ({ id: period, label: this.i18n.translate(`ui.report.compare.${period}`) }));
  });
  readonly againstOptions = computed<SMTSelectOption<CompareAgainst>[]>(() => {
    this.i18n.currentLang();
    return COMPARE_AGAINST.map((against) => ({
      id: against,
      label: this.i18n.translate(`ui.report.compare.against_${against}`),
    }));
  });

  /** The custom period as the range picker holds it. */
  readonly range = computed<DateRange | null>(() => {
    const compare = this.compare();
    return compare?.from || compare?.to ? { from: compare.from ?? null, to: compare.to ?? null } : null;
  });

  constructor() {
    // A pivot needs two groupings; a comparison drops when its field becomes a grouping or leaves the list.
    effect(() => {
      if (this.pivot() && this.groupBy().length !== 2) this.pivot.set(false);
      const compare = this.compare();
      if (compare && !this.fieldOptions().some((option) => option.id === compare.field)) this.compare.set(null);
    });
  }

  setPivot(on: boolean): void {
    if (this.locked()) return;
    this.pivot.set(on && this.canPivot());
  }

  setTotals(on: boolean): void {
    if (!this.locked()) this.totals.set(on);
  }

  setCompared(on: boolean): void {
    if (this.locked()) return;
    const field = this.fieldOptions()[0]?.id;
    this.compare.set(on && field ? { field, period: 'month', against: 'previous' } : null);
    if (on) this.pivot.set(false);
  }

  setRange(range: DateRange | null): void {
    this.change({ from: range?.from ?? undefined, to: range?.to ?? undefined });
  }

  change(part: Partial<ReportCompare>): void {
    const compare = this.compare();
    if (this.locked() || !compare) return;
    const next: ReportCompare = { ...compare, ...part };
    if (next.period !== 'custom') {
      delete next.from;
      delete next.to;
    }
    this.compare.set(next);
  }
}
