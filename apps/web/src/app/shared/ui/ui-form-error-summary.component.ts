import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TranslatePipe } from '@core/services/i18n.service';

/** One error of the summary: its field's id (to jump to it), the field's label and the message. */
export interface UiFormErrorItem {
  /** The id of the field to focus; none for an error that belongs to no drawn field. */
  readonly fieldId?: string;
  readonly label?: string;
  readonly message: string;
}

/**
 * The errors of a form at the top of it (docs/guidelines/forms-ux-standard.md, section 4): shown once a submit has
 * found at least `threshold` errors, or any error that belongs to no field drawn (a server message about a hidden
 * field). Each item links to its field. The errors stay under the fields too; the summary only collects them.
 *
 * <ui-form-error-summary [errors]="summary()" />
 */
@Component({
  selector: 'ui-form-error-summary',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe],
  host: { class: 'ui-form-error-summary' },
  template: `
    @if (shown()) {
      <div class="ui-form-error-summary__box" role="alert" data-testid="form-error-summary">
        <p class="ui-form-error-summary__title">{{ 'ui.form.error_summary' | t: { count: errors().length } }}</p>
        <ul class="ui-form-error-summary__list">
          @for (error of errors(); track $index) {
            <li>
              @if (error.fieldId) {
                <a
                  class="ui-form-error-summary__link"
                  [href]="'#' + error.fieldId"
                  (click)="jump($event, error.fieldId)"
                >
                  @if (error.label) {
                    <span class="ui-form-error-summary__label">{{ error.label }}:</span>
                  }
                  {{ error.message }}
                </a>
              } @else {
                @if (error.label) {
                  <span class="ui-form-error-summary__label">{{ error.label }}:</span>
                }
                {{ error.message }}
              }
            </li>
          }
        </ul>
      </div>
    }
  `,
  styles: [
    `
      .ui-form-error-summary__box {
        padding: 10px 12px;
        border: 1px solid var(--danger-border);
        border-radius: var(--radius-md);
        background: var(--danger-bg);
        color: var(--danger-text);
        font-size: 13px;
      }
      .ui-form-error-summary__title {
        margin: 0 0 4px;
        font-weight: 600;
      }
      .ui-form-error-summary__list {
        margin: 0;
        padding-left: 18px;
      }
      .ui-form-error-summary__link {
        color: inherit;
        text-decoration: underline;
      }
      .ui-form-error-summary__label {
        font-weight: 600;
      }
    `,
  ],
})
export class UiFormErrorSummaryComponent {
  readonly errors = input<readonly UiFormErrorItem[]>([]);

  /** From how many field errors the summary appears; an error without a field shows it always. */
  readonly threshold = input(3);

  readonly shown = computed(() => {
    const errors = this.errors();
    return errors.length >= this.threshold() || errors.some((error) => !error.fieldId);
  });

  /** Focuses the field instead of changing the address. */
  jump(event: Event, fieldId: string): void {
    const field = document.getElementById(fieldId);
    if (!field) return;
    event.preventDefault();
    field.scrollIntoView?.({ block: 'center' });
    field.focus({ preventScroll: true });
  }
}
