import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, OnInit, TemplateRef, ViewChild, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProblemDetail } from '../../../core/models/common.models';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { SMTControlComponent } from '../../../shared/ui-kit/components/forms/control';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiBadgeComponent } from '../../../shared/ui/ui-badge.component';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { SMTDatePickerComponent, SMTDatePickerValueAccessor } from '../../../shared/ui-kit/components/forms/date-picker';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '../../../shared/ui-kit/components/forms/select';
import { LookupChannel } from '../../../shared/paging/lookup-channel';
import { KeysetPager } from '../../../shared/paging/keyset-pager';
import { ListViewState, ListViewsApi } from '../../../shared/list-views/list-views';
import { TableColumnStateStore } from '../../../shared/ui-kit/services/table-column-state.store';
import { UiServerTableComponent } from '../../../shared/ui/ui-server-table.component';
import { registryTableConfig, sortFromHeader } from '../../../shared/ui/registry-table-config';
import { OrderBy, TableConfig } from '../../../shared/ui-kit/components/table/table.types';
import { QueryListMeta } from '../../../core/models/query-meta.models';
import { QueryMetaService, parseSort } from '../../../core/services/query-meta.service';
import { UPL_PERIODICITY_KEY } from '../upl-labels';
import { UplSource, UplSourceItem } from '../upl-api';
import { PackageCardComponent } from './package-card.component';
import { UplPackageItem, UplPackagesApiService } from './packages-api';
import { UplPackageFormErrors, UplTranslate, mapUplUploadProblem } from './packages-errors';
import {
  UPL_PACKAGE_STATUS_KEY,
  UPL_PACKAGE_STATUS_VARIANT,
  formatUplDateTime,
  formatUplPeriod,
  uplPackageRowsText
} from './packages-labels';

/** Поля формы «Новая загрузка»: обычный объект, чтобы работал `[(ngModel)]`. */
interface PackageUploadForm {
  sourceId: number | null;
  periodFrom: string;
  periodTo: string;
  file: File | null;
}

const PAGE_SIZE = 50;

function emptyForm(): PackageUploadForm {
  return { sourceId: null, periodFrom: '', periodTo: '', file: null };
}

function emptyFormErrors(): UplPackageFormErrors {
  return { source: [], period: [], file: [], form: [] };
}

