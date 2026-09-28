import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '../ui-kit/components/button';

let nextCardId = 0;

/**
 * One widget of a dashboard (roadmap wave 5): a titled region with its own
 * loading, failure and empty states, so a slow or failed widget never blanks
 * the page around it. Actions sit in the header (`[cardActions]`); the body is
 * projected content shown when the data is there.
 */
@Component({
  selector: 'ui-dashboard-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTButtonComponent],
  template: `
    <section class="dash-card" [attr.aria-labelledby]="titleId" [attr.aria-busy]="loading() || null">
      <header class="dash-card__head">
        <div class="dash-card__titles">
          <h2 class="dash-card__title" [id]="titleId">{{ title() }}</h2>
          @if (subtitle()) {
            <p class="dash-card__subtitle">{{ subtitle() }}</p>
          }
        </div>
        <div class="dash-card__actions"><ng-content select="[cardActions]" /></div>
      </header>
      <div class="dash-card__body">
        @if (failed()) {
          <div class="dash-card__state dash-card__state--error" role="alert" data-testid="dash-card-error">
            <span>{{ errorText() || ('ui.dashboard.load_error' | t) }}</span>
            <button smt-button type="button" smtVariant="secondary" smtSize="sm" (click)="retry.emit()">
              {{ 'common.retry' | t }}
            </button>
          </div>
        } @else if (loading()) {
          <div class="dash-card__state" role="status" data-testid="dash-card-loading">
            <span class="dash-card__skeleton" aria-hidden="true"></span>
            <span class="sr-only">{{ 'common.loading' | t }}</span>
          </div>
        } @else if (empty()) {
          <p class="dash-card__state dash-card__empty" data-testid="dash-card-empty">
            {{ emptyText() || ('ui.dashboard.empty' | t) }}
          </p>
        } @else {
          <ng-content />
        }
      </div>
    </section>
  `,
  styleUrl: './ui-dashboard-card.component.css',
})
export class UiDashboardCardComponent {
  readonly title = input.required<string>();
  readonly subtitle = input('');
  readonly loading = input(false);
  readonly failed = input(false);
  readonly empty = input(false);
  readonly errorText = input('');
  readonly emptyText = input('');
  readonly retry = output<void>();

  readonly titleId = `dash-card-title-${nextCardId++}`;
}
