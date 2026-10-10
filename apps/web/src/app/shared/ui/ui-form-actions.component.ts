import { booleanAttribute, ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { I18nService } from '@core/services/i18n.service';
import { SMTButtonComponent, type SMTButtonVariant } from '@shared/ui-kit/components/button';

/**
 * The button row of a form (docs/guidelines/forms-ux-standard.md, section 6): one order and one behaviour for every
 * dialog and page form. The secondary action ("Cancel") comes first, the primary one ("Save", "Create") last, on the
 * right; content marked `uiFormActionsStart` (a destructive action, a link) sits apart on the left.
 *
 * - With `form` the primary button submits that form (`type="submit"`, the HTML `form` attribute), so Enter in a field
 *   and the button run the same handler. Without it the button emits `submitted`.
 * - While `submitting` the primary button shows the kit spinner and is disabled, and `submitted` is not emitted again:
 *   one request per press. Cancel is disabled too, so a dialog cannot close under a running save.
 * - The primary button is never disabled because the form is invalid: the form shows its errors instead. Use
 *   `submitDisabled` only for a state the person cannot fix in the form (no right, nothing to save yet).
 *
 * In a dialog the row is marked `footer`, so smt-dialog keeps it at the bottom while the body scrolls:
 *
 * <form id="role-create" (submit)="$event.preventDefault(); save()" novalidate>…</form>
 * <ui-form-actions footer form="role-create" [submitLabel]="'common.create' | t" [submitting]="saving()"
 *   (cancelled)="close()" />
 */
@Component({
  selector: 'ui-form-actions',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTButtonComponent],
  host: { class: 'ui-form-actions', role: 'group' },
  template: `
    <div class="ui-form-actions__start"><ng-content select="[uiFormActionsStart]" /></div>
    <ng-content />
    @if (showCancel()) {
      <button
        smt-button
        type="button"
        smtVariant="secondary"
        data-testid="form-cancel"
        [disabled]="submitting()"
        (click)="cancel()"
      >
        {{ cancelText() }}
      </button>
    }
    <button
      smt-button
      [attr.type]="form() ? 'submit' : 'button'"
      [attr.form]="form() || null"
      [smtVariant]="submitVariant()"
      [smtLoading]="submitting()"
      [disabled]="submitDisabled()"
      data-testid="form-submit"
      (click)="submit()"
    >
      {{ submitText() }}
    </button>
  `,
  styles: [
    `
      :host {
        display: flex;
        align-items: center;
        justify-content: flex-end;
        flex-wrap: wrap;
        gap: 8px;
      }
      .ui-form-actions__start {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-right: auto;
      }
      .ui-form-actions__start:empty {
        display: none;
      }
    `,
  ],
})
export class UiFormActionsComponent {
  private readonly i18n = inject(I18nService);

  /** The id of the form the primary button submits; empty: the button emits `submitted`. */
  readonly form = input('');

  /** The primary button's text; "Save" (`common.save`) when empty. */
  readonly submitLabel = input('');

  /** The secondary button's text; "Cancel" (`common.cancel`) when empty. */
  readonly cancelLabel = input('');

  readonly submitting = input(false, { transform: booleanAttribute });

  /** Only for a state the form cannot fix (no right, nothing changed); never for invalid values. */
  readonly submitDisabled = input(false, { transform: booleanAttribute });

  readonly showCancel = input(true, { transform: booleanAttribute });

  /** `danger` when the primary action destroys something (a confirmation dialog). */
  readonly submitVariant = input<Extract<SMTButtonVariant, 'primary' | 'danger'>>('primary');

  /** The primary button was pressed and no form is linked; not emitted while submitting. */
  readonly submitted = output<void>();

  readonly cancelled = output<void>();

  readonly submitText = computed(() => {
    this.i18n.currentLang();
    return this.submitLabel() || this.i18n.translate('common.save');
  });

  readonly cancelText = computed(() => {
    this.i18n.currentLang();
    return this.cancelLabel() || this.i18n.translate('common.cancel');
  });

  submit(): void {
    if (this.form() || this.submitting() || this.submitDisabled()) return;
    this.submitted.emit();
  }

  cancel(): void {
    if (!this.submitting()) this.cancelled.emit();
  }
}
