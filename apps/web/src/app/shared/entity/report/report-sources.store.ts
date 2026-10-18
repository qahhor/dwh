import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Subscription } from 'rxjs';
import type { ApiSchema } from '@core/api/api-schema';
import type { QueryFieldMeta, QueryListMeta } from '@core/models/query-meta.models';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { problemText } from '@shared/ui/problem-text';

type ReportDataset = ApiSchema<'ReportDatasetItem'>;

/** The format of a measure a dataset computes by a formula, among the fields of its metadata (ADR-0044). */
export const FORMULA_FORMAT = 'formula';

/** A projection never silently falls back to a different data source (ADR-0039). */
@Injectable()
export class ReportSourcesStore {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  readonly dataset = signal<string | null>(null);
  readonly projected = signal<QueryListMeta | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly catalogError = signal('');
  readonly catalogLoading = signal(false);
  readonly catalog = signal<ReportDataset[]>([]);

  readonly options = computed(() => [
    { id: '', label: this.i18n.translate('ui.report.dataset_base') },
    ...this.catalog().map((item) => ({ id: item.code, label: item.name ?? this.i18n.translate(item.titleKey) })),
  ]);

  /** The metadata of the selected dataset with its computed measures. */
  readonly meta = computed<QueryListMeta | null>(() => {
    const projected = this.projected();
    return projected ? { ...projected, fields: [...projected.fields, ...this.computedFields()] } : null;
  });

  /**
   * The measures the selected administrator's dataset computes by formulas (ADR-0044), as fields of its metadata a
   * measure names: a number nobody filters, groups or sums, its format `formula`.
   */
  private readonly computedFields = computed<QueryFieldMeta[]>(() => {
    const item = this.catalog().find((candidate) => candidate.code === this.dataset());
    return (item?.measures ?? []).map((measure) => ({
      key: measure.key,
      labelKey: '',
      label: measure.label,
      type: 'number',
      ops: [],
      sortable: false,
      nullable: true,
      defaultVisible: false,
      enumValues: [],
      enumLabelPrefix: null,
      format: FORMULA_FORMAT,
    }));
  });

  private code = '';
  private request?: Subscription;
  private catalogRequest?: Subscription;
  private operation = 0;

  configure(code: string): void {
    if (code === this.code) return;
    this.code = code;
    this.select(null);
    this.reloadCatalog();
  }
  reloadCatalog(): void {
    if (!this.code) return;
    this.catalogRequest?.unsubscribe();
    this.catalogLoading.set(true);
    this.catalogError.set('');
    this.catalog.set([]);
    this.catalogRequest = this.api
      .get<ApiSchema<'ReportDatasetCatalog'>>(`/entities/${encodeURIComponent(this.code)}/report-datasets`, undefined, {
        notifyError: false,
      })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.catalogLoading.set(false)),
      )
      .subscribe({
        next: (result) => this.catalog.set(result.items),
        error: (error) => this.catalogError.set(problemText(error) || this.i18n.translate('ui.report.dataset_failed')),
      });
  }
  select(dataset: string | null): void {
    ++this.operation;
    this.request?.unsubscribe();
    this.dataset.set(dataset);
    this.projected.set(null);
    this.error.set('');
    this.loading.set(!!dataset);
    if (!dataset) return;
    const operation = this.operation;
    this.request = this.api
      .get<QueryListMeta>(`/entities/${encodeURIComponent(this.code)}/report-meta`, { dataset }, { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (operation === this.operation) this.loading.set(false);
        }),
      )
      .subscribe({
        next: (meta) => {
          if (operation === this.operation) this.projected.set(meta);
        },
        error: (error) => {
          if (operation === this.operation)
            this.error.set(problemText(error) || this.i18n.translate('ui.report.dataset_failed'));
        },
      });
  }
}
