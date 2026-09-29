import { Injectable, ResourceRef, computed, inject, linkedSignal, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { ProblemDetail } from '@core/models/common.models';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { UplApiService, UplFormatDraftRequest, UplFormatVersion, UplSource, UplUnit, UplVersionItem } from '../upl-api';
import { UPL_ERROR, UPL_VERSION_STATUS_KEY, uplErrorKey, uplProblemText } from '../upl-labels';
import { UplFieldError, localFormatErrors, parseUplProblem } from './upl-format-errors';
import { UplFormatStep, buildDraftRequest, emptyModel, uplErrorStep } from './upl-format-model';
import { TBadgeVariant } from '@shared/ui-kit/components/badge/badge.component';

/** Everything one opening of a version needs. */
interface FormatEditorData {
  source: UplSource;
  version: UplFormatVersion;
  versions: UplVersionItem[];
  units: UplUnit[];
}

/**
 * What a load leaves on screen. A refusal is a value, so it never throws out of the resource, and it
 * keeps the data of the load before: a failed reload leaves the draft being edited as it was.
 */
interface FormatEditorLoad {
  data: FormatEditorData | null;
  problem: ProblemDetail | null;
}

/**
 * The draft built from a version, with the text it is compared with to tell unsaved edits. Both are
 * taken at once, so the snapshot never sees an edit made to the draft in place.
 */
interface FormatDraft {
  model: UplFormatDraftRequest;
  snapshot: string;
  sheets: number;
}

/**
 * One format version being edited: loading it, the draft, saving, publishing
 * and turning the server's refusals into addressed errors. Provided by the
 * editor screen, so it lives as long as the screen does.
 */
@Injectable()
export class FormatEditorStore {
  private readonly api = inject(UplApiService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly router = inject(Router);

  readonly sourceId = signal('');
  readonly versionNumber = signal('');
  /** The version as the server last gave it: loaded, or answered by a save. */
  readonly version = linkedSignal(() => this.data()?.version ?? null);
  /** A loaded version opens on its sheets when it has some, otherwise on the file settings. */
  readonly step = linkedSignal<UplFormatStep>(() =>
    (this.data()?.version.sheets ?? []).length > 0 ? 'sheets' : 'file',
  );
  /** Stays on a sheet that still exists when the draft is replaced. */
  readonly activeSheet = linkedSignal<FormatDraft, number>({
    source: () => this.draft(),
    computation: (draft, previous) => {
      const active = previous?.value ?? 0;
      return active >= draft.sheets ? Math.max(0, draft.sheets - 1) : active;
    },
  });
  readonly errors = signal<UplFieldError[]>([]);
  readonly isSaving = signal(false);
  readonly isPublishing = signal(false);
  readonly conflict = signal(false);
  readonly actionError = signal<string | null>(null);
  readonly isPublishOpen = signal(false);
  readonly validFrom = signal('');
  readonly publishDateError = signal<string | null>(null);
  /**
   * The draft. The steps edit its model in place (they redraw when shown); it is replaced when the
   * version is loaded or saved, and on revert.
   */
  private readonly draft = linkedSignal<UplFormatVersion | null, FormatDraft>({
    source: () => this.version(),
    computation: (version) => draftOf(version),
  });
  private readonly savedSnapshot = linkedSignal(() => this.draft().snapshot);
  private readonly opened = signal(false);

  readonly source = computed(() => this.data()?.source ?? null);
  readonly versions = computed(() => this.data()?.versions ?? []);
  readonly units = computed(() => this.data()?.units ?? []);
  readonly model = computed(() => this.draft().model);
  /** True until the first answer, and again while a reload is on its way. */
  readonly isLoading = computed(() => !this.opened() || this.loaded.isLoading());
  readonly notFound = computed(() => this.problem()?.status === 404);
  readonly loadError = computed(() => this.problem() !== null && !this.notFound());
  readonly canEdit = computed(() => this.permissions.hasPermission('upl.sources', 'edit'));
  readonly editable = computed(
    () => this.version()?.status === 'draft' && this.permissions.hasPermission('upl.sources', 'edit'),
  );
  readonly canPublish = computed(
    () => this.version()?.status === 'draft' && this.permissions.hasPermission('upl.sources', 'publish'),
  );
  readonly statusKey = computed(() => {
    const status = this.version()?.status;
    return status ? UPL_VERSION_STATUS_KEY[status] : '';
  });
  readonly statusVariant = computed<TBadgeVariant>(() => {
    const status = this.version()?.status;
    if (status === 'draft') return 'blue';
    if (status === 'published') return 'success';
    return 'gray';
  });
  readonly previousValidFrom = computed(() => {
    const published = this.versions().filter((item) => item.status === 'published' && item.validFrom);
    if (published.length === 0) return null;
    return published.reduce((latest, item) => (item.version > latest.version ? item : latest)).validFrom;
  });
  private readonly data = computed<FormatEditorData | null>(() => this.loaded.value()?.data ?? null);
  /** The refusal of the last load; a load that answers clears it. */
  private readonly problem = computed(() => this.loaded.value()?.problem ?? null);

  private readonly loaded: ResourceRef<FormatEditorLoad | undefined> = rxResource({
    params: () => (this.opened() ? { sourceId: this.sourceId(), versionNumber: this.versionNumber() } : undefined),
    stream: ({ params }) => {
      const shown: FormatEditorData | null = untracked(this.data);
      return forkJoin({
        source: this.api.getSource(params.sourceId),
        version: this.api.getVersion(params.sourceId, params.versionNumber),
        versions: this.api.listVersions(params.sourceId),
        units: this.api.listUnits(),
      }).pipe(
        map(({ source, version, versions, units }): FormatEditorLoad => ({
          data: { source, version, versions: versions ?? [], units: units ?? [] },
          problem: null,
        })),
        catchError((problem: ProblemDetail) => of<FormatEditorLoad>({ data: shown, problem })),
      );
    },
  });

  /** Opens the version named by the route. */
  open(sourceId: string, versionNumber: string): void {
    this.sourceId.set(sourceId);
    this.versionNumber.set(versionNumber);
    this.opened.set(true);
    this.reload();
  }

  /** Asks for the version again. New route parameters load by themselves; then this only clears what was said. */
  reload(): void {
    this.conflict.set(false);
    this.actionError.set(null);
    this.errors.set([]);
    this.loaded.reload();
  }

  discardAndReload(): void {
    this.conflict.set(false);
    this.reload();
  }

  buildRequest(): UplFormatDraftRequest {
    const model = this.model();
    return buildDraftRequest(model, this.version()?.lockVersion ?? model.lockVersion);
  }

  isDirty(): boolean {
    return this.editable() && JSON.stringify(this.buildRequest()) !== this.savedSnapshot();
  }

  revert(): void {
    const version = this.version();
    if (!version) return;
    this.draft.set(draftOf(version));
    this.errors.set([]);
    this.actionError.set(null);
  }

  save(onSaved?: () => void): void {
    if (this.isSaving()) return;
    const local = localFormatErrors(this.model());
    if (local.length > 0) {
      this.errors.set(local);
      this.showError(local[0]);
      return;
    }
    this.isSaving.set(true);
    this.actionError.set(null);
    this.api.saveDraft(this.sourceId(), this.versionNumber(), this.buildRequest()).subscribe({
      next: (saved) => {
        this.isSaving.set(false);
        this.version.set(saved);
        this.errors.set([]);
        this.toast.success(this.i18n.translate('upl.format.saved'));
        onSaved?.();
      },
      error: (problem: ProblemDetail) => {
        this.isSaving.set(false);
        this.handleProblem(problem);
      },
    });
  }

  openPublish(): void {
    if (this.isDirty()) {
      this.save(() => this.showPublishDialog());
      return;
    }
    this.showPublishDialog();
  }

  closePublish(): void {
    this.isPublishOpen.set(false);
  }

  confirmPublish(): void {
    if (this.isPublishing()) return;
    if (!this.validFrom()) {
      this.publishDateError.set(uplErrorKey('NotNull'));
      return;
    }
    this.isPublishing.set(true);
    this.publishDateError.set(null);
    this.actionError.set(null);
    this.api.publish(this.sourceId(), this.versionNumber(), this.validFrom()).subscribe({
      next: () => {
        this.isPublishing.set(false);
        this.isPublishOpen.set(false);
        this.savedSnapshot.set(JSON.stringify(this.buildRequest()));
        this.toast.success(this.i18n.translate('upl.version.published_toast', { version: this.versionNumber() }));
        this.router.navigate(['/upl/sources', this.sourceId()]);
      },
      error: (problem: ProblemDetail) => {
        this.isPublishing.set(false);
        if (problem?.messageKey === UPL_ERROR.notAfterPrevious) {
          this.publishDateError.set(UPL_ERROR.notAfterPrevious);
          return;
        }
        this.handleProblem(problem);
      },
    });
  }

  /** An error leads to its step and, when it belongs to a sheet, to that sheet's tab. */
  showError(problem: UplFieldError): void {
    this.step.set(uplErrorStep(problem));
    if (problem.sheet !== null && problem.sheet < this.model().sheets.length) {
      this.activeSheet.set(problem.sheet);
    }
  }

  private showPublishDialog(): void {
    this.publishDateError.set(null);
    this.validFrom.set(this.today());
    this.isPublishOpen.set(true);
  }

  private today(): string {
    const now = new Date();
    const pad = (value: number) => String(value).padStart(2, '0');
    return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  }

  private handleProblem(problem: ProblemDetail): void {
    if (problem?.status === 422) {
      const parsed = parseUplProblem(problem);
      this.errors.set(parsed);
      this.isPublishOpen.set(false);
      const addressed = parsed.find((item) => item.sheet !== null) ?? parsed[0];
      if (addressed) {
        this.showError(addressed);
      }
      return;
    }
    if (problem?.messageKey === UPL_ERROR.staleVersion) {
      this.conflict.set(true);
      return;
    }
    if (problem?.messageKey === UPL_ERROR.formatNotDraft) {
      this.toast.info(this.problemText(problem));
      this.reload();
      return;
    }
    this.actionError.set(this.problemText(problem));
  }

  private problemText(problem: ProblemDetail): string {
    return uplProblemText(problem, (key, params) => this.i18n.translate(key, params));
  }
}

/** The draft of a version: a deep copy the steps may edit, and the request it gives unedited. */
function draftOf(version: UplFormatVersion | null): FormatDraft {
  if (!version) {
    const model = emptyModel();
    return { model, snapshot: JSON.stringify(model), sheets: 0 };
  }
  const model: UplFormatDraftRequest = {
    lockVersion: version.lockVersion,
    fileKind: version.fileKind ?? 'xlsx',
    encoding: version.encoding,
    delimiter: version.delimiter,
    matchColumnsBy: version.matchColumnsBy ?? 'header',
    sheets: structuredClone(version.sheets ?? []),
  };
  return {
    model,
    snapshot: JSON.stringify(buildDraftRequest(model, version.lockVersion)),
    sheets: model.sheets.length,
  };
}
