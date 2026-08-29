import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../core/services/toast.service';
import { I18nService, Language, TranslatePipe } from '../../core/services/i18n.service';
import { PermissionService } from '../../core/services/permission.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
    UiButtonComponent
  ],
  template: `
    <div class="settings-page">
      <!-- Page Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'settings.title' | t }}</h1>
          <span class="view-subtitle">{{ 'settings.subtitle' | t }}</span>
        </div>
        <div class="header-actions">
          <button class="icon-refresh-btn" (click)="loadAllSettings()" title="{{ 'common.refresh' | t }}">
            <span class="material-symbols-outlined">refresh</span>
          </button>
        </div>
      </div>

      <!-- Tabs Navigation -->
      <div class="tabs-nav-bar">
        <div class="tab-buttons">
          <button
            *ngIf="canManageSystemSettings()"
            class="nav-tab-btn"
            [class.active]="activeTab === 'general'"
            (click)="activeTab = 'general'"
          >
            <span class="material-symbols-outlined">tune</span>
            {{ 'settings.tab.general' | t }}
          </button>

          <button
            *ngIf="canManageSystemSettings()"
            class="nav-tab-btn"
            [class.active]="activeTab === 'security'"
            (click)="activeTab = 'security'"
          >
            <span class="material-symbols-outlined">security</span>
            {{ 'settings.tab.security' | t }}
          </button>

          <button
            *ngIf="canManageSystemSettings()"
            class="nav-tab-btn"
            [class.active]="activeTab === 'storage'"
            (click)="activeTab = 'storage'"
          >
            <span class="material-symbols-outlined">cloud</span>
            {{ 'settings.tab.storage' | t }}
          </button>

          <button
            class="nav-tab-btn"
            [class.active]="activeTab === 'preferences'"
            (click)="activeTab = 'preferences'"
          >
            <span class="material-symbols-outlined">person</span>
            {{ 'settings.tab.preferences' | t }}
          </button>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 1: GENERAL SYSTEM SETTINGS -->
      <!-- =================================================================== -->
      <div class="tab-content" *ngIf="activeTab === 'general' && canManageSystemSettings()">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon">corporate_fare</span>
              <div>
                <h3 class="card-title">Конфигурация компании</h3>
                <p class="card-desc">Глобальные параметры для всех сотрудников организации</p>
              </div>
            </div>
          </div>

          <div class="form-grid">
            <div class="form-group full-width">
              <label class="form-label">{{ 'settings.company_name' | t }}</label>
              <input
                type="text"
                class="form-input"
                [(ngModel)]="systemSettings['system.company_name']"
                placeholder="Smartup DWH Platform"
              />
            </div>

            <div class="form-group">
              <label class="form-label">{{ 'settings.default_language' | t }}</label>
              <select class="form-select" [(ngModel)]="systemSettings['system.default_language']">
                <option value="ru">Русский (RU) 🇷🇺</option>
                <option value="uz">O'zbekcha (UZ) 🇺🇿</option>
                <option value="en">English (EN) 🇬🇧</option>
              </select>
            </div>

            <div class="form-group">
              <label class="form-label">{{ 'settings.default_timezone' | t }}</label>
              <select class="form-select" [(ngModel)]="systemSettings['system.default_timezone']">
                <option value="Asia/Tashkent">Asia/Tashkent (UTC+5)</option>
                <option value="Asia/Almaty">Asia/Almaty (UTC+5)</option>
                <option value="Europe/Moscow">Europe/Moscow (UTC+3)</option>
                <option value="UTC">UTC (GMT+0)</option>
              </select>
            </div>

            <div class="form-group">
              <label class="form-label">{{ 'settings.date_format' | t }}</label>
              <select class="form-select" [(ngModel)]="systemSettings['system.date_format']">
                <option value="dd.MM.yyyy HH:mm">29.08.2026 14:30 (dd.MM.yyyy HH:mm)</option>
                <option value="yyyy-MM-dd HH:mm">2026-08-29 14:30 (yyyy-MM-dd HH:mm)</option>
                <option value="MM/dd/yyyy hh:mm a">08/29/2026 02:30 PM (MM/dd/yyyy)</option>
              </select>
            </div>
          </div>

          <div class="card-footer-actions">
            <ui-button [loading]="isSaving()" (click)="saveSystemSettings()">
              {{ 'common.save' | t }}
            </ui-button>
          </div>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 2: SECURITY POLICIES -->
      <!-- =================================================================== -->
      <div class="tab-content" *ngIf="activeTab === 'security' && canManageSystemSettings()">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon">lock</span>
              <div>
                <h3 class="card-title">Политики безопасности и авторизации</h3>
                <p class="card-desc">Требования к паролям, 2FA и веб-сессиям</p>
              </div>
            </div>
          </div>

          <div class="form-grid">
            <div class="form-group">
              <label class="form-label">{{ 'settings.min_password_len' | t }}</label>
              <input
                type="number"
                min="8"
                max="32"
                class="form-input"
                [(ngModel)]="systemSettings['security.min_password_length']"
              />
              <span class="hint-text">Рекомендуется не менее 10 символов</span>
            </div>

            <div class="form-group">
              <label class="form-label">{{ 'settings.session_lifetime' | t }}</label>
              <input
                type="number"
                min="1"
                max="8760"
                class="form-input"
                [(ngModel)]="systemSettings['security.session_lifetime_hours']"
              />
              <span class="hint-text">По умолчанию: 720 часов (30 дней)</span>
            </div>

            <div class="form-group full-width">
              <div class="toggle-row">
                <div class="toggle-info">
                  <span class="toggle-title">{{ 'settings.require_2fa' | t }}</span>
                  <span class="toggle-desc">Принудительно требовать двухфакторную аутентификацию (OTP) для всех аккаунтов</span>
                </div>
                <label class="switch-toggle">
                  <input
                    type="checkbox"
                    [checked]="systemSettings['security.require_2fa'] === 'true'"
                    (change)="toggleRequire2fa($event)"
                  />
                  <span class="toggle-slider"></span>
                </label>
              </div>
            </div>
          </div>

          <div class="card-footer-actions">
            <ui-button [loading]="isSaving()" (click)="saveSystemSettings()">
              {{ 'common.save' | t }}
            </ui-button>
          </div>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 3: STORAGE QUOTAS -->
      <!-- =================================================================== -->
      <div class="tab-content" *ngIf="activeTab === 'storage' && canManageSystemSettings()">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon">folder_shared</span>
              <div>
                <h3 class="card-title">Параметры хранилища и квоты</h3>
                <p class="card-desc">Лимиты дискового пространства для новых сотрудников</p>
              </div>
            </div>
          </div>

          <div class="form-grid">
            <div class="form-group">
              <label class="form-label">{{ 'settings.default_user_quota' | t }}</label>
              <input
                type="number"
                min="100"
                max="102400"
                class="form-input"
                [(ngModel)]="systemSettings['storage.default_user_quota_mb']"
              />
              <span class="hint-text">1024 MB = 1 GB на каждого сотрудника</span>
            </div>
          </div>

          <div class="card-footer-actions">
            <ui-button [loading]="isSaving()" (click)="saveSystemSettings()">
              {{ 'common.save' | t }}
            </ui-button>
          </div>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 4: USER PREFERENCES -->
      <!-- =================================================================== -->
      <div class="tab-content" *ngIf="activeTab === 'preferences'">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon">palette</span>
              <div>
                <h3 class="card-title">Персональные предпочтения</h3>
                <p class="card-desc">Настройки внешнего вида и языка для вашей учётной записи</p>
              </div>
            </div>
          </div>

          <div class="form-grid">
            <div class="form-group">
              <label class="form-label">Язык интерфейса</label>
              <select class="form-select" [ngModel]="i18n.currentLang()" (ngModelChange)="changePersonalLang($event)">
                <option value="ru">Русский (RU) 🇷🇺</option>
                <option value="uz">O'zbekcha (UZ) 🇺🇿</option>
                <option value="en">English (EN) 🇬🇧</option>
              </select>
            </div>

            <div class="form-group">
              <label class="form-label">{{ 'settings.theme' | t }}</label>
              <select class="form-select" [(ngModel)]="userSettings['user.theme']">
                <option value="dark">Тёмная (Dark Premium) 🌙</option>
                <option value="light">Светлая (Light Clean) ☀️</option>
                <option value="system">Системная тема 💻</option>
              </select>
            </div>

            <div class="form-group full-width">
              <div class="toggle-row">
                <div class="toggle-info">
                  <span class="toggle-title">{{ 'settings.notifications_sound' | t }}</span>
                  <span class="toggle-desc">Воспроизводить звуковой сигнал при получении нового уведомления</span>
                </div>
                <label class="switch-toggle">
                  <input
                    type="checkbox"
                    [checked]="userSettings['user.notifications_sound'] !== 'false'"
                    (change)="toggleSound($event)"
                  />
                  <span class="toggle-slider"></span>
                </label>
              </div>
            </div>
          </div>

          <div class="card-footer-actions">
            <ui-button [loading]="isSaving()" (click)="saveUserSettings()">
              {{ 'common.save' | t }}
            </ui-button>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .settings-page {
      display: flex;
      flex-direction: column;
      gap: 20px;
      padding: 0;
      max-width: 1000px;
      margin: 0 auto;
    }

    .view-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .header-left {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .view-title {
      font-size: 24px;
      font-weight: 700;
      color: var(--text-primary, #f1f5f9);
      margin: 0;
    }

    .view-subtitle {
      font-size: 13px;
      color: #94a3b8;
    }

    .icon-refresh-btn {
      width: 36px;
      height: 36px;
      border-radius: 8px;
      border: 1px solid rgba(255, 255, 255, 0.08);
      background: rgba(255, 255, 255, 0.04);
      color: #94a3b8;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .icon-refresh-btn:hover {
      background: rgba(255, 255, 255, 0.08);
      color: var(--text-primary, #f1f5f9);
    }

    /* Tabs */
    .tabs-nav-bar {
      display: flex;
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);
    }

    .tab-buttons {
      display: flex;
      gap: 4px;
    }

    .nav-tab-btn {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 10px 16px;
      border: none;
      background: transparent;
      color: #94a3b8;
      font-size: 14px;
      font-weight: 500;
      cursor: pointer;
      border-bottom: 2px solid transparent;
      transition: all 0.15s ease;
    }

    .nav-tab-btn .material-symbols-outlined {
      font-size: 18px;
    }

    .nav-tab-btn.active {
      color: var(--color-primary, #818cf8);
      border-bottom-color: var(--color-primary, #818cf8);
    }

    /* Settings Card */
    .settings-card {
      background: var(--bg-card, #1e293b);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 12px;
      padding: 24px;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }

    .card-header-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid rgba(255, 255, 255, 0.06);
      padding-bottom: 16px;
    }

    .card-title-group {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .card-icon {
      font-size: 28px;
      color: var(--color-primary, #818cf8);
    }

    .card-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-primary, #f1f5f9);
      margin: 0;
    }

    .card-desc {
      font-size: 13px;
      color: #94a3b8;
      margin: 2px 0 0 0;
    }

    .form-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 18px;
    }

    @media (max-width: 768px) {
      .form-grid {
        grid-template-columns: 1fr;
      }
    }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .form-group.full-width {
      grid-column: span 2;
    }

    @media (max-width: 768px) {
      .form-group.full-width {
        grid-column: span 1;
      }
    }

    .form-label {
      font-size: 13px;
      font-weight: 500;
      color: #cbd5e1;
    }

    .form-input, .form-select {
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 8px;
      padding: 9px 12px;
      color: var(--text-primary, #f1f5f9);
      font-size: 13px;
      outline: none;
      transition: border-color 0.15s ease;
    }

    .form-input:focus, .form-select:focus {
      border-color: var(--color-primary, #818cf8);
    }

    .form-select option {
      background: #1e293b;
      color: #f1f5f9;
    }

    .hint-text {
      font-size: 11px;
      color: #64748b;
    }

    /* Toggle Switch */
    .toggle-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 14px 16px;
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid rgba(255, 255, 255, 0.06);
      border-radius: 8px;
    }

    .toggle-info {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .toggle-title {
      font-size: 14px;
      font-weight: 500;
      color: var(--text-primary, #f1f5f9);
    }

    .toggle-desc {
      font-size: 12px;
      color: #94a3b8;
    }

    .switch-toggle {
      position: relative;
      display: inline-block;
      width: 44px;
      height: 24px;
    }

    .switch-toggle input {
      opacity: 0;
      width: 0;
      height: 0;
    }

    .toggle-slider {
      position: absolute;
      cursor: pointer;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background-color: rgba(255, 255, 255, 0.15);
      transition: .2s;
      border-radius: 24px;
    }

    .toggle-slider:before {
      position: absolute;
      content: "";
      height: 18px;
      width: 18px;
      left: 3px;
      bottom: 3px;
      background-color: white;
      transition: .2s;
      border-radius: 50%;
    }

    input:checked + .toggle-slider {
      background-color: var(--color-primary, #6366f1);
    }

    input:checked + .toggle-slider:before {
      transform: translateX(20px);
    }

    .card-footer-actions {
      display: flex;
      justify-content: flex-end;
      padding-top: 12px;
      border-top: 1px solid rgba(255, 255, 255, 0.06);
    }
  `]
})
export class SettingsComponent implements OnInit {
  activeTab: 'general' | 'security' | 'storage' | 'preferences' = 'general';

