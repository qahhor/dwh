import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../../core/services/auth.service';
import { ApiService } from '../../../core/services/api.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiBadgeComponent } from '../../../shared/ui/ui-badge.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { UserSession, ApiToken, CreatedTokenResponse } from '../../../core/models/auth.models';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [CommonModule, FormsModule, UiButtonComponent, UiBadgeComponent, UiModalComponent],
  template: `
    <div class="profile-container">
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">Мой профиль</h1>
          <span class="count-badge">Security & Settings</span>
        </div>
      </div>

      <!-- User Info Card -->
      <div class="card user-card" *ngIf="authService.currentUser() as user">
        <div class="user-avatar-large">
          {{ user.name ? user.name.charAt(0).toUpperCase() : 'U' }}
        </div>
        <div class="user-details">
          <div class="user-title-row">
            <h3 class="user-fullname">{{ user.name }}</h3>
            <ui-badge [variant]="user.state === 'A' ? 'active' : 'passive'" [dot]="true">
              {{ user.state === 'A' ? 'Активен' : 'Заблокирован' }}
            </ui-badge>
            <ui-badge *ngIf="user.is2faEnabled" variant="active">
              <span class="material-symbols-outlined badge-icon" aria-hidden="true">verified_user</span> 2FA Включена
            </ui-badge>
          </div>
          <div class="user-info-grid">
            <div class="info-item">
              <span class="info-label">Логин:</span>
              <span class="info-value font-mono">&#64;{{ user.login }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">Email:</span>
              <span class="info-value font-mono">{{ user.email }}</span>
            </div>
            <div class="info-item" *ngIf="user.phone">
              <span class="info-label">Телефон:</span>
              <span class="info-value font-mono">{{ user.phone }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">Язык / Зона:</span>
              <span class="info-value">{{ user.language || 'ru' }} ({{ user.timezone || 'Asia/Tashkent' }})</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Main Grid Sections -->
      <div class="sections-grid">
        <!-- Change Password Card -->
        <div class="card section-card">
          <div class="section-header">
            <div class="section-title-box">
              <span class="material-symbols-outlined section-icon" aria-hidden="true">lock_reset</span>
              <h4 class="section-title">Смена пароля</h4>
            </div>
          </div>

          <form class="password-form" (submit)="submitChangePassword($event)">
            <div class="form-group">
              <label class="form-label" for="profile-current-password">Текущий пароль <span class="req">*</span></label>
              <input
                id="profile-current-password"
                type="password"
                class="form-input font-mono"
                autocomplete="current-password"
                [(ngModel)]="passwordForm.oldPassword"
                name="oldPassword"
                [attr.aria-invalid]="isPasswordSubmitted && !passwordForm.oldPassword"
                [attr.aria-describedby]="isPasswordSubmitted && !passwordForm.oldPassword ? 'profile-current-password-error' : null"
                placeholder="Введите текущий пароль"
                required
              />
              <span id="profile-current-password-error" class="field-error" *ngIf="isPasswordSubmitted && !passwordForm.oldPassword">Введите текущий пароль</span>
            </div>

            <div class="form-group">
              <label class="form-label" for="profile-new-password">Новый пароль <span class="req">*</span></label>
              <div class="password-input-box">
                <input
                  id="profile-new-password"
                  [type]="showNewPassword() ? 'text' : 'password'"
                  class="form-input font-mono"
                  autocomplete="new-password"
                  minlength="10"
                  [(ngModel)]="passwordForm.newPassword"
                  name="newPassword"
                  [attr.aria-invalid]="isPasswordSubmitted && passwordForm.newPassword.length < 10"
                  [attr.aria-describedby]="isPasswordSubmitted && passwordForm.newPassword.length < 10 ? 'profile-new-password-hint profile-new-password-error' : 'profile-new-password-hint'"
                  placeholder="Минимум 10 символов"
                  required
                />
                <button type="button" class="pwd-toggle-btn" [attr.aria-label]="showNewPassword() ? 'Скрыть новый пароль' : 'Показать новый пароль'" [attr.aria-pressed]="showNewPassword()" (click)="showNewPassword.update(v => !v)">
                  <span class="material-symbols-outlined" aria-hidden="true">{{ showNewPassword() ? 'visibility_off' : 'visibility' }}</span>
                </button>
              </div>
              <span id="profile-new-password-hint" class="field-hint">Минимум 10 символов, не из черного списка и не совпадает с логином</span>
              <span id="profile-new-password-error" class="field-error" *ngIf="isPasswordSubmitted && passwordForm.newPassword.length < 10">Пароль должен содержать не менее 10 символов</span>
            </div>

            <div class="form-group">
              <label class="form-label" for="profile-confirm-password">Подтверждение нового пароля <span class="req">*</span></label>
              <input
                id="profile-confirm-password"
                type="password"
                class="form-input font-mono"
                autocomplete="new-password"
                [(ngModel)]="passwordForm.confirmPassword"
                name="confirmPassword"
                [attr.aria-invalid]="isPasswordSubmitted && (!passwordForm.confirmPassword || passwordForm.newPassword !== passwordForm.confirmPassword)"
                [attr.aria-describedby]="isPasswordSubmitted && (!passwordForm.confirmPassword || passwordForm.newPassword !== passwordForm.confirmPassword) ? 'profile-confirm-password-error' : null"
                placeholder="Повторите новый пароль"
                required
              />
              <span id="profile-confirm-password-error" class="field-error" *ngIf="isPasswordSubmitted && (!passwordForm.confirmPassword || passwordForm.newPassword !== passwordForm.confirmPassword)">
                {{ !passwordForm.confirmPassword ? 'Подтвердите новый пароль' : 'Пароли не совпадают' }}
              </span>
            </div>

            <div class="form-actions">
              <ui-button variant="primary" size="md" [loading]="isChangingPassword()" type="submit">
                Обновить пароль
              </ui-button>
            </div>
          </form>
        </div>

        <!-- Security & 2FA Info Card -->
        <div class="card section-card">
          <div class="section-header">
            <div class="section-title-box">
              <span class="material-symbols-outlined section-icon" aria-hidden="true">security</span>
              <h4 class="section-title">Безопасность и 2FA</h4>
            </div>
          </div>

          <div class="security-info-box">
            <div class="twofa-status-banner" [class.enabled]="authService.currentUser()?.is2faEnabled">
              <span class="material-symbols-outlined twofa-big-icon" aria-hidden="true">
                {{ authService.currentUser()?.is2faEnabled ? 'verified_user' : 'gpp_maybe' }}
              </span>
              <div class="twofa-status-text">
                <div class="twofa-status-title">
                  {{ authService.currentUser()?.is2faEnabled ? 'Двухфакторная защита активна' : '2FA защита не включена' }}
                </div>
                <div class="twofa-status-desc">
                  {{ authService.currentUser()?.is2faEnabled
                    ? 'При входе с новых устройств запрашивается 6-значный OTP код подтверждения.'
                    : 'Обратитесь к администратору для включения обязательной 2FA аутентификации.' }}
                </div>
              </div>
            </div>

            <div class="security-tips">
              <div class="tip-item">
                <span class="material-symbols-outlined tip-icon" aria-hidden="true">check_circle</span>
                <span>Защита от подбора паролей: 5 неверных попыток блокируют вход на 10 минут.</span>
              </div>
              <div class="tip-item">
                <span class="material-symbols-outlined tip-icon" aria-hidden="true">check_circle</span>
                <span>Сессии автоматически закрываются при бездействии более 12 часов.</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Active Sessions Card -->
        <div class="card section-card full-width">
          <div class="section-header">
            <div class="section-title-box">
              <span class="material-symbols-outlined section-icon" aria-hidden="true">devices</span>
              <h4 class="section-title">Активные сессии</h4>
              <span class="badge-count">{{ sessions().length }}</span>
            </div>
            <div class="sessions-header-actions">
              <ui-button
                *ngIf="sessions().length > 1"
                variant="danger"
                size="sm"
                icon="logout"
                title="Завершить все остальные сессии кроме текущей"
                (onClick)="requestTerminateOtherSessions()"
              >
                Завершить другие сессии
              </ui-button>
              <ui-button variant="secondary" size="sm" icon="refresh" (onClick)="loadSessions()">
                Обновить
              </ui-button>
            </div>
          </div>

          <div class="table-wrapper" role="region" aria-label="Таблица активных сессий" tabindex="0">
            <table class="data-table" aria-label="Активные сессии">
              <thead>
                <tr>
                  <th>IP Адрес</th>
                  <th>Устройство / Браузер</th>
                  <th>Создана</th>
                  <th>Последняя активность</th>
                  <th class="text-right">Действие</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let s of sessions()">
                  <td class="tabular-nums font-mono">{{ s.ip }}</td>
                  <td>{{ s.deviceInfo || s.userAgent || 'Неизвестное устройство' }}</td>
                  <td class="tabular-nums text-muted">{{ s.createdAt | date:'dd.MM.yyyy HH:mm' }}</td>
                  <td class="tabular-nums font-medium">{{ s.lastSeenAt | date:'dd.MM.yyyy HH:mm:ss' }}</td>
                  <td class="text-right">
                    <ui-button variant="danger" size="sm" [ariaLabel]="'Завершить сессию с IP ' + s.ip" (onClick)="requestTerminateSession(s)">Завершить</ui-button>
                  </td>
                </tr>
                <tr *ngIf="sessions().length === 0">
                  <td colspan="5" class="empty-cell">Нет активных сессий</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- API Tokens Card -->
        <div class="card section-card full-width">
          <div class="section-header">
            <div class="section-title-box">
              <span class="material-symbols-outlined section-icon" aria-hidden="true">key</span>
              <h4 class="section-title">API Токены доступа (Bearer Tokens)</h4>
              <span class="badge-count">{{ tokens().length }}</span>
            </div>
            <ui-button variant="primary" size="sm" icon="add" (onClick)="openCreateTokenModal()">Выпустить токен</ui-button>
          </div>

          <div class="table-wrapper" role="region" aria-label="Таблица API-токенов" tabindex="0">
            <table class="data-table" aria-label="API-токены">
              <thead>
                <tr>
                  <th>Название токена</th>
                  <th>Префикс токена</th>
                  <th>Создан</th>
                  <th>Срок действия</th>
                  <th class="text-right">Действие</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let t of tokens()">
                  <td class="font-medium">{{ t.name }}</td>
                  <td class="tabular-nums font-mono token-prefix-cell">{{ t.tokenPrefix }}...</td>
                  <td class="tabular-nums text-muted">{{ t.createdAt | date:'dd.MM.yyyy' }}</td>
                  <td class="tabular-nums">{{ t.expiresAt ? (t.expiresAt | date:'dd.MM.yyyy') : 'Бессрочно' }}</td>
                  <td class="text-right">
                    <ui-button variant="danger" size="sm" icon="delete" [ariaLabel]="'Отозвать API-токен ' + t.name" (onClick)="requestRevokeToken(t)">Отозвать</ui-button>
                  </td>
                </tr>
                <tr *ngIf="tokens().length === 0">
                  <td colspan="5" class="empty-cell">Нет созданных API токенов</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <!-- Create Token Modal -->
    <ui-modal
      [isOpen]="isCreateTokenModalOpen()"
      title="Выпуск нового API Токена"
      size="sm"
      (close)="isCreateTokenModalOpen.set(false)"
    >
      <div body class="token-form">
        <div class="form-group">
          <label class="form-label" for="profile-token-name">Название токена <span class="req">*</span></label>
          <input id="profile-token-name" name="profileTokenName" type="text" class="form-input" required
            [attr.aria-invalid]="isTokenSubmitted && !newTokenName.trim()"
            [attr.aria-describedby]="isTokenSubmitted && !newTokenName.trim() ? 'profile-token-name-error' : null"
            [(ngModel)]="newTokenName" placeholder="Например: CI/CD Deployer / Kafka Sync" />
          <span id="profile-token-name-error" class="field-error" *ngIf="isTokenSubmitted && !newTokenName.trim()">Введите название API-токена</span>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isCreateTokenModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" (onClick)="createTokenSubmit()">Сгенерировать</ui-button>
      </div>
    </ui-modal>

    <!-- Token Secret Reveal Modal -->
    <ui-modal
      [isOpen]="isTokenSecretModalOpen()"
      title="API Токен успешно создан"
      size="md"
      [hasFooter]="false"
      (close)="isTokenSecretModalOpen.set(false)"
    >
      <div body class="secret-reveal-body">
        <div class="warning-box">
          <span class="material-symbols-outlined" aria-hidden="true">warning</span>
          <p>Скопируйте и сохраните токен сейчас. В целях безопасности он больше никогда не будет показан!</p>
        </div>
        <div class="token-secret-box">
          <code>{{ createdTokenSecret }}</code>
          <ui-button variant="secondary" size="sm" icon="content_copy" (onClick)="copySecret()">Скопировать</ui-button>
        </div>
        <ui-button variant="primary" size="md" class="mt-4" (onClick)="isTokenSecretModalOpen.set(false)">Я сохранил токен</ui-button>
      </div>
    </ui-modal>

    <!-- Session Termination Confirmation -->
    <ui-modal
      [isOpen]="sessionToTerminate !== null"
      title="Завершение сессии"
      size="sm"
      (close)="sessionToTerminate = null"
    >
      <div body class="confirmation-body" *ngIf="sessionToTerminate as target">
        <p *ngIf="target === 'others'">Завершить все остальные активные сессии, кроме текущей?</p>
        <p *ngIf="target !== 'others'">Завершить сессию с IP <strong>{{ target.ip }}</strong>?</p>
        <span class="confirmation-hint">На завершённых устройствах потребуется выполнить вход заново.</span>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="sessionToTerminate = null">Отмена</ui-button>
        <ui-button variant="danger" size="md" (onClick)="confirmTerminateSession()">Завершить</ui-button>
      </div>
    </ui-modal>

    <!-- Token Revocation Confirmation -->
    <ui-modal
      [isOpen]="tokenToRevoke !== null"
      title="Отзыв API-токена"
      size="sm"
      (close)="tokenToRevoke = null"
    >
      <div body class="confirmation-body" *ngIf="tokenToRevoke as token">
        <p>Отозвать API-токен <strong>{{ token.name }}</strong>?</p>
        <span class="confirmation-hint">Интеграции с этим токеном немедленно потеряют доступ. Действие необратимо.</span>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="tokenToRevoke = null">Отмена</ui-button>
        <ui-button variant="danger" size="md" (onClick)="confirmRevokeToken()">Отозвать</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .profile-container {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 1400px;
    }

    .page-header {
      margin-bottom: 4px;
    }

    .page-title {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-main);
    }

    .page-subtitle {
      font-size: 13px;
      color: var(--text-muted);
      margin-top: 2px;
    }

    .card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      padding: 16px 20px;
    }

    .user-card {
      display: flex;
      align-items: center;
      gap: 20px;
    }

    .user-avatar-large {
      width: 64px;
      height: 64px;
      border-radius: 50%;
      background-color: var(--primary);
      color: #ffffff;
      font-size: 26px;
      font-weight: 700;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }

    .user-details { flex: 1; }

    .user-title-row {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 8px;
    }

    .user-fullname {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }

    .badge-icon { font-size: 14px; vertical-align: middle; }

    .user-info-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 10px;
      font-size: 13px;
    }

    .info-label { color: var(--text-muted); margin-right: 6px; }
    .info-value { color: var(--text-main); font-weight: 500; }

    .sections-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
    }

    .full-width {
      grid-column: 1 / -1;
    }

    @media (max-width: 1024px) {
      .sections-grid {
        grid-template-columns: 1fr;
      }
    }

    .section-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 14px;
      padding-bottom: 8px;
      border-bottom: 1px solid var(--border-color);
    }

    .section-title-box {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--text-main);
    }

    .section-icon {
      font-size: 20px;
      color: var(--primary);
    }

    .section-title {
      font-size: 15px;
      font-weight: 600;
      margin: 0;
    }

    .badge-count {
      background-color: var(--bg-hover);
      color: var(--primary);
      font-size: 11px;
      font-weight: 600;
      padding: 1px 6px;
      border-radius: 10px;
      border: 1px solid var(--border-color);
    }

    .sessions-header-actions {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .password-form {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .form-label {
      font-size: 12px;
      font-weight: 500;
      color: var(--text-main);
    }

    .req { color: var(--danger); }

    .field-hint {
      font-size: 11px;
      color: var(--text-muted);
    }
    .field-error { font-size: 11px; color: var(--danger); }

    .form-input {
      height: 36px;
      padding: 6px 10px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }
    .form-input:focus { border-color: var(--primary); }

    .password-input-box {
      position: relative;
      display: flex;
      align-items: center;
    }
    .password-input-box .form-input {
      width: 100%;
      padding-right: 36px;
    }
    .pwd-toggle-btn {
      position: absolute;
      right: 6px;
      background: transparent;
      border: none;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      align-items: center;
      padding: 4px;
    }

    .form-actions {
      margin-top: 4px;
      display: flex;
      justify-content: flex-end;
    }

    .security-info-box {
      display: flex;
      flex-direction: column;
      gap: 14px;
    }

    .twofa-status-banner {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px 14px;
      border-radius: var(--radius-md);
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
    }
    .twofa-status-banner.enabled {
      background-color: rgba(16, 185, 129, 0.08);
      border-color: rgba(16, 185, 129, 0.25);
    }

    .twofa-big-icon {
      font-size: 32px;
      color: var(--text-light);
    }
    .twofa-status-banner.enabled .twofa-big-icon {
      color: var(--success);
    }

    .twofa-status-text {
      display: flex;
      flex-direction: column;
    }
    .twofa-status-title {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-main);
    }
    .twofa-status-desc {
      font-size: 12px;
      color: var(--text-muted);
    }

    .security-tips {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 10px 12px;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
    }

    .tip-item {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;
      color: var(--text-muted);
    }
    .tip-icon { font-size: 16px; color: var(--primary); }

    .table-wrapper { overflow-x: auto; }

    .data-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
    }

    .data-table th {
      text-align: left;
      padding: 8px 12px;
      font-weight: 600;
      color: var(--text-muted);
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      font-size: 12px;
    }

    .data-table td {
      padding: 10px 12px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
      vertical-align: middle;
    }

    .token-prefix-cell {
      background-color: var(--bg-hover);
      padding: 2px 6px;
      border-radius: 4px;
      display: inline-block;
    }

    .text-right { text-align: right; }
    .font-mono { font-family: monospace; }
    .font-medium { font-weight: 500; }
    .empty-cell { text-align: center; color: var(--text-muted); padding: 24px !important; }

    .warning-box {
      background-color: var(--warning-bg);
      color: var(--warning);
      padding: 10px 12px;
      border-radius: var(--radius-sm);
      display: flex;
      gap: 8px;
      font-size: 12px;
      margin-bottom: 12px;
    }

    .token-secret-box {
      display: flex;
      align-items: center;
      justify-content: space-between;
      background-color: var(--bg-hover);
      padding: 8px 12px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      font-family: monospace;
      font-size: 13px;
      word-break: break-all;
    }

    .mt-4 { margin-top: 16px; }
    .confirmation-body { display: flex; flex-direction: column; gap: 8px; }
    .confirmation-body p { margin: 0; }
    .confirmation-hint { color: var(--text-muted); font-size: 12px; }
  `]
})
export class ProfileComponent implements OnInit {
  readonly sessions = signal<UserSession[]>([]);
  readonly tokens = signal<ApiToken[]>([]);

