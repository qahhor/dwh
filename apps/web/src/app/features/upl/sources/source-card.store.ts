import { Injectable, ResourceRef, computed, effect, inject, linkedSignal, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { applyWhen, disabled, form } from '@angular/forms/signals';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { ProblemDetail } from '@core/models/common.models';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { UplApiService, UplPeriodicity, UplSource, UplSourceRequest, UplStrictness, UplVersionItem } from '../upl-api';
import { parseUplProblem, uplFieldErrorText } from '../formats/upl-format-errors';
import { UPL_ERROR, uplProblemText } from '../upl-labels';
import { UplRuleMessage, uplSourceLengthLimits, uplSourceRequisiteRules } from './source-form-rules';

/** Реквизиты источника в форме экрана: код не правится и здесь не хранится. */
export interface SourceForm {
  name: string;
  ownerOrg: string;
  ownerContact: string;
  periodicity: UplPeriodicity;
  slaDays: number | null;
  reconciliationStrictness: UplStrictness | null;
}

export type DraftMode = 'empty' | 'copy';

interface SourceCardData {
  source: UplSource;
  versions: UplVersionItem[];
}

/**
 * What a load leaves on screen. A refusal is a value, so it never throws out of the resource, and it
 * keeps the data of the load before: a failed reload leaves the card as it was.
 */
interface SourceCardLoad {
  data: SourceCardData | null;
  problem: ProblemDetail | null;
}

/**
 * One source's card: loading the source and its versions, saving its
 * requisites and starting a new format draft. Provided by the card screen, so
 * it lives as long as the screen does.
 */
@Injectable()
export class SourceCardStore {
  private readonly api = inject(UplApiService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly sourceId = signal<string | null>(null);
  /** The source as the server last gave it: loaded, or answered by a save. */
  readonly source = linkedSignal(() => this.data()?.source ?? null);

  readonly versions = linkedSignal(() => this.data()?.versions ?? []);

  readonly isSaving = signal(false);
  /* A source that arrives (loaded or saved) refills the form, so what was said about the last attempt goes. */
  readonly fieldErrors = linkedSignal<UplSource | null, Record<string, string>>({
    source: () => this.source(),
    computation: () => ({}),
  });
  readonly saveError = linkedSignal<UplSource | null, string | null>({
    source: () => this.source(),
    computation: () => null,
  });
  readonly conflict = signal(false);

  readonly isDraftOpen = signal(false);
  readonly draftMode = signal<DraftMode>('empty');
  readonly copyFrom = signal<number | null>(null);
  readonly isCreatingDraft = signal(false);
  readonly draftError = signal<string | null>(null);
  readonly draftExists = signal(false);

  /** The requisites being edited; the card's fields write it through {@link requisites}. */
  readonly form = linkedSignal<UplSource | null, SourceForm>({
    source: () => this.source(),
    computation: (source, previous) => (source ? formOf(source) : (previous?.value ?? emptyForm())),
  });

  /* The rules run only after a save attempt, so errors still appear on save (not while typing), as before. */
  private readonly submitted = linkedSignal<UplSource | null, boolean>({
    source: () => this.source(),
    computation: () => false,
  });

  /** True until the first answer, and again while a reload is on its way. */
  readonly isLoading = computed(() => !this.sourceId() || this.loaded.isLoading());
  readonly notFound = computed(() => this.problem()?.status === 404);
  readonly loadError = computed(() => this.problem() !== null && !this.notFound());
  readonly canEdit = computed(() => this.permissions.hasPermission('upl.sources', 'edit'));
  readonly draftVersion = computed(() => this.versions().find((v) => v.status === 'draft') ?? null);
  /** Versions a draft can be copied from, newest first. */
  readonly copyCandidates = computed(() =>
    this.versions()
      .filter((v) => v.status === 'published' || v.status === 'superseded')
      .sort((a, b) => b.version - a.version),
  );
  private readonly data = computed<SourceCardData | null>(() => this.loaded.value()?.data ?? null);
  /** The refusal of the last load; a load that answers clears it. */
  private readonly problem = computed(() => this.loaded.value()?.problem ?? null);

  /** A link that asks for a new draft opens its dialog once the versions are known. */
  private readonly draftFromQuery = effect(() => {
    if (this.data()) {
      untracked(() => this.openDraftDialogFromQuery());
    }
  });

  private readonly ruleMessage: UplRuleMessage = (key) => ({ kind: key, message: this.i18n.translate(key) });
  /** The card's form: without the edit right every field is disabled. */
  readonly requisites = form(this.form, (path) => {
    disabled(path, () => !this.canEdit());
    uplSourceLengthLimits(path);
    applyWhen(
      path,
      () => this.submitted(),
      (checked) => uplSourceRequisiteRules(checked, this.ruleMessage),
    );
  });

  private readonly loaded: ResourceRef<SourceCardLoad | undefined> = rxResource({
    params: () => this.sourceId() ?? undefined,
    stream: ({ params: id }) => {
      const shown: SourceCardData | null = untracked(this.data);
      return forkJoin({ source: this.api.getSource(id), versions: this.api.listVersions(id) }).pipe(
        map(({ source, versions }): SourceCardLoad => ({ data: { source, versions: versions ?? [] }, problem: null })),
        catchError((problem: ProblemDetail) => of<SourceCardLoad>({ data: shown, problem })),
      );
    },
  });

  /** Opens the source named by the route. */
  open(sourceId: string | null): void {
    this.sourceId.set(sourceId);
    this.reload();
  }

  /** Asks for the source again; a new source id loads by itself. */
  reload(): void {
    this.loaded.reload();
  }

  save(): void {
    const source = this.source();
    const id = this.sourceId();
    if (!source || !id || this.isSaving()) {
      return;
    }
    this.submitted.set(true);
    markSMTFormFieldsTouched(this.requisites);
    this.fieldErrors.set({});
    if (!this.requisites().valid()) {
      return;
    }
    const form = this.form();
    const contact = form.ownerContact.trim();
    const body: UplSourceRequest = {
      code: source.code,
      name: form.name.trim(),
      ownerOrg: form.ownerOrg.trim(),
      ownerContact: contact.length > 0 ? contact : null,
      periodicity: form.periodicity,
      slaDays: Number(form.slaDays),
      sourceType: 'file',
      reconciliationStrictness: form.reconciliationStrictness,
      lockVersion: source.lockVersion,
    };
    this.isSaving.set(true);
    this.saveError.set(null);
    this.api.updateSource(id, body).subscribe({
      next: (saved) => {
        this.isSaving.set(false);
        this.source.set(saved);
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
        if (problem?.messageKey === UPL_ERROR.draftExists) {
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

  private openDraftDialogFromQuery(): void {
    const wanted = this.route.snapshot?.queryParamMap?.get('newDraft') === '1';
    if (wanted && this.canEdit() && !this.draftVersion()) {
      this.openDraftDialog();
    }
  }

  private handleSaveError(problem: ProblemDetail): void {
    if (problem?.status === 409 && problem?.messageKey === UPL_ERROR.staleVersion) {
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
    return uplProblemText(problem, (key, params) => this.i18n.translate(key, params));
  }
}

function emptyForm(): SourceForm {
  return {
    name: '',
    ownerOrg: '',
    ownerContact: '',
    periodicity: 'month',
    slaDays: 0,
    reconciliationStrictness: 'error',
  };
}

/** The requisites of a source as the form edits them. */
function formOf(source: UplSource): SourceForm {
  return {
    name: source.name,
    ownerOrg: source.ownerOrg,
    ownerContact: source.ownerContact ?? '',
    periodicity: source.periodicity,
    slaDays: source.slaDays,
    reconciliationStrictness: source.reconciliationStrictness,
  };
}
