import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { problemText } from '@shared/ui/problem-text';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTTagComponent } from '@shared/ui-kit/components/tag';
import { runTone } from './mailing-words';
import {
  ReportMailingsApi,
  type ReportMailing,
  type ReportMailingDelivery,
  type ReportMailingRun,
  type ReportMailingRunDetail,
} from './report-mailings.api';

/**
 * The run history of a mailing (ADR-0045): scheduled runs, sends now and tests, newest first, each with its outcome
 * and counts; a run opens what each recipient got on each channel — sent, queued with the outbox's state, skipped or
 * failed, and why. Statuses are words and tags, never colour alone.
 */
@Component({
  selector: 'app-report-mailing-runs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    SMTAlertComponent,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTTagComponent,
    TranslatePipe,
  ],
  templateUrl: './report-mailing-runs.component.html',
  styleUrl: './report-mailing-runs.component.css',
})
export class ReportMailingRunsComponent implements OnInit {
  private readonly api = inject(ReportMailingsApi);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  readonly mailing = input.required<ReportMailing>();
  readonly closed = output<void>();

  readonly runs = signal<ReportMailingRun[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly detail = signal<ReportMailingRunDetail | null>(null);

  readonly tone = runTone;

  ngOnInit(): void {
    this.load(null);
  }

  load(cursor: string | null): void {
    this.loading.set(true);
    this.error.set('');
    this.api
      .runs(this.mailing().id, cursor)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.loading.set(false);
          this.runs.update((runs) => (cursor ? [...runs, ...page.items] : page.items));
          this.cursor.set(page.hasMore ? (page.nextCursor ?? null) : null);
        },
        error: (failure: unknown) => {
          this.loading.set(false);
          this.error.set(problemText(failure) || this.i18n.translate('reports.mailings.runs.load_failed'));
        },
      });
  }

  open(run: ReportMailingRun): void {
    if (this.detail()?.run.id === run.id) {
      this.detail.set(null);
      return;
    }
    this.api
      .run(this.mailing().id, run.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (detail) => this.detail.set(detail),
        error: (failure: unknown) =>
          this.error.set(problemText(failure) || this.i18n.translate('reports.mailings.runs.load_failed')),
      });
  }

  /** What a delivery came to, in words: its status, the outbox's state of a queued one, and the reason. */
  result(delivery: ReportMailingDelivery): string {
    const parts = [this.i18n.translate(`reports.mailings.runs.delivery.${delivery.status}`)];
    if (delivery.outbox) {
      parts.push(this.i18n.translate(`reports.mailings.runs.outbox.${delivery.outbox.toLowerCase()}`));
    }
    if (delivery.reason) parts.push(this.i18n.translate(`reports.mailings.runs.reason.${delivery.reason}`));
    return parts.join(' — ');
  }
}
