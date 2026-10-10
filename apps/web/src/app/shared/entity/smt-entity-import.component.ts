import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, switchMap, takeWhile, timer } from 'rxjs';
import type { FormMeta } from '@core/models/form-meta.models';
import { canDo, hasCapability } from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { problemText } from '../ui/problem-text';
import { SMTButtonComponent } from '../ui-kit/components/button';
import { UiFormActionsComponent } from '../ui/ui-form-actions.component';
import { SMTDropzoneComponent } from '../ui-kit/components/dropzone';
import { SMTDialogComponent, SMTDialogContentDirective } from '../ui-kit/components/modal';
import { EntitiesApi } from './entities.api';
import { EntityImport, EntityImportMode, EntityImportsApi, importRunning } from './entity-imports.api';

/** How often a running import is asked for its state, in milliseconds. */
export const IMPORT_POLL_MS = 1000;

/** The text of each problem of a whole file (`errorCode` of the import). */
const FAILURE_KEYS: Record<string, string> = {
  IMPORT_UNREADABLE: 'ui.entity_import.error_unreadable',
  IMPORT_STRUCTURE: 'ui.entity_import.error_structure',
  IMPORT_EMPTY: 'ui.entity_import.error_empty',
  IMPORT_TOO_MANY_ROWS: 'ui.entity_import.error_too_many_rows',
  IMPORT_FORBIDDEN: 'ui.entity_import.error_forbidden',
  IMPORT_FAILED: 'ui.entity_import.error_failed',
};

/** The largest file an import takes: the files module's limit (50 MB). */
const MAX_BYTES = 50 * 1024 * 1024;

/**
 * The import of an entity's records from a file (ADR-0032 10.1), offered in the list's toolbar when the entity declares
 * IMPORT and the viewer holds its right (`form-meta` names `import` among the actions): the template to fill, the file
 * uploaded to the files module, a dry run that shows each refused row without writing, the load itself, its progress,
 * and the report of the refused rows. The server checks every row as a save of the runtime.
 */
@Component({
  selector: 'smt-entity-import',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    SMTDropzoneComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    UiFormActionsComponent,
  ],
  host: { class: 'smt-entity-import' },
  template: `
    @if (offered()) {
      <button
        smt-button
        type="button"
        smtVariant="secondary"
        smtSize="sm"
        smtIcon="upload_file"
        data-testid="entity-import"
        (click)="open.set(true)"
      >
        {{ 'ui.entity_import.open' | t }}
      </button>
      <smt-dialog [open]="open()" [smtTitle]="'ui.entity_import.title' | t" smtSize="lg" (closed)="close()">
        <ng-template smtDialogContent>
          <div class="entity-import" data-testid="entity-import-dialog">
            <p class="entity-import-hint">{{ 'ui.entity_import.hint' | t }}</p>
            <a
              smt-button
              smtVariant="ghost"
              smtSize="sm"
              smtIcon="download"
              data-testid="entity-import-template"
              [attr.href]="templateUrl()"
              download
            >
              {{ 'ui.entity_import.template' | t }}
            </a>
            <smt-dropzone
              smtAccept=".xlsx"
              [smtMaxBytes]="maxBytes"
              [smtHint]="'ui.entity_import.file_hint' | t"
              [disabled]="busy()"
              (filesSelected)="upload($event)"
            />
            @if (fileName(); as name) {
              <p class="entity-import-file" data-testid="entity-import-file">
                {{ 'ui.entity_import.file' | t: { name } }}
              </p>
            }
            @if (problem(); as text) {
              <p class="entity-import-problem" role="alert">{{ text }}</p>
            }
            @if (current(); as item) {
              <section class="entity-import-result" data-testid="entity-import-result" aria-live="polite">
                @if (running()) {
                  <p>{{ stateKey() | t }}</p>
                  <div
                    class="entity-import-progress"
                    role="progressbar"
                    [attr.aria-label]="'ui.entity_import.progress_label' | t"
                    aria-valuemin="0"
                    [attr.aria-valuemax]="item.rowsTotal ?? 0"
                    [attr.aria-valuenow]="item.rowsDone ?? 0"
                  >
                    <div class="entity-import-progress-fill" [style.width.%]="percent()"></div>
                  </div>
                  <p>
                    {{ 'ui.entity_import.progress' | t: { done: item.rowsDone ?? 0, total: item.rowsTotal ?? '…' } }}
                  </p>
                } @else if (item.state === 'failed') {
                  <p class="entity-import-problem" role="alert" data-testid="entity-import-failed">
                    {{ failure() }}
                  </p>
                } @else {
                  <p data-testid="entity-import-summary">
                    {{
                      (item.mode === 'dry_run' ? 'ui.entity_import.summary_dry_run' : 'ui.entity_import.summary_apply')
                        | t: { created: item.created ?? 0, updated: item.updated ?? 0, failed: item.failed ?? 0 }
                    }}
                  </p>
                }
                @if ((item.errors ?? []).length > 0) {
                  <div class="entity-import-scroll">
                    <table class="entity-import-errors" data-testid="entity-import-errors">
                      <caption>
                        {{
                          'ui.entity_import.errors_title' | t
                        }}
                      </caption>
                      <thead>
                        <tr>
                          <th scope="col">{{ 'ui.entity_import.col_row' | t }}</th>
                          <th scope="col">{{ 'ui.entity_import.col_field' | t }}</th>
                          <th scope="col">{{ 'ui.entity_import.col_message' | t }}</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (error of item.errors; track $index) {
                          <tr>
                            <td>{{ error.row }}</td>
                            <td>{{ fieldOf(error.field) }}</td>
                            <td>{{ error.message }}</td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                }
                @if (item.report && item.id) {
                  <a
                    smt-button
                    smtVariant="ghost"
                    smtSize="sm"
                    smtIcon="download"
                    data-testid="entity-import-report"
                    [attr.href]="reportUrl(item.id)"
                    download
                  >
                    {{ 'ui.entity_import.report' | t }}
                  </a>
                }
              </section>
            }
          </div>
          <!-- Nothing to start before a file is uploaded: that is no field error, so the buttons wait for it. -->
          <ui-form-actions
            footer
            data-testid="entity-import-actions"
            [cancelLabel]="'common.close' | t"
            [submitLabel]="'ui.entity_import.apply' | t"
            [submitDisabled]="!fileId() || busy()"
            (cancelled)="close()"
            (submitted)="start('apply')"
          >
            <button
              smt-button
              type="button"
              smtVariant="secondary"
              smtIcon="fact_check"
              data-testid="entity-import-dry-run"
              [disabled]="!fileId() || busy()"
              (click)="start('dry_run')"
            >
              {{ 'ui.entity_import.dry_run' | t }}
            </button>
          </ui-form-actions>
        </ng-template>
      </smt-dialog>
    }
  `,
  styleUrl: './smt-entity-import.component.css',
})
export class SMTEntityImportComponent {
  private readonly imports = inject(EntityImportsApi);
  private readonly entities = inject(EntitiesApi);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  readonly meta = input<FormMeta | null>(null);

