import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { QueryMetaService } from '@core/services/query-meta.service';
import { EntityReportsApi, type ReportWidget } from '@shared/entity/report/entity-reports';
import { SMTEntityReportComponent } from '@shared/entity/report/smt-entity-report.component';
import { problemText } from '@shared/ui/problem-text';
import { UiDashboardCardComponent } from '@shared/ui/ui-dashboard-card.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';

/**
 * One widget of the person's dashboard (ADR-0032 10.2): a report saved on an entity's list, run when the dashboard
 * opens under the entity's scope and field rights, drawn as its chart. Its card keeps its own loading and failure, so
 * one slow or refused report never blanks the others.
 */
@Component({
  selector: 'app-analytics-widget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, SMTButtonComponent, SMTEntityReportComponent, TranslatePipe, UiDashboardCardComponent],
  template: `
    <ui-dashboard-card
      data-testid="analytics-widget"
      [title]="widget().name"
      [loading]="loaded.isLoading()"
      [failed]="!!failure()"
      [errorText]="failure()"
      (retry)="loaded.reload()"
    >
      <ng-container cardActions>
        <a smt-button smtVariant="ghost" smtSize="sm" smtIcon="open_in_new" [routerLink]="['/e', widget().entity]">
          {{ 'analytics.widgets.open' | t }}
        </a>
        <button
          smt-button
          type="button"
          smtVariant="ghost"
          smtSize="sm"
          smtIcon="close"
          smtIconOnly
          [attr.aria-label]="'analytics.widgets.remove' | t: { name: widget().name }"
          (click)="remove.emit(widget())"
        ></button>
      </ng-container>
      <smt-entity-report
        [result]="loaded.value()?.result ?? null"
        [meta]="loaded.value()?.meta ?? null"
        [chart]="widget().state.chart"
        [caption]="widget().name"
        [showTable]="widget().state.chart === 'table'"
      />
    </ui-dashboard-card>
  `,
})
export class AnalyticsWidgetComponent {
  private readonly i18n = inject(I18nService);
  private readonly reports = inject(EntityReportsApi);
  private readonly queryMeta = inject(QueryMetaService);

  readonly widget = input.required<ReportWidget>();
  /** The person takes the widget off the dashboard. */
  readonly remove = output<ReportWidget>();

  readonly failure = computed(() => this.loaded.value()?.failure ?? '');

  readonly loaded = rxResource({
    params: () => this.widget(),
    stream: ({ params: widget }) =>
      forkJoin({
        meta: this.queryMeta.get(widget.listCode),
        result: this.reports.saved(widget.entity, widget.id),
      }).pipe(
        map((loaded) => ({ ...loaded, failure: '' })),
        catchError((failure: unknown) =>
          of({
            meta: null,
            result: null,
            failure: problemText(failure) || this.i18n.translate('analytics.widgets.failed'),
          }),
        ),
      ),
  });
}
