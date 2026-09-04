import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { ApiService } from '../../../core/services/api.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule, UiButtonComponent, UiModalComponent],
  template: `
    <main class="login-wrapper">
      <div class="login-card">
        <div class="login-header">
          <div class="brand-lockup" aria-label="SmartupCMS">
            <span class="brand-mark" aria-hidden="true">S</span>
            <span class="brand-name" aria-hidden="true">SmartupCMS</span>
          </div>
          <h1 class="login-title">Корпоративный вход</h1>
          <p class="login-subtitle">Платформа управления данными и задачами</p>
        </div>

        <!-- Step 1: Login & Password Form -->
        <form *ngIf="step() === 'credentials'" (ngSubmit)="onLoginSubmit()" class="login-form">
          <div class="form-group">
            <label class="form-label" for="login">Логин или Email</label>
            <input
              id="login"
              type="text"
              class="form-input"
              [(ngModel)]="login"
              name="login"
              required
              autocomplete="username"
              placeholder="user@company.com"
              aria-required="true"
              [attr.aria-invalid]="formError() ? 'true' : null"
              [attr.aria-describedby]="formError() ? 'login-error' : null"
              [disabled]="isLoading()"
            />
          </div>

          <div class="form-group">
            <div class="password-label-row">
              <label class="form-label" for="password">Пароль</label>
              <button type="button" class="forgot-link" (click)="openResetModal()">Забыли пароль?</button>
            </div>
            <input
              id="password"
              type="password"
              class="form-input"
              [(ngModel)]="password"
              name="password"
              required
              autocomplete="current-password"
              placeholder="••••••••"
              aria-required="true"
              [attr.aria-invalid]="formError() ? 'true' : null"
              [attr.aria-describedby]="formError() ? 'login-error' : null"
              [disabled]="isLoading()"
            />
          </div>

          <p *ngIf="formError()" id="login-error" class="form-error" role="alert">{{ formError() }}</p>

          <ui-button
            type="submit"
            variant="primary"
            size="lg"
            [loading]="isLoading()"
            [fullWidth]="true"
            class="submit-btn"
          >
            Войти в систему
          </ui-button>

        </form>

        <!-- Step 2: 2FA OTP Code Verification -->
        <form *ngIf="step() === 'otp'" (ngSubmit)="onOtpSubmit()" class="login-form">
          <div class="otp-banner">
            <span class="material-symbols-outlined">shield_person</span>
            <div>
              <strong>Двухфакторная аутентификация</strong>
              <p id="otp-hint">Введите 6-значный код подтверждения, отправленный в ваш Telegram</p>
            </div>
          </div>

          <div class="form-group">
            <label class="form-label" for="otp-code">Код подтверждения (OTP)</label>
            <input
              id="otp-code"
              type="text"
              class="form-input otp-input tabular-nums"
              [(ngModel)]="otpCode"
              name="otpCode"
              required
              maxlength="6"
              placeholder="123456"
              inputmode="numeric"
              autocomplete="one-time-code"
              pattern="[0-9]*"
              aria-required="true"
              aria-describedby="otp-hint"
              [attr.aria-invalid]="formError() ? 'true' : null"
              [disabled]="isLoading()"
              autofocus
            />
          </div>

          <p *ngIf="formError()" class="form-error" role="alert">{{ formError() }}</p>

          <div class="otp-actions">
            <ui-button
              type="submit"
              variant="primary"
              size="lg"
              [loading]="isLoading()"
              [fullWidth]="true"
              class="submit-btn"
            >
              Подтвердить вход
            </ui-button>

            <ui-button
              type="button"
              variant="ghost"
              size="md"
              (onClick)="step.set('credentials')"
            >
              Вернуться назад
            </ui-button>
          </div>
        </form>

        <!-- Step 3: Mandatory Password Change on First Login -->
        <form *ngIf="step() === 'must_change_password'" (ngSubmit)="onChangePasswordSubmit()" class="login-form">
          <div class="otp-banner" style="background-color: var(--warning-bg); color: var(--warning);">
            <span class="material-symbols-outlined">lock_reset</span>
            <div>
              <strong>Смена временного пароля</strong>
              <p style="font-size: 11px; margin-top: 2px;">Установите постоянный пароль (от 10 символов) для завершения входа.</p>
            </div>
          </div>

          <div class="form-group">
            <label class="form-label" for="new-password">Новый пароль</label>
            <input
              id="new-password"
              type="password"
              class="form-input"
              [(ngModel)]="newPassword"
              name="newPassword"
              required
              minlength="10"
              placeholder="••••••••••••"
              [disabled]="isLoading()"
              autofocus
            />
          </div>

          <div class="form-group">
            <label class="form-label" for="confirm-new-password">Повторите новый пароль</label>
            <input
              id="confirm-new-password"
              type="password"
              class="form-input"
              [(ngModel)]="confirmNewPassword"
              name="confirmNewPassword"
              required
              minlength="10"
              placeholder="••••••••••••"
              [disabled]="isLoading()"
            />
          </div>

          <p *ngIf="formError()" class="form-error" role="alert">{{ formError() }}</p>

          <div class="otp-actions">
            <ui-button
              type="submit"
              variant="primary"
              size="lg"
              [loading]="isLoading()"
              [fullWidth]="true"
              class="submit-btn"
            >
              Сменить пароль и войти
            </ui-button>

            <ui-button
              type="button"
              variant="ghost"
              size="md"
              (onClick)="step.set('credentials')"
            >
              Отмена
            </ui-button>
          </div>
        </form>
      </div>
    </main>

    <!-- Password Reset Modal -->
    <ui-modal
      [isOpen]="isResetModalOpen()"
      title="Восстановление пароля"
      size="sm"
      (close)="isResetModalOpen.set(false)"
    >
      <div body class="reset-body">
        <p id="reset-hint" class="reset-hint">Введите email вашей учётной записи. Мы отправим код для сброса пароля.</p>
        <div class="form-group">
          <label class="form-label" for="reset-email">Email</label>
          <input
            id="reset-email"
            name="resetEmail"
            type="email"
            class="form-input"
            [(ngModel)]="resetEmail"
            placeholder="user@company.com"
            autocomplete="email"
            aria-describedby="reset-hint"
            [attr.aria-invalid]="resetError() ? 'true' : null"
          />
        </div>
        <p *ngIf="resetError()" class="form-error" role="alert">{{ resetError() }}</p>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isResetModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isResetLoading()" (onClick)="sendResetRequest()">Отправить код</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .login-wrapper {
      min-height: 100vh;
      width: 100vw;
      display: flex;
      align-items: center;
      justify-content: center;
      background-color: var(--bg-app);
      padding: 16px;
    }

    .login-card {
      width: 100%;
      max-width: 380px;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow-overlay);
      padding: 32px 28px;
    }

    .login-header {
      text-align: center;
      margin-bottom: 24px;
    }

    .brand-lockup {
      display: inline-flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 12px;
    }

    .brand-mark {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 44px;
      height: 44px;
      flex: 0 0 44px;
      border-radius: var(--radius-md);
      background-color: var(--primary);
      color: #ffffff;
      font-weight: 700;
      font-size: 16px;
    }

    .brand-name {
      color: var(--text-main);
      font-size: 18px;
      font-weight: 700;
      letter-spacing: -0.2px;
    }

    .login-title {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-main);
      margin-bottom: 4px;
    }

    .login-subtitle {
      font-size: 12px;
      color: var(--text-muted);
    }

    .login-form {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 5px;
    }

    .form-label {
      font-size: 12px;
      font-weight: 500;
      color: var(--text-main);
    }

    .password-label-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .forgot-link {
      font-size: 11px;
      color: var(--primary);
      cursor: pointer;
      text-decoration: none;
      border: 0;
      padding: 2px;
      background: transparent;
      font-family: inherit;
    }
    .forgot-link:hover {
      text-decoration: underline;
    }

    .form-input {
      height: 36px;
      padding: 6px 12px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      font-family: inherit;
      outline: none;
      transition: border-color 0.15s ease;
    }
    .form-input:focus {
      border-color: var(--primary);
    }

    .submit-btn {
      width: 100%;
      margin-top: 6px;
    }

    .otp-banner {
      background-color: var(--info-bg);
      color: var(--info);
      padding: 10px 12px;
      border-radius: var(--radius-sm);
      display: flex;
      gap: 10px;
      font-size: 12px;
      line-height: 1.4;
    }

    .otp-input {
      font-size: 18px;
      letter-spacing: 4px;
      text-align: center;
      font-weight: 600;
    }

    .otp-actions {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .form-error {
      color: var(--danger);
      font-size: 12px;
      line-height: 1.4;
    }

    .reset-hint {
      font-size: 12px;
      color: var(--text-muted);
      margin-bottom: 12px;
      line-height: 1.4;
    }

  `]
})
export class LoginComponent {
  login = '';
  password = '';
  otpCode = '';
  otpToken = '';
  newPassword = '';
  confirmNewPassword = '';
  tempOldPassword = '';

