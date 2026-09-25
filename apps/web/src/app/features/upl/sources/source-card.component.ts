import { ChangeDetectionStrategy, Component, computed, inject, signal, Signal, TemplateRef, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ProblemDetail } from '../../../core/models/common.models';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { SMTControlComponent } from '../../../shared/ui-kit/components/forms/control';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiBadgeComponent } from '../../../shared/ui/ui-badge.component';
import { UiLocalTableComponent } from '../../../shared/ui/ui-local-table.component';
import { TableConfig } from '../../../shared/ui-kit/components/table/table.types';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import {
  UPL_PERIODICITIES,
  UPL_STRICTNESSES,
  UplApiService,
  UplPeriodicity,
  UplSource,
  UplSourceRequest,
  UplStrictness,
  UplVersionItem,
  UplVersionStatus
} from '../upl-api';
import { parseUplProblem, uplFieldErrorText } from '../formats/upl-format-errors';
import {
  UPL_PERIODICITY_KEY,
  UPL_STRICTNESS_KEY,
  UPL_VERSION_STATUS_KEY,
  uplProblemText
} from '../upl-labels';

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
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTControlComponent,
    UiLocalTableComponent,
    CommonModule,
    FormsModule,
    RouterLink,
    TranslatePipe,
    UiButtonComponent,
    UiModalComponent,
    UiBadgeComponent
  ],
  template: `
    @if (isLoading()) {
      <div class="upl-skeleton" aria-hidden="true">
        <span class="upl-skeleton-bar upl-skeleton-bar-wide"></span>
        <span class="upl-skeleton-bar"></span>
        <span class="upl-skeleton-bar"></span>
      </div>
    } @else if (notFound()) {
      <div class="upl-empty" data-testid="upl-not-found">
        <p>{{ 'upl.err.UPL_SOURCE_NOT_FOUND' | t }}</p>
        <a class="upl-crumb-link" routerLink="/upl/sources">{{ 'upl.card.back_to_list' | t }}</a>
      </div>
    } @else if (loadError()) {
      <div class="alert alert-error" role="alert" data-testid="upl-load-error">
        <span>{{ 'upl.err.LOAD_FAILED' | t }}</span>
        <ui-button variant="secondary" (onClick)="reload()">{{ 'upl.common.retry' | t }}</ui-button>
      </div>
    } @else if (source(); as s) {
      <nav class="upl-crumbs" aria-label="breadcrumb">
        <a class="upl-crumb-link" routerLink="/upl/sources">{{ 'upl.list.title' | t }}</a>
        <span class="upl-crumb-sep" aria-hidden="true">/</span>
        <span class="upl-crumb-current">{{ s.name }}</span>
      </nav>

      <header class="upl-head">
        <h1 class="upl-title">{{ s.name }}</h1>
        <code class="upl-code">{{ s.code }}</code>
        @if (s.lastPublishedVersion !== null) {
          <ui-badge variant="success">{{ activeVersionLabel(s) }}</ui-badge>
        }
        @if (s.hasDraft) {
          <ui-badge variant="info">{{ 'upl.list.has_draft' | t }}</ui-badge>
        }
      </header>

      <section class="upl-block">
        <h2 class="upl-block-title">{{ 'upl.card.requisites' | t }}</h2>

        @if (conflict()) {
          <div class="alert alert-error" role="alert" data-testid="upl-conflict">
            <span>{{ 'upl.err.STALE_VERSION' | t }}</span>
            <ui-button variant="secondary" data-testid="upl-conflict-refresh" (onClick)="refreshAfterConflict()">
              {{ 'upl.common.refresh_discard' | t }}
            </ui-button>
          </div>
        }
        @if (saveError(); as err) {
          <div class="alert alert-error" role="alert" data-testid="upl-save-error">{{ err | t }}</div>
        }

        <div class="upl-form">
          <div class="form-group">
            <span class="form-label">{{ 'upl.source.field.code' | t }}</span>
            <code class="upl-code">{{ s.code }}</code>
          </div>

          <smt-control class="form-group" [smtLabel]="'upl.source.field.name' | t" [smtError]="fieldErrorText('name')">
            <input
              id="upl-source-name"
              class="form-input"
              type="text"
              maxlength="200"
              data-testid="upl-field-name"
              [disabled]="!canEdit()"
              [(ngModel)]="form.name"
            />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'upl.source.field.owner_org' | t" [smtError]="fieldErrorText('ownerOrg')">
            <input
              id="upl-source-owner-org"
              class="form-input"
              type="text"
              maxlength="200"
              data-testid="upl-field-ownerOrg"
              [disabled]="!canEdit()"
              [(ngModel)]="form.ownerOrg"
            />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'upl.source.field.owner_contact' | t" [smtError]="fieldErrorText('ownerContact')">
            <input
              id="upl-source-owner-contact"
              class="form-input"
              type="text"
              maxlength="200"
              data-testid="upl-field-ownerContact"
              [disabled]="!canEdit()"
              [(ngModel)]="form.ownerContact"
            />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'upl.source.field.periodicity' | t">
            <select
              id="upl-source-periodicity"
              class="form-select"
              data-testid="upl-field-periodicity"
              [disabled]="!canEdit()"
              [(ngModel)]="form.periodicity"
            >
              @for (p of periodicities; track p) {
                <option [value]="p">{{ periodicityKey[p] | t }}</option>
              }
            </select>
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'upl.source.field.sla_days' | t" [smtError]="fieldErrorText('slaDays')">
            <input
              id="upl-source-sla-days"
              class="form-input"
              type="number"
              min="0"
              max="366"
              data-testid="upl-field-slaDays"
              [disabled]="!canEdit()"
              [(ngModel)]="form.slaDays"
            />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'upl.source.field.strictness' | t">
            <select
              id="upl-source-strictness"
              class="form-select"
              data-testid="upl-field-strictness"
              [disabled]="!canEdit()"
              [(ngModel)]="form.reconciliationStrictness"
            >
              @for (st of strictnesses; track st) {
                <option [value]="st">{{ strictnessKey[st] | t }}</option>
              }
            </select>
          </smt-control>
        </div>

        @if (canEdit()) {
          <div class="upl-actions">
            <ui-button data-testid="upl-save-source" [loading]="isSaving()" (onClick)="save()">
              {{ 'upl.common.save' | t }}
            </ui-button>
          </div>
        }
      </section>

      <section class="upl-block">
        <div class="upl-block-head">
          <h2 class="upl-block-title">{{ 'upl.version.title' | t }}</h2>
          @if (draftVersion(); as d) {
            <a
              class="upl-crumb-link"
              data-testid="upl-open-draft"
              [routerLink]="['/upl/sources', sourceId(), 'formats', d.version]"
            >{{ 'upl.version.open_draft' | t }}</a>
          } @else if (canEdit()) {
            <ui-button data-testid="upl-new-draft" (onClick)="openDraftDialog()">
              {{ 'upl.version.new_draft' | t }}
            </ui-button>
          }
        </div>

        @if (versions().length === 0) {
          <p class="upl-empty" data-testid="upl-versions-empty">{{ 'upl.version.empty' | t }}</p>
        } @else {
          <div class="table-card">
            <div class="table-scroll">
              <ui-local-table [rows]="versions()" [config]="versionsConfig()" [sortValues]="versionSortValues" />
            </div>
          </div>
        }
      </section>

      <ng-template #versionCell let-v>
        <a class="upl-crumb-link" data-testid="upl-version-row" [routerLink]="['/upl/sources', sourceId(), 'formats', v.version]">{{ v.version }}</a>
      </ng-template>
      <ng-template #versionStatusCell let-v>
        <ui-badge [variant]="statusVariant(v.status)">{{ statusKeyOf(v) | t }}</ui-badge>
      </ng-template>
      <ng-template #validFromCell let-v>{{ v.validFrom ? (v.validFrom | date: 'dd.MM.yyyy') : dash }}</ng-template>
      <ng-template #validToCell let-v>{{ v.validTo ? (v.validTo | date: 'dd.MM.yyyy') : dash }}</ng-template>
      <ng-template #publishedCell let-v>
        @if (v.publishedAt) {
          <span>{{ v.publishedBy }}</span>
          <span class="upl-muted">{{ v.publishedAt | date: 'dd.MM.yyyy HH:mm' }}</span>
        } @else {
          <span>{{ dash }}</span>
        }
      </ng-template>
      <ng-template #templateCell let-v>
        <a class="upl-crumb-link upl-template-link" data-testid="upl-version-template" [href]="templateUrl(v.version)" download
          [attr.aria-label]="'upl.template.download_named' | t: { version: v.version }">
          <span class="material-symbols-outlined" aria-hidden="true">download</span>{{ 'upl.template.download' | t }}
        </a>
      </ng-template>

      <ui-modal
        [isOpen]="isDraftOpen()"
        [title]="'upl.version.new_draft' | t"
        size="sm"
        [hasFooter]="true"
        (close)="closeDraftDialog()"
      >
        <div body class="upl-draft-body">
          <label class="upl-radio">
            <input
              type="radio"
              name="uplDraftMode"
              data-testid="upl-draft-mode-empty"
              [checked]="draftMode() === 'empty'"
              (change)="draftMode.set('empty')"
            />
            <span>{{ 'upl.version.draft_empty' | t }}</span>
          </label>

          @if (copyCandidates().length > 0) {
            <label class="upl-radio">
              <input
                type="radio"
                name="uplDraftMode"
                data-testid="upl-draft-mode-copy"
                [checked]="draftMode() === 'copy'"
                (change)="draftMode.set('copy')"
              />
              <span>{{ 'upl.version.draft_copy' | t }}</span>
            </label>
            <select
              class="form-select"
              data-testid="upl-draft-copy-from"
              [disabled]="draftMode() !== 'copy'"
              [ngModel]="copyFrom()"
              (ngModelChange)="copyFrom.set($event)"
            >
              @for (v of copyCandidates(); track v.version) {
                <option [ngValue]="v.version">{{ v.version }}</option>
              }
            </select>
          }

          @if (draftExists()) {
            <div class="alert alert-error" role="alert">
              <span>{{ 'upl.err.FND_VERSION_DRAFT_EXISTS' | t }}</span>
              <ui-button variant="secondary" data-testid="upl-open-existing-draft" (onClick)="openExistingDraft()">
                {{ 'upl.version.open' | t }}
              </ui-button>
            </div>
          }
          @if (draftError(); as err) {
            <div class="alert alert-error" role="alert" data-testid="upl-draft-error">{{ err | t }}</div>
          }
        </div>

        <div footer class="upl-actions">
          <ui-button variant="secondary" [disabled]="isCreatingDraft()" (onClick)="closeDraftDialog()">
            {{ 'upl.common.cancel' | t }}
          </ui-button>
          <ui-button data-testid="upl-create-draft" [loading]="isCreatingDraft()" (onClick)="createDraft()">
            {{ 'upl.common.create' | t }}
          </ui-button>
        </div>
      </ui-modal>
    }
  `,
  styles: [`
    .upl-crumbs {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 0.75rem;
      color: var(--text-muted);
    }
    .upl-crumb-link {
      color: var(--primary);
      text-decoration: none;
    }
    .upl-crumb-link:hover {
      text-decoration: underline;
    }
    .upl-crumb-sep {
      color: var(--text-light);
    }
    .upl-crumb-current {
      color: var(--text-main);
    }
    .upl-head {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.75rem;
      margin-bottom: 1.5rem;
    }
    .upl-title {
      margin: 0;
      font-size: 1.5rem;
      color: var(--text-main);
    }
    .upl-code {
      font-family: monospace;
      background: var(--bg-hover);
      border-radius: var(--radius-sm);
      padding: 0.125rem 0.5rem;
      color: var(--text-muted);
    }
    .upl-block {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      padding: 1.25rem;
      margin-bottom: 1.5rem;
    }
    .upl-block-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      margin-bottom: 1rem;
    }
    .upl-block-title {
      margin: 0 0 1rem;
      font-size: 1.125rem;
      color: var(--text-main);
    }
    .upl-block-head .upl-block-title {
      margin: 0;
    }
    .upl-form {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
      gap: 1rem;
    }
    .upl-field-error {
      display: block;
      margin-top: 0.25rem;
      color: var(--danger);
      font-size: 0.8125rem;
    }
    .upl-actions {
      display: flex;
      justify-content: flex-end;
      gap: 0.75rem;
      margin-top: 1.25rem;
    }
    .upl-empty {
      color: var(--text-muted);
      padding: 1.5rem 0;
      text-align: center;
    }
    .upl-muted {
      color: var(--text-muted);
      margin-left: 0.5rem;
    }
    .upl-draft-body {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }
    .upl-radio {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      color: var(--text-main);
    }
    .upl-skeleton {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
      padding: 1.5rem 0;
    }
    .upl-skeleton-bar {
      display: block;
      height: 1rem;
      border-radius: var(--radius-sm);
      background: var(--bg-hover);
    }
    .upl-skeleton-bar-wide {
      height: 2rem;
    }
  `]
})
export class SourceCardComponent {
  private readonly api = inject(UplApiService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly periodicities = UPL_PERIODICITIES;
  readonly strictnesses = UPL_STRICTNESSES;
  readonly periodicityKey = UPL_PERIODICITY_KEY;
  readonly strictnessKey = UPL_STRICTNESS_KEY;
  readonly versionStatusKey = UPL_VERSION_STATUS_KEY;
  readonly dash = '—';

  readonly sourceId = signal<string | null>(null);
  readonly source = signal<UplSource | null>(null);
  /** The translated message for a field's error code, or nothing; smt-control links it to the field. */
  fieldErrorText(key: string): string {
    const code = this.fieldErrors()[key];
    return code ? this.i18n.translate(code) : '';
  }

  readonly versions = signal<UplVersionItem[]>([]);

  private readonly versionCell = viewChild.required<TemplateRef<unknown>>('versionCell');
  private readonly versionStatusCell = viewChild.required<TemplateRef<unknown>>('versionStatusCell');
  private readonly validFromCell = viewChild.required<TemplateRef<unknown>>('validFromCell');
  private readonly validToCell = viewChild.required<TemplateRef<unknown>>('validToCell');
  private readonly publishedCell = viewChild.required<TemplateRef<unknown>>('publishedCell');
  private readonly templateCell = viewChild.required<TemplateRef<unknown>>('templateCell');

  /** All versions of a source are loaded, so a header click sorts them all. */
  readonly versionSortValues = {
    version: (v: UplVersionItem) => v.version,
    status: (v: UplVersionItem) => v.status,
    validFrom: (v: UplVersionItem) => v.validFrom,
    validTo: (v: UplVersionItem) => v.validTo,
    published: (v: UplVersionItem) => v.publishedAt
  };

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
        template: { header: header('upl.template.column'), content: cell(this.templateCell), width: '170px' }
      },
      columnsOrder: ['version', 'status', 'validFrom', 'validTo', 'published', 'template']
    };
  });

  /** The supplier's file for a version, in the reader's language; the browser downloads it with the session cookie. */
  templateUrl(version: number): string {
    const id = encodeURIComponent(this.sourceId() ?? '');
    const lang = encodeURIComponent(this.i18n.currentLang());
    return `/api/v1/upl/sources/${id}/format-versions/${version}/template?lang=${lang}`;
  }

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

  readonly canEdit = computed(() => this.permissions.hasPermission('upl.sources', 'edit'));
  readonly draftVersion = computed(() => this.versions().find(v => v.status === 'draft') ?? null);
  readonly copyCandidates = computed(() =>
    this.versions()
      .filter(v => v.status === 'published' || v.status === 'superseded')
      .sort((a, b) => b.version - a.version)
  );

  form: SourceForm = {
    name: '',
    ownerOrg: '',
    ownerContact: '',
    periodicity: 'month',
    slaDays: 0,
    reconciliationStrictness: 'error'
  };

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
      this.sourceId.set(params.get('id'));
      this.reload();
    });
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
      }
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
      lockVersion: source.lockVersion
    };
    this.isSaving.set(true);
    this.saveError.set(null);
    this.api.updateSource(id, body).subscribe({
      next: saved => {
        this.isSaving.set(false);
        this.source.set(saved);
        this.fillForm(saved);
        this.toast.success(this.i18n.translate('upl.source.saved'));
      },
      error: (problem: ProblemDetail) => {
        this.isSaving.set(false);
        this.handleSaveError(problem);
      }
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
    const published = candidates.find(v => v.status === 'published');
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
    const copyFrom = this.draftMode() === 'copy' ? this.copyFrom() ?? undefined : undefined;
    this.isCreatingDraft.set(true);
    this.draftError.set(null);
    this.draftExists.set(false);
    this.api.createDraft(id, copyFrom).subscribe({
      next: created => {
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
      }
    });
  }

  openExistingDraft(): void {
    const id = this.sourceId();
    if (!id) {
      return;
    }
    this.api.listVersions(id).subscribe({
      next: versions => {
        this.versions.set(versions ?? []);
        const draft = (versions ?? []).find(v => v.status === 'draft');
        if (!draft) {
          this.draftError.set(this.i18n.translate('upl.err.FND_VERSION_UNKNOWN'));
          return;
        }
        this.isDraftOpen.set(false);
        this.router.navigate(['/upl/sources', id, 'formats', draft.version]);
      },
      error: (problem: ProblemDetail) => this.draftError.set(this.problemText(problem))
    });
  }

  private fillForm(source: UplSource): void {
    this.form = {
      name: source.name,
      ownerOrg: source.ownerOrg,
      ownerContact: source.ownerContact ?? '',
      periodicity: source.periodicity,
      slaDays: source.slaDays,
      reconciliationStrictness: source.reconciliationStrictness
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
        errors[item.field] = uplFieldErrorText(item, key => this.i18n.translate(key));
      }
      this.fieldErrors.set(errors);
      this.saveError.set('upl.err.VALIDATION_FAILED');
      return;
    }
    this.saveError.set(this.problemText(problem));
  }

  private problemText(problem: ProblemDetail): string {
    return uplProblemText(problem, key => this.i18n.translate(key));
  }
}
