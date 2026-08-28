import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
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
    <div class="login-wrapper">
      <div class="login-card">
        <div class="login-header">
          <div class="brand-badge">DWH</div>
          <h2 class="login-title">Корпоративный вход</h2>
          <p class="login-subtitle">Платформа управления данными и задачами</p>
        </div>

        <!-- Step 1: Login & Password Form -->
        <form *ngIf="step() === 'credentials'" (ngSubmit)="onLoginSubmit()" class="login-form">
          <div class="form-group">
            <label class="form-label">Логин или Email</label>
            <input
              type="text"
              class="form-input"
              [(ngModel)]="login"
              name="login"
              required
              autocomplete="username"
              placeholder="admin"
              [disabled]="isLoading()"
            />
          </div>

          <div class="form-group">
            <div class="password-label-row">
              <label class="form-label">Пароль</label>
              <a class="forgot-link" (click)="openResetModal()">Забыли пароль?</a>
            </div>
            <input
              type="password"
              class="form-input"
              [(ngModel)]="password"
              name="password"
              required
              autocomplete="current-password"
              placeholder="••••••••"
              [disabled]="isLoading()"
            />
          </div>

          <ui-button
            type="submit"
            variant="primary"
            size="lg"
            [loading]="isLoading()"
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
              <p>Введите 6-значный код подтверждения, отправленный в ваш Telegram</p>
            </div>
          </div>

          <div class="form-group">
            <label class="form-label">Код подтверждения (OTP)</label>
            <input
              type="text"
              class="form-input otp-input tabular-nums"
              [(ngModel)]="otpCode"
              name="otpCode"
              required
              maxlength="6"
              placeholder="123456"
              [disabled]="isLoading()"
              autofocus
            />
          </div>

          <div class="otp-actions">
            <ui-button
              type="submit"
              variant="primary"
              size="lg"
              [loading]="isLoading()"
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
      </div>
    </div>

    <!-- Password Reset Modal -->
    <ui-modal
      [isOpen]="isResetModalOpen()"
      title="Восстановление пароля"
      size="sm"
      (close)="isResetModalOpen.set(false)"
    >
      <div body class="reset-body">
        <p class="reset-hint">Введите email вашей учётной записи. Мы отправим код для сброса пароля.</p>
        <div class="form-group">
          <label class="form-label">Email</label>
          <input
            type="email"
            class="form-input"
            [(ngModel)]="resetEmail"
            placeholder="user@company.com"
          />
        </div>
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

    .brand-badge {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 44px;
      height: 44px;
      border-radius: var(--radius-md);
      background-color: var(--primary);
      color: #ffffff;
      font-weight: 700;
      font-size: 16px;
      margin-bottom: 12px;
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

    .reset-hint {
      font-size: 12px;
      color: var(--text-muted);
      margin-bottom: 12px;
      line-height: 1.4;
    }
  `]
})
export class LoginComponent {
  login = 'admin';
  password = '';
  otpCode = '';
  otpToken = '';

  readonly step = signal<'credentials' | 'otp'>('credentials');
  readonly isLoading = signal<boolean>(false);
  readonly isResetModalOpen = signal<boolean>(false);
  readonly isResetLoading = signal<boolean>(false);
  resetEmail = '';

  constructor(
    private authService: AuthService,
    private api: ApiService,
    private toast: ToastService
  ) {}

  onLoginSubmit() {
    if (!this.login || !this.password) return;

    this.isLoading.set(true);
    this.authService.login(this.login, this.password, navigator.userAgent).subscribe({
      next: res => {
        this.isLoading.set(false);
        if (res.step === 'otp') {
          this.otpToken = res.otp_token || '';
          this.step.set('otp');
        }
      },
      error: () => {
        this.isLoading.set(false);
      }
    });
  }

  onOtpSubmit() {
    if (!this.otpCode || !this.otpToken) return;

    this.isLoading.set(true);
    this.authService.verifyOtp(this.otpToken, this.otpCode, navigator.userAgent).subscribe({
      next: () => {
        this.isLoading.set(false);
      },
      error: () => {
        this.isLoading.set(false);
      }
    });
  }

  openResetModal() {
    this.resetEmail = '';
    this.isResetModalOpen.set(true);
  }

  sendResetRequest() {
    if (!this.resetEmail) return;
    this.isResetLoading.set(true);
    this.api.post('/auth/password-reset/request', { email: this.resetEmail }).subscribe({
      next: () => {
        this.isResetLoading.set(false);
        this.isResetModalOpen.set(false);
        this.toast.success('Инструкция по сбросу пароля отправлена на указанный email');
      },
      error: () => {
        this.isResetLoading.set(false);
      }
    });
  }
}