  readonly step = signal<'credentials' | 'otp' | 'must_change_password'>('credentials');
  readonly isLoading = signal<boolean>(false);
  readonly isResetModalOpen = signal<boolean>(false);
  readonly isResetLoading = signal<boolean>(false);
  readonly formError = signal<string>('');
  readonly resetError = signal<string>('');
  resetEmail = '';

  constructor(
    private authService: AuthService,
    private api: ApiService,
    private toast: ToastService,
    private router: Router
  ) {}

  onLoginSubmit() {
    if (!this.login || !this.password) return;

    this.formError.set('');
    this.isLoading.set(true);
    this.authService.login(this.login, this.password, navigator.userAgent).subscribe({
      next: res => {
        this.isLoading.set(false);
        if (res.step === 'otp') {
          this.otpToken = res.otp_token || '';
          this.step.set('otp');
        } else if (res.step === 'success' && res.user?.forcePasswordChange) {
          this.tempOldPassword = this.password;
          this.newPassword = '';
          this.confirmNewPassword = '';
          this.step.set('must_change_password');
        }
      },
      error: err => {
        this.isLoading.set(false);
        this.formError.set(this.errorMessage(err, 'Не удалось выполнить вход. Проверьте данные и повторите попытку.'));
      }
    });
  }

  onOtpSubmit() {
    if (!this.otpCode || !this.otpToken) return;

    this.formError.set('');
    this.isLoading.set(true);
    this.authService.verifyOtp(this.otpToken, this.otpCode, navigator.userAgent).subscribe({
      next: res => {
        this.isLoading.set(false);
        if (res.step === 'success' && res.user?.forcePasswordChange) {
          this.tempOldPassword = this.password;
          this.newPassword = '';
          this.confirmNewPassword = '';
          this.step.set('must_change_password');
        }
      },
      error: err => {
        this.isLoading.set(false);
        this.formError.set(this.errorMessage(err, 'Код не подтверждён. Проверьте код и повторите попытку.'));
      }
    });
  }

