import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslatePipe } from '../../core/services/i18n.service';
import { UiButtonComponent } from './ui-button.component';

let nextCardId = 0;

/**
 * One widget of a dashboard (roadmap wave 5): a titled region with its own
 * loading, failure and empty states, so a slow or failed widget never blanks
 * the page around it. Actions sit in the header (`[cardActions]`); the body is
 * projected content shown when the data is there.
 */
@Component({
  selector: 'ui-dashboard-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, UiButtonComponent],
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
            <ui-button variant="secondary" size="sm" (onClick)="retry.emit()">{{ 'common.retry' | t }}</ui-button>
          </div>
        } @else if (loading()) {
          <div class="dash-card__state" role="status" data-testid="dash-card-loading">
            <span class="dash-card__skeleton" aria-hidden="true"></span>
            <span class="sr-only">{{ 'common.loading' | t }}</span>
          </div>
        } @else if (empty()) {
          <p class="dash-card__state dash-card__empty" data-testid="dash-card-empty">{{ emptyText() || ('ui.dashboard.empty' | t) }}</p>
        } @else {
          <ng-content />
        }
      </div>
    </section>
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .dash-card {
      display: flex; flex-direction: column; gap: 12px; height: 100%;
      padding: 16px; border: 1px solid var(--border-color); border-radius: var(--radius-lg);
      background: var(--bg-surface); box-shadow: var(--shadow-sm); min-width: 0;
    }
    .dash-card__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
    .dash-card__titles { min-width: 0; }
    .dash-card__title { margin: 0; font-size: 15px; font-weight: 700; color: var(--text-main); }
    .dash-card__subtitle { margin: 2px 0 0; font-size: 12px; color: var(--text-muted); }
    .dash-card__actions { display: flex; gap: 6px; flex-shrink: 0; }
    .dash-card__body { min-width: 0; flex: 1; }
    .dash-card__state { display: flex; align-items: center; gap: 10px; min-height: 64px; font-size: 13px; color: var(--text-muted); }
    .dash-card__state--error { color: var(--danger-text); justify-content: space-between; flex-wrap: wrap; }
    .dash-card__empty { margin: 0; justify-content: center; }
    .dash-card__skeleton {
      display: block; width: 100%; height: 48px; border-radius: var(--radius-sm);
      background: linear-gradient(90deg, var(--bg-hover) 25%, var(--border-color) 50%, var(--bg-hover) 75%);
      background-size: 200% 100%; animation: dash-shimmer 1.5s infinite;
    }
    @keyframes dash-shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }
    @media (prefers-reduced-motion: reduce) { .dash-card__skeleton { animation: none; } }
  `]
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