@Component({
  selector: 'app-upl-packages',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTControlComponent,
    CommonModule, FormsModule, TranslatePipe, UiBadgeComponent, UiButtonComponent, PackageCardComponent,
    SMTDatePickerComponent, SMTDatePickerValueAccessor, SMTSelectComponent, SMTSelectValueAccessor,
    UiServerTableComponent,
  ],
  template: `
    @if (selected(); as current) {
      <app-upl-package-card
        [item]="current"
        [canApply]="canApply()"
        (back)="closeCard()"
        (refresh)="load()"
        (applied)="onApplied($event)"
      ></app-upl-package-card>
    } @else {
      <div class="upl-page">
        <div class="toolbar upl-toolbar">
          <h1 class="upl-title">{{ 'upl.pkg.title' | t }}</h1>
          <ui-button variant="secondary" icon="refresh" data-testid="upl-pkg-refresh" (onClick)="load()">
            {{ 'upl.pkg.refresh' | t }}
          </ui-button>
        </div>

        @if (canUpload()) {
          <form class="upl-pkg-form" data-testid="upl-pkg-form" (ngSubmit)="submit()" novalidate>
            <h2 class="upl-pkg-form-title">{{ 'upl.pkg.form.title' | t }}</h2>

            @if (formErrors().form.length > 0) {
              <div class="alert alert-error upl-pkg-err-form" role="alert" data-testid="upl-pkg-err-form">
                @for (message of formErrors().form; track $index) {
                  <span class="upl-pkg-err-line">{{ message }}</span>
                }
              </div>
            }

            <div class="upl-pkg-fields">
              <smt-control class="form-group" [smtLabel]="'upl.pkg.form.source' | t" [smtError]="formErrors().source.join(' ')">
                <!-- A lookup over the server list: search by code or name, columns, "create" from the typed text. -->
                <smt-select
                  smtTriggerId="upl-pkg-source-field"
                  name="sourceId"
                  data-testid="upl-pkg-source"
                  [disabled]="isSending()"
                  [(ngModel)]="form.sourceId"
                  [options]="sourceOptions()"
                  [placeholder]="'upl.pkg.form.source_placeholder' | t"
                  [searchPlaceholder]="'upl.pkg.form.source_search' | t"
                  [emptyLabel]="'upl.pkg.form.source_placeholder' | t"
                  [remoteSearch]="true"
                  [loading]="sourceLookup.loading()"
                  [loadError]="sourceLookup.error()"
                  [hasMore]="sourceLookup.hasMore()"
                  [smtColumnHeaders]="sourceColumns()"
                  [smtAllowCreate]="canCreateSource()"
                  (searchChange)="searchSources($event)"
                  (loadMore)="sourceLookup.load(false, selectedSource)"
                  (retry)="sourceLookup.retry(selectedSource)"
                  (create)="createSource($event)"
                ></smt-select>
              </smt-control>
              @if (templateLink(); as link) {
                <a class="upl-pkg-template" data-testid="upl-pkg-template" [href]="link.href" download>
                  <span class="material-symbols-outlined" aria-hidden="true">download</span>
                  {{ 'upl.pkg.form.template' | t: { version: link.version } }}
                </a>
              }

              <smt-control class="form-group" [smtLabel]="'upl.pkg.form.period_from' | t">
                <smt-date-picker
                  smtInputId="upl-pkg-period-from-field"
                  name="periodFrom"
                  data-testid="upl-pkg-period-from"
                  [disabled]="isSending()"
                  [ngModel]="form.periodFrom"
                  (ngModelChange)="form.periodFrom = $event ?? ''"
                />
              </smt-control>

              <smt-control class="form-group" [smtLabel]="'upl.pkg.form.period_to' | t" [smtError]="formErrors().period.join(' ')">
                <smt-date-picker
                  smtInputId="upl-pkg-period-to-field"
                  name="periodTo"
                  data-testid="upl-pkg-period-to"
                  [disabled]="isSending()"
                  [ngModel]="form.periodTo"
                  (ngModelChange)="form.periodTo = $event ?? ''"
                />
              </smt-control>

              <smt-control class="form-group" [smtLabel]="'upl.pkg.form.file' | t" [smtError]="formErrors().file.join(' ')">
                <input
                  #fileInput
                  class="form-input"
                  id="upl-pkg-file-field"
                  type="file"
                  accept=".xlsx"
                  data-testid="upl-pkg-file"
                  [disabled]="isSending()"
                  (change)="pickFile($event)"
                />
              </smt-control>
            </div>

            <div class="upl-pkg-form-actions">
              <ui-button
                variant="primary"
                [disabled]="!isReady()"
                [loading]="isSending()"
                data-testid="upl-pkg-submit"
                (onClick)="submit()"
              >{{ 'upl.pkg.form.submit' | t }}</ui-button>
            </div>
          </form>
        }

        @if (metaError()) {
          <div class="alert alert-error upl-alert" role="alert" data-testid="upl-pkg-load-error">
            <span>{{ 'upl.pkg.load_error' | t }}</span>
            <ui-button variant="secondary" data-testid="upl-pkg-retry" (onClick)="load()">
              {{ 'upl.common.retry' | t }}
            </ui-button>
          </div>
        } @else if (tableConfig(); as config) {
          <ui-server-table
            [pager]="pager"
            [config]="config"
            [views]="views"
            [filterMeta]="meta()"
            [loadingLabel]="'upl.common.loading' | t"
            [errorLabel]="'upl.pkg.load_error' | t"
            [emptyTemplate]="emptyState"
            (sortChange)="onSort($event)"
            (rowClick)="openCard($event)" />
        } @else {
          <p class="upl-muted" role="status" data-testid="upl-pkg-meta-loading">{{ 'upl.common.loading' | t }}</p>
        }
      </div>
    }

    <ng-template #emptyState>
      <div class="upl-empty" data-testid="upl-pkg-empty">
        <span class="material-symbols-outlined upl-empty-icon" aria-hidden="true">upload_file</span>
        <p class="upl-empty-text">{{ (canUpload() ? 'upl.pkg.empty_hint' : 'upl.pkg.empty') | t }}</p>
      </div>
    </ng-template>
    <ng-template #uploadedAtCell let-item><span data-testid="upl-pkg-row">{{ dateTime(item.uploadedAt) }}</span></ng-template>
    <ng-template #periodCell let-item>{{ period(item) }}</ng-template>
    <ng-template #statusCell let-item>
      <ui-badge [variant]="variantOf(item)"
        [attr.title]="item.status === 'received' ? ('upl.pkg.status.received_hint' | t) : null">{{ statusKeyOf(item) | t }}</ui-badge>
    </ng-template>
    <ng-template #rowsCell let-item>{{ rowsText(item) }}</ng-template>
  `,
  styles: [`
    .upl-page {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .upl-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
    }

    .upl-title {
      margin: 0;
      font-family: var(--font-family);
      font-size: 1.25rem;
      color: var(--text-main);
    }

    .upl-pkg-form {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
      padding: 1rem;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      background: var(--bg-surface);
    }

    .upl-pkg-form-title {
      margin: 0;
      font-family: var(--font-family);
      font-size: 1rem;
      color: var(--text-main);
    }

    .upl-pkg-fields {
      display: flex;
      flex-wrap: wrap;
      gap: 1rem;
    }

    .upl-pkg-fields .form-group {
      flex: 1 1 12rem;
      min-width: 12rem;
    }

    .upl-pkg-form-actions {
      display: flex;
      justify-content: flex-end;
    }

    .upl-pkg-err-form {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .upl-pkg-err-line {
      display: block;
    }

    .upl-pkg-template {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      align-self: flex-start;
      font-size: 0.8125rem;
      color: var(--primary-text, var(--primary));
    }

    .upl-pkg-template .material-symbols-outlined {
      font-size: 16px;
    }

    .upl-field-error {
      display: block;
      margin-top: 0.25rem;
      color: var(--danger);
      font-size: 0.8125rem;
    }

    .upl-alert {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
    }

    .upl-muted {
      color: var(--text-muted);
    }

    .upl-empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.75rem;
      padding: 3rem 1rem;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      background: var(--bg-surface);
    }

    .upl-empty-icon {
      font-size: 2.5rem;
      color: var(--text-light);
    }

    .upl-empty-text {
      margin: 0;
      color: var(--text-muted);
    }
  `]
})
export class PackagesComponent implements OnInit {
  private readonly api = inject(UplPackagesApiService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  @ViewChild('fileInput') fileInput?: ElementRef<HTMLInputElement>;

  private readonly queryMeta = inject(QueryMetaService);
  private readonly destroyRef = inject(DestroyRef);

  /** Field metadata of the list (`query-meta/upl.packages`). */
  readonly meta = signal<QueryListMeta | null>(null);
  readonly metaError = signal(false);
  readonly views = new ListViewState('upl.packages', inject(ListViewsApi), {
    defaultSort: () => {
      const meta = this.meta();
      return meta ? parseSort(meta.defaultSort) : null;
    },
    onApply: () => this.pager.first(),
    columnsStore: inject(TableColumnStateStore)
  });
  readonly pager = new KeysetPager<UplPackageItem>(
    (cursor, limit) => this.api.list(limit, cursor, { sort: this.views.sort(), conditions: this.views.filter() }),
    { pageSize: PAGE_SIZE, destroyRef: this.destroyRef, onLoaded: rows => this.syncSelected(rows) }
  );
  readonly items = this.pager.items;

  private readonly uploadedAtCell = viewChild.required<TemplateRef<unknown>>('uploadedAtCell');
  private readonly periodCell = viewChild.required<TemplateRef<unknown>>('periodCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('statusCell');
  private readonly rowsCell = viewChild.required<TemplateRef<unknown>>('rowsCell');

  readonly tableConfig = computed<TableConfig<UplPackageItem> | null>(() => {
    const meta = this.meta();
    if (!meta) return null;
    return registryTableConfig<UplPackageItem>(meta, {
      translate: key => this.i18n.translate(key),
      trackBy: (_index, item) => item.id,
      ariaLabel: this.i18n.translate('upl.pkg.title'),
      sort: this.views.sort(),
      cells: {
        uploadedAt: { type: 'templateRef', value: this.uploadedAtCell },
        periodFrom: { type: 'templateRef', value: this.periodCell },
        status: { type: 'templateRef', value: this.statusCell },
        rowsTotal: { type: 'templateRef', value: this.rowsCell }
      }
    });
  });
  readonly selected = signal<UplPackageItem | null>(null);

  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /** Options of the source lookup: the rows the last search returned, with the chosen one kept. */
  readonly sourceOptions = signal<SMTSelectOption<number>[]>([]);
  readonly sourceColumns = computed(() => [
    this.i18n.translate('upl.list.col.code'),
    this.i18n.translate('upl.list.col.periodicity'),
    this.i18n.translate('upl.list.col.published_version')
  ]);
  readonly selectedSource = () => this.form.sourceId;
  readonly sourceLookup = new LookupChannel<UplSourceItem, number | null>(
    (query, cursor, pageSize) => this.api.searchSources(query, cursor, pageSize),
    (rows, append, selected) => {
      const found = rows.map(row => this.sourceOption(row));
      this.sourceOptions.update(current => {
        const next = append ? [...current, ...found] : found;
        const chosen = current.find(option => option.id === selected);
        return chosen && !next.some(option => option.id === selected) ? [chosen, ...next] : next;
      });
    },
    null,
    { pageSize: 20 }
  );
  readonly isSending = signal(false);
  readonly formErrors = signal<UplPackageFormErrors>(emptyFormErrors());

  readonly statusKey = UPL_PACKAGE_STATUS_KEY;
  readonly statusVariant = UPL_PACKAGE_STATUS_VARIANT;

  form: PackageUploadForm = emptyForm();

  private readonly translate: UplTranslate = (key, params) => this.i18n.translate(key, params);

  ngOnInit(): void {
    this.load();
    const created = this.route.snapshot.queryParamMap.get('source');
    if (this.canUpload() && created && /^\d+$/.test(created)) {
      this.api.source(created).subscribe({ next: source => this.chooseSource(source) });
    }
  }

  canUpload(): boolean {
    return this.permissions.hasPermission('upl.packages', 'upload');
  }

  canApply(): boolean {
    return this.permissions.hasPermission('upl.packages', 'apply');
  }

  /** Ответ «Применить» сразу показывается в карточке; список перечитывается, чтобы статус совпал и там. */
  onApplied(result: UplPackageItem): void {
    this.selected.set(result);
    this.load();
  }

  dateTime(value: string): string {
    return formatUplDateTime(value);
  }

  period(item: UplPackageItem): string {
    return formatUplPeriod(item.periodFrom, item.periodTo);
  }

  rowsText(item: UplPackageItem): string {
    return uplPackageRowsText(item);
  }

  canCreateSource(): boolean {
    return this.permissions.hasPermission('upl.sources', 'create');
  }

  /** An empty search (the popup just opened, or the text was cleared) shows the first page at once; typing waits for a pause. */
  searchSources(query: string): void {
    if (query.trim() === '') this.sourceLookup.reset(this.selectedSource);
    else this.sourceLookup.search(query, this.selectedSource);
  }

  /** «Создать из поля»: карточка нового источника с набранным названием; после создания форма получит его выбранным. */
  createSource(name: string): void {
    void this.router.navigate(['/upl/sources'], { queryParams: { create: name, returnTo: 'packages' } });
  }

  private chooseSource(source: UplSource | UplSourceItem): void {
    const option = this.sourceOption(source);
    this.sourceOptions.update(current => [option, ...current.filter(item => item.id !== option.id)]);
    this.form = { ...this.form, sourceId: source.id };
  }

  /** The published format version of each source seen in the lookup, for the template link. */
  private readonly publishedVersions = new Map<number, number | null>();

  /**
   * The file to fill for the chosen source: the template of its latest published format version,
   * so a supplier starts from the right headers. None until a source with a published version is chosen.
   */
  templateLink(): { href: string; version: number } | null {
    const id = this.form.sourceId;
    const version = id === null || id === undefined ? null : this.publishedVersions.get(id) ?? null;
    if (id === null || id === undefined || version === null) return null;
    const lang = encodeURIComponent(this.i18n.currentLang());
    return { href: `/api/v1/upl/sources/${id}/format-versions/${version}/template?lang=${lang}`, version };
  }

  private sourceOption(source: UplSource | UplSourceItem): SMTSelectOption<number> {
    this.publishedVersions.set(source.id, source.lastPublishedVersion ?? null);
    return {
      id: source.id,
      label: source.name,
      columns: [
        source.code,
        this.i18n.translate(UPL_PERIODICITY_KEY[source.periodicity]),
        source.lastPublishedVersion === null || source.lastPublishedVersion === undefined ? '—' : String(source.lastPublishedVersion)
      ]
    };
  }

  /** The list's metadata once, then its first page; later calls reload the first page (after an upload or a retry). */
  load(): void {
    if (this.meta()) {
      this.pager.first();
      return;
    }
    this.metaError.set(false);
    this.queryMeta.get('upl.packages').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: meta => {
        this.meta.set(meta);
        this.views.load().subscribe(() => this.pager.first());
      },
      error: () => this.metaError.set(true)
    });
  }

