import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  Signal,
  TemplateRef,
  viewChild,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ProblemDetail } from '@core/models/common.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { UiBadgeComponent } from '@shared/ui/ui-badge.component';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import {
  UPL_PERIODICITIES,
  UPL_STRICTNESSES,
  UplApiService,
  UplPeriodicity,
  UplSource,
  UplSourceRequest,
  UplStrictness,
  UplVersionItem,
  UplVersionStatus,
} from '../upl-api';
import { parseUplProblem, uplFieldErrorText } from '../formats/upl-format-errors';
import { UPL_PERIODICITY_KEY, UPL_STRICTNESS_KEY, UPL_VERSION_STATUS_KEY, uplProblemText } from '../upl-labels';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '@shared/ui-kit/components/forms/select';

/** Реквизиты источника в форме экрана: код не правится и здесь не хранится. */
interface SourceForm {
  name: string;
  ownerOrg: string;
  ownerContact: string;
  periodicity: UplPeriodicity;
  slaDays: number | null;
  reconciliationStrictness: UplStrictness | null;
}

type DraftMode = 'empty' | 'copy';

@Component({
  selector: 'app-upl-source-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTInputValueAccessor,
    SMTSelectComponent,
    SMTSelectValueAccessor,
    SMTAlertComponent,
    SMTControlComponent,
    UiLocalTableComponent,
    FormsModule,
    RouterLink,
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    UiBadgeComponent,
    SMTRadioGroupComponent,
    DatePipe,
  ],
  templateUrl: './source-card.component.html',
  styleUrl: './source-card.component.css',
})
export class SourceCardComponent {
  private readonly api = inject(UplApiService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  private readonly versionCell = viewChild.required<TemplateRef<unknown>>('versionCell');
  private readonly versionStatusCell = viewChild.required<TemplateRef<unknown>>('versionStatusCell');
  private readonly validFromCell = viewChild.required<TemplateRef<unknown>>('validFromCell');
  private readonly validToCell = viewChild.required<TemplateRef<unknown>>('validToCell');
  private readonly publishedCell = viewChild.required<TemplateRef<unknown>>('publishedCell');
  private readonly templateCell = viewChild.required<TemplateRef<unknown>>('templateCell');

  readonly sourceId = signal<string | null>(null);
  readonly source = signal<UplSource | null>(null);

  readonly versions = signal<UplVersionItem[]>([]);

  readonly isLoading = signal(true);
  readonly loadError = signal(false);
  readonly notFound = signal(false);

  readonly isSaving = signal(false);
  readonly fieldErrors = signal<Record<string, string>>({});
  readonly saveError = signal<string | null>(null);
  readonly conflict = signal(false);

  readonly isDraftOpen = signal(false);
  readonly draftMode = signal<DraftMode>('empty');
  readonly copyFrom = signal<number | null>(null);
  readonly isCreatingDraft = signal(false);
  readonly draftError = signal<string | null>(null);
  readonly draftExists = signal(false);

  readonly versionsConfig = computed<TableConfig<UplVersionItem>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, v) => v.version,
      ariaLabel: this.i18n.translate('upl.version.title'),
      layout: 'fit',
      columns: {
        version: { header: header('upl.version.col.version'), content: cell(this.versionCell), width: '100px' },
        status: { header: header('upl.version.col.status'), content: cell(this.versionStatusCell), width: '150px' },
        validFrom: { header: header('upl.version.col.valid_from'), content: cell(this.validFromCell), width: '140px' },
        validTo: { header: header('upl.version.col.valid_to'), content: cell(this.validToCell), width: '140px' },
        published: { header: header('upl.version.col.published'), content: cell(this.publishedCell) },
        template: { header: header('upl.template.column'), content: cell(this.templateCell), width: '170px' },
      },
      columnsOrder: ['version', 'status', 'validFrom', 'validTo', 'published', 'template'],
    };
  });

  readonly canEdit = computed(() => this.permissions.hasPermission('upl.sources', 'edit'));
  readonly draftVersion = computed(() => this.versions().find((v) => v.status === 'draft') ?? null);
  /** A copy is offered only when there is a version to copy. */
  readonly draftModes = computed<SMTRadioOption<DraftMode>[]>(() => [
    { value: 'empty', label: this.i18n.translate('upl.version.draft_empty') },
    ...(this.copyCandidates().length > 0
      ? [{ value: 'copy' as const, label: this.i18n.translate('upl.version.draft_copy') }]
      : []),
  ]);
  readonly copyCandidates = computed(() =>
    this.versions()
      .filter((v) => v.status === 'published' || v.status === 'superseded')
      .sort((a, b) => b.version - a.version),
  );

  /** Versions a draft can be copied from, newest first. */
  readonly copyFromOptions = computed<SMTSelectOption<number>[]>(() =>
    this.copyCandidates().map((v) => ({ id: v.version, label: String(v.version) })),
  );
  private readonly periodicityMemo = optionsMemo<SMTSelectOption<UplPeriodicity>[]>();
  private readonly strictnessMemo = optionsMemo<SMTSelectOption<UplStrictness>[]>();
  readonly versionStatusKey = UPL_VERSION_STATUS_KEY;
  readonly dash = '—';

  /** All versions of a source are loaded, so a header click sorts them all. */
  readonly versionSortValues = {
    version: (v: UplVersionItem) => v.version,
    status: (v: UplVersionItem) => v.status,
    validFrom: (v: UplVersionItem) => v.validFrom,
    validTo: (v: UplVersionItem) => v.validTo,
    published: (v: UplVersionItem) => v.publishedAt,
  };

  form: SourceForm = {
    name: '',
    ownerOrg: '',
    ownerContact: '',
    periodicity: 'month',
    slaDays: 0,
    reconciliationStrictness: 'error',
  };

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.sourceId.set(params.get('id'));
      this.reload();
    });
  }

  /** The translated message for a field's error code, or nothing; smt-control links it to the field. */
  /** Periodicities of a source; translated again when the language changes. */
  periodicityOptions(): SMTSelectOption<UplPeriodicity>[] {
    return this.periodicityMemo([this.i18n.currentLang()], () =>
      UPL_PERIODICITIES.map((p) => ({ id: p, label: this.i18n.translate(UPL_PERIODICITY_KEY[p]) })),
    );
  }

  /** Reconciliation strictness levels; translated again when the language changes. */
  strictnessOptions(): SMTSelectOption<UplStrictness>[] {
    return this.strictnessMemo([this.i18n.currentLang()], () =>
      UPL_STRICTNESSES.map((st) => ({ id: st, label: this.i18n.translate(UPL_STRICTNESS_KEY[st]) })),
    );
  }

  fieldErrorText(key: string): string {
    const code = this.fieldErrors()[key];
    return code ? this.i18n.translate(code) : '';
  }

  /** The supplier's file for a version, in the reader's language; the browser downloads it with the session cookie. */
  templateUrl(version: number): string {
    const id = encodeURIComponent(this.sourceId() ?? '');
    const lang = encodeURIComponent(this.i18n.currentLang());
    return `/api/v1/upl/sources/${id}/format-versions/${version}/template?lang=${lang}`;
  }

  reload(): void {
    const id = this.sourceId();
    if (!id) {
      return;
    }
    this.isLoading.set(true);
    this.loadError.set(false);
    this.notFound.set(false);
    forkJoin({ source: this.api.getSource(id), versions: this.api.listVersions(id) }).subscribe({
      next: ({ source, versions }) => {
        this.source.set(source);
        this.versions.set(versions ?? []);
        this.fillForm(source);
        this.isLoading.set(false);
        this.openDraftDialogFromQuery();
      },
      error: (problem: ProblemDetail) => {
        this.isLoading.set(false);
        if (problem?.status === 404) {
          this.notFound.set(true);
          return;
        }
        this.loadError.set(true);
      },
    });
  }

  activeVersionLabel(source: UplSource): string {
    return this.i18n.translate('upl.card.active_version', { version: source.lastPublishedVersion ?? 0 });
  }

  statusKeyOf(version: UplVersionItem): string {
    return this.versionStatusKey[version.status];
  }

  statusVariant(status: UplVersionStatus): 'success' | 'info' | 'neutral' {
    if (status === 'published') {
      return 'success';
    }
    return status === 'draft' ? 'info' : 'neutral';
  }

  save(): void {
    const source = this.source();
    const id = this.sourceId();
    if (!source || !id || this.isSaving()) {
      return;
    }
    if (!this.validate()) {
      return;
    }
    const contact = this.form.ownerContact.trim();
    const body: UplSourceRequest = {
      code: source.code,
      name: this.form.name.trim(),
      ownerOrg: this.form.ownerOrg.trim(),
      ownerContact: contact.length > 0 ? contact : null,
      periodicity: this.form.periodicity,
      slaDays: Number(this.form.slaDays),
      sourceType: 'file',
      reconciliationStrictness: this.form.reconciliationStrictness,
      lockVersion: source.lockVersion,
    };
    this.isSaving.set(true);
    this.saveError.set(null);
    this.api.updateSource(id, body).subscribe({
      next: (saved) => {
        this.isSaving.set(false);
        this.source.set(saved);
        this.fillForm(saved);
        this.toast.success(this.i18n.translate('upl.source.saved'));
      },
      error: (problem: ProblemDetail) => {
        this.isSaving.set(false);
        this.handleSaveError(problem);
      },
    });
  }

  refreshAfterConflict(): void {
    this.conflict.set(false);
    this.reload();
  }

  openDraftDialog(): void {
    this.draftError.set(null);
    this.draftExists.set(false);
    this.draftMode.set('empty');
    const candidates = this.copyCandidates();
    const published = candidates.find((v) => v.status === 'published');
    this.copyFrom.set(published?.version ?? candidates[0]?.version ?? null);
    this.isDraftOpen.set(true);
  }

  closeDraftDialog(): void {
    if (this.isCreatingDraft()) {
      return;
    }
    this.isDraftOpen.set(false);
  }

  createDraft(): void {
    const id = this.sourceId();
    if (!id || this.isCreatingDraft()) {
      return;
    }
    const copyFrom = this.draftMode() === 'copy' ? (this.copyFrom() ?? undefined) : undefined;
    this.isCreatingDraft.set(true);
    this.draftError.set(null);
    this.draftExists.set(false);
    this.api.createDraft(id, copyFrom).subscribe({
      next: (created) => {
        this.isCreatingDraft.set(false);
        this.isDraftOpen.set(false);
        this.router.navigate(['/upl/sources', id, 'formats', created.version]);
      },
      error: (problem: ProblemDetail) => {
        this.isCreatingDraft.set(false);
        if (problem?.detail === 'FND_VERSION_DRAFT_EXISTS') {
          this.draftExists.set(true);
          return;
        }
        this.draftError.set(this.problemText(problem));
      },
    });
  }

  openExistingDraft(): void {
    const id = this.sourceId();
    if (!id) {
      return;
    }
    this.api.listVersions(id).subscribe({
      next: (versions) => {
        this.versions.set(versions ?? []);
        const draft = (versions ?? []).find((v) => v.status === 'draft');
        if (!draft) {
          this.draftError.set(this.i18n.translate('upl.err.FND_VERSION_UNKNOWN'));
          return;
        }
        this.isDraftOpen.set(false);
        this.router.navigate(['/upl/sources', id, 'formats', draft.version]);
      },
      error: (problem: ProblemDetail) => this.draftError.set(this.problemText(problem)),
    });
  }

  private fillForm(source: UplSource): void {
    this.form = {
      name: source.name,
      ownerOrg: source.ownerOrg,
      ownerContact: source.ownerContact ?? '',
      periodicity: source.periodicity,
      slaDays: source.slaDays,
      reconciliationStrictness: source.reconciliationStrictness,
    };
    this.fieldErrors.set({});
    this.saveError.set(null);
  }

  private openDraftDialogFromQuery(): void {
    const wanted = this.route.snapshot?.queryParamMap?.get('newDraft') === '1';
    if (wanted && this.canEdit() && !this.draftVersion()) {
      this.openDraftDialog();
    }
  }

  private validate(): boolean {
    const errors: Record<string, string> = {};
    const name = this.form.name.trim();
    if (name.length === 0) {
      errors['name'] = 'upl.source.err.required';
    } else if (name.length > 200) {
      errors['name'] = 'upl.source.err.name_length';
    }
    if (this.form.ownerOrg.trim().length === 0) {
      errors['ownerOrg'] = 'upl.source.err.required';
    }
    const sla = Number(this.form.slaDays);
    if (this.form.slaDays === null || !Number.isInteger(sla) || sla < 0 || sla > 366) {
      errors['slaDays'] = 'upl.source.err.sla_range';
    }
    this.fieldErrors.set(errors);
    return Object.keys(errors).length === 0;
  }

  private handleSaveError(problem: ProblemDetail): void {
    if (problem?.status === 409 && problem?.detail === 'STALE_VERSION') {
      this.conflict.set(true);
      return;
    }
    if (problem?.status === 422) {
      const errors: Record<string, string> = {};
      for (const item of parseUplProblem(problem)) {
        errors[item.field] = uplFieldErrorText(item, (key) => this.i18n.translate(key));
      }
      this.fieldErrors.set(errors);
      this.saveError.set('upl.err.VALIDATION_FAILED');
      return;
    }
    this.saveError.set(this.problemText(problem));
  }

  private problemText(problem: ProblemDetail): string {
    return uplProblemText(problem, (key) => this.i18n.translate(key));
  }
}
