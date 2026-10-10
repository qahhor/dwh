import { ChangeDetectionStrategy, Component, ElementRef, inject, Injector, signal } from '@angular/core';
import { Router } from '@angular/router';
import { disabled, form, FormField, maxLength, required, validate } from '@angular/forms/signals';
import { PasswordApi } from '../password.api';
import { passwordProblemFields, problemMessage } from '../password-problem';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { focusFirstInvalid, UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { LoginHeaderComponent } from '../login/components/login-header.component';
import { LoginTopBarComponent } from '../login/components/login-top-bar.component';
import { fitsPasswordPolicy, PASSWORD_POLICY } from '@core/security/password-policy';

type ResetState = 'form' | 'done' | 'invalid';

/**
 * Sets a new password by the one-time link sent to the user's confirmed email or Telegram.
 *
 * The token comes in the URL fragment (`/reset-password#token=...`): a fragment never reaches the server logs or
 * the Referer header. It is read once and removed from the address bar and the history.
 *
 * The form follows docs/guidelines/forms-ux-standard.md: each field explains its own error, the policy refusal of
 * the server goes under the new password, and a refusal of no field is an alert above the button.
 */
@Component({
  selector: 'app-reset-password',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    SMTAlertComponent,
    SMTControlComponent,
    SMTInputComponent,
    FormField,
    UiFocusFirstInvalidDirective,
    LoginHeaderComponent,
    LoginTopBarComponent,
  ],
  styleUrl: '../login/login.component.css',
  template: `
    <main class="login-wrapper">
      <app-login-top-bar></app-login-top-bar>
      <div class="login-card">
        <app-login-header></app-login-header>

        @if (state() === 'form') {
          <form
            uiFocusFirstInvalid
            (submit)="$event.preventDefault(); submit()"
            novalidate
            class="login-form"
            [attr.aria-busy]="isLoading()"
          >
            <div class="otp-banner">
              <span class="material-symbols-outlined" aria-hidden="true">lock_reset</span>
              <div>
                <strong>{{ 'auth.reset.title' | t }}</strong>
                <p id="reset-password-hint">{{ 'auth.reset.hint' | t: passwordPolicy }}</p>
              </div>
            </div>

            <smt-control
              class="form-group"
              [smtLabel]="'auth.password.new_password' | t"
              [smtError]="serverError('newPassword')"
            >
              <smt-input
                smtFieldId="reset-new-password"
                type="password"
                [formField]="resetForm.newPassword"
                (edited)="edited('newPassword')"
                autocomplete="new-password"
                [spellcheck]="false"
                smtDescribedBy="reset-password-hint"
              />
            </smt-control>

            <smt-control
              class="form-group"
              [smtLabel]="'auth.password.repeat_new_password' | t"
              [smtError]="serverError('confirmPassword')"
            >
              <smt-input
                smtFieldId="reset-confirm-password"
                type="password"
                [formField]="resetForm.confirmPassword"
                (edited)="edited('confirmPassword')"
                autocomplete="new-password"
                [spellcheck]="false"
              />
            </smt-control>

            @if (formError()) {
              <smt-alert id="reset-password-error" smtTone="danger">{{ formError() }}</smt-alert>
            }

            <button
              smt-button
              type="submit"
              smtVariant="primary"
              smtSize="lg"
              [smtLoading]="isLoading()"
              [smtFullWidth]="true"
              class="submit-btn"
            >
              {{ 'auth.reset.submit' | t }}
            </button>
          </form>
        } @else {
          <div class="login-form">
            <p [attr.role]="state() === 'invalid' ? 'alert' : 'status'">
              {{ (state() === 'done' ? 'auth.reset.done' : 'auth.reset.invalid_link') | t }}
            </p>
            <button
              smt-button
              type="button"
              smtVariant="primary"
              smtSize="lg"
              [smtFullWidth]="true"
              (click)="toLogin()"
            >
              {{ 'auth.reset.to_login' | t }}
            </button>
          </div>
        }
      </div>
    </main>
  `,
})
export class ResetPasswordComponent {
  private readonly passwordApi = inject(PasswordApi);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18nService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly state = signal<ResetState>('form');
  readonly isLoading = signal(false);
  /** A refusal of no field, shown once above the button. */
  readonly formError = signal('');
  /** Refusals the server tied to a field; shown under it until it is edited. */
  readonly serverErrors = signal<Readonly<Record<string, string>>>({});
  readonly model = signal({ newPassword: '', confirmPassword: '' });

  readonly passwordPolicy = PASSWORD_POLICY;
  private readonly token = readToken();

  readonly resetForm = form(this.model, (path) => {
    disabled(path, () => this.isLoading());
    required(path.newPassword, { message: () => this.i18n.translate('auth.login.new_password_required') });
    validate(path.newPassword, ({ value }) =>
      !value() || fitsPasswordPolicy(value())
        ? null
        : { kind: 'policy', message: this.i18n.translate('password.policy.length_error', PASSWORD_POLICY) },
    );
    maxLength(path.newPassword, PASSWORD_POLICY.max);
    required(path.confirmPassword, { message: () => this.i18n.translate('auth.login.confirm_password_required') });
    validate(path.confirmPassword, ({ value, valueOf }) =>
      !value() || value() === valueOf(path.newPassword)
        ? null
        : { kind: 'mismatch', message: this.i18n.translate('auth.password.mismatch') },
    );
    maxLength(path.confirmPassword, PASSWORD_POLICY.max);
  });

  constructor() {
    if (!this.token) this.state.set('invalid');
  }

  serverError(field: string): string {
    return this.serverErrors()[field] ?? '';
  }

  edited(field: string): void {
    this.formError.set('');
    if (field in this.serverErrors()) {
      this.serverErrors.set(Object.fromEntries(Object.entries(this.serverErrors()).filter(([name]) => name !== field)));
    }
  }

  submit(): void {
    if (this.isLoading()) return;
    markSMTFormFieldsTouched(this.resetForm);
    this.formError.set('');
    this.serverErrors.set({});
    if (!this.resetForm().valid()) return;
    this.isLoading.set(true);
    this.passwordApi.confirmReset(this.token, this.model().newPassword).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.model.set({ newPassword: '', confirmPassword: '' });
        this.state.set('done');
      },
      error: (err: unknown) => {
        this.isLoading.set(false);
        if (errorCode(err) === 'reset_code_invalid') {
          this.state.set('invalid');
          return;
        }
        const fields = passwordProblemFields(err, ['newPassword']).fields;
        if (Object.keys(fields).length > 0) {
          this.serverErrors.set(fields);
          focusFirstInvalid(this.host.nativeElement, this.injector);
          return;
        }
        this.formError.set(problemMessage(err, this.i18n.translate('auth.reset.failed')));
      },
    });
  }

  toLogin(): void {
    void this.router.navigate(['/login'], { replaceUrl: true });
  }
}

/** Reads the token from `#token=...` and drops the fragment, so the token stays out of the history. */
function readToken(): string {
  const params = new URLSearchParams(window.location.hash.replace(/^#/, ''));
  const token = params.get('token') ?? '';
  if (window.location.hash) {
    window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search);
  }
  return token;
}

function errorCode(error: unknown): string | null {
  const code = error && typeof error === 'object' ? (error as { code?: unknown }).code : null;
  return typeof code === 'string' ? code.toLowerCase() : null;
}