  readonly isCreateTokenModalOpen = signal<boolean>(false);
  readonly isTokenSecretModalOpen = signal<boolean>(false);
  readonly showNewPassword = signal<boolean>(false);
  readonly isChangingPassword = signal<boolean>(false);
  isPasswordSubmitted = false;
  isTokenSubmitted = false;

  newTokenName = '';
  createdTokenSecret = '';
  sessionToTerminate: UserSession | 'others' | null = null;
  tokenToRevoke: ApiToken | null = null;

  passwordForm = {
    oldPassword: '',
    newPassword: '',
    confirmPassword: ''
  };

  constructor(
    public authService: AuthService,
    private api: ApiService,
    private toast: ToastService
  ) {}

  ngOnInit() {
    this.loadSessions();
    this.loadTokens();
  }

  loadSessions() {
    this.api.get<UserSession[]>('/iam/profile/sessions').subscribe(res => {
      this.sessions.set(res || []);
    });
  }

  requestTerminateSession(session: UserSession) {
    this.sessionToTerminate = session;
  }

  requestTerminateOtherSessions() {
    this.sessionToTerminate = 'others';
  }

  confirmTerminateSession() {
    const target = this.sessionToTerminate;
    if (!target) return;

    if (target === 'others') {
      this.api.delete('/iam/profile/sessions/others').subscribe({
        next: () => {
          this.sessionToTerminate = null;
          this.toast.success('Все остальные сессии успешно завершены');
          this.loadSessions();
        },
        error: (err: any) => {
          this.toast.error(err?.error?.detail || 'Ошибка при завершении сессий');
        }
      });
      return;
    }

    this.api.delete(`/iam/profile/sessions/${target.id}`).subscribe({
      next: () => {
        this.sessionToTerminate = null;
        this.toast.success('Сессия успешно завершена');
        this.loadSessions();
      },
      error: (err: any) => {
        this.toast.error(err?.error?.detail || 'Ошибка при завершении сессии');
      }
    });
  }

