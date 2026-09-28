import { Injectable, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ProblemDetail } from '@core/models/common.models';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { UplApiService, UplPeriodicity, UplSource, UplSourceRequest, UplStrictness, UplVersionItem } from '../upl-api';
import { parseUplProblem, uplFieldErrorText } from '../formats/upl-format-errors';
import { uplProblemText } from '../upl-labels';

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

  /**
   * The requisites being edited. The fields edit this object in place; the
   * signal changes only when the whole form is refilled from the server.
   */
  readonly form = signal<SourceForm>({
    name: '',
    ownerOrg: '',
    ownerContact: '',
    periodicity: 'month',
    slaDays: 0,
    reconciliationStrictness: 'error',
  });

  readonly canEdit = computed(() => this.permissions.hasPermission('upl.sources', 'edit'));
  readonly draftVersion = computed(() => this.versions().find((v) => v.status === 'draft') ?? null);
  /** Versions a draft can be copied from, newest first. */
  readonly copyCandidates = computed(() =>
    this.versions()
      .filter((v) => v.status === 'published' || v.status === 'superseded')
      .sort((a, b) => b.version - a.version),
  );

  /** Opens the source named by the route. */
  open(sourceId: string | null): void {
    this.sourceId.set(sourceId);
    this.reload();
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

  save(): void {
    const source = this.source();
    const id = this.sourceId();
    if (!source || !id || this.isSaving()) {
      return;
    }
    if (!this.validate()) {
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
    this.form.set({
      name: source.name,
      ownerOrg: source.ownerOrg,
      ownerContact: source.ownerContact ?? '',
      periodicity: source.periodicity,
      slaDays: source.slaDays,
      reconciliationStrictness: source.reconciliationStrictness,
    });
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
    const form = this.form();
    const errors: Record<string, string> = {};
    const name = form.name.trim();
    if (name.length === 0) {
      errors['name'] = 'upl.source.err.required';
    } else if (name.length > 200) {
      errors['name'] = 'upl.source.err.name_length';
    }
    if (form.ownerOrg.trim().length === 0) {
      errors['ownerOrg'] = 'upl.source.err.required';
    }
    const sla = Number(form.slaDays);
    if (form.slaDays === null || !Number.isInteger(sla) || sla < 0 || sla > 366) {
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
