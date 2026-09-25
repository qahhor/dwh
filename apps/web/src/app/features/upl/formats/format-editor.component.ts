import { ChangeDetectionStrategy, ChangeDetectorRef, Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, forkJoin } from 'rxjs';
import { FieldErrorItem, ProblemDetail } from '../../../core/models/common.models';
import { RecordNavigationDecision, RecordNavigationPage } from '../../../core/guards/record-navigation.guard';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiBadgeComponent } from '../../../shared/ui/ui-badge.component';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { SMTDatePickerComponent, SMTDatePickerValueAccessor } from '../../../shared/ui-kit/components/forms/date-picker';
import { SMTProgressStep, SMTProgressStepperComponent } from '../../../shared/ui-kit/components/progress-stepper';
import {
  UplApiService,
  UplFileKind,
  UplFormatDraftRequest,
  UplFormatVersion,
  UplSource,
  UplUnit,
  UplVersionItem
} from '../upl-api';
import { UPL_FILE_KIND_KEY, UPL_VERSION_STATUS_KEY, uplErrorKey, uplProblemText } from '../upl-labels';
import { FormatFileStepComponent } from './format-file-step.component';
import { FormatPublishStepComponent } from './format-publish-step.component';
import { FormatSheetsStepComponent } from './format-sheets-step.component';
import { UplFieldError, parseUplFieldErrors, parseUplProblem, uplFieldErrorText } from './upl-format-errors';
import { UplFormatStep, emptyModel, trimToNull, uplErrorStep } from './upl-format-model';
import { SMTAlertComponent } from '../../../shared/ui-kit/components/alert';

const TARGET_FIELD_PATTERN = /^[a-z][a-z0-9_]{0,62}$/;

/** Лист поля ошибки (`errors[].field` → хвост после `columns[n].`) → ключ заголовка колонки в таблице (М-21). */
const COLUMN_FIELD_LABEL_KEY: Record<string, string> = {
  nameInFile: 'upl.format.col.name_in_file',
  targetField: 'upl.format.col.target_field',
  dataType: 'upl.format.col.type',
  required: 'upl.format.col.required',
  sourceUnit: 'upl.format.col.source_unit',
  baseUnit: 'upl.format.col.base_unit',
  keyMask: 'upl.format.col.key_mask',
  keyPadLength: 'upl.format.col.key_pad_length',
  keyPadMax: 'upl.format.col.key_pad_max',
  refBookCode: 'upl.format.col.ref_book',
  filePosition: 'upl.format.col.file_position'
};

