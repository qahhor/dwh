import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';

/**
 * What the general entity screen shows instead of its content (ADR-0032 7.1): an entity that is unknown or not the
 * viewer's (the server answers 404 for both), a record out of reach, or a failed load with a retry.
 */
@Component({
  selector: 'smt-entity-page-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, SMTButtonComponent, TranslatePipe],
  host: { class: 'smt-entity-page-state' },
  template: `
    <div class="entity-state" [attr.role]="kind() === 'failed' ? 'alert' : null" data-testid="entity-page-state">
      <span class="material-symbols-outlined entity-state-icon" aria-hidden="true">{{ icon() }}</span>
      <h2>{{ titleKey() | t }}</h2>
      <p>{{ descriptionKey() | t }}</p>
      <div class="entity-state-actions">
        @if (kind() === 'failed') {
          <button smt-button type="button" smtVariant="secondary" smtIcon="refresh" (click)="retry.emit()">
            {{ 'common.retry' | t }}
          </button>
        }
        @if (back(); as link) {
          <a smt-button smtVariant="ghost" smtIcon="arrow_back" [routerLink]="link">{{ 'ui.entity_page.back' | t }}</a>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .entity-state {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 12px;
        padding: 48px 24px;
        text-align: center;
        border: 2px dashed var(--border-color);
        border-radius: 12px;
      }
      .entity-state h2 {
        margin: 0;
        font-size: 1.125rem;
      }
      .entity-state p {
        margin: 0;
        color: var(--text-secondary);
      }
      .entity-state-icon {
        font-size: 48px;
        color: var(--text-muted);
      }
      .entity-state-actions {
        display: flex;
        gap: 8px;
      }
    `,
  ],
})
export class SMTEntityPageStateComponent {
  /**
   * `entity` — no such entity for this viewer; `record` — no such record in reach; `denied` — the viewer may not do
   * this with it; `failed` — the load failed.
   */
  readonly kind = input.required<'entity' | 'record' | 'denied' | 'failed'>();

  /** Where "back" leads; none — no link. */
  readonly back = input<string | null>(null);

  readonly retry = output<void>();

  icon(): string {
    switch (this.kind()) {
      case 'failed':
        return 'error';
      case 'denied':
        return 'lock';
      default:
        return 'search_off';
    }
  }

  titleKey(): string {
    switch (this.kind()) {
      case 'entity':
        return 'ui.entity_page.entity_missing';
      case 'record':
        return 'ui.entity_page.record_missing';
      case 'denied':
        return 'ui.entity_page.denied';
      default:
        return 'ui.entity_page.load_failed';
    }
  }

  descriptionKey(): string {
    switch (this.kind()) {
      case 'entity':
        return 'ui.entity_page.entity_missing_hint';
      case 'record':
        return 'ui.entity_page.record_missing_hint';
      case 'denied':
        return 'ui.entity_page.denied_hint';
      default:
        return 'ui.entity_page.load_failed_hint';
    }
  }
}