  submitChangePassword(event: Event) {
    event.preventDefault();
    this.isPasswordSubmitted = true;

    if (!this.passwordForm.oldPassword || !this.passwordForm.newPassword || !this.passwordForm.confirmPassword) {
      this.toast.warning('Заполните все поля смены пароля');
      return;
    }

    if (this.passwordForm.newPassword.length < 10) {
      this.toast.warning('Новый пароль должен содержать минимум 10 символов');
      return;
    }

    if (this.passwordForm.newPassword !== this.passwordForm.confirmPassword) {
      this.toast.warning('Новый пароль и подтверждение не совпадают');
      return;
    }

    this.isChangingPassword.set(true);
    this.api.post('/iam/users/me/password', {
      oldPassword: this.passwordForm.oldPassword,
      newPassword: this.passwordForm.newPassword
    }).subscribe({
      next: () => {
        this.isChangingPassword.set(false);
        this.toast.success('Пароль успешно изменён');
        this.passwordForm = { oldPassword: '', newPassword: '', confirmPassword: '' };
        this.isPasswordSubmitted = false;
      },
      error: (err: any) => {
        this.isChangingPassword.set(false);
        this.toast.error(err?.error?.detail || 'Ошибка при смене пароля');
      }
    });
  }

  loadTokens() {
    this.api.get<ApiToken[]>('/iam/profile/tokens').subscribe(res => {
      this.tokens.set(res || []);
    });
  }