  onChangePasswordSubmit() {
    if (!this.newPassword || !this.confirmNewPassword) return;
    if (this.newPassword.length < 10) {
      this.formError.set('Длина нового пароля должна быть не менее 10 символов');
      return;
    }
    if (this.newPassword !== this.confirmNewPassword) {
      this.formError.set('Введенные пароли не совпадают');
      return;
    }

    this.formError.set('');
    this.isLoading.set(true);
    this.api.post('/auth/password', {
      oldPassword: this.tempOldPassword || this.password,
      newPassword: this.newPassword
    }).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.toast.success('Пароль успешно изменен! Добро пожаловать.');
        this.authService.refreshMe().subscribe({
          next: () => this.router.navigate(['/tasks']),
          error: () => this.router.navigate(['/tasks'])
        });
      },
      error: err => {
        this.isLoading.set(false);
        this.formError.set(this.errorMessage(err, 'Не удалось изменить пароль. Проверьте сложность пароля.'));
      }
    });
  }

  openResetModal() {
    this.resetEmail = '';
    this.resetError.set('');
    this.isResetModalOpen.set(true);
  }

  sendResetRequest() {
    if (!this.resetEmail) return;
    this.resetError.set('');
    this.isResetLoading.set(true);
    this.api.post('/auth/password-reset/request', { email: this.resetEmail }).subscribe({
      next: () => {
        this.isResetLoading.set(false);
        this.isResetModalOpen.set(false);
        this.toast.success('Инструкция по сбросу пароля отправлена на указанный email');
      },
      error: err => {
        this.isResetLoading.set(false);
        this.resetError.set(this.errorMessage(err, 'Не удалось отправить инструкцию. Повторите попытку позже.'));
      }
    });
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object') {
      const value = error as { detail?: unknown; message?: unknown };
      if (typeof value.detail === 'string' && value.detail.trim()) return value.detail;
      if (typeof value.message === 'string' && value.message.trim()) return value.message;
    }
    return fallback;
  }
}
