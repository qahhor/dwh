import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTTextareaComponent } from '@shared/ui-kit/components/forms/textarea';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';

/** The longest comment of a transition, as the server keeps it (ADR-0043). */
export const MAX_TRANSITION_COMMENT = 1000;

/**
 * The comment of a transition of a live form's document (ADR-0043): the record moves to its next status with the words
 * of the person who moves it — required for a transition that asks for them (a rejection), optional otherwise. The
 * server checks the same and keeps the comment in the record's history; a refusal shows under the field.
 */
@Component({
  selector: 'smt-entity-transition-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTControlComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTTextareaComponent,
    UiFormActionsComponent,
  ],
  template: `
    @if (open()) {
      <smt-dialog [open]="open()" [smtTitle]="title()" smtSize="md" (closed)="close()">
        <ng-template smtDialogContent>
          <form
            id="entity-transition"
            novalidate
            data-testid="entity-transition-form"
            (submit)="$event.preventDefault(); submit()"
          >
            <smt-control
              [smtLabel]="'ui.entity_page.transition_comment' | t"
              [smtHint]="
                (required() ? 'ui.entity_page.transition_comment_required' : 'ui.entity_page.transition_comment_hint')
                  | t
              "
              [smtError]="error()"
              [required]="required()"
            >
              <smt-textarea
                smtFieldId="entity-transition-comment"
                name="transitionComment"
                smtFocusInitial
                [rows]="4"
                [maxLength]="max"
                [required]="required()"
                [value]="comment()"
                (valueChange)="edit($event)"
              />
            </smt-control>
          </form>
          <ui-form-actions
            footer
            form="entity-transition"
            data-testid="entity-transition-actions"
            [submitLabel]="title()"
            [submitting]="busy()"
            (cancelled)="close()"
          />
        </ng-template>
      </smt-dialog>
    }
  `,
})
export class SMTEntityTransitionDialogComponent {
  private readonly i18n = inject(I18nService);
  readonly open = input(false);
  /** The name of the transition: the dialog's title and its button. */
  readonly title = input('');
  readonly required = input(false);
  readonly busy = input(false);
  /** The server's refusal of the comment, shown under it. */
  readonly serverError = input('');
  readonly confirmed = output<string>();
  readonly closed = output<void>();

  readonly comment = signal('');
  private readonly tried = signal(false);
  readonly error = computed(() => {
    if (this.serverError()) return this.serverError();
    return this.tried() && this.required() && !this.comment().trim()
      ? this.i18n.translate('ui.entity_page.transition_comment_missing')
      : '';
  });

  readonly max = MAX_TRANSITION_COMMENT;

  constructor() {
    effect(() => {
      if (this.open()) {
        this.comment.set('');
        this.tried.set(false);
      }
    });
  }

  edit(value: unknown): void {
    this.comment.set(typeof value === 'string' ? value : '');
  }

  submit(): void {
    if (this.busy()) return;
    this.tried.set(true);
    if (this.required() && !this.comment().trim()) return;
    this.confirmed.emit(this.comment().trim());
  }

  close(): void {
    if (!this.busy()) this.closed.emit();
  }
}
