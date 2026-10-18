import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { problemText } from '@shared/ui/problem-text';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { ReportDatasetDialogComponent } from './report-dataset-dialog.component';
import { ReportDatasetsApi, type DatasetSource, type ReportDataset } from './report-datasets.api';

/**
 * The report datasets page (ADR-0044): the datasets administrators define — a form or a list, optionally at the grain
 * of its document lines, the fields reports may use, a fixed filter and measures computed by formulas — each to
 * create, change or delete at the revision read. A report over a dataset still shows each reader only their data.
 */
@Component({
  selector: 'app-report-datasets',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    TranslatePipe,
    SMTAlertComponent,
    SMTButtonComponent,
    UiPageHeaderComponent,
    ReportDatasetDialogComponent,
  ],
  templateUrl: './report-datasets.component.html',
  styleUrl: './report-datasets.component.css',
})
export class ReportDatasetsComponent implements OnInit {
  private readonly api = inject(ReportDatasetsApi);
  private readonly i18n = inject(I18nService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly modal = inject(SMTModalService);
  private readonly destroyRef = inject(DestroyRef);

  readonly datasets = signal<ReportDataset[]>([]);
  readonly sources = signal<DatasetSource[]>([]);
  readonly loading = signal(false);
  readonly error = signal('');
  /** The dialog: undefined — closed, null — a new dataset, else the dataset changed. */
  readonly editing = signal<ReportDataset | null | undefined>(undefined);

  readonly mayView = computed(() => this.permissions.hasPermission('md.report_datasets', 'view'));
  readonly mayManage = computed(() => this.permissions.hasPermission('md.report_datasets', 'manage'));

  ngOnInit(): void {
    this.load();
    if (this.mayManage()) {
      this.api
        .entities()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({ next: (sources) => this.sources.set(sources), error: () => this.sources.set([]) });
    }
  }

  load(): void {
    if (!this.mayView()) return;
    this.loading.set(true);
    this.error.set('');
    this.api
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (datasets) => {
          this.datasets.set(datasets);
          this.loading.set(false);
        },
        error: (failure: unknown) => {
          this.loading.set(false);
          this.error.set(problemText(failure) || this.i18n.translate('md.report_datasets.load_failed'));
        },
      });
  }

  name(dataset: ReportDataset): string {
    const lang = this.i18n.currentLang() as 'ru' | 'uz' | 'en';
    return dataset.name[lang] ?? dataset.name.ru;
  }

  source(dataset: ReportDataset): string {
    const found = this.sources().find((source) => source.code === dataset.entity);
    return found ? this.i18n.translate(found.labelKey) : dataset.entity;
  }

  saved(dataset: ReportDataset): void {
    this.editing.set(undefined);
    this.toast.success(this.i18n.translate('md.report_datasets.saved', { name: this.name(dataset) }));
    this.load();
  }

  remove(dataset: ReportDataset): void {
    this.modal
      .confirm({
        message: this.i18n.translate('md.report_datasets.delete_confirm', { name: this.name(dataset) }),
        destructive: true,
      })
      .subscribe((confirmed) => {
        if (!confirmed) return;
        this.api
          .remove(dataset.code, dataset.revision)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({
            next: () => this.load(),
            error: (failure: unknown) =>
              this.toast.error(problemText(failure) || this.i18n.translate('md.report_datasets.save_failed')),
          });
      });
  }
}