  openCreateTokenModal() {
    this.newTokenName = '';
    this.isTokenSubmitted = false;
    this.isCreateTokenModalOpen.set(true);
  }

  createTokenSubmit() {
    this.isTokenSubmitted = true;
    if (!this.newTokenName.trim()) {
      this.toast.warning('Введите название API токена');
      return;
    }

    this.api.post<CreatedTokenResponse>('/iam/profile/tokens', { name: this.newTokenName.trim() }).subscribe({
      next: res => {
        this.isCreateTokenModalOpen.set(false);
        this.isTokenSubmitted = false;
        this.createdTokenSecret = res.rawSecretToken;
        this.isTokenSecretModalOpen.set(true);
        this.loadTokens();
      },
      error: (err: any) => {
        this.toast.error(err?.error?.detail || 'Ошибка при создании API токена');
      }
    });
  }

  requestRevokeToken(token: ApiToken) {
    this.tokenToRevoke = token;
  }

  confirmRevokeToken() {
    if (!this.tokenToRevoke) return;
    const token = this.tokenToRevoke;
    this.api.delete(`/iam/profile/tokens/${token.id}`).subscribe({
      next: () => {
        this.tokenToRevoke = null;
        this.toast.success('Токен успешно отозван');
        this.loadTokens();
      },
      error: (err: any) => {
        this.toast.error(err?.error?.detail || 'Ошибка при отзыве токена');
      }
    });
  }

  copySecret() {
    navigator.clipboard.writeText(this.createdTokenSecret);
    this.toast.success('Токен скопирован в буфер обмена');
  }
}
