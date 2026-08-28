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
      <div class="page-header">
        <div>
          <h2 class="page-title">Мой профиль</h2>
          <p class="page-subtitle">Управление личными данными, активными сессиями и API токенами</p>
        </div>
      </div>

      <!-- User Info Card -->
      <div class="card user-card" *ngIf="authService.currentUser() as user">
        <div class="user-avatar-large">
          {{ user.name.charAt(0).toUpperCase() }}
        </div>
        <div class="user-details">
          <div class="user-title-row">
            <h3 class="user-fullname">{{ user.name }}</h3>
            <ui-badge [variant]="user.state === 'A' ? 'active' : 'passive'" [dot]="true">
              {{ user.state === 'A' ? 'Активен' : 'Заблокирован' }}
            </ui-badge>
            <ui-badge *ngIf="user.is2faEnabled" variant="info">2FA Включена</ui-badge>
          </div>
          <div class="user-info-grid">
            <div class="info-item">
              <span class="info-label">Логин:</span>
              <span class="info-value">&#64;{{ user.login }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">Email:</span>
              <span class="info-value">{{ user.email }}</span>
            </div>
            <div class="info-item" *ngIf="user.phone">
              <span class="info-label">Телефон:</span>
              <span class="info-value">{{ user.phone }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">Часовой пояс:</span>
              <span class="info-value">{{ user.timezone }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Sections Grid -->
      <div class="sections-grid">
        <!-- Active Sessions -->
        <div class="card section-card">
          <div class="section-header">
            <div class="section-title-box">
              <span class="material-symbols-outlined">devices</span>
              <h4 class="section-title">Активные сессии</h4>
            </div>
            <ui-button variant="secondary" size="sm" (onClick)="loadSessions()">Обновить</ui-button>
          </div>

          <div class="table-wrapper">
            <table class="data-table">
              <thead>
                <tr>
                  <th>IP Адрес</th>
                  <th>Устройство</th>
                  <th>Последняя активность</th>
                  <th class="text-right">Действие</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let s of sessions()">
                  <td class="tabular-nums font-mono">{{ s.ip }}</td>
                  <td>{{ s.deviceInfo || s.userAgent }}</td>
                  <td class="tabular-nums">{{ s.lastSeenAt | date:'dd.MM.yyyy HH:mm' }}</td>
                  <td class="text-right">
                    <ui-button variant="danger" size="sm" (onClick)="terminateSession(s.id)">Завершить</ui-button>
                  </td>
                </tr>
                <tr *ngIf="sessions().length === 0">
                  <td colspan="4" class="empty-cell">Нет активных сессий</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- API Tokens -->
        <div class="card section-card">
          <div class="section-header">
            <div class="section-title-box">
              <span class="material-symbols-outlined">key</span>
              <h4 class="section-title">API Токены доступа</h4>
            </div>
            <ui-button variant="primary" size="sm" icon="add" (onClick)="openCreateTokenModal()">Выпустить токен</ui-button>
          </div>

          <div class="table-wrapper">
            <table class="data-table">
              <thead>
                <tr>
                  <th>Название</th>
                  <th>Префикс</th>
                  <th>Истекает</th>
                  <th class="text-right">Действие</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let t of tokens()">
                  <td class="font-medium">{{ t.name }}</td>
                  <td class="tabular-nums font-mono">{{ t.tokenPrefix }}...</td>
                  <td class="tabular-nums">{{ t.expiresAt ? (t.expiresAt | date:'dd.MM.yyyy') : 'Бессрочно' }}</td>
                  <td class="text-right">
                    <ui-button variant="danger" size="sm" (onClick)="revokeToken(t.id)">Отозвать</ui-button>
                  </td>
                </tr>
                <tr *ngIf="tokens().length === 0">
                  <td colspan="4" class="empty-cell">Нет созданных API токенов</td>
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
          <label class="form-label">Название токена</label>
          <input type="text" class="form-input" [(ngModel)]="newTokenName" placeholder="Например: CI/CD Deployer" />
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
          <span class="material-symbols-outlined">warning</span>
          <p>Скопируйте и сохраните токен сейчас. В целях безопасности он больше никогда не будет показан!</p>
        </div>
        <div class="token-secret-box">
          <code>{{ createdTokenSecret }}</code>
          <ui-button variant="secondary" size="sm" (onClick)="copySecret()">Скопировать</ui-button>
        </div>
        <ui-button variant="primary" size="md" class="mt-4" (onClick)="isTokenSecretModalOpen.set(false)">Я сохранил токен</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .profile-container {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 1200px;
    }

    .page-header {
      margin-bottom: 4px;
    }

    .page-title {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-main);
    }

    .page-subtitle {
      font-size: 12px;
      color: var(--text-muted);
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
      width: 60px;
      height: 60px;
      border-radius: 50%;
      background-color: var(--primary);
      color: #ffffff;
      font-size: 24px;
      font-weight: 700;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }

    .user-details {
      flex: 1;
    }

    .user-title-row {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 8px;
    }

    .user-fullname {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-main);
    }

    .user-info-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 10px;
      font-size: 13px;
    }

    .info-label {
      color: var(--text-muted);
      margin-right: 6px;
    }

    .info-value {
      color: var(--text-main);
      font-weight: 500;
    }

    .sections-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
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
      margin-bottom: 12px;
    }

    .section-title-box {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--text-main);
    }

    .section-title {
      font-size: 14px;
      font-weight: 600;
    }

    .table-wrapper {
      overflow-x: auto;
    }

    .data-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 12px;
    }

    .data-table th {
      text-align: left;
      padding: 8px 10px;
      font-weight: 600;
      color: var(--text-muted);
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
    }

    .data-table td {
      padding: 8px 10px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
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
  `]
})
export class ProfileComponent implements OnInit {
  readonly sessions = signal<UserSession[]>([]);
  readonly tokens = signal<ApiToken[]>([]);

  readonly isCreateTokenModalOpen = signal<boolean>(false);
  readonly isTokenSecretModalOpen = signal<boolean>(false);
  newTokenName = '';
  createdTokenSecret = '';

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

  terminateSession(id: number) {
    this.api.delete(`/iam/profile/sessions/${id}`).subscribe(() => {
      this.toast.success('Сессия успешно завершена');
      this.loadSessions();
    });
  }

  loadTokens() {
    this.api.get<ApiToken[]>('/iam/profile/tokens').subscribe(res => {
      this.tokens.set(res || []);
    });
  }

  openCreateTokenModal() {
    this.newTokenName = '';
    this.isCreateTokenModalOpen.set(true);
  }

  createTokenSubmit() {
    if (!this.newTokenName.trim()) return;

    this.api.post<CreatedTokenResponse>('/iam/profile/tokens', { name: this.newTokenName.trim() }).subscribe(res => {
      this.isCreateTokenModalOpen.set(false);
      this.createdTokenSecret = res.rawSecretToken;
      this.isTokenSecretModalOpen.set(true);
      this.loadTokens();
    });
  }

  revokeToken(id: number) {
    this.api.delete(`/iam/profile/tokens/${id}`).subscribe(() => {
      this.toast.success('Токен успешно отозван');
      this.loadTokens();
    });
  }

  copySecret() {
    navigator.clipboard.writeText(this.createdTokenSecret);
    this.toast.success('Токен скопирован в буфер обмена');
  }
}
