import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { EntityReportsApi, type ReportWidget } from '@shared/entity/report/entity-reports';
import { problemText } from '@shared/ui/problem-text';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { AnalyticsWidgetComponent } from './analytics-widget.component';

/**
 * The person's own widgets on the dashboard (ADR-0032 10.2; plan 10/10, item 5.8): reports saved on entity lists with
 * "show on the dashboard", configured as data in the list's report tab — no code per widget. Taking a widget off keeps
 * it as a report of its list.
 */
@Component({
  selector: 'app-analytics-widgets',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AnalyticsWidgetComponent, SMTAlertComponent, SMTButtonComponent, TranslatePipe],
  template: `
    <section
      class="widgets"
      aria-labelledby="analytics-widgets-title"
      [attr.aria-busy]="list.isLoading() || null"
      data-testid="analytics-widgets"
    >
      <h2 class="widgets-title" id="analytics-widgets-title">{{ 'analytics.widgets.title' | t }}</h2>
      @if (list.isLoading()) {
        <p class="widgets-empty" role="status">{{ 'common.loading' | t }}</p>
      } @else if (failure(); as message) {
        <smt-alert smtTone="danger" data-testid="analytics-widgets-error">
          <div class="widgets-error">
            <span>{{ message }}</span>
            <button smt-button type="button" smtVariant="secondary" smtSize="sm" (click)="list.reload()">
              {{ 'common.retry' | t }}
            </button>
          </div>
        </smt-alert>
      } @else if (widgets().length === 0) {
        <p class="widgets-empty" data-testid="analytics-widgets-empty">{{ 'analytics.widgets.empty' | t }}</p>
      } @else {
        <div class="widgets-grid">
          @for (widget of widgets(); track widget.id) {
            <app-analytics-widget [widget]="widget" (remove)="takeOff($event)" />
          }
        </div>
      }
    </section>
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .widgets {
        display: flex;
        flex-direction: column;
        gap: 12px;
        margin-top: 20px;
      }
      .widgets-title {
        margin: 0;
        font-size: 16px;
        font-weight: 600;
      }
      .widgets-empty {
        margin: 0;
        color: var(--text-muted);
      }
      .widgets-error {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 12px;
      }
      .widgets-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(min(100%, 360px), 1fr));
        gap: 16px;
      }
    `,
  ],
})
export class AnalyticsWidgetsComponent {
  private readonly i18n = inject(I18nService);
  private readonly toast = inject(ToastService);
  private readonly reports = inject(EntityReportsApi);

  readonly widgets = computed(() => (this.list.hasValue() ? this.list.value() : []));

  readonly failure = computed(() => {
    const failure = this.list.error();
    if (!failure) return '';
    const cause = failure instanceof Error && failure.cause !== undefined ? failure.cause : failure;
    return problemText(cause) || this.i18n.translate('analytics.widgets.load_failed');
  });

  readonly list = rxResource({
    stream: () => this.reports.widgets(),
  });

  /** Takes the widget off the dashboard; it stays a report of its list. */
  takeOff(widget: ReportWidget): void {
    this.reports.update(widget.listCode, { ...widget, kind: 'report' }).subscribe({
      next: () => {
        this.list.update((widgets) => (widgets ?? []).filter((item) => item.id !== widget.id));
        this.toast.success(this.i18n.translate('analytics.widgets.removed'));
      },
      error: (failure: unknown) =>
        this.toast.error(problemText(failure) || this.i18n.translate('analytics.widgets.failed')),
    });
  }
}
