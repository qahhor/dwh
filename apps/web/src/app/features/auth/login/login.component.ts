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

import { AuthService } from '@core/services/auth.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { PasswordApi } from '../password.api';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { ThemeService } from '@core/services/theme.service';
import { LoginStep, PasswordField } from './login.models';
import { LoginTopBarComponent } from './components/login-top-bar.component';
import { LoginHeaderComponent } from './components/login-header.component';
import { LoginResetModalComponent } from './components/login-reset-modal.component';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { fitsPasswordPolicy, PASSWORD_POLICY } from '@core/security/password-policy';

export * from './login.models';

@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
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
  readonly formError = signal<string>('');
  readonly login = signal('');
  readonly password = signal('');
  readonly otpCode = signal('');
  readonly otpToken = signal('');
  readonly newPassword = signal('');
  readonly confirmNewPassword = signal('');
  readonly tempOldPassword = signal('');

  readonly passwordPolicy = PASSWORD_POLICY;
  private readonly uiI18n = this.i18n;

  constructor() {
    // Apply the saved theme on this public route before the app shell exists.
    inject(ThemeService);
    this.focusInput('login');
  }

  onLoginSubmit() {
    if (this.isLoading() || this.step() !== 'credentials' || this.isResetModalOpen()) return;
    if (!this.login() || !this.password()) {
      this.formError.set(this.uiI18n.translate('auth.enter_login_and_password'));
      this.focusInput(!this.login() ? 'login' : 'password');
      return;
    }

    this.formError.set('');
    this.capsLockField.set(null);
    this.isLoading.set(true);
    this.authService
      .login(this.login(), this.password(), navigator.userAgent)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.isLoading.set(false);
          if (res.step === 'otp') {
            this.otpToken.set(res.otpToken ?? '');
            this.otpCode.set('');
            this.changeStep('otp');
          } else if (res.step === 'success' && res.user?.forcePasswordChange) {
            this.tempOldPassword.set(this.password());
            this.newPassword.set('');
            this.confirmNewPassword.set('');
            this.changeStep('must_change_password');
          }
        },
        error: (err) => {
          this.isLoading.set(false);
          this.formError.set(
            this.errorMessage(err, this.uiI18n.translate('auth.ne_udalos_vypolnit_vhod_proverte_dannye_i_povtor')),
          );
          this.focusInput('password');
        },
      });
  }

  onOtpSubmit() {
    if (this.isLoading() || this.step() !== 'otp' || !this.otpToken()) return;
    if (!/^[0-9]{6}$/.test(this.otpCode())) {
      this.formError.set(this.uiI18n.translate('auth.enter_six_digit_code'));
      this.focusInput('otp-code');
      return;
    }

    this.formError.set('');
    this.isLoading.set(true);
    this.authService
      .verifyOtp(this.otpToken(), this.otpCode(), navigator.userAgent)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.isLoading.set(false);
          if (res.step === 'success' && res.user?.forcePasswordChange) {
            this.tempOldPassword.set(this.password());
            this.newPassword.set('');
            this.confirmNewPassword.set('');
            this.changeStep('must_change_password');
          }
        },
        error: (err) => {
          this.isLoading.set(false);
          this.formError.set(
            this.errorMessage(err, this.uiI18n.translate('auth.kod_ne_podtverzhden_proverte_kod_i_povtorite_pop')),
          );
          this.focusInput('otp-code');
        },
      });
  }

  onChangePasswordSubmit() {
    if (this.isLoading() || this.step() !== 'must_change_password') return;
    if (!this.newPassword() || !this.confirmNewPassword()) {
      this.formError.set(this.uiI18n.translate('auth.enter_and_confirm_new_password'));
      this.focusInput(!this.newPassword() ? 'new-password' : 'confirm-new-password');
      return;
    }
    if (!fitsPasswordPolicy(this.newPassword())) {
      this.formError.set(this.uiI18n.translate('password.policy.length_error', PASSWORD_POLICY));
      this.focusInput('new-password');
      return;
    }
    if (this.newPassword() !== this.confirmNewPassword()) {
      this.formError.set(this.uiI18n.translate('auth.vvedennye_paroli_ne_sovpadayut'));
      this.focusInput('confirm-new-password');
      return;
    }

    this.formError.set('');
    this.capsLockField.set(null);
    this.isLoading.set(true);
    // A committed password change must clear global authentication even if
    // navigation destroys this view before the response arrives.
    this.passwordApi.change(this.tempOldPassword() || this.password(), this.newPassword()).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.password.set('');
        this.tempOldPassword.set('');
        this.newPassword.set('');
        this.confirmNewPassword.set('');
        this.otpToken.set('');
        this.otpCode.set('');
        this.changeStep('credentials');
        this.authService.onPasswordChanged();
      },
      error: (err) => {
        this.isLoading.set(false);
        this.formError.set(
          this.errorMessage(err, this.uiI18n.translate('auth.ne_udalos_izmenit_parol_proverte_slozhnost_parol')),
        );
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

  passwordDescription(field: PasswordField): string | null {
    const descriptions: string[] = [];
    if (field !== 'password') descriptions.push('password-policy-hint');
    if (this.formError()) descriptions.push(field === 'password' ? 'login-error' : 'password-change-error');
    if (this.capsLockField() === field) descriptions.push(`${field}-caps-lock`);
    return descriptions.join(' ') || null;
  }

  backToCredentials(): void {
    if (this.isLoading()) return;
    this.password.set('');
    this.tempOldPassword.set('');
    this.otpToken.set('');
    this.otpCode.set('');
    this.newPassword.set('');
    this.confirmNewPassword.set('');
    this.changeStep('credentials');
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object') {
      const value = error as { detail?: unknown; message?: unknown };
      if (typeof value.detail === 'string' && value.detail.trim()) return value.detail;
      if (typeof value.message === 'string' && value.message.trim()) return value.message;
    }
    return fallback;
  }

  private changeStep(step: LoginStep): void {
    this.formError.set('');
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