@Component({
  selector: 'app-upl-format-editor',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTAlertComponent, CommonModule, FormsModule, RouterLink, TranslatePipe, UiButtonComponent, UiModalComponent, UiBadgeComponent,
    SMTDatePickerComponent, SMTDatePickerValueAccessor, SMTProgressStepperComponent,
    FormatFileStepComponent, FormatSheetsStepComponent, FormatPublishStepComponent,
  ],
  template: `
    <div class="upl-editor">
      @if (isLoading()) {
        <p class="upl-muted">{{ 'upl.common.loading' | t }}</p>
      } @else if (notFound()) {
        <smt-alert smtTone="danger" data-testid="upl-not-found">
          <span>{{ 'upl.err.UPL_SOURCE_NOT_FOUND' | t }}</span>
          <a routerLink="/upl/sources">{{ 'upl.list.title' | t }}</a>
        </smt-alert>
      } @else if (loadError()) {
        <smt-alert smtTone="danger" data-testid="upl-load-error">
          <span>{{ 'upl.common.load_error' | t }}</span>
          <ui-button variant="secondary" size="sm" (onClick)="reload()">{{ 'upl.common.retry' | t }}</ui-button>
        </smt-alert>
      } @else {
        <nav class="upl-crumbs" [attr.aria-label]="'upl.format.crumbs' | t">
          <a routerLink="/upl/sources">{{ 'upl.list.title' | t }}</a>
          <span aria-hidden="true">›</span>
          <a [routerLink]="['/upl/sources', sourceId]">{{ source()?.name }}</a>
          <span aria-hidden="true">›</span>
          <span>{{ text('upl.format.crumb_version', { version: versionNumber }) }}</span>
        </nav>

        <div class="upl-head">
          <h1 class="upl-title">{{ text('upl.format.title', { name: source()?.name ?? '', version: versionNumber }) }}</h1>
          <ui-badge [variant]="statusVariant()">{{ statusKey() | t }}</ui-badge>
        </div>

        @if (version()?.status !== 'draft') {
          <p class="upl-note" data-testid="upl-readonly-note">
            <span>{{ 'upl.format.readonly_note' | t }}</span>
            @if (canEdit() && source() && !source()!.hasDraft) {
              <a [routerLink]="['/upl/sources', sourceId]" [queryParams]="{ newDraft: 1 }">{{ 'upl.version.new_draft' | t }}</a>
            }
          </p>
        }

        @if (conflict()) {
          <smt-alert smtTone="danger" data-testid="upl-conflict">
            <span>{{ 'upl.err.STALE_VERSION' | t }}</span>
            <ui-button variant="secondary" size="sm" (onClick)="discardAndReload()">{{ 'upl.common.refresh_discard' | t }}</ui-button>
          </smt-alert>
        }

        @if (actionError(); as problem) {
          <smt-alert smtTone="danger" data-testid="upl-action-error">{{ problem }}</smt-alert>
        }

        <smt-progress-stepper
          data-testid="upl-steps"
          [smtLabel]="'upl.format.steps' | t"
          [smtSteps]="steps()"
          [(smtCurrent)]="step"
        />

        @if (errors().length > 0) {
          <smt-alert smtTone="danger" data-testid="upl-errors-summary">
            <p class="upl-errors-title">{{ 'upl.format.errors_title' | t }}</p>
            <ul class="upl-errors-list">
              @for (problem of errors(); track $index) {
                <li>
                  <button type="button" class="upl-error-item" (click)="focusError(problem)">
                    @if (problem.sheet !== null) {
                      <span class="upl-error-at">{{ errorAddress(problem) }}</span>
                    }
                    <span>{{ errorText(problem) }}</span>
                  </button>
                </li>
              }
            </ul>
          </smt-alert>
        }

        <section class="upl-block" id="upl-step-file" data-testid="upl-step-file" [hidden]="step() !== 'file'">
          <app-upl-format-file-step [model]="model" [editable]="editable()" />
        </section>

        <section class="upl-block" id="upl-step-sheets" data-testid="upl-step-sheets" [hidden]="step() !== 'sheets'">
          <app-upl-format-sheets-step
            [model]="model"
            [editable]="editable()"
            [units]="units()"
            [(activeSheet)]="activeSheet"
            [(errors)]="errors"
          />
        </section>

        <section class="upl-block" id="upl-step-publish" data-testid="upl-step-publish" [hidden]="step() !== 'publish'">
          <app-upl-format-publish-step
            [version]="version()"
            [model]="model"
            [errorCount]="errors().length"
            [dirty]="isDirty()"
            [previousValidFrom]="previousValidFrom()"
          />
        </section>

        @if (version()?.status === 'draft' && (canEdit() || canPublish())) {
          <div class="upl-actions" data-testid="upl-actions">
            @if (canEdit()) {
              <ui-button
                variant="primary"
                data-testid="upl-save"
                [loading]="isSaving()"
                [disabled]="!isDirty()"
                (onClick)="save()"
              >{{ 'upl.format.save' | t }}</ui-button>
            }
            @if (canPublish()) {
              <ui-button variant="secondary" data-testid="upl-publish" (onClick)="openPublish()">
                {{ 'upl.format.publish' | t }}
              </ui-button>
            }
            @if (canEdit()) {
              <ui-button variant="ghost" data-testid="upl-revert" [disabled]="!isDirty()" (onClick)="revert()">
                {{ 'upl.format.revert' | t }}
              </ui-button>
            }
          </div>
        }


        <ui-modal [isOpen]="isPublishOpen()" [title]="'upl.version.publish_title' | t" size="sm" (close)="closePublish()">
          <div body>
            <div class="form-group">
              <label class="form-label" for="upl-valid-from">{{ 'upl.version.valid_from' | t }}</label>
              <smt-date-picker
                smtInputId="upl-valid-from"
                data-testid="upl-valid-from"
                [ngModel]="validFrom()"
                [ngModelOptions]="{ standalone: true }"
                (ngModelChange)="validFrom.set($event ?? '')"
              />
              @if (previousValidFrom(); as previous) {
                <span class="upl-hint">{{ text('upl.version.prev_valid_from', { date: previous }) }}</span>
              }
              @if (publishDateError(); as problem) {
                <span class="upl-field-error" data-testid="upl-publish-date-error">{{ problem | t }}</span>
              }
            </div>
            <p>{{ 'upl.version.publish_note' | t }}</p>
          </div>
          <div footer class="upl-modal-actions">
            <ui-button variant="secondary" (onClick)="closePublish()">{{ 'upl.common.cancel' | t }}</ui-button>
            <ui-button
              variant="primary"
              data-testid="upl-publish-confirm"
              [loading]="isPublishing()"
              (onClick)="confirmPublish()"
            >{{ 'upl.format.publish' | t }}</ui-button>
          </div>
        </ui-modal>

        <ui-modal [isOpen]="isLeaveOpen()" [title]="'upl.format.leave_title' | t" size="sm" (close)="settleLeave(false)">
          <p body>{{ 'upl.format.leave_confirm' | t }}</p>
          <div footer class="upl-modal-actions">
            <ui-button variant="secondary" (onClick)="settleLeave(false)">{{ 'upl.format.stay' | t }}</ui-button>
            <ui-button variant="danger" data-testid="upl-leave-confirm" (onClick)="settleLeave(true)">
              {{ 'upl.format.leave' | t }}
            </ui-button>
          </div>
        </ui-modal>
      }
    </div>
  `,
  styles: [`
    .upl-editor { display: flex; flex-direction: column; gap: 1rem; padding-bottom: 5rem; }
    .upl-muted { color: var(--text-muted); }
    .upl-crumbs { display: flex; align-items: center; gap: 0.5rem; color: var(--text-muted); font-size: 0.875rem; }
    .upl-crumbs a { color: var(--primary); text-decoration: none; }
    .upl-head { display: flex; align-items: center; gap: 0.75rem; }
    .upl-title { margin: 0; color: var(--text-main); font-size: 1.25rem; }
    .upl-note { display: flex; gap: 0.5rem; color: var(--text-muted); margin: 0; }
    .upl-note a { color: var(--primary); }
    .upl-block { display: flex; flex-direction: column; gap: 0.75rem; background: var(--bg-surface); border: 1px solid var(--border-color); border-radius: var(--radius-md); padding: 1rem; }
    .upl-block[hidden] { display: none; }
    .upl-hint { color: var(--text-light); font-size: 0.75rem; }
    .upl-field-error { color: var(--danger); font-size: 0.75rem; }
    .upl-errors-title { margin: 0 0 0.375rem; font-weight: 600; }
    .upl-errors-list { margin: 0; padding-left: 1rem; }
    .upl-error-item { display: inline-flex; gap: 0.375rem; padding: 0; background: none; border: none; color: inherit; text-align: left; cursor: pointer; }
    .upl-error-at { color: var(--text-muted); }
    .upl-actions { position: sticky; bottom: 0; display: flex; gap: 0.5rem; padding: 0.75rem 1rem; background: var(--bg-surface); border-top: 1px solid var(--border-color); border-radius: var(--radius-md) var(--radius-md) 0 0; }
    .upl-modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; }
  `]
})
export class FormatEditorComponent implements RecordNavigationPage {
  private readonly api = inject(UplApiService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly cdr = inject(ChangeDetectorRef);

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
  readonly isLeaveOpen = signal(false);

  readonly canEdit = computed(() => this.permissions.hasPermission('upl.sources', 'edit'));
  readonly editable = computed(() => this.version()?.status === 'draft' && this.permissions.hasPermission('upl.sources', 'edit'));
  readonly canPublish = computed(() => this.version()?.status === 'draft' && this.permissions.hasPermission('upl.sources', 'publish'));
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
    const published = this.versions().filter(item => item.status === 'published' && item.validFrom);
    if (published.length === 0) return null;
    return published.reduce((latest, item) => (item.version > latest.version ? item : latest)).validFrom;
  });

  private readonly navigationDecision = new RecordNavigationDecision();

  sourceId = '';
  versionNumber = '';
  model: UplFormatDraftRequest = emptyModel();
  private savedSnapshot = JSON.stringify(emptyModel());

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
      this.sourceId = params.get('id') ?? '';
      this.versionNumber = params.get('v') ?? '';
      this.reload();
    });
  }

  /** Шаги анкеты со статусом: «есть ошибки» — по адресам ошибок, «готово» — по заполненности. */
  steps(): SMTProgressStep[] {
    const errors = this.errors();
    const fileErrors = errors.filter(item => uplErrorStep(item) === 'file').length;
    const sheetErrors = errors.length - fileErrors;
    const sheets = this.model.sheets;
    const columns = sheets.reduce((total, sheet) => total + sheet.columns.length, 0);
    const sheetsFilled = sheets.length > 0 && sheets.every(sheet => sheet.columns.length > 0);
    const status = this.version()?.status;
    const errorHint = (count: number) => this.text('upl.format.step.errors', { count: count.toString() });
    return [
      {
        id: 'file',
        label: this.text('upl.format.step.file'),
        controls: 'upl-step-file',
        status: fileErrors > 0 ? 'error' : 'complete',
        hint: fileErrors > 0 ? errorHint(fileErrors) : this.text(UPL_FILE_KIND_KEY[this.model.fileKind ?? 'xlsx'])
      },
      {
        id: 'sheets',
        label: this.text('upl.format.step.sheets'),
        controls: 'upl-step-sheets',
        status: sheetErrors > 0 ? 'error' : sheetsFilled ? 'complete' : 'none',
        hint: sheetErrors > 0
          ? errorHint(sheetErrors)
          : this.text('upl.format.step.sheets_hint', { sheets: sheets.length.toString(), columns: columns.toString() })
      },
      {
        id: 'publish',
        label: this.text('upl.format.step.publish'),
        controls: 'upl-step-publish',
        status: status === 'draft' || !status ? 'none' : 'complete',
        hint: this.statusKey() ? this.text(this.statusKey()) : undefined
      }
    ];
  }

  text(key: string, params?: Record<string, string>): string {
    return this.i18n.translate(key, params);
  }

  reload(): void {
    this.isLoading.set(true);
    this.loadError.set(false);
    this.notFound.set(false);
    this.conflict.set(false);
    this.actionError.set(null);
    this.errors.set([]);
    forkJoin({
      source: this.api.getSource(this.sourceId),
      version: this.api.getVersion(this.sourceId, this.versionNumber),
      versions: this.api.listVersions(this.sourceId),
      units: this.api.listUnits()
    }).subscribe({
      next: loaded => {
        this.source.set(loaded.source);
        this.versions.set(loaded.versions ?? []);
        this.units.set(loaded.units ?? []);
        this.version.set(loaded.version);
        this.resetModel(loaded.version);
        this.step.set(this.model.sheets.length > 0 ? 'sheets' : 'file');
        this.isLoading.set(false);
        this.cdr.markForCheck();
      },
      error: (problem: ProblemDetail) => {
        this.isLoading.set(false);
        if (problem?.status === 404) {
          this.notFound.set(true);
        } else {
          this.loadError.set(true);
        }
        this.cdr.markForCheck();
      }
    });
  }

  discardAndReload(): void {
    this.conflict.set(false);
    this.reload();
  }

  buildRequest(): UplFormatDraftRequest {
    const fileKind: UplFileKind = this.model.fileKind ?? 'xlsx';
    const csv = fileKind === 'csv';
    return {
      lockVersion: this.version()?.lockVersion ?? this.model.lockVersion,
      fileKind,
      encoding: csv ? this.model.encoding : null,
      delimiter: csv ? trimToNull(this.model.delimiter) : null,
      matchColumnsBy: this.model.matchColumnsBy ?? 'header',
      sheets: this.model.sheets.map((sheet, index) => ({
        id: sheet.id,
        ordinal: index + 1,
        sheetName: csv ? null : trimToNull(sheet.sheetName),
        headerRow: sheet.headerRow,
        totalRowMarker: trimToNull(sheet.totalRowMarker),
        columns: sheet.columns.map((column, columnIndex) => ({
          id: column.id,
          ordinal: columnIndex + 1,
          filePosition: column.filePosition,
          nameInFile: (column.nameInFile ?? '').trim(),
          targetField: (column.targetField ?? '').trim(),
          dataType: column.dataType,
          required: column.required,
          sourceUnit: trimToNull(column.sourceUnit),
          baseUnit: trimToNull(column.baseUnit),
          keyMask: trimToNull(column.keyMask),
          keyPadLength: column.keyPadLength,
          keyPadMax: column.keyPadMax,
          refBookCode: trimToNull(column.refBookCode),
          headerSynonyms: (column.headerSynonyms ?? []).map(name => name.trim()).filter(name => name.length > 0)
        }))
      }))
    };
  }

  isDirty(): boolean {
    return this.editable() && JSON.stringify(this.buildRequest()) !== this.savedSnapshot;
  }

  /** Адрес ошибки в сводке: с именем поля, если оно известно по заголовку таблицы (М-21). */
  errorAddress(problem: UplFieldError): string {
    if (problem.sheet === null) return '';
    const sheet = (problem.sheet + 1).toString();
    if (problem.column === null) return this.text('upl.format.err_at_sheet', { sheet });
    const column = (problem.column + 1).toString();
    const labelKey = COLUMN_FIELD_LABEL_KEY[problem.field];
    return labelKey
      ? this.text('upl.format.err_at_field', { sheet, column, field: this.i18n.translate(labelKey) })
      : this.text('upl.format.err_at_column', { sheet, column });
  }

  /** Неизвестный код не прячем: показываем сообщение сервера и сам код. */
  errorText(problem: UplFieldError): string {
    return uplFieldErrorText(problem, key => this.i18n.translate(key));
  }

  /** Ошибка в сводке ведёт на свой шаг и, если она у листа, на его вкладку. */
  focusError(problem: UplFieldError): void {
    this.showError(problem);
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
    const local = this.localErrors();
    if (local.length > 0) {
      this.errors.set(local);
      this.showError(local[0]);
      return;
    }
    this.isSaving.set(true);
    this.actionError.set(null);
    this.api.saveDraft(this.sourceId, this.versionNumber, this.buildRequest()).subscribe({
      next: saved => {
        this.isSaving.set(false);
        this.version.set(saved);
        this.resetModel(saved);
        this.errors.set([]);
        this.toast.success(this.i18n.translate('upl.format.saved'));
        this.cdr.markForCheck();
        onSaved?.();
      },
      error: (problem: ProblemDetail) => {
        this.isSaving.set(false);
        this.handleProblem(problem);
        this.cdr.markForCheck();
      }
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
    this.api.publish(this.sourceId, this.versionNumber, this.validFrom()).subscribe({
      next: () => {
        this.isPublishing.set(false);
        this.isPublishOpen.set(false);
        this.savedSnapshot = JSON.stringify(this.buildRequest());
        this.toast.success(this.i18n.translate('upl.version.published_toast', { version: this.versionNumber }));
        this.cdr.markForCheck();
        this.router.navigate(['/upl/sources', this.sourceId]);
      },
      error: (problem: ProblemDetail) => {
        this.isPublishing.set(false);
        if (problem?.detail === 'FND_VERSION_NOT_AFTER_PREVIOUS') {
          this.publishDateError.set(uplErrorKey('FND_VERSION_NOT_AFTER_PREVIOUS'));
          this.cdr.markForCheck();
          return;
        }
        this.handleProblem(problem);
        this.cdr.markForCheck();
      }
    });
  }

  canLeaveRecordPage(): boolean | Observable<boolean> {
    if (!this.isDirty()) return true;
    return this.navigationDecision.request(
      () => { this.isLeaveOpen.set(true); this.cdr.markForCheck(); },
      () => { this.isLeaveOpen.set(false); this.cdr.markForCheck(); }
    );
  }

  settleLeave(allow: boolean): void {
    this.isLeaveOpen.set(false);
    this.navigationDecision.settle(allow);
  }

  /** Проверка до отправки — то, что сервер отверг бы кодами Bean Validation; коды те же, текст — из словаря. */
  private localErrors(): UplFieldError[] {
    const found: FieldErrorItem[] = [];
    this.model.sheets.forEach((sheet, s) => {
      const row = Number(sheet.headerRow);
      if (sheet.headerRow === null || !Number.isInteger(row) || row < 1) {
        found.push({ field: `sheets[${s}].headerRow`, code: 'Min', message: '' });
      }
      sheet.columns.forEach((column, c) => {
        if ((column.nameInFile ?? '').trim().length === 0) {
          found.push({ field: `sheets[${s}].columns[${c}].nameInFile`, code: 'NotBlank', message: '' });
        }
        const target = (column.targetField ?? '').trim();
        if (target.length === 0) {
          found.push({ field: `sheets[${s}].columns[${c}].targetField`, code: 'NotBlank', message: '' });
        } else if (!TARGET_FIELD_PATTERN.test(target)) {
          found.push({ field: `sheets[${s}].columns[${c}].targetField`, code: 'Pattern', message: '' });
        }
      });
    });
    return parseUplFieldErrors(found);
  }

  private showError(problem: UplFieldError): void {
    this.step.set(uplErrorStep(problem));
    if (problem.sheet !== null && problem.sheet < this.model.sheets.length) {
      this.activeSheet.set(problem.sheet);
    }
  }

  private showPublishDialog(): void {
    this.publishDateError.set(null);
    this.validFrom.set(this.today());
    this.isPublishOpen.set(true);
    this.cdr.markForCheck();
  }

  private today(): string {
    const now = new Date();
    const pad = (value: number) => String(value).padStart(2, '0');
    return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  }

  private resetModel(version: UplFormatVersion): void {
    this.model = {
      lockVersion: version.lockVersion,
      fileKind: version.fileKind ?? 'xlsx',
      encoding: version.encoding,
      delimiter: version.delimiter,
      matchColumnsBy: version.matchColumnsBy ?? 'header',
      sheets: structuredClone(version.sheets ?? [])
    };
    if (this.activeSheet() >= this.model.sheets.length) {
      this.activeSheet.set(Math.max(0, this.model.sheets.length - 1));
    }
    this.savedSnapshot = JSON.stringify(this.buildRequest());
  }

  private handleProblem(problem: ProblemDetail): void {
    if (problem?.status === 422) {
      const parsed = parseUplProblem(problem);
      this.errors.set(parsed);
      this.isPublishOpen.set(false);
      const addressed = parsed.find(item => item.sheet !== null) ?? parsed[0];
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
    this.actionError.set(this.problemText(problem));
  }

  private problemText(problem: ProblemDetail): string {
    return uplProblemText(problem, key => this.i18n.translate(key));
  }
}