  /** A header click sorts the whole list on the server; switching sorting off returns to the default order. */
  onSort(event: { column: string; sortBy: OrderBy } | undefined): void {
    if (!this.meta()) return;
    this.views.setSort(sortFromHeader(event));
    this.pager.first();
  }

  pickFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.form.file = input.files && input.files.length > 0 ? input.files[0] : null;
  }

  /** Кнопка «Загрузить» оживает, только когда заполнены все четыре поля. */
  isReady(): boolean {
    return (
      !this.isSending() &&
      this.form.sourceId !== null &&
      this.form.periodFrom.length > 0 &&
      this.form.periodTo.length > 0 &&
      this.form.file !== null
    );
  }

  submit(): void {
    if (!this.isReady()) {
      return;
    }
    const file = this.form.file as File;
    this.isSending.set(true);
    this.formErrors.set(emptyFormErrors());
    this.api
      .upload({
        sourceId: Number(this.form.sourceId),
        periodFrom: this.form.periodFrom,
        periodTo: this.form.periodTo,
        file
      })
      .subscribe({
        next: () => {
          this.isSending.set(false);
          this.clearFile();
          this.toast.success(this.i18n.translate('upl.pkg.toast.accepted'));
          this.load();
        },
        error: (problem: ProblemDetail) => {
          this.isSending.set(false);
          this.formErrors.set(mapUplUploadProblem(problem, this.translate));
        }
      });
  }

  variantOf(item: UplPackageItem): string {
    return this.statusVariant[item.status];
  }

  statusKeyOf(item: UplPackageItem): string {
    return this.statusKey[item.status];
  }

  openCard(item: UplPackageItem): void {
    this.selected.set(item);
  }

  closeCard(): void {
    this.selected.set(null);
  }

  /** После успеха чистим только файл: источник и период нужны для следующего файла. */
  private clearFile(): void {
    this.form.file = null;
    if (this.fileInput) {
      this.fileInput.nativeElement.value = '';
    }
  }

  /** Открытая карточка подхватывает свежие данные списка; пропала из порции — остаётся как была. */
  private syncSelected(loaded: UplPackageItem[]): void {
    const current = this.selected();
    if (current === null) {
      return;
    }
    const fresh = loaded.find(item => item.id === current.id);
    if (fresh) {
      this.selected.set(fresh);
    }
  }
}
