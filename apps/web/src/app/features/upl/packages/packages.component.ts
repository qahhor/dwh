import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  TemplateRef,
  computed,
  inject,
  linkedSignal,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { Observable, catchError, of, switchMap, tap } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { ProblemDetail } from '@core/models/common.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDatePickerComponent } from '@shared/ui-kit/components/forms/date-picker';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { LookupChannel } from '@shared/paging/lookup-channel';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { ListViewState, ListViewsApi } from '@shared/list-views/list-views';
import { TableColumnStateStore } from '@shared/ui-kit/services/table-column-state.store';
import { UiServerTableComponent } from '@shared/ui/ui-server-table.component';
import { registryTableConfig, sortFromHeader } from '@shared/ui/registry-table-config';
import { OrderBy, TableConfig } from '@shared/ui-kit/components/table/table.types';
import { QueryListMeta } from '@core/models/query-meta.models';
import { QueryMetaService, parseSort } from '@core/services/query-meta.service';
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
  uplPackageRowsText,
} from './packages-labels';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { TBadgeVariant } from '@shared/ui-kit/components/badge/badge.component';

/** Fields of the "new upload" form; the form signal holds them and is replaced on every change. */
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
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTAlertComponent,
    SMTControlComponent,
    TranslatePipe,
    SMTBadgeComponent,
    SMTButtonComponent,
    PackageCardComponent,
    SMTDatePickerComponent,
    SMTSelectComponent,
    UiServerTableComponent,
  ],
  templateUrl: './packages.component.html',
  styleUrl: './packages.component.css',
})
export class PackagesComponent implements OnInit {
  private readonly api = inject(UplPackagesApiService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  private readonly queryMeta = inject(QueryMetaService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  private readonly uploadedAtCell = viewChild.required<TemplateRef<unknown>>('uploadedAtCell');
  private readonly periodCell = viewChild.required<TemplateRef<unknown>>('periodCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('statusCell');
  private readonly rowsCell = viewChild.required<TemplateRef<unknown>>('rowsCell');

  readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');

  /** Field metadata of the list (`query-meta/upl.packages`). */
  readonly meta = signal<QueryListMeta | null>(null);
  readonly metaError = signal(false);
  /** The upload whose card is open; a link to one upload (from the data overview) opens its card at once. */
  readonly selected = linkedSignal<UplPackageItem | null, UplPackageItem | null>({
    source: () => this.linkedUpload(),
    computation: (linked, previous) => linked ?? previous?.value ?? null,
  });

  /** Options of the source lookup: the rows the last search returned, with the chosen one kept. */
  readonly sourceOptions = signal<SMTSelectOption<number>[]>([]);
  readonly isSending = signal(false);
  readonly formErrors = signal<UplPackageFormErrors>(emptyFormErrors());
  /* A signal, so that a change made in a callback (a source chosen from a link, the file cleared after an
     upload) redraws this OnPush screen by itself. */
  readonly form = signal<PackageUploadForm>(emptyForm());

  readonly tableConfig = computed<TableConfig<UplPackageItem> | null>(() => {
    const meta = this.meta();
    if (!meta) return null;
    return registryTableConfig<UplPackageItem>(meta, {
      translate: (key) => this.i18n.translate(key),
      trackBy: (_index, item) => item.id,
      ariaLabel: this.i18n.translate('upl.pkg.title'),
      sort: this.views.sort(),
      cells: {
        uploadedAt: { type: 'templateRef', value: this.uploadedAtCell },
        periodFrom: { type: 'templateRef', value: this.periodCell },
        status: { type: 'templateRef', value: this.statusCell },
        rowsTotal: { type: 'templateRef', value: this.rowsCell },
      },
    });
  });
  readonly sourceColumns = computed(() => [
    this.i18n.translate('upl.list.col.code'),
    this.i18n.translate('upl.list.col.periodicity'),
    this.i18n.translate('upl.list.col.published_version'),
  ]);

  readonly views = new ListViewState('upl.packages', inject(ListViewsApi), {
    defaultSort: () => {
      const meta = this.meta();
      return meta ? parseSort(meta.defaultSort) : null;
    },
    onApply: () => this.pager.first(),
    columnsStore: inject(TableColumnStateStore),
  });
  readonly pager = new KeysetPager<UplPackageItem>(
    (cursor, limit) =>
      this.api.list(limit, cursor, {
        sort: this.views.sort(),
        conditions: this.views.filter(),
        match: this.views.match(),
      }),
    { pageSize: PAGE_SIZE, destroyRef: this.destroyRef, onLoaded: (rows) => this.syncSelected(rows) },
  );
  readonly items = this.pager.items;
  readonly selectedSource = () => this.form().sourceId;
  readonly sourceLookup = new LookupChannel<UplSourceItem, number | null>(
    (query, cursor, pageSize) => this.api.searchSources(query, cursor, pageSize),
    (rows, append, selected) => {
      const found = rows.map((row) => this.sourceOption(row));
      this.sourceOptions.update((current) => {
        const next = append ? [...current, ...found] : found;
        const chosen = current.find((option) => option.id === selected);
        return chosen && !next.some((option) => option.id === selected) ? [chosen, ...next] : next;
      });
    },
    null,
    { pageSize: 20 },
  );

  readonly statusKey = UPL_PACKAGE_STATUS_KEY;
  readonly statusVariant = UPL_PACKAGE_STATUS_VARIANT;

  private readonly translate: UplTranslate = (key, params) => this.i18n.translate(key, params);

  /** The published format version of each source seen in the lookup, for the template link. */
  private readonly publishedVersions = new Map<number, number | null>();

  private readonly linkedUpload = toSignal(this.uploadFromLink(), { initialValue: null });

  ngOnInit(): void {
    this.load();
    const created = this.route.snapshot.queryParamMap.get('source');
    if (this.canUpload() && created && /^\d+$/.test(created)) {
      this.api.source(created).subscribe({ next: (source) => this.chooseSource(source) });
    }
  }

  canUpload(): boolean {
    return this.permissions.hasPermission('upl.packages', 'upload');
  }

  canApply(): boolean {
    return this.permissions.hasPermission('upl.packages', 'apply');
  }

  /** The "Apply" answer shows in the card at once; the list is reread so that the status matches there too. */
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

  /** "Create from the field": the card of a new source with the typed name; once created, the form gets it selected. */
  createSource(name: string): void {
    void this.router.navigate(['/upl/sources'], { queryParams: { create: name, returnTo: 'packages' } });
  }

  /**
   * The file to fill for the chosen source: the template of its latest published format version,
   * so a supplier starts from the right headers. None until a source with a published version is chosen.
   */
  templateLink(): { href: string; version: number } | null {
    const id = this.form().sourceId;
    const version = id === null || id === undefined ? null : (this.publishedVersions.get(id) ?? null);
    if (id === null || id === undefined || version === null) return null;
    const lang = encodeURIComponent(this.i18n.currentLang());
    return { href: `/api/v1/upl/sources/${id}/format-versions/${version}/template?lang=${lang}`, version };
  }

  /** The list's metadata once, then its first page; later calls reload the first page (after an upload or a retry). */
  load(): void {
    if (this.meta()) {
      this.pager.first();
      return;
    }
    this.metaError.set(false);
    // The saved views apply their sort over the metadata, so they load only once it is here.
    this.queryMeta
      .get('upl.packages')
      .pipe(
        tap((meta) => this.meta.set(meta)),
        switchMap(() => this.views.load()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.pager.first(),
        error: () => this.metaError.set(true),
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
    this.patchForm({ file: input.files && input.files.length > 0 ? input.files[0] : null });
  }

  patchForm(patch: Partial<PackageUploadForm>): void {
    this.form.update((form) => ({ ...form, ...patch }));
  }

  /** The "Upload" button comes alive only when all four fields are filled in. */
  isReady(): boolean {
    const form = this.form();
    return (
      !this.isSending() &&
      form.sourceId !== null &&
      form.periodFrom.length > 0 &&
      form.periodTo.length > 0 &&
      form.file !== null
    );
  }

  submit(): void {
    if (!this.isReady()) {
      return;
    }
    const form = this.form();
    const file = form.file as File;
    this.isSending.set(true);
    this.formErrors.set(emptyFormErrors());
    this.api
      .upload({
        sourceId: Number(form.sourceId),
        periodFrom: form.periodFrom,
        periodTo: form.periodTo,
        file,
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
        },
      });
  }

  variantOf(item: UplPackageItem): TBadgeVariant {
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

  private chooseSource(source: UplSource | UplSourceItem): void {
    const option = this.sourceOption(source);
    this.sourceOptions.update((current) => [option, ...current.filter((item) => item.id !== option.id)]);
    this.patchForm({ sourceId: source.id });
  }

  private sourceOption(source: UplSource | UplSourceItem): SMTSelectOption<number> {
    this.publishedVersions.set(source.id, source.lastPublishedVersion ?? null);
    return {
      id: source.id,
      label: source.name,
      columns: [
        source.code,
        this.i18n.translate(UPL_PERIODICITY_KEY[source.periodicity]),
        source.lastPublishedVersion === null || source.lastPublishedVersion === undefined
          ? '—'
          : String(source.lastPublishedVersion),
      ],
    };
  }

  /** After a success only the file is cleared: the source and the period are needed for the next file. */
  private clearFile(): void {
    this.patchForm({ file: null });
    const fileInput = this.fileInput();
    if (fileInput) {
      fileInput.nativeElement.value = '';
    }
  }

  /** The upload named by the `open` link, or nothing; a link that fails leaves the list on screen. */
  private uploadFromLink(): Observable<UplPackageItem | null> {
    const open = this.route.snapshot.queryParamMap.get('open');
    return open ? this.api.get(open).pipe(catchError(() => of(null))) : of(null);
  }

  /** The open card picks up fresh list data; if it dropped out of the portion, it stays as it was. */
  private syncSelected(loaded: UplPackageItem[]): void {
    const current = this.selected();
    if (current === null) {
      return;
    }
    const fresh = loaded.find((item) => item.id === current.id);
    if (fresh) {
      this.selected.set(fresh);
    }
  }
}
