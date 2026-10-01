import {
  ChangeDetectionStrategy,
  Component,
  Signal,
  TemplateRef,
  computed,
  viewChild,
  inject,
  linkedSignal,
  signal,
  input,
  output,
  DestroyRef,
  OnInit,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, filter, map, of, switchMap, take, timer } from 'rxjs';
import { ProblemDetail } from '@core/models/common.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { UPL_ERROR, uplProblemText } from '../upl-labels';
import { UplPackageErrorItem, UplPackageErrors, UplPackageItem, UplPackagesApiService } from './packages-api';
import { UplTranslate, uplPackageCodeText } from './packages-errors';
import {
  UPL_PACKAGE_STATUS_KEY,
  UPL_PACKAGE_STATUS_VARIANT,
  formatUplDateTime,
  formatUplPeriod,
} from './packages-labels';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';

/** Text keys of the UPL module errors start like this. */
const UPL_ERROR_PREFIX = 'error.upl.';

/** The stored errors of an upload or the refusal to give them, so a failure never throws out of the resource. */
type ErrorsLoad = { errors: UplPackageErrors | null } | { problem: ProblemDetail };

@Component({
  selector: 'app-upl-package-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTAlertComponent, TranslatePipe, SMTBadgeComponent, SMTButtonComponent, UiLocalTableComponent],
  template: `
    <div class="upl-pkg-card">
      <div class="upl-pkg-card-head">
        <button smt-button type="button" smtVariant="ghost" data-testid="upl-pkg-back" (click)="back.emit()">
          {{ 'upl.pkg.card.back' | t }}
        </button>
        <button
          smt-button
          type="button"
          smtVariant="secondary"
          smtIcon="refresh"
          data-testid="upl-pkg-card-refresh"
          (click)="refresh.emit()"
        >
          {{ 'upl.pkg.refresh' | t }}
        </button>
        @if (item().status === 'verified' && canApply() && (item().rowsAccepted ?? 0) > 0) {
          <button
            smt-button
            type="button"
            smtVariant="primary"
            data-testid="upl-pkg-apply"
            [disabled]="applying()"
            (click)="apply()"
          >
            {{ 'upl.pkg.card.apply' | t }}
          </button>
        }
      </div>

      <div class="upl-pkg-card-title">
        <span class="upl-pkg-file">{{ item().fileName }}</span>
        <smt-badge smtSize="SM" [smtVariant]="statusVariant[item().status]">{{
          statusKey[item().status] | t
        }}</smt-badge>
      </div>
      <div class="upl-pkg-card-meta" data-testid="upl-pkg-card-meta">{{ metaText() }}</div>
      @if (item().status === 'applying') {
        <smt-alert smtTone="info" class="upl-pkg-alert" role="status" data-testid="upl-pkg-applying">{{
          'upl.pkg.card.applying' | t
        }}</smt-alert>
      }
      @if (applyLost()) {
        <smt-alert smtTone="warning" class="upl-pkg-alert" data-testid="upl-pkg-applying-lost">{{
          'upl.pkg.card.applying_lost' | t
        }}</smt-alert>
      }
      @if (applyError(); as message) {
        <smt-alert smtTone="danger" class="upl-pkg-alert" data-testid="upl-pkg-apply-error">{{ message }}</smt-alert>
      }

      @if (item().status === 'received') {
        <p class="upl-pkg-checking" data-testid="upl-pkg-checking">{{ 'upl.pkg.card.checking' | t }}</p>
      } @else {
        @if (item().status === 'rejected') {
          <smt-alert smtTone="danger" class="upl-pkg-rejected" data-testid="upl-pkg-rejected">
            <strong>{{ 'upl.pkg.card.rejected' | t }}</strong>
            <span class="upl-pkg-reject-reason">{{ rejectText() }}</span>
            @for (row of structRows(); track $index) {
              <span class="upl-pkg-struct-row" data-testid="upl-pkg-struct-row">{{ codeText(row) }}</span>
            }
            <span class="upl-pkg-hint">{{ 'upl.pkg.card.rejected_hint' | t }}</span>
            @if (structRows().length > 0) {
              <a class="upl-pkg-errors-file" data-testid="upl-pkg-errors-file" [href]="errorsFileUrl()" download
                ><span class="material-symbols-outlined" aria-hidden="true">download</span
                >{{ 'upl.errfile.download' | t }}</a
              >
            }
          </smt-alert>
        } @else {
          <div class="upl-pkg-counters" data-testid="upl-pkg-counters">
            <span class="upl-pkg-counter">
              <span class="upl-pkg-counter-label">{{ 'upl.pkg.card.rows_total' | t }}</span>
              <span class="upl-pkg-counter-value">{{ item().rowsTotal ?? '—' }}</span>
            </span>
            <span class="upl-pkg-counter">
              <span class="upl-pkg-counter-label">{{ 'upl.pkg.card.rows_accepted' | t }}</span>
              <span class="upl-pkg-counter-value">{{ item().rowsAccepted ?? '—' }}</span>
            </span>
            <span class="upl-pkg-counter">
              <span class="upl-pkg-counter-label">{{ 'upl.pkg.card.rows_rejected' | t }}</span>
              <span class="upl-pkg-counter-value">{{ item().rowsRejected ?? '—' }}</span>
            </span>
          </div>
          @if (reconciliationText(); as line) {
            <smt-alert
              smtTone="success"
              smtLive="off"
              class="upl-pkg-reconciliation"
              data-testid="upl-pkg-reconciliation"
              >{{ line }}</smt-alert
            >
          }
        }

        @if (loadError()) {
          <smt-alert smtTone="danger" class="upl-pkg-alert" data-testid="upl-pkg-errors-load-error">
            <span>{{ loadError() }}</span>
            <button
              smt-button
              type="button"
              smtVariant="secondary"
              data-testid="upl-pkg-errors-retry"
              (click)="reloadErrors()"
            >
              {{ 'upl.common.retry' | t }}
            </button>
          </smt-alert>
        } @else if (isLoading()) {
          <div class="table-card" data-testid="upl-pkg-errors-loading">
            <ui-local-table [rows]="[]" [config]="errorsConfig()" [loading]="true" />
          </div>
        } @else if (item().status !== 'rejected') {
          @if (errors(); as loaded) {
            @if (loaded.total === 0) {
              <p class="upl-pkg-no-errors" data-testid="upl-pkg-no-errors">{{ 'upl.pkg.card.no_errors' | t }}</p>
            } @else {
              @if (loaded.total > loaded.shown) {
                <p class="upl-pkg-shown" data-testid="upl-pkg-errors-shown">
                  {{ 'upl.pkg.errors.shown_first' | t: { shown: loaded.shown, n: loaded.total } }}
                </p>
              }
              <a class="upl-pkg-errors-file" data-testid="upl-pkg-errors-file" [href]="errorsFileUrl()" download
                ><span class="material-symbols-outlined" aria-hidden="true">download</span
                >{{ 'upl.errfile.download' | t }}</a
              >
              <div class="table-card">
                <div class="table-scroll">
                  <div data-testid="upl-pkg-errors-table">
                    <ui-local-table [rows]="cellRows()" [config]="errorsConfig()" [sortValues]="errorSortValues" />
                  </div>
                </div>
              </div>
            }
          }
        }
      }
    </div>

    <ng-template #errorValueCell let-row
      ><code class="upl-pkg-value" [title]="row.value ?? ''">{{ row.value ?? '—' }}</code></ng-template
    >
    <ng-template #errorWhatCell let-row>{{ codeText(row) }}</ng-template>
  `,
  styleUrl: './package-card.component.css',
})
export class PackageCardComponent implements OnInit {
  private readonly api = inject(UplPackagesApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly i18n = inject(I18nService);

  readonly item = input.required<UplPackageItem>();

  readonly canApply = input(false);

  readonly back = output<void>();
  readonly refresh = output<void>();
  readonly applied = output<UplPackageItem>();

  private readonly errorValueCell = viewChild.required<TemplateRef<unknown>>('errorValueCell');
  private readonly errorWhatCell = viewChild.required<TemplateRef<unknown>>('errorWhatCell');

  readonly applying = signal(false);
  /** The result of a queued apply could not be read three times in a row: the person refreshes by hand. */
  readonly applyLost = signal(false);
  /** Another upload in the card starts without the refusal of the previous one. */
  readonly applyError = linkedSignal<UplPackageItem, string | null>({
    source: () => this.item(),
    computation: () => null,
  });

  readonly isLoading = computed(() => this.errorsLoad.isLoading());
  /** Nothing while they load again, so a retry never shows the old rows. */
  readonly errors = computed(() => {
    const load = this.errorsLoad.value();
    return !this.isLoading() && load && 'errors' in load ? load.errors : null;
  });
  readonly loadError = computed(() => {
    const load = this.errorsLoad.value();
    return !this.isLoading() && load && 'problem' in load ? this.loadErrorText(load.problem) : null;
  });

  readonly errorsConfig = computed<TableConfig<UplPackageErrorItem>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (index: number) => index,
      ariaLabel: this.i18n.translate('upl.pkg.errors.title'),
      layout: 'fit',
      columns: {
        sheet: {
          header: header('upl.pkg.errors.col.sheet'),
          content: { type: 'primitive', value: (row) => row.sheet ?? '—' },
          width: '140px',
        },
        row: {
          header: header('upl.pkg.errors.col.row'),
          content: { type: 'primitive', value: (row) => row.rowNo },
          width: '90px',
          align: 'right',
        },
        column: {
          header: header('upl.pkg.errors.col.column'),
          content: { type: 'primitive', value: (row) => row.columnName ?? '—' },
        },
        value: { header: header('upl.pkg.errors.col.value'), content: cell(this.errorValueCell) },
        what: { header: header('upl.pkg.errors.col.what'), content: cell(this.errorWhatCell) },
      },
      columnsOrder: ['sheet', 'row', 'column', 'value', 'what'],
    };
  });

  /** How often a card asks for the result of an apply that runs as a job. */
  static readonly POLL_MS = 2000;
  /** Failed reads in a row after which the card stops asking. */
  static readonly POLL_TRIES = 3;

  /** The stored errors are all on screen, so a header click sorts them all: by sheet, row, column or reason. */
  readonly errorSortValues = {
    sheet: (row: UplPackageErrorItem) => row.sheet,
    row: (row: UplPackageErrorItem) => row.rowNo,
    column: (row: UplPackageErrorItem) => row.columnName,
    value: (row: UplPackageErrorItem) => row.value,
    what: (row: UplPackageErrorItem) => this.codeText(row),
  };
  readonly statusKey = UPL_PACKAGE_STATUS_KEY;
  readonly statusVariant = UPL_PACKAGE_STATUS_VARIANT;

  private readonly translate: UplTranslate = (key, params) => this.i18n.translate(key, params);

  /** Each upload shown asks for its errors, except one still being checked, which has none yet. */
  private readonly errorsLoad = rxResource({
    params: () => {
      const item = this.item();
      return item.status === 'received' ? undefined : item;
    },
    stream: ({ params: item }) =>
      this.api.errors(item.id).pipe(
        map((loaded): ErrorsLoad => ({ errors: loaded ?? null })),
        catchError((problem: ProblemDetail) => of<ErrorsLoad>({ problem })),
      ),
  });

  /** Every stored error as an xlsx the supplier fixes the data from, in the reader's language. */
  errorsFileUrl(): string {
    return (
      '/api/v1/upl/packages/' +
      encodeURIComponent(this.item().id) +
      '/errors/file?lang=' +
      encodeURIComponent(this.i18n.currentLang())
    );
  }

  /** The header line: source · period · file format version · who uploaded · when. */
  metaText(): string {
    const item = this.item();
    return [
      this.item().sourceName,
      formatUplPeriod(this.item().periodFrom, this.item().periodTo),
      this.i18n.translate('upl.pkg.card.format_version', { v: this.item().formatVersion }),
      // Who uploaded comes only to those who may see people; without it the line skips the part.
      item.uploadedBy ? this.i18n.translate('upl.pkg.card.uploaded_by', { who: item.uploadedBy }) : '',
      formatUplDateTime(this.item().uploadedAt),
    ]
      .filter(Boolean)
      .join(' · ');
  }

  /** The rejection reason in words; with no rejection code it stays empty. */
  rejectText(): string {
    const item = this.item();
    return item.rejectCode ? uplPackageCodeText(item.rejectCode, item.rejectParams, this.translate) : '';
  }

  /** The reconciliation line: only for an applied load and only when the server returned both numbers. */
  ngOnInit(): void {
    if (this.item().status === 'applying') {
      this.waitForResult(this.item().id);
    }
  }

  reconciliationText(): string {
    const { status, rowsTotal, rawRows } = this.item();
    if (status !== 'applied' || rowsTotal == null || rawRows == null) {
      return '';
    }
    return this.i18n.translate('upl.pkg.card.reconciliation', { n: rowsTotal, m: rawRows });
  }

  apply(): void {
    this.applying.set(true);
    this.applyError.set(null);
    this.api.apply(this.item().id).subscribe({
      next: (queued) => {
        this.applied.emit(queued);
        this.waitForResult(queued.id);
      },
      error: (problem: ProblemDetail) => {
        this.applying.set(false);
        this.applyError.set(this.applyErrorText(problem));
      },
    });
  }

  codeText(row: UplPackageErrorItem): string {
    return uplPackageCodeText(row.code, row.params, this.translate);
  }

  /** Differences from the file format: records without a row number (the server omits empty fields entirely). */
  structRows(): UplPackageErrorItem[] {
    return (this.errors()?.items ?? []).filter((row) => (row.rowNo ?? null) === null);
  }

  /** Cell errors: records with a row address, they go to the table. */
  cellRows(): UplPackageErrorItem[] {
    return (this.errors()?.items ?? []).filter((row) => (row.rowNo ?? null) !== null);
  }

  reloadErrors(): void {
    this.errorsLoad.reload();
  }

  /**
   * The apply runs as a job (plan 10/10, item 3.9): the card asks for the package until it is no longer applying, then
   * hands the result on. A package opened while applying is followed the same way.
   */
  private waitForResult(id: string): void {
    this.applying.set(true);
    this.applyLost.set(false);
    let failures = 0;
    timer(PackageCardComponent.POLL_MS, PackageCardComponent.POLL_MS)
      .pipe(
        switchMap(() =>
          this.api.get(id).pipe(
            map((item) => {
              failures = 0;
              return item;
            }),
            catchError(() => {
              failures++;
              return of(null);
            }),
          ),
        ),
        filter((item) => (item !== null && item.status !== 'applying') || failures >= PackageCardComponent.POLL_TRIES),
        take(1),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        this.applying.set(false);
        if (result === null) {
          this.applyLost.set(true);
        } else {
          this.applied.emit(result);
        }
      });
  }

  /** A server refusal: a load error shows its key's text, anything else (no permission, network) a general text. */
  private applyErrorText(problem: ProblemDetail | null | undefined): string {
    return problem?.messageKey?.startsWith(UPL_ERROR_PREFIX)
      ? uplProblemText(problem, this.translate)
      : this.i18n.translate('upl.pkg.card.apply_failed');
  }

  /** "Load not found" shows the error text, other failures a general text. */
  private loadErrorText(problem: ProblemDetail | null | undefined): string {
    return problem?.messageKey === UPL_ERROR.packageNotFound
      ? uplProblemText(problem, this.translate)
      : this.i18n.translate('upl.pkg.load_error');
  }
}
