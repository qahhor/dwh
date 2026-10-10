import { ChangeDetectionStrategy, Component, effect, inject, signal, input, output, untracked } from '@angular/core';
import { email, form, FormField, required } from '@angular/forms/signals';

import { PasswordApi } from '@features/auth/password.api';
import { problemMessage } from '@features/auth/password-problem';
import { ToastService } from '@core/services/toast.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { problemFieldErrors } from '@shared/ui/problem-fields';

/**
 * Asks for a password reset link (docs/guidelines/forms-ux-standard.md): the email field takes focus when the dialog
 * opens, an empty or malformed address is explained under the field, and a refusal of no field is an alert in the
 * dialog. Closing with a typed address asks first.
 */
@Component({
  selector: 'app-login-reset-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTControlComponent,
    SMTAlertComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    UiFormActionsComponent,
    UiFocusFirstInvalidDirective,
    FormField,
    TranslatePipe,
  ],
  template: `
    <smt-dialog
      [open]="isOpen()"
      [smtTitle]="'auth.reset_request.title' | t"
      smtSize="sm"
      [dismissible]="!isResetLoading()"
      (closed)="requestClose()"
    >
      <ng-template smtDialogContent>
        <form
          body
          id="login-reset-form"
          class="reset-body"
          uiFocusFirstInvalid
          novalidate
          (submit)="$event.preventDefault(); sendResetRequest()"
        >
          <p id="reset-hint" class="reset-hint">{{ 'auth.reset.request_hint' | t }}</p>
          <smt-control [smtLabel]="'auth.reset_request.email' | t" [smtError]="emailError()">
            <smt-input
              smtFieldId="reset-email"
              type="email"
              [formField]="resetForm.email"
              placeholder="user@company.com"
              autocomplete="email"
              smtDescribedBy="reset-hint"
              smtFocusInitial
              (edited)="edited()"
            />
          </smt-control>
          @if (resetError()) {
            <smt-alert smtTone="danger">{{ resetError() }}</smt-alert>
          }
        </form>
        <ui-form-actions
          footer
          form="login-reset-form"
          [submitLabel]="'auth.reset.send_link' | t"
          [submitting]="isResetLoading()"
          (cancelled)="requestClose()"
        />
      </ng-template>
    </smt-dialog>
  `,
  styleUrl: './login-reset-modal.component.css',
})
export class LoginResetModalComponent {
  private readonly passwordApi = inject(PasswordApi);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  readonly isOpen = input(false);

  readonly closeModal = output<void>();

  /** A refusal of no field, shown as an alert in the dialog. */
  readonly resetError = signal<string>('');
  /** The server's word about the address itself, shown under the field. */
  readonly emailError = signal<string>('');
  readonly isResetLoading = signal<boolean>(false);

  readonly model = signal({ email: '' });

  readonly resetForm = form(this.model, (path) => {
    required(path.email, { message: () => this.i18n.translate('auth.reset_request.email_required') });
    email(path.email);
  });

  private readonly askDiscard = discardChangesQuestion();

  constructor() {
    // Every opening starts from an empty, untouched form.
    effect(() => {
      if (this.isOpen()) untracked(() => this.reset());
    });
  }

  edited(): void {
    this.resetError.set('');
    this.emailError.set('');
  }

  /** Escape, the backdrop, the close button and Cancel: a typed address is lost only after a question. */
  requestClose(): void {
    if (this.isResetLoading()) return;
    this.askDiscard(!!this.model().email.trim()).subscribe((discard) => {
      if (discard) this.close();
    });
  }

  sendResetRequest(): void {
    if (this.isResetLoading()) return;
    markSMTFormFieldsTouched(this.resetForm);
    this.edited();
    if (!this.resetForm().valid()) return;
    this.isResetLoading.set(true);
    this.passwordApi.requestReset(this.model().email.trim()).subscribe({
      next: () => {
        this.isResetLoading.set(false);
        this.close();
        this.toast.success(this.i18n.translate('auth.reset.request_sent'));
      },
      error: (err) => {
        this.isResetLoading.set(false);
        const field = problemFieldErrors(err, { known: ['email'] }).fields['email'];
        if (field) this.emailError.set(field);
        else this.resetError.set(problemMessage(err, this.i18n.translate('auth.reset_request.send_failed')));
      },
    });
  }

  private close(): void {
    this.reset();
    this.closeModal.emit();
  }

  private reset(): void {
    this.model.set({ email: '' });
    this.resetForm().reset();
    this.edited();
  }
}
