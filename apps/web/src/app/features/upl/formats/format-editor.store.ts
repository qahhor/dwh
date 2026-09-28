import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ProblemDetail } from '@core/models/common.models';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { UplApiService, UplFormatDraftRequest, UplFormatVersion, UplSource, UplUnit, UplVersionItem } from '../upl-api';
import { UPL_VERSION_STATUS_KEY, uplErrorKey, uplProblemText } from '../upl-labels';
import { UplFieldError, localFormatErrors, parseUplProblem } from './upl-format-errors';
import { UplFormatStep, buildDraftRequest, emptyModel, uplErrorStep } from './upl-format-model';

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
  readonly source = signal<UplSource | null>(null);
  readonly version = signal<UplFormatVersion | null>(null);
  readonly versions = signal<UplVersionItem[]>([]);
  readonly units = signal<UplUnit[]>([]);
  readonly isLoading = signal(true);
  readonly loadError = signal(false);
  readonly notFound = signal(false);
  readonly activeSheet = signal(0);
  readonly step = signal<UplFormatStep>('file');
  readonly errors = signal<UplFieldError[]>([]);
  readonly isSaving = signal(false);
  readonly isPublishing = signal(false);
  readonly conflict = signal(false);
  readonly actionError = signal<string | null>(null);
  readonly isPublishOpen = signal(false);
  readonly validFrom = signal('');
  readonly publishDateError = signal<string | null>(null);
  /**
   * The draft. The steps edit this one object in place (they redraw when shown);
   * the signal changes only when the whole draft is replaced on load, save or revert.
   */
  readonly model = signal<UplFormatDraftRequest>(emptyModel());
  private readonly savedSnapshot = signal(JSON.stringify(emptyModel()));

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
  readonly statusVariant = computed(() => {
    const status = this.version()?.status;
    if (status === 'draft') return 'info';
    if (status === 'published') return 'success';
    return 'neutral';
  });
  readonly previousValidFrom = computed(() => {
    const published = this.versions().filter((item) => item.status === 'published' && item.validFrom);
    if (published.length === 0) return null;
    return published.reduce((latest, item) => (item.version > latest.version ? item : latest)).validFrom;
  });

  /** Opens the version named by the route. */
  open(sourceId: string, versionNumber: string): void {
    this.sourceId.set(sourceId);
    this.versionNumber.set(versionNumber);
    this.reload();
  }

  reload(): void {
    this.isLoading.set(true);
    this.loadError.set(false);
    this.notFound.set(false);
    this.conflict.set(false);
    this.actionError.set(null);
    this.errors.set([]);
    forkJoin({
      source: this.api.getSource(this.sourceId()),
      version: this.api.getVersion(this.sourceId(), this.versionNumber()),
      versions: this.api.listVersions(this.sourceId()),
      units: this.api.listUnits(),
    }).subscribe({
      next: (loaded) => {
        this.source.set(loaded.source);
        this.versions.set(loaded.versions ?? []);
        this.units.set(loaded.units ?? []);
        this.version.set(loaded.version);
        this.resetModel(loaded.version);
        this.step.set(this.model().sheets.length > 0 ? 'sheets' : 'file');
        this.isLoading.set(false);
      },
      error: (problem: ProblemDetail) => {
        this.isLoading.set(false);
        if (problem?.status === 404) {
          this.notFound.set(true);
        } else {
          this.loadError.set(true);
        }
      },
    });
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
    this.resetModel(version);
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
        this.resetModel(saved);
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
        if (problem?.detail === 'FND_VERSION_NOT_AFTER_PREVIOUS') {
          this.publishDateError.set(uplErrorKey('FND_VERSION_NOT_AFTER_PREVIOUS'));
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

  private resetModel(version: UplFormatVersion): void {
    const model: UplFormatDraftRequest = {
      lockVersion: version.lockVersion,
      fileKind: version.fileKind ?? 'xlsx',
      encoding: version.encoding,
      delimiter: version.delimiter,
      matchColumnsBy: version.matchColumnsBy ?? 'header',
      sheets: structuredClone(version.sheets ?? []),
    };
    this.model.set(model);
    if (this.activeSheet() >= model.sheets.length) {
      this.activeSheet.set(Math.max(0, model.sheets.length - 1));
    }
    this.savedSnapshot.set(JSON.stringify(this.buildRequest()));
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
    if (problem?.detail === 'STALE_VERSION') {
      this.conflict.set(true);
      return;
    }
    if (problem?.detail === 'UPL_FORMAT_NOT_DRAFT') {
      this.toast.info(this.i18n.translate('upl.err.UPL_FORMAT_NOT_DRAFT'));
      this.reload();
      return;
    }
    this.actionError.set(uplProblemText(problem, (key) => this.i18n.translate(key)));
  }
}
