import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, finalize } from 'rxjs';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { problemText } from '@shared/ui/problem-text';
import { ReportExportApi, type ReportExportSource } from './report-export.api';

@Component({
  selector: 'smt-report-export',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTButtonComponent, TranslatePipe],
  template: `
    <button
      smt-button
      type="button"
      smtVariant="secondary"
      smtSize="sm"
      smtIcon="download"
      [disabled]="disabled() || !source()"
      [smtLoading]="busy()"
      (click)="download()"
      data-testid="report-export"
    >
      {{ 'ui.report.export' | t }}
    </button>
    @if (failure(); as message) {
      <p role="alert">{{ message }}</p>
    }
  `,
})
export class ReportExportComponent {
  private readonly api = inject(ReportExportApi);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  readonly source = input<ReportExportSource | null>(null);
  readonly disabled = input(false);

  readonly busy = signal(false);
  readonly failure = signal('');

  private request?: Subscription;

  constructor() {
    effect(() => {
      this.source();
      this.request?.unsubscribe();
      this.failure.set('');
    });
  }

  download(): void {
    const source = this.source();
    if (!source || this.disabled() || this.busy()) return;
    this.failure.set('');
    this.busy.set(true);
    this.request = this.api
      .download(source, this.i18n.currentLang())
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next: (blob) => {
          try {
            downloadReport(blob);
          } catch (failure: unknown) {
            this.failure.set(problemText(failure) || this.i18n.translate('ui.report.export_failed'));
          }
        },
        error: (failure: unknown) =>
          this.failure.set(problemText(failure) || this.i18n.translate('ui.report.export_failed')),
      });
  }
}

function downloadReport(blob: Blob): void {
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'report.xlsx';
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
