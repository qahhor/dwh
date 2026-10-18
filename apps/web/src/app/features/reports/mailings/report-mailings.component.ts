import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import type { QueryListMeta } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { QueryMetaService } from '@core/services/query-meta.service';
import { ToastService } from '@core/services/toast.service';
import type { ReportViewItem } from '@shared/entity/report/report-catalog.api';
import { problemText } from '@shared/ui/problem-text';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { SMTTagComponent } from '@shared/ui-kit/components/tag';
import { channelsText, runTone, scheduleText } from './mailing-words';
import { ReportMailingDialogComponent } from './report-mailing-dialog.component';
import { ReportMailingRunsComponent } from './report-mailing-runs.component';
import {
  ReportMailingsApi,
  mailingRequest,
  type ReportMailing,
  type ReportMailingOptions,
} from './report-mailings.api';

/**
 * The mailings of a saved report (ADR-0045), opened from the reports catalog: each with its schedule, channels,
 * recipients, next send and latest run, to change, pause or resume, send now to everyone, send to oneself as a test,
 * look through its history or delete. The report is built for each recipient with their own rights; the page only
 * keeps the mailing, and the server checks every step.
 */
@Component({
  selector: 'app-report-mailings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    RouterLink,
    TranslatePipe,
    SMTAlertComponent,
    SMTButtonComponent,
    SMTTagComponent,
    UiPageHeaderComponent,
    ReportMailingDialogComponent,
    ReportMailingRunsComponent,
  ],
  templateUrl: './report-mailings.component.html',
  styleUrl: './report-mailings.component.css',
})
export class ReportMailingsComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ReportMailingsApi);
  private readonly queryMeta = inject(QueryMetaService);
  private readonly i18n = inject(I18nService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly modal = inject(SMTModalService);
  private readonly destroyRef = inject(DestroyRef);

  readonly report = signal<ReportViewItem | null>(null);
  readonly meta = signal<QueryListMeta | null>(null);
  readonly options = signal<ReportMailingOptions | null>(null);
  readonly mailings = signal<ReportMailing[]>([]);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly busy = signal<number | null>(null);
  /** The dialog: undefined — closed, null — a new mailing, else the mailing changed. */
  readonly editing = signal<ReportMailing | null | undefined>(undefined);
  readonly history = signal<ReportMailing | null>(null);

  readonly mayKeep = computed(() => {
    const report = this.report();
    if (!report) return false;
    return (
      this.permissions.hasPermission('report.mailings', 'manage') ||
      (report.owned && this.permissions.hasPermission('report.mailings', 'own'))
    );
  });

  readonly tone = runTone;
  readonly viewId = Number(this.route.snapshot.paramMap.get('id'));

  ngOnInit(): void {
    this.api
      .options()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (options) => this.options.set(options), error: () => this.options.set(null) });
    this.api
      .report(this.viewId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (report) => {
          this.report.set(report);
          this.loadMeta(report);
          if (this.mayKeep()) this.load();
        },
        error: (failure: unknown) =>
          this.error.set(problemText(failure) || this.i18n.translate('reports.mailings.report_failed')),
      });
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.api
      .list(this.viewId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.loading.set(false);
          this.mailings.set(page.items);
        },
        error: (failure: unknown) => {
          this.loading.set(false);
          this.error.set(problemText(failure) || this.i18n.translate('reports.mailings.load_failed'));
        },
      });
  }

  schedule(mailing: ReportMailing): string {
    this.i18n.currentLang();
    return scheduleText(mailing.schedule, (key, params) => this.i18n.translate(key, params));
  }

  channels(mailing: ReportMailing): string {
    this.i18n.currentLang();
    return channelsText(mailing, (key) => this.i18n.translate(key));
  }

  saved(mailing: ReportMailing): void {
    this.editing.set(undefined);
    this.toast.success(this.i18n.translate('reports.mailings.saved', { name: mailing.name }));
    this.load();
  }

  /** Pauses an active mailing or resumes a paused one, from the revision read. */
  toggle(mailing: ReportMailing): void {
    this.busy.set(mailing.id);
    this.api
      .update(mailing.id, mailing.revision, mailingRequest(mailing, { active: !mailing.active }))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.busy.set(null);
          this.load();
        },
        error: (failure: unknown) => this.failed(failure),
      });
  }

  send(mailing: ReportMailing, scope: 'all' | 'me'): void {
    this.busy.set(mailing.id);
    this.api
      .send(mailing.id, scope)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.busy.set(null);
          this.toast.success(this.i18n.translate('reports.mailings.sent_queued'));
          this.load();
        },
        error: (failure: unknown) => this.failed(failure),
      });
  }

  remove(mailing: ReportMailing): void {
    this.modal
      .confirm({
        message: this.i18n.translate('reports.mailings.delete_confirm', { name: mailing.name }),
        destructive: true,
      })
      .subscribe((confirmed) => {
        if (!confirmed) return;
        this.api
          .remove(mailing.id, mailing.revision)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({ next: () => this.load(), error: (failure: unknown) => this.failed(failure) });
      });
  }

  private failed(failure: unknown): void {
    this.busy.set(null);
    this.toast.error(problemText(failure) || this.i18n.translate('reports.mailings.action_failed'));
    this.load();
  }

  private loadMeta(report: ReportViewItem): void {
    const dataset = report.state.dataset;
    const request = dataset ? this.queryMeta.report(report.entity, dataset) : this.queryMeta.get(report.listCode);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (meta) => this.meta.set(meta),
      error: () => this.meta.set(null),
    });
  }
}
