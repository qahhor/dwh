import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { PasswordApi } from '../password.api';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { SMTButtonComponent } from '../../../shared/ui-kit/components/button';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../shared/ui-kit/components/forms/input';
import { LoginHeaderComponent } from '../login/components/login-header.component';
import { LoginTopBarComponent } from '../login/components/login-top-bar.component';
import { fitsPasswordPolicy, PASSWORD_POLICY } from '../../../core/security/password-policy';

type ResetState = 'form' | 'done' | 'invalid';

/**
 * Sets a new password by the one-time link sent to the user's confirmed email or Telegram.
 *
 * The token comes in the URL fragment (`/reset-password#token=...`): a fragment never reaches the server logs or
 * the Referer header. It is read once and removed from the address bar and the history.
 */
@Component({
  selector: 'app-reset-password',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    TranslatePipe,
    SMTButtonComponent,
    SMTInputComponent,
    SMTInputValueAccessor,
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
          <form (ngSubmit)="submit()" class="login-form" [attr.aria-busy]="isLoading()">
            <div class="otp-banner">
              <span class="material-symbols-outlined" aria-hidden="true">lock_reset</span>
              <div>
                <strong>{{ 'auth.reset.title' | t }}</strong>
                <p id="reset-password-hint">{{ 'auth.reset.hint' | t: passwordPolicy }}</p>
              </div>
            </div>

            <div class="form-group">
              <label class="form-label" for="reset-new-password">{{ 'auth.novyy_parol' | t }}</label>
              <smt-input
                smtFieldId="reset-new-password"
                type="password"
                [(ngModel)]="newPassword"
                (ngModelChange)="formError.set('')"
                name="newPassword"
                required
                [minLength]="passwordPolicy.min"
                [maxLength]="passwordPolicy.max"
                autocomplete="new-password"
                [spellcheck]="false"
                [smtInvalid]="!!formError()"
                smtDescribedBy="reset-password-hint"
                [disabled]="isLoading()"
              />
            </div>

            <div class="form-group">
              <label class="form-label" for="reset-confirm-password">{{ 'auth.povtorite_novyy_parol' | t }}</label>
              <smt-input
                smtFieldId="reset-confirm-password"
                type="password"
                [(ngModel)]="confirmPassword"
                (ngModelChange)="formError.set('')"
                name="confirmPassword"
                required
                [minLength]="passwordPolicy.min"
                [maxLength]="passwordPolicy.max"
                autocomplete="new-password"
                [spellcheck]="false"
                [smtInvalid]="!!formError()"
                [smtDescribedBy]="formError() ? 'reset-password-error' : null"
                [disabled]="isLoading()"
              />
            </div>

            @if (formError()) {
              <p id="reset-password-error" class="form-error" role="alert">{{ formError() }}</p>
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

  readonly state = signal<ResetState>('form');
  readonly isLoading = signal(false);
  readonly formError = signal('');
  readonly newPassword = signal('');
  readonly confirmPassword = signal('');

  readonly passwordPolicy = PASSWORD_POLICY;
  private readonly token = readToken();

  constructor() {
    if (!this.token) this.state.set('invalid');
  }

  submit(): void {
    if (this.isLoading()) return;
    if (!fitsPasswordPolicy(this.newPassword())) {
      this.formError.set(this.i18n.translate('password.policy.length_error', PASSWORD_POLICY));
      return;
    }
    if (this.newPassword() !== this.confirmPassword()) {
      this.formError.set(this.i18n.translate('auth.vvedennye_paroli_ne_sovpadayut'));
      return;
    }
    this.isLoading.set(true);
    this.passwordApi.confirmReset(this.token, this.newPassword()).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.newPassword.set('');
        this.confirmPassword.set('');
        this.state.set('done');
      },
      error: (err: unknown) => {
        this.isLoading.set(false);
        if (errorCode(err) === 'reset_code_invalid') {
          this.state.set('invalid');
          return;
        }
        this.formError.set(errorDetail(err) ?? this.i18n.translate('auth.reset.failed'));
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

function errorDetail(error: unknown): string | null {
  if (error && typeof error === 'object') {
    const value = error as { detail?: unknown; message?: unknown };
    if (typeof value.detail === 'string' && value.detail.trim()) return value.detail;
    if (typeof value.message === 'string' && value.message.trim()) return value.message;
  }
  return null;
}