  /** Emitted when an applied import has finished, so the screen reloads its list. */
  readonly imported = output<EntityImport>();

  readonly open = signal(false);
  readonly fileId = signal<string | null>(null);
  readonly fileName = signal<string | null>(null);
  readonly uploading = signal(false);
  readonly problem = signal<string | null>(null);
  readonly current = signal<EntityImport | null>(null);

  readonly offered = computed(() => hasCapability(this.meta(), 'import') && canDo(this.meta(), 'import'));
  readonly running = computed(() => importRunning(this.current()));
  readonly busy = computed(() => this.uploading() || this.running());
  readonly templateUrl = computed(() => {
    const meta = this.meta();
    return meta ? this.imports.templateUrl(meta.code, this.i18n.currentLang()) : null;
  });
  readonly percent = computed(() => {
    const item = this.current();
    const total = item?.rowsTotal ?? 0;
    return total > 0 ? Math.round(((item?.rowsDone ?? 0) * 100) / total) : 0;
  });
  readonly stateKey = computed(() =>
    this.current()?.state === 'running' ? 'ui.entity_import.state_running' : 'ui.entity_import.state_queued',
  );
  readonly failure = computed(() =>
    this.i18n.translate(FAILURE_KEYS[this.current()?.errorCode ?? ''] ?? 'ui.entity_import.error_failed'),
  );

  readonly maxBytes = MAX_BYTES;

  private polling: Subscription | null = null;

  /** Uploads the chosen file to the files module; the import then names it by its id. */
  upload(files: File[]): void {
    const file = files[0];
    if (!file) return;
    this.uploading.set(true);
    this.problem.set(null);
    this.current.set(null);
    this.entities
      .uploadFile(file)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (stored) => {
          this.uploading.set(false);
          this.fileId.set(stored.id);
          this.fileName.set(file.name);
        },
        error: (error: unknown) => {
          this.uploading.set(false);
          this.fileId.set(null);
          this.problem.set(problemText(error) || this.i18n.translate('ui.entity_import.upload_failed'));
        },
      });
  }

  /** Starts a dry run or the load of the uploaded file and follows it until it finishes. */
  start(mode: EntityImportMode): void {
    const meta = this.meta();
    const fileId = this.fileId();
    if (!meta || !fileId || this.busy()) return;
    this.problem.set(null);
    this.imports
      .start(meta.code, fileId, mode, this.i18n.currentLang())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (item) => {
          this.current.set(item);
          this.follow(item);
        },
        error: (error: unknown) =>
          this.problem.set(problemText(error) || this.i18n.translate('ui.entity_import.start_failed')),
      });
  }

  close(): void {
    if (this.running()) return;
    this.polling?.unsubscribe();
    this.open.set(false);
    this.fileId.set(null);
    this.fileName.set(null);
    this.problem.set(null);
    this.current.set(null);
  }

  /** The field of a problem's address (`rows[17].qty` → `qty`), empty for the whole row. */
  fieldOf(address: string | undefined): string {
    const at = (address ?? '').indexOf('].');
    return at < 0 ? '' : (address ?? '').slice(at + 2);
  }

  reportUrl(id: string): string {
    return this.imports.reportUrl(id);
  }

  private follow(started: EntityImport): void {
    const id = started.id;
    if (!id) return;
    this.polling?.unsubscribe();
    this.polling = timer(IMPORT_POLL_MS, IMPORT_POLL_MS)
      .pipe(
        switchMap(() => this.imports.get(id)),
        takeWhile((item) => importRunning(item), true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (item) => {
          this.current.set(item);
          if (!importRunning(item) && item.mode === 'apply' && item.state === 'done') this.imported.emit(item);
        },
        error: (error: unknown) =>
          this.problem.set(problemText(error) || this.i18n.translate('ui.entity_import.start_failed')),
      });
  }
}
