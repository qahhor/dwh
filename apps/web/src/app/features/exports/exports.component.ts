import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Signal,
  TemplateRef,
  computed,
  inject,
  linkedSignal,
  signal,
  viewChild,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, catchError, of, tap, timer } from 'rxjs';
import { ExportItem, ExportsService } from '@core/services/exports.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { TBadgeVariant } from '@shared/ui-kit/components/badge/badge.component';

/** Titles of the lists that can be exported; an unknown code is shown as it is. */
const LIST_TITLES: Record<string, string> = {
  'upl.sources': 'nav.upl_sources',
  'upl.packages': 'nav.upl_packages',
  'mf.files': 'layout.app_shell.files',
};
const STATE_KEYS: Record<ExportItem['state'], string> = {
  queued: 'exports.state.queued',
  running: 'exports.state.running',
  done: 'exports.state.done',
  failed: 'exports.state.failed',
};
const ERROR_KEYS: Record<string, string> = {
  EXPORT_FORBIDDEN: 'exports.error.forbidden',
  EXPORT_INVALID: 'exports.error.invalid',
};
/** How often an unfinished export is looked at again. */
const POLL_MS = 3000;

/**
 * The person's exports journal (ADR-0018): each export of a list with its
 * state, rows and a download while the file is kept. The page refreshes by
 * itself while an export is waiting or running, and stops when none is.
 */
@Component({
  selector: 'app-exports',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTAlertComponent, DatePipe, TranslatePipe, SMTBadgeComponent, SMTButtonComponent, UiLocalTableComponent],
  template: `
    <section class="exports-page" aria-labelledby="exports-title">
      <header class="exports-head">
        <div>
          <h1 id="exports-title" class="exports-title">{{ 'exports.title' | t }}</h1>
          <p class="exports-subtitle">{{ 'exports.subtitle' | t }}</p>
        </div>
        <button
          smt-button
          type="button"
          smtVariant="secondary"
          smtSize="sm"
          smtIcon="refresh"
          data-testid="exports-refresh"
          (click)="load()"
        >
          {{ 'common.refresh' | t }}
        </button>
      </header>

      @if (failed()) {
        <smt-alert smtTone="danger" data-testid="exports-error">
          <span>{{ 'exports.load_error' | t }}</span>
          <button smt-button type="button" smtVariant="secondary" smtSize="sm" (click)="load()">
            {{ 'common.retry' | t }}
          </button>
        </smt-alert>
      }

      <div class="table-card" data-testid="exports-table">
        <ui-local-table
          [rows]="items()"
          [config]="config()"
          [sortValues]="sortValues"
          [loading]="loading()"
          [emptyTemplate]="empty"
        />
      </div>
    </section>

    <ng-template #listCell let-e
      ><span class="exports-list">{{ listTitle(e) }}</span></ng-template
    >
    <ng-template #stateCell let-e>
      <smt-badge smtSize="SM" [smtVariant]="stateVariant(e)" smtHasDot>{{ stateText(e) }}</smt-badge>
      @if (e.state === 'failed' && e.errorCode) {
        <span class="exports-note">{{ errorText(e) }}</span>
      }
    </ng-template>
    <ng-template #rowsCell let-e>
      @if (e.rowsCount !== null && e.rowsCount !== undefined) {
        <span class="tabular-nums">{{ e.rowsCount }}</span>
        @if (e.truncated) {
          <span class="exports-note">{{ 'exports.truncated' | t }}</span>
        }
      } @else {
        <span class="exports-muted">—</span>
      }
    </ng-template>
    <ng-template #createdCell let-e
      ><span class="tabular-nums">{{ e.createdAt | date: 'dd.MM.yyyy HH:mm' }}</span></ng-template
    >
    <ng-template #expiresCell let-e
      ><span class="tabular-nums exports-muted">{{ e.expiresAt | date: 'dd.MM.yyyy' }}</span></ng-template
    >
    <ng-template #fileCell let-e>
      @if (e.state === 'done') {
        <a
          class="exports-download"
          data-testid="exports-download"
          [href]="fileUrl(e)"
          download
          [attr.aria-label]="'exports.download_named' | t: { name: e.fileName || listTitle(e) }"
        >
          <span class="material-symbols-outlined" aria-hidden="true">download</span>{{ 'exports.download' | t }}
        </a>
      }
    </ng-template>
    <ng-template #empty
      ><p class="exports-empty">{{ 'exports.empty' | t }}</p></ng-template
    >
  `,
  styleUrl: './exports.component.css',
})
export class ExportsComponent {
  private readonly exports = inject(ExportsService);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly listCell = viewChild.required<TemplateRef<unknown>>('listCell');
  private readonly stateCell = viewChild.required<TemplateRef<unknown>>('stateCell');
  private readonly rowsCell = viewChild.required<TemplateRef<unknown>>('rowsCell');
  private readonly createdCell = viewChild.required<TemplateRef<unknown>>('createdCell');
  private readonly expiresCell = viewChild.required<TemplateRef<unknown>>('expiresCell');
  private readonly fileCell = viewChild.required<TemplateRef<unknown>>('fileCell');

