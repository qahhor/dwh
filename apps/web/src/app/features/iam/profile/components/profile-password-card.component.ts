import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, Injector, signal } from '@angular/core';
import { NgClass } from '@angular/common';
import { form, FormField, maxLength, required, validate } from '@angular/forms/signals';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { focusFirstInvalid, UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { AuthService } from '@core/services/auth.service';
import { fitsPasswordPolicy, PASSWORD_POLICY } from '@core/security/password-policy';
import { passwordProblemFields, problemMessage } from '@features/auth/password-problem';
import { ProfileApi } from '../profile.api';
import { PasswordForm, passwordStrengthOf } from '../profile.models';

const EMPTY: PasswordForm = { oldPassword: '', newPassword: '', confirmPassword: '' };

/**
 * Changes the signed-in person's password (docs/guidelines/forms-ux-standard.md). A Signal Form: every field is
 * required and explains its error under itself on blur and on submit, focus goes to the first invalid field, and the
 * server's refusal of a field (a wrong current password, a password the policy rejects) goes under that field. A
 * success ends every session, this one included.
 */
@Component({
  selector: 'app-profile-password-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTControlComponent,
    SMTAlertComponent,
    FormField,
    UiFocusFirstInvalidDirective,
    UiFormActionsComponent,
    TranslatePipe,
    NgClass,
  ],
  templateUrl: './profile-password-card.component.html',
  styleUrl: './profile-password-card.component.css',
})
export class ProfilePasswordCardComponent {
  private readonly profile = inject(ProfileApi);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly model = signal<PasswordForm>({ ...EMPTY });
  readonly saving = signal(false);
  /** Refusals the server tied to a field; shown under it until it is edited. */
  readonly serverErrors = signal<Readonly<Record<string, string>>>({});
  /** A refusal of no field, shown above the button. */
  readonly formError = signal('');

  readonly strength = computed(() => passwordStrengthOf(this.model().newPassword));
  readonly hasMinLength = computed(() => fitsPasswordPolicy(this.model().newPassword));
  readonly hasLettersAndNumbers = computed(() => {
    const value = this.model().newPassword;
    return /[a-zA-ZЀ-ӿ]/.test(value) && /\d/.test(value);
  });
  readonly hasMixedCase = computed(() => {
    const value = this.model().newPassword;
    return /[a-z\u0430-\u044f]/.test(value) && /[A-Z\u0410-\u042f]/.test(value);
  });
  readonly passwordsMatch = computed(() => {
    const { newPassword, confirmPassword } = this.model();
    return !!newPassword && newPassword === confirmPassword;
  });

  readonly passwordPolicy = PASSWORD_POLICY;

  private readonly message = (key: string, params?: Record<string, string | number>) => () =>
    this.i18n.translate(key, params);

  readonly passwordForm = form(this.model, (path) => {
    required(path.oldPassword, { message: this.message('iam.profile.password.enter_current_password') });
    required(path.newPassword, { message: this.message('password.policy.length_error', PASSWORD_POLICY) });
    validate(path.newPassword, ({ value }) =>
      !value() || fitsPasswordPolicy(value())
        ? null
        : { kind: 'policy', message: this.i18n.translate('password.policy.length_error', PASSWORD_POLICY) },
    );
    maxLength(path.newPassword, PASSWORD_POLICY.max);
    required(path.confirmPassword, { message: this.message('iam.confirm_new_password') });
    validate(path.confirmPassword, ({ value, valueOf }) =>
      !value() || value() === valueOf(path.newPassword)
        ? null
        : { kind: 'mismatch', message: this.i18n.translate('iam.passwords_do_not_match') },
    );
  });

  serverError(field: keyof PasswordForm): string {
    return this.serverErrors()[field] ?? '';
  }

  edited(field: keyof PasswordForm): void {
    this.formError.set('');
    if (field in this.serverErrors()) {
      this.serverErrors.set(Object.fromEntries(Object.entries(this.serverErrors()).filter(([name]) => name !== field)));
    }
  }

  submit(): void {
    if (this.saving()) return;
    markSMTFormFieldsTouched(this.passwordForm);
    this.serverErrors.set({});
    this.formError.set('');
    if (!this.passwordForm().valid()) return;

    const { oldPassword, newPassword } = this.model();
    this.saving.set(true);
    this.profile.changePassword(oldPassword, newPassword).subscribe({
      next: () => {
        this.saving.set(false);
        this.model.set({ ...EMPTY });
        this.passwordForm().reset();
        this.auth.onPasswordChanged();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        const fields = passwordProblemFields(err, ['oldPassword', 'newPassword', 'confirmPassword']).fields;
        if (Object.keys(fields).length > 0) {
          this.serverErrors.set(fields);
          focusFirstInvalid(this.host.nativeElement, this.injector);
          return;
        }
        this.formError.set(problemMessage(err, this.i18n.translate('iam.profile.password.change_failed')));
      },
    });
  }
}
