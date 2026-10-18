import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, output } from '@angular/core';
import { enumLabel, fieldLabel, type QueryFieldMeta, type QueryListMeta } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { RefLookups } from '@shared/lookups/ref-lookup';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import {
  SMTDataSelectComponent,
  type SMTLookupKey,
  type SMTLookupSource,
} from '@shared/ui-kit/components/forms/data-select';
import { SMTDateRangePickerComponent, type DateRange } from '@shared/ui-kit/components/forms/date-picker';
import { SMTMultiSelectComponent } from '@shared/ui-kit/components/forms/multi-select';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import {
  PERIOD_PRESETS,
  filled,
  parameterKind,
  type ParameterKind,
  type ParameterValue,
  type ReportParameter,
} from './report-analytics';

/** One parameter as the form shows it. */
interface ParameterRow {
  parameter: ReportParameter;
  field: QueryFieldMeta | undefined;
  kind: ParameterKind | null;
  label: string;
}

let nextParametersId = 0;

/**
 * The parameters a saved report asks its reader for (ADR-0044): a period — a ready one or two days —, the values of a
 * choice, a row of a reference (a record, a user, an org unit) or yes/no, each prefilled with its default. The report
 * runs with the values once the reader applies them and every required one is filled; the server checks them again and
 * applies the reader's scope.
 */
@Component({
  selector: 'smt-report-parameters',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgTemplateOutlet,
    SMTButtonComponent,
    SMTControlComponent,
    SMTDataSelectComponent,
    SMTDateRangePickerComponent,
    SMTMultiSelectComponent,
    SMTSelectComponent,
    TranslatePipe,
  ],
  host: { class: 'smt-report-parameters' },
  templateUrl: './smt-report-parameters.component.html',
  styleUrl: './smt-report-parameters.component.css',
})
export class SMTReportParametersComponent {
  private readonly i18n = inject(I18nService);
  private readonly refLookups = inject(RefLookups);

  readonly parameters = input.required<ReportParameter[]>();
  readonly meta = input.required<QueryListMeta>();
  /**
   * Inside another form (a report mailing, ADR-0045): the fields alone, without a form of their own and the apply
   * button; each change is told by `changed`.
   */
  readonly embedded = input(false);
  /** Values to start from instead of the defaults, as a mailing keeps them. */
  readonly initial = input<Record<string, ParameterValue> | null>(null);

  /** The values the report runs with; their keys are the parameters'. */
  readonly applied = output<Record<string, ParameterValue>>();
  /** The filled values after each change, in embedded mode. */
  readonly changed = output<Record<string, ParameterValue>>();

  /** What the reader has entered, starting from the defaults. */
  readonly values = linkedSignal<Record<string, ParameterValue>>(() => {
    const initial = this.initial();
    if (initial) return { ...initial };
    const values: Record<string, ParameterValue> = {};
    for (const parameter of this.parameters()) {
      if (parameter.default !== undefined) values[parameter.key] = parameter.default;
    }
    return values;
  });

  readonly rows = computed<ParameterRow[]>(() => {
    const lang = this.i18n.currentLang() as 'ru' | 'uz' | 'en';
    return this.parameters().map((parameter) => {
      const field = this.meta().fields.find((candidate) => candidate.key === parameter.field);
      const label =
        parameter.labels?.[lang] ?? (field ? fieldLabel(field, (key) => this.i18n.translate(key)) : parameter.field);
      return { parameter, field, kind: parameterKind(field), label };
    });
  });

  /** The required parameters still empty: the report does not run without them. */
  readonly missing = computed(() =>
    this.rows()
      .filter((row) => row.parameter.required && !filled(this.values()[row.parameter.key]))
      .map((row) => row.label),
  );

  readonly presetOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return [
      ...PERIOD_PRESETS.map((preset) => ({ id: preset, label: this.i18n.translate(`ui.report.preset.${preset}`) })),
      { id: 'custom', label: this.i18n.translate('ui.report.preset.custom') },
    ];
  });
  readonly flagOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return [
      { id: 'true', label: this.i18n.translate('common.yes') },
      { id: 'false', label: this.i18n.translate('common.no') },
    ];
  });

  readonly ids = `report-parameters-${nextParametersId++}`;
  private readonly sources = new Map<string, SMTLookupSource<Record<string, unknown>, SMTLookupKey>>();

  choiceOptions(field: QueryFieldMeta | undefined): SMTSelectOption<string>[] {
    if (!field) return [];
    this.i18n.currentLang();
    return field.enumValues.map((value) => ({
      id: value,
      label: enumLabel(field, value, (key) => this.i18n.translate(key)),
    }));
  }

  source(field: QueryFieldMeta): SMTLookupSource<Record<string, unknown>, SMTLookupKey> {
    let source = this.sources.get(field.key);
    if (!source) {
      source = this.refLookups.source(field.ref!);
      this.sources.set(field.key, source);
    }
    return source;
  }

  /** The preset a period holds, `custom` for two days, null when it is empty. */
  preset(key: string): string | null {
    const value = this.values()[key];
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
    return 'preset' in value ? value.preset : 'custom';
  }

  range(key: string): DateRange | null {
    const value = this.values()[key];
    return value && typeof value === 'object' && !Array.isArray(value) && 'from' in value
      ? { from: value.from, to: value.to }
      : null;
  }

  list(key: string): string[] {
    const value = this.values()[key];
    return Array.isArray(value) ? value.map(String) : [];
  }

  first(key: string): SMTLookupKey | null {
    const value = this.values()[key];
    return Array.isArray(value) && value.length ? (value[0] as SMTLookupKey) : null;
  }

  flag(key: string): string | null {
    const value = this.values()[key];
    return typeof value === 'boolean' ? String(value) : null;
  }

  setPreset(key: string, preset: string | null): void {
    if (!preset) return this.clear(key);
    this.set(key, preset === 'custom' ? { from: '', to: '' } : ({ preset } as ParameterValue));
  }

  setRange(key: string, range: DateRange | null): void {
    this.set(key, { from: range?.from ?? '', to: range?.to ?? '' });
  }

  setList(key: string, values: readonly (string | number)[] | null): void {
    if (!values?.length) return this.clear(key);
    this.set(key, [...values]);
  }

  setFlag(key: string, value: string | null): void {
    if (value === null) return this.clear(key);
    this.set(key, value === 'true');
  }

  apply(): void {
    if (this.missing().length) return;
    this.applied.emit(this.filledValues());
  }

  private filledValues(): Record<string, ParameterValue> {
    const values: Record<string, ParameterValue> = {};
    for (const [key, value] of Object.entries(this.values())) {
      if (filled(value)) values[key] = value;
    }
    return values;
  }

  private set(key: string, value: ParameterValue): void {
    this.values.update((values) => ({ ...values, [key]: value }));
    if (this.embedded()) this.changed.emit(this.filledValues());
  }

  private clear(key: string): void {
    this.values.update((values) => {
      const next = { ...values };
      delete next[key];
      return next;
    });
    if (this.embedded()) this.changed.emit(this.filledValues());
  }
}