  /** A failed read keeps the journal on screen. */
  readonly items = linkedSignal<ExportItem[] | null | undefined, ExportItem[]>({
    source: () => this.journal.value(),
    computation: (items, previous) => items ?? previous?.value ?? [],
  });
  /** Only the first read shows the table as loading; a refresh or a poll keeps the rows in place. */
  readonly loading = linkedSignal<ExportItem[] | null | undefined, boolean>({
    source: () => this.journal.value(),
    computation: (items, previous) => (items === undefined ? (previous?.value ?? true) : false),
  });

  /** Bumped to read the journal again, by hand or by the poll; a new value cancels a read in flight. */
  private readonly revision = signal(0);

  readonly failed = computed(() => this.journal.value() === null);

  readonly config = computed<TableConfig<ExportItem>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, e) => e.id,
      ariaLabel: this.i18n.translate('exports.title'),
      layout: 'fit',
      columns: {
        list: { header: header('exports.col.list'), content: cell(this.listCell) },
        state: { header: header('common.status'), content: cell(this.stateCell), width: '200px' },
        rows: { header: header('exports.col.rows'), content: cell(this.rowsCell), width: '140px', align: 'right' },
        created: { header: header('exports.col.created'), content: cell(this.createdCell), width: '150px' },
        expires: { header: header('exports.col.expires'), content: cell(this.expiresCell), width: '130px' },
        file: { header: header('exports.col.file'), content: cell(this.fileCell), width: '140px' },
      },
      columnsOrder: ['list', 'state', 'rows', 'created', 'expires', 'file'],
    };
  });

  /** The journal; null when the read failed. */
  private readonly journal = rxResource({
    params: this.revision,
    stream: () =>
      this.exports.journal().pipe(
        tap((items) => this.schedule(items)),
        catchError(() => of(null)),
      ),
  });

  private poll?: Subscription;

  /** The journal holds a person's last exports whole, so a header click sorts them all. */
  readonly sortValues = {
    list: (e: ExportItem) => this.listTitle(e),
    state: (e: ExportItem) => this.stateText(e),
    rows: (e: ExportItem) => e.rowsCount,
    created: (e: ExportItem) => new Date(e.createdAt),
    expires: (e: ExportItem) => new Date(e.expiresAt),
  };

  load(): void {
    this.revision.update((revision) => revision + 1);
  }

  listTitle(e: ExportItem): string {
    const key = LIST_TITLES[e.list];
    return key ? this.i18n.translate(key) : e.list;
  }

  stateText(e: ExportItem): string {
    return this.i18n.translate(STATE_KEYS[e.state] ?? STATE_KEYS.failed);
  }

  stateVariant(e: ExportItem): TBadgeVariant {
    return e.state === 'done' ? 'success' : e.state === 'failed' ? 'error' : e.state === 'running' ? 'blue' : 'warning';
  }

  errorText(e: ExportItem): string {
    return this.i18n.translate(ERROR_KEYS[e.errorCode ?? ''] ?? 'exports.error.failed');
  }

  fileUrl(e: ExportItem): string {
    return this.exports.fileUrl(e.id);
  }

  /** Looks again soon while an export is unfinished; a finished journal is left alone. */
  private schedule(items: ExportItem[]): void {
    this.poll?.unsubscribe();
    if (!items.some((e) => e.state === 'queued' || e.state === 'running')) return;
    this.poll = timer(POLL_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.load());
  }
}
