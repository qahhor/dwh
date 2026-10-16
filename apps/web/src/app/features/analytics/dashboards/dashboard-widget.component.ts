import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { fieldLabel } from '@core/models/query-meta.models';
import { QueryMetaService } from '@core/services/query-meta.service';
import { SMTEntityReportComponent } from '@shared/entity/report/smt-entity-report.component';
import { ReportExportComponent } from '@shared/entity/report/report-export.component';
import type { ReportExportSource } from '@shared/entity/report/report-export.api';
import { UiDashboardCardComponent } from '@shared/ui/ui-dashboard-card.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { problemText } from '@shared/ui/problem-text';
import { DashboardApi } from './dashboard.api';
import type { DashboardWidget, DashboardPeriod } from './dashboard.models';

@Component({
  selector: 'app-dashboard-widget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    SMTButtonComponent,
    TranslatePipe,
    UiDashboardCardComponent,
    SMTEntityReportComponent,
    ReportExportComponent,
  ],
  template: `
    <ui-dashboard-card
      [title]="title()"
      [subtitle]="periodLabel()"
      [loading]="loaded.isLoading()"
      [failed]="!!failure()"
      [errorText]="failure()"
      (retry)="loaded.reload()"
      data-testid="dashboard-widget"
    >
      <ng-container cardActions>
        @if (widget().report; as report) {
          <a smt-button smtVariant="ghost" smtSize="sm" smtIcon="open_in_new" [routerLink]="['/e', report.entity]">{{
            'analytics.widgets.open' | t
          }}</a>
        }
        @if (exportSource(); as source) {
          <smt-report-export [source]="source" [disabled]="loaded.isLoading() || !!failure()" />
        }
      </ng-container>
      <smt-entity-report
        [result]="loaded.value()?.result ?? null"
        [meta]="loaded.value()?.meta ?? null"
        [chart]="widget().report?.state?.chart ?? 'table'"
        [caption]="title()"
        [showTable]="widget().report?.state?.chart === 'table'"
      />
    </ui-dashboard-card>
  `,
})
export class DashboardWidgetComponent {
  private readonly api = inject(DashboardApi);
  private readonly metadata = inject(QueryMetaService);
  private readonly i18n = inject(I18nService);
  readonly dashboardId = input.required<number>();
  readonly revision = input.required<number>();
  readonly widget = input.required<DashboardWidget>();
  readonly period = input<DashboardPeriod | null>(null);
  readonly refresh = input(0);
  readonly stale = output<void>();
  readonly title = computed(() => this.widget().report?.name ?? this.i18n.translate('analytics.panels.unavailable'));
  readonly failure = computed(() => this.loaded.value()?.failure ?? '');
  readonly exportSource = computed<ReportExportSource | null>(() =>
    this.loaded.value()?.result && !this.failure()
      ? {
          kind: 'dashboard',
          id: this.dashboardId(),
          viewId: this.widget().viewId,
          revision: this.revision(),
          period: this.period(),
        }
      : null,
  );
  readonly periodLabel = computed(() => {
    if (!this.period() || !this.widget().periodField) return '';
    const field = this.loaded.value()?.meta?.fields.find((candidate) => candidate.key === this.widget().periodField);
    return this.i18n.translate('analytics.panels.mapped', {
      field: field ? fieldLabel(field, (key) => this.i18n.translate(key)) : this.widget().periodField!,
    });
  });
  readonly loaded = rxResource({
    params: () => ({
      id: this.dashboardId(),
      revision: this.revision(),
      widget: this.widget(),
      period: this.period(),
      refresh: this.refresh(),
    }),
    stream: ({ params }) => {
      const report = params.widget.report;
      if (!params.widget.available || !report)
        return of({ meta: null, result: null, failure: this.i18n.translate('analytics.panels.unavailable') });
      if (params.period && !params.widget.periodField)
        return of({ meta: null, result: null, failure: this.i18n.translate('analytics.panels.mapping_required') });
      return forkJoin({
        meta: report.state.dataset
          ? this.metadata.report(report.entity, report.state.dataset)
          : this.metadata.get(report.listCode),
        result: this.api.result(params.id, params.widget.viewId, params.revision, params.period),
      }).pipe(
        map((value) => ({ ...value, failure: '' })),
        catchError((failure: unknown) => {
          if (typeof failure === 'object' && failure !== null && (failure as { status?: number }).status === 409)
            this.stale.emit();
          return of({
            meta: null,
            result: null,
            failure: problemText(failure) || this.i18n.translate('analytics.widgets.failed'),
          });
        }),
      );
    },
  });
}
