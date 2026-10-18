import { ChangeDetectionStrategy, Component, computed, effect, inject, input, model } from '@angular/core';
import { enumLabel, fieldLabel, type QueryFieldMeta, type QueryListMeta } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTMultiSelectComponent } from '@shared/ui-kit/components/forms/multi-select';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import {
  MAX_PARAMETERS,
  PERIOD_PRESETS,
  parameterKey,
  parameterKind,
  type PeriodPreset,
  type ReportParameter,
} from './report-analytics';

/**
 * The parameters an author declares on a report (ADR-0044): up to eight, each bound to a field of the list a parameter
 * can narrow — a date, a choice, a reference or yes/no —, required or not, a period with a ready default and a choice
 * with default values. The reader fills them when opening the saved report; the server checks the declaration on save.
 */
@Component({
  selector: 'smt-report-parameters-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTButtonComponent,
    SMTCheckboxComponent,
    SMTControlComponent,
    SMTMultiSelectComponent,
    SMTSelectComponent,
    TranslatePipe,
  ],
  host: { class: 'smt-report-parameters-editor' },
  templateUrl: './smt-report-parameters-editor.component.html',
  styleUrl: './smt-report-parameters-editor.component.css',
})
export class SMTReportParametersEditorComponent {
  private readonly i18n = inject(I18nService);

  readonly meta = input.required<QueryListMeta>();
  readonly ids = input.required<string>();

  /** The field of the comparison: its periods come from the comparison, not from a parameter. */
  readonly compareField = input<string | null>(null);
  readonly locked = input(false);

  readonly parameters = model.required<ReportParameter[]>();

  readonly fieldOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return this.meta()
      .fields.filter((field) => parameterKind(field) && field.key !== this.compareField())
      .map((field) => ({ id: field.key, label: fieldLabel(field, (key) => this.i18n.translate(key)) }));
  });
  readonly presetOptions = computed<SMTSelectOption<PeriodPreset>[]>(() => {
    this.i18n.currentLang();
    return PERIOD_PRESETS.map((preset) => ({ id: preset, label: this.i18n.translate(`ui.report.preset.${preset}`) }));
  });

  readonly maxParameters = MAX_PARAMETERS;

  constructor() {
    // A parameter whose field left the list, or became the comparison's, goes away with it.
    effect(() => {
      const offered = new Set(this.fieldOptions().map((option) => option.id));
      const parameters = this.parameters();
      if (parameters.some((parameter) => !offered.has(parameter.field))) {
        this.parameters.set(parameters.filter((parameter) => offered.has(parameter.field)));
      }
    });
  }

  field(key: string): QueryFieldMeta | undefined {
    return this.meta().fields.find((field) => field.key === key);
  }

  kind(parameter: ReportParameter): string | null {
    return parameterKind(this.field(parameter.field));
  }

  choiceOptions(parameter: ReportParameter): SMTSelectOption<string>[] {
    const field = this.field(parameter.field);
    if (!field) return [];
    this.i18n.currentLang();
    return field.enumValues.map((value) => ({
      id: value,
      label: enumLabel(field, value, (key) => this.i18n.translate(key)),
    }));
  }

  preset(parameter: ReportParameter): PeriodPreset | null {
    const value = parameter.default;
    return value && typeof value === 'object' && !Array.isArray(value) && 'preset' in value ? value.preset : null;
  }

  values(parameter: ReportParameter): string[] {
    return Array.isArray(parameter.default) ? parameter.default.map(String) : [];
  }

  add(): void {
    const first = this.fieldOptions().find((option) => !this.parameters().some((p) => p.field === option.id));
    if (this.locked() || !first || this.parameters().length >= MAX_PARAMETERS) return;
    const taken = this.parameters().map((parameter) => parameter.key);
    this.parameters.update((parameters) => [
      ...parameters,
      { key: parameterKey(first.id, taken), field: first.id, required: false },
    ]);
  }

  remove(index: number): void {
    if (!this.locked()) this.parameters.update((parameters) => parameters.filter((_p, at) => at !== index));
  }

  setField(index: number, field: string | null): void {
    if (this.locked() || !field) return;
    const taken = this.parameters()
      .filter((_p, at) => at !== index)
      .map((parameter) => parameter.key);
    this.update(index, (parameter) => ({ key: parameterKey(field, taken), field, required: parameter.required }));
  }

  setRequired(index: number, required: boolean): void {
    if (!this.locked()) this.update(index, (parameter) => ({ ...parameter, required }));
  }

  setPreset(index: number, preset: PeriodPreset | null): void {
    if (this.locked()) return;
    this.update(index, (parameter) => this.withDefault(parameter, preset ? { preset } : undefined));
  }

  setValues(index: number, values: readonly string[] | null): void {
    if (this.locked()) return;
    this.update(index, (parameter) => this.withDefault(parameter, values?.length ? [...values] : undefined));
  }

  private withDefault(parameter: ReportParameter, value: ReportParameter['default']): ReportParameter {
    const next: ReportParameter = { key: parameter.key, field: parameter.field, required: parameter.required };
    if (parameter.labels) next.labels = parameter.labels;
    if (value !== undefined) next.default = value;
    return next;
  }

  private update(index: number, change: (parameter: ReportParameter) => ReportParameter): void {
    this.parameters.update((parameters) =>
      parameters.map((parameter, at) => (at === index ? change(parameter) : parameter)),
    );
  }
}