  systemSettings: Record<string, string> = {};
  userSettings: Record<string, string> = {};

  readonly isSaving = signal<boolean>(false);

  constructor(
    private api: ApiService,
    private toast: ToastService,
    public i18n: I18nService,
    private permService: PermissionService
  ) {}

  ngOnInit() {
    if (!this.canManageSystemSettings()) {
      this.activeTab = 'preferences';
    }
    this.loadAllSettings();
  }

  canManageSystemSettings(): boolean {
    return this.permService.hasPermission('platform.settings', 'view') ||
           this.permService.hasPermission('platform.settings', 'update') ||
           this.permService.hasPermission('settings', 'view') ||
           this.permService.hasPermission('settings', 'update');
  }

  loadAllSettings() {
    if (this.canManageSystemSettings()) {
      this.api.get<Record<string, string>>('/settings/system').subscribe({
        next: res => this.systemSettings = { ...res }
      });
    }

    this.api.get<Record<string, string>>('/settings/user').subscribe({
      next: res => this.userSettings = { ...res }
    });
  }

  saveSystemSettings() {
    this.isSaving.set(true);
    this.api.patch('/settings/system', this.systemSettings).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.toast.success(this.i18n.translate('common.saved'));
      },
      error: () => this.isSaving.set(false)
    });
  }

  saveUserSettings() {
    this.isSaving.set(true);
    this.api.patch('/settings/user', this.userSettings).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.toast.success(this.i18n.translate('common.saved'));
      },
      error: () => this.isSaving.set(false)
    });
  }

  changePersonalLang(lang: Language) {
    this.i18n.setLanguage(lang);
    this.userSettings['user.language'] = lang;
  }

  toggleRequire2fa(event: any) {
    this.systemSettings['security.require_2fa'] = event.target.checked ? 'true' : 'false';
  }

  toggleSound(event: any) {
    this.userSettings['user.notifications_sound'] = event.target.checked ? 'true' : 'false';
  }
}
