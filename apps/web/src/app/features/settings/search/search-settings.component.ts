import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  Signal,
  TemplateRef,
  computed,
  signal,
  inject,
  Injector,
  viewChild,
} from '@angular/core';
import {
  SearchEntityType,
  SearchFieldPolicy,
  SearchGenerationStatus,
  SearchJobStatus,
} from '@core/models/search-management.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { focusFirstInvalid } from '@shared/ui/focus-first-invalid';
import { formatBytes, formatJobError } from './search-settings.models';
import { SearchSettingsStore } from './search-settings.store';

@Component({
  selector: 'app-search-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTButtonComponent,
    TranslatePipe,
    SMTDialogComponent,
    SMTDialogContentDirective,
    UiLocalTableComponent,
    SMTSelectComponent,
    SMTInputComponent,
    SMTCheckboxComponent,
    SMTControlComponent,
    UiFormActionsComponent,
    DatePipe,
  ],
  providers: [SearchSettingsStore],
  templateUrl: './search-settings.component.html',
  styleUrl: './search-settings.component.scss',
})
export class SearchSettingsComponent implements OnInit {
  /** The screen's state and requests; the template reads it directly. */
  readonly store = inject(SearchSettingsStore);
  private readonly i18n = inject(I18nService);
  private readonly injector = inject(Injector);

  private readonly generationIdCell = viewChild.required<TemplateRef<unknown>>('generationIdCell');
  private readonly generationStateCell = viewChild.required<TemplateRef<unknown>>('generationStateCell');
  private readonly generationDocumentsCell = viewChild.required<TemplateRef<unknown>>('generationDocumentsCell');
  private readonly generationQueueCell = viewChild.required<TemplateRef<unknown>>('generationQueueCell');
  private readonly jobIdCell = viewChild.required<TemplateRef<unknown>>('jobIdCell');
  private readonly jobStateCell = viewChild.required<TemplateRef<unknown>>('jobStateCell');
  private readonly jobGenerationCell = viewChild.required<TemplateRef<unknown>>('jobGenerationCell');
  private readonly jobCreatedCell = viewChild.required<TemplateRef<unknown>>('jobCreatedCell');
  private readonly jobActionsCell = viewChild.required<TemplateRef<unknown>>('jobActionsCell');

  /** Typed text for the preview; a signal, so the OnPush button follows it. */
  readonly previewQuery = signal('');
  /** "Enter a query", after a preview was asked without one; typing clears it. */
  readonly previewQueryError = signal('');

