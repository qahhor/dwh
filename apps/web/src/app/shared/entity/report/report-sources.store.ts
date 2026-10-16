import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Subscription } from 'rxjs';
import type { ApiSchema } from '@core/api/api-schema';
import type { QueryListMeta } from '@core/models/query-meta.models';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { problemText } from '@shared/ui/problem-text';

type ReportDataset = ApiSchema<'ReportDatasetItem'>;

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
    ...this.catalog().map((item) => ({ id: item.code, label: this.i18n.translate(item.titleKey) })),
  ]);

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
