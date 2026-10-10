import {
  ChangeDetectionStrategy,
  afterNextRender,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  signal,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { disabled, form, FormField, maxLength, required, validate } from '@angular/forms/signals';

import { AuthService } from '@core/services/auth.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { PasswordApi } from '../password.api';
import { passwordProblemFields, problemMessage } from '../password-problem';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { ThemeService } from '@core/services/theme.service';
import { LoginStep, PasswordField } from './login.models';
import { LoginTopBarComponent } from './components/login-top-bar.component';
import { LoginHeaderComponent } from './components/login-header.component';
import { LoginResetModalComponent } from './components/login-reset-modal.component';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { fitsPasswordPolicy, PASSWORD_POLICY } from '@core/security/password-policy';

export * from './login.models';

/** Codes of a refused one-time code: the code itself is wrong or old, so the message goes under the field. */
const OTP_FIELD_CODES = new Set(['otp_invalid', 'otp_expired']);

/**
 * The sign-in page: credentials, then the one-time code when two-factor sign-in is on, then the permanent password a
 * temporary one requires (docs/guidelines/forms-ux-standard.md). Each step is a Signal Form: a field shows its own
 * error after it is left and all errors on submit, the first invalid field takes focus, and a refusal the server ties
 * to a field goes under that field. A refusal of no field (wrong credentials, no connection) is one alert above the
 * button.
 */
@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTControlComponent,
    SMTAlertComponent,
    FormField,
    UiFocusFirstInvalidDirective,
    TranslatePipe,
    SMTButtonComponent,
    LoginTopBarComponent,
    LoginHeaderComponent,
    LoginResetModalComponent,
  ],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent {
  readonly i18n = inject(I18nService);
  private authService = inject(AuthService);
  private passwordApi = inject(PasswordApi);
  private readonly injector = inject(Injector);
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroyRef = inject(DestroyRef);

  readonly step = signal<LoginStep>('credentials');
  readonly capsLockField = signal<PasswordField | null>(null);
  readonly isLoading = signal<boolean>(false);
  readonly isResetModalOpen = signal<boolean>(false);
  /** A refusal that belongs to no field, shown once above the button. */
  readonly formError = signal<string>('');
  /** Refusals the server tied to a field, by field name; shown under the field until it is edited. */
  readonly serverErrors = signal<Readonly<Record<string, string>>>({});
  readonly otpToken = signal('');
  readonly tempOldPassword = signal('');

  readonly credentials = signal({ login: '', password: '' });
  readonly otp = signal({ code: '' });
  readonly newPassword = signal({ newPassword: '', confirmPassword: '' });

  readonly passwordPolicy = PASSWORD_POLICY;

  private readonly message = (key: string, params?: Record<string, string | number>) => () =>
    this.i18n.translate(key, params);

  readonly credentialsForm = form(this.credentials, (path) => {
    disabled(path, () => this.isLoading());
    required(path.login, { message: this.message('auth.login.login_required') });
    required(path.password, { message: this.message('auth.login.password_required') });
  });

  readonly otpForm = form(this.otp, (path) => {
    disabled(path, () => this.isLoading());
    required(path.code, { message: this.message('auth.enter_six_digit_code') });
    validate(path.code, ({ value }) =>
      !value() || /^[0-9]{6}$/.test(value())
        ? null
        : { kind: 'otp_format', message: this.i18n.translate('auth.enter_six_digit_code') },
    );
    maxLength(path.code, 6);
  });

  readonly newPasswordForm = form(this.newPassword, (path) => {
    disabled(path, () => this.isLoading());
    required(path.newPassword, { message: this.message('auth.login.new_password_required') });
    validate(path.newPassword, ({ value }) =>
      !value() || fitsPasswordPolicy(value())
        ? null
        : { kind: 'policy', message: this.i18n.translate('password.policy.length_error', PASSWORD_POLICY) },
    );
    maxLength(path.newPassword, PASSWORD_POLICY.max);
    required(path.confirmPassword, { message: this.message('auth.login.confirm_password_required') });
    validate(path.confirmPassword, ({ value, valueOf }) =>
      !value() || value() === valueOf(path.newPassword)
        ? null
        : { kind: 'mismatch', message: this.i18n.translate('auth.password.mismatch') },
    );
    maxLength(path.confirmPassword, PASSWORD_POLICY.max);
  });

  constructor() {
    // Apply the saved theme on this public route before the app shell exists.
    inject(ThemeService);
    this.focusInput('login');
  }

  /** The server's message for a field, shown at once under it. */
  serverError(field: string): string {
    return this.serverErrors()[field] ?? '';
  }

  /** An edit answers the refusal: its message, and the alert above the button, go away. */
  edited(field: string): void {
    this.formError.set('');
    if (field in this.serverErrors()) {
      this.serverErrors.set(Object.fromEntries(Object.entries(this.serverErrors()).filter(([name]) => name !== field)));
    }
  }

  onLoginSubmit() {
    if (this.isLoading() || this.step() !== 'credentials' || this.isResetModalOpen()) return;
    markSMTFormFieldsTouched(this.credentialsForm);
    this.clearErrors();
    if (!this.credentialsForm().valid()) return;

    const { login, password } = this.credentials();
    this.capsLockField.set(null);
    this.isLoading.set(true);
    this.authService
      .login(login, password, navigator.userAgent)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.isLoading.set(false);
          if (res.step === 'otp') {
            this.otpToken.set(res.otpToken ?? '');
            this.otp.set({ code: '' });
            this.otpForm().reset();
            this.changeStep('otp');
          } else if (res.step === 'success' && res.user?.forcePasswordChange) {
            this.startPasswordChange(password);
          }
        },
        error: (err) => {
          this.isLoading.set(false);
          if (this.showFieldErrors(problemFieldErrors(err, { known: ['login', 'password'] }).fields)) return;
          this.formError.set(problemMessage(err, this.i18n.translate('auth.login.sign_in_failed')));
          this.focusInput('password');
        },
      });
  }

  onOtpSubmit() {
    if (this.isLoading() || this.step() !== 'otp' || !this.otpToken()) return;
    markSMTFormFieldsTouched(this.otpForm);
    this.clearErrors();
    if (!this.otpForm().valid()) return;

    this.isLoading.set(true);
    this.authService
      .verifyOtp(this.otpToken(), this.otp().code, navigator.userAgent)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.isLoading.set(false);
          if (res.step === 'success' && res.user?.forcePasswordChange) {
            this.startPasswordChange(this.credentials().password);
          }
        },
        error: (err) => {
          this.isLoading.set(false);
          const message = problemMessage(err, this.i18n.translate('auth.login.otp_failed'));
          const fields = problemFieldErrors(err, { known: ['code'], rename: { otpCode: 'code' } }).fields;
          if (this.showFieldErrors(OTP_FIELD_CODES.has(errorCode(err)) ? { code: message } : fields)) return;
          this.formError.set(message);
          this.focusInput('otp-code');
        },
      });
  }

  onChangePasswordSubmit() {
    if (this.isLoading() || this.step() !== 'must_change_password') return;
    markSMTFormFieldsTouched(this.newPasswordForm);
    this.clearErrors();
    if (!this.newPasswordForm().valid()) return;

    this.capsLockField.set(null);
    this.isLoading.set(true);
    // A committed password change must clear global authentication even if
    // navigation destroys this view before the response arrives.
    const oldPassword = this.tempOldPassword() || this.credentials().password;
    this.passwordApi.change(oldPassword, this.newPassword().newPassword).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.forgetSecrets();
        this.changeStep('credentials');
        this.authService.onPasswordChanged();
      },
      error: (err) => {
        this.isLoading.set(false);
        if (this.showFieldErrors(passwordProblemFields(err, ['newPassword']).fields)) return;
        this.formError.set(problemMessage(err, this.i18n.translate('auth.login.change_password_failed')));
        this.focusInput('new-password');
      },
    });
  }

  openResetModal() {
    if (this.isLoading()) return;
    this.capsLockField.set(null);
    this.isResetModalOpen.set(true);
  }

  checkCapsLock(event: KeyboardEvent, field: PasswordField): void {
    this.capsLockField.set(event.getModifierState('CapsLock') ? field : null);
  }

  backToCredentials(): void {
    if (this.isLoading()) return;
    this.forgetSecrets();
    this.changeStep('credentials');
  }

  private startPasswordChange(oldPassword: string): void {
    this.tempOldPassword.set(oldPassword);
    this.newPassword.set({ newPassword: '', confirmPassword: '' });
    this.newPasswordForm().reset();
    this.changeStep('must_change_password');
  }

  /** Drops every secret typed so far; the login stays for the next attempt. */
  private forgetSecrets(): void {
    this.credentials.update((value) => ({ ...value, password: '' }));
    this.tempOldPassword.set('');
    this.otpToken.set('');
    this.otp.set({ code: '' });
    this.newPassword.set({ newPassword: '', confirmPassword: '' });
    this.credentialsForm.password().reset();
    this.otpForm().reset();
    this.newPasswordForm().reset();
  }

  private clearErrors(): void {
    this.formError.set('');
    this.serverErrors.set({});
  }

  /** Shows the server's field errors and focuses the first of them; false when there were none. */
  private showFieldErrors(fields: Readonly<Record<string, string>>): boolean {
    if (Object.keys(fields).length === 0) return false;
    this.serverErrors.set(fields);
    const order: Record<LoginStep, [string, string][]> = {
      credentials: [
        ['login', 'login'],
        ['password', 'password'],
      ],
      otp: [['code', 'otp-code']],
      must_change_password: [
        ['newPassword', 'new-password'],
        ['confirmPassword', 'confirm-new-password'],
      ],
    };
    const first = order[this.step()].find(([field]) => field in fields);
    if (first) this.focusInput(first[1]);
    return true;
  }

  private changeStep(step: LoginStep): void {
    this.clearErrors();
    this.capsLockField.set(null);
    this.step.set(step);
    this.focusInput(step === 'credentials' ? 'login' : step === 'otp' ? 'otp-code' : 'new-password');
  }

  private focusInput(id: string, attempts = 5): void {
    if (this.destroyRef.destroyed) return;
    afterNextRender(
      () => {
        // The field is enabled only once isLoading() is false and smt-input has rendered that: an input
        // still disabled here (a render that has not caught up yet) waits for the next render.
        queueMicrotask(() => {
          if (this.destroyRef.destroyed || this.isResetModalOpen()) return;
          const input = this.element.nativeElement.querySelector<HTMLInputElement>(`#${id}`);
          if (input?.disabled && attempts > 1) {
            this.focusInput(id, attempts - 1);
            return;
          }
          input?.focus();
        });
      },
      { injector: this.injector },
    );
  }
}

function errorCode(error: unknown): string {
  const code = error && typeof error === 'object' ? (error as { code?: unknown }).code : null;
  return typeof code === 'string' ? code.toLowerCase() : '';
}
