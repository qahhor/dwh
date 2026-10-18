import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { fieldLabel, type QueryCondition, type QueryListMeta, type QueryMatch } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { filterDsl, readFilterDsl } from '@core/services/query-meta.service';
import { UiFilterBarComponent } from '@shared/ui/ui-filter-bar.component';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { problemText } from '@shared/ui/problem-text';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTMultiSelectComponent } from '@shared/ui-kit/components/forms/multi-select';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTTextareaComponent } from '@shared/ui-kit/components/forms/textarea';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import {
  ReportDatasetsApi,
  type DatasetSource,
  type ReportDataset,
  type ReportDatasetBody,
  type ReportDatasetMeasure,
} from './report-datasets.api';

const MAX_MEASURES = 8;

/**
 * The dialog of a report dataset (ADR-0044): its code (on creation only), names in three languages, the source entity
 * and optionally one table of its document lines, the fields reports may use, a fixed filter of the list's own
 * filter bar and up to eight measures computed by formulas over aggregates. The server checks every part and its
 * messages stand under the part to fix.
 */
@Component({
  selector: 'app-report-dataset-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTButtonComponent,
    SMTControlComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTInputComponent,
    SMTMultiSelectComponent,
    SMTSelectComponent,
    SMTTextareaComponent,
    TranslatePipe,
    UiFilterBarComponent,
    UiFocusFirstInvalidDirective,
    UiFormActionsComponent,
  ],
  templateUrl: './report-dataset-dialog.component.html',
  styleUrl: './report-dataset-dialog.component.css',
})
export class ReportDatasetDialogComponent {
  private readonly api = inject(ReportDatasetsApi);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  readonly sources = input.required<DatasetSource[]>();

  /** The dataset to change; null — a new one. */
  readonly dataset = input<ReportDataset | null>(null);

  readonly saved = output<ReportDataset>();
  readonly closed = output<void>();

  readonly code = signal('');
  readonly names = signal({ ru: '', uz: '', en: '' });
  readonly entity = signal<string | null>(null);
  readonly lines = signal<string | null>(null);
  readonly fields = signal<string[]>([]);
  readonly conditions = signal<QueryCondition[]>([]);
  readonly match = signal<QueryMatch>('all');
  readonly measures = signal<ReportDatasetMeasure[]>([]);
  readonly tables = signal<DatasetSource[]>([]);
  readonly meta = signal<QueryListMeta | null>(null);
  readonly busy = signal(false);
  readonly errors = signal<Record<string, string>>({});
  readonly alert = signal('');

  readonly creating = computed(() => this.dataset() === null);
  readonly entityOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return this.sources().map((source) => ({ id: source.code, label: this.i18n.translate(source.labelKey) }));
  });
  readonly lineOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return this.tables().map((table) => ({ id: table.code, label: this.i18n.translate(table.labelKey) }));
  });
  readonly fieldOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return (this.meta()?.fields ?? []).map((field) => ({
      id: field.key,
      label: `${fieldLabel(field, (key) => this.i18n.translate(key))} (${field.key})`,
    }));
  });

  readonly maxMeasures = MAX_MEASURES;

  constructor() {
    effect(() => {
      const dataset = this.dataset();
      untracked(() => this.reset(dataset));
    });
  }

  setEntity(entity: string | null): void {
    this.entity.set(entity);
    this.lines.set(null);
    this.fields.set([]);
    this.conditions.set([]);
    this.tables.set([]);
    if (!entity) return;
    this.api
      .lines(entity)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (tables) => this.tables.set(tables), error: () => this.tables.set([]) });
    this.loadFields();
  }

  setLines(lines: string | null): void {
    this.lines.set(lines);
    this.fields.set([]);
    this.loadFields();
  }

  setName(language: 'ru' | 'uz' | 'en', value: string | number | null): void {
    this.names.update((names) => ({ ...names, [language]: value === null ? '' : String(value) }));
  }

  setFilter(conditions: QueryCondition[], match?: QueryMatch): void {
    this.conditions.set(conditions);
    if (match) this.match.set(match);
  }

  addMeasure(): void {
    if (this.measures().length >= MAX_MEASURES) return;
    this.measures.update((measures) => [...measures, { key: '', labels: { ru: '', uz: '', en: '' }, formula: 'sum_' }]);
  }

  removeMeasure(index: number): void {
    this.measures.update((measures) => measures.filter((_measure, at) => at !== index));
  }

  setMeasure(index: number, part: 'key' | 'formula' | 'ru' | 'uz' | 'en', value: string | number | null): void {
    const text = value === null ? '' : String(value);
    this.measures.update((measures) =>
      measures.map((measure, at) => {
        if (at !== index) return measure;
        if (part === 'key' || part === 'formula') return { ...measure, [part]: text };
        return { ...measure, labels: { ...measure.labels, [part]: text } };
      }),
    );
  }

  error(field: string): string {
    return this.errors()[field] ?? '';
  }

  submit(): void {
    const entity = this.entity();
    if (this.busy()) return;
    const body: ReportDatasetBody = {
      ...(this.creating() ? { code: this.code().trim() } : {}),
      name: this.names(),
      entity: entity ?? '',
      lines: this.lines(),
      fields: this.fields(),
      filter: filterDsl(this.conditions(), this.match(), true),
      measures: this.measures(),
    };
    const current = this.dataset();
    this.busy.set(true);
    this.errors.set({});
    this.alert.set('');
    const request = current ? this.api.update(current.code, current.revision, body) : this.api.create(body);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (dataset) => {
        this.busy.set(false);
        this.saved.emit(dataset);
      },
      error: (failure: unknown) => {
        this.busy.set(false);
        const found = problemFieldErrors(failure);
        this.errors.set({ ...found.fields });
        const status = (failure as { status?: number }).status;
        const conflict = status === 409 || status === 428;
        this.alert.set(
          conflict
            ? this.i18n.translate('md.report_datasets.conflict')
            : [problemText(failure) || this.i18n.translate('md.report_datasets.save_failed'), ...found.other].join(' '),
        );
      },
    });
  }

  private reset(dataset: ReportDataset | null): void {
    this.code.set(dataset?.code ?? '');
    this.names.set(dataset ? { ...dataset.name } : { ru: '', uz: '', en: '' });
    this.measures.set(dataset ? dataset.measures.map((measure) => ({ ...measure })) : []);
    this.errors.set({});
    this.alert.set('');
    if (!dataset) {
      this.setEntity(null);
      return;
    }
    this.entity.set(dataset.entity);
    this.lines.set(dataset.lines ?? null);
    this.fields.set([...dataset.fields]);
    const read = readFilterDsl((dataset.filter as unknown as unknown[]) ?? []);
    this.conditions.set(read.conditions);
    this.match.set(read.match);
    this.api
      .lines(dataset.entity)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (tables) => this.tables.set(tables), error: () => this.tables.set([]) });
    this.loadFields();
  }

  private loadFields(): void {
    const entity = this.entity();
    this.meta.set(null);
    if (!entity) return;
    this.api
      .fields(entity, this.lines())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (meta) => this.meta.set(meta),
        error: (failure: unknown) =>
          this.alert.set(problemText(failure) || this.i18n.translate('md.report_datasets.fields_failed')),
      });
  }
}