  readonly generationsConfig = computed<TableConfig<SearchGenerationStatus>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, g) => g.id,
      ariaLabel: this.i18n.translate('settings.search.generations.scroll_label'),
      layout: 'fit',
      columns: {
        id: { header: header('settings.search.generations.generation'), content: cell(this.generationIdCell) },
        state: { header: header('common.status'), content: cell(this.generationStateCell) },
        profile: {
          header: header('settings.search.status.profile'),
          content: { type: 'primitive', value: (g) => g.registeredProfile },
        },
        documents: {
          header: header('settings.search.generations.documents'),
          content: cell(this.generationDocumentsCell),
        },
        storage: {
          header: header('settings.search.generations.storage'),
          content: { type: 'primitive', value: (g) => this.displayBytes(g.storageBytes) },
        },
        queue: { header: header('settings.search.generations.queue'), content: cell(this.generationQueueCell) },
      },
      columnsOrder: ['id', 'state', 'profile', 'documents', 'storage', 'queue'],
    };
  });

  /**
   * The job history comes newest first a page at a time ("load more"), so it
   * offers no header sorting: sorting the loaded pages would pass for sorting
   * the whole history.
   */
  readonly jobsConfig = computed<TableConfig<SearchJobStatus>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (index, job) => this.trackJob(index, job),
      ariaLabel: this.i18n.translate('settings.search.jobs.scroll_label'),
      layout: 'fit',
      columns: {
        id: { header: header('settings.search.jobs.id'), content: cell(this.jobIdCell) },
        action: {
          header: header('settings.search.jobs.action'),
          content: { type: 'primitive', value: (job) => job.action },
        },
        state: { header: header('common.status'), content: cell(this.jobStateCell) },
        generation: { header: header('settings.search.jobs.generation'), content: cell(this.jobGenerationCell) },
        processed: {
          header: header('settings.search.jobs.processed'),
          content: { type: 'primitive', value: (job) => job.processedCount },
          align: 'right',
        },
        failed: {
          header: header('settings.search.jobs.failed'),
          content: { type: 'primitive', value: (job) => job.failedCount },
          align: 'right',
        },
        created: { header: header('settings.search.jobs.created'), content: cell(this.jobCreatedCell) },
        actions: { header: header('common.actions'), content: cell(this.jobActionsCell) },
      },
      columnsOrder: ['id', 'action', 'state', 'generation', 'processed', 'failed', 'created', 'actions'],
    };
  });

  readonly schemaProfileOptions: readonly SMTSelectOption<string>[] = [
    { id: 'MIXED', label: 'MIXED' },
    { id: 'RU', label: 'RU' },
  ];

  private readonly previewEntityMemo = optionsMemo<SMTSelectOption<SearchEntityType>[]>();

  previewEntity: SearchEntityType | '' = '';

  /** At most four generations exist and all of them are shown, so a header click sorts them all. */
  readonly generationSortValues = {
    id: (g: SearchGenerationStatus) => g.id,
    state: (g: SearchGenerationStatus) => g.state,
    profile: (g: SearchGenerationStatus) => g.registeredProfile,
    documents: (g: SearchGenerationStatus) => g.documentCount,
    storage: (g: SearchGenerationStatus) => g.storageBytes,
    queue: (g: SearchGenerationStatus) => g.pendingDeliveries,
  };

  /** A policy rule's error under its field once a save was tried (forms standard, section 4). */
  policyError(key: string): string {
    return this.store.saveAttempted() && this.store.policyErrors().includes(key) ? this.i18n.translate(key) : '';
  }

  setPreviewQuery(query: string): void {
    this.previewQuery.set(query);
    if (query.trim()) this.previewQueryError.set('');
  }

  /** The preview button stays enabled: without a query it says so under the field and focuses it. */
  runPreview(): void {
    if (!this.previewQuery().trim()) {
      this.previewQueryError.set(this.i18n.translate('settings.search.preview.query_required'));
      const field = document.getElementById('search-preview-query')?.closest('smt-control');
      if (field instanceof HTMLElement) focusFirstInvalid(field, this.injector);
      return;
    }
    this.store.preview(this.previewQuery(), this.previewEntity);
  }

  /** Every entity the search indexes, in the order the server names them (ADR-0032, 10.3). */
  get displayedEntities(): SearchEntityType[] {
    return this.store.entities();
  }

  previewEntityOptions(): SMTSelectOption<SearchEntityType>[] {
    const entities = this.displayedEntities;
    const names = entities.map((entity) => this.entityLabel(entity));
    return this.previewEntityMemo([this.i18n.currentLang(), entities.join(), names.join()], () =>
      entities.map((entity, index) => ({ id: entity, label: names[index] })),
    );
  }

  /** The name of an indexed entity on this screen. */
  entityLabel(entity: SearchEntityType): string {
    return this.i18n.translate(this.store.entityLabelKey(entity));
  }

  /** The name of a searched field of an entity on this screen. */
  fieldLabel(entity: SearchEntityType, field: string): string {
    return this.i18n.translate(this.store.fieldLabelKey(entity, field));
  }

  /** The documents of each entity of a generation, named. */
  documentCounts(generation: SearchGenerationStatus): string {
    return Object.entries(generation.entityDocumentCounts ?? {})
      .map(([entity, count]) => `${this.entityLabel(entity)} ${this.displayNumber(count)}`)
      .join(' · ');
  }

  ngOnInit(): void {
    this.store.init();
  }

  jobErrorMessage(code?: string | null): string {
    return formatJobError(code, (key) => this.i18n.translate(key));
  }

  displayNumber(value: number | null | undefined): string {
    return value === null || value === undefined ? this.i18n.translate('settings.search.unknown') : String(value);
  }

  displayBytes(value: number | null | undefined): string {
    return formatBytes(value, this.i18n.translate('settings.search.unknown'));
  }

  trackField(_index: number, field: SearchFieldPolicy): string {
    return field.field;
  }

  trackJob(_index: number, job: SearchJobStatus): string {
    return job.id;
  }
}
