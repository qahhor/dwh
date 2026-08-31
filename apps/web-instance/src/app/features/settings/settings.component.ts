import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../core/services/toast.service';
import { I18nService, Language, TranslatePipe } from '../../core/services/i18n.service';
import { PermissionService } from '../../core/services/permission.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../shared/ui/ui-modal.component';

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
    UiButtonComponent,
    UiModalComponent
  ],
  template: `
    <div class="settings-page">
      <!-- Page Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'settings.title' | t }}</h1>
          <span class="count-badge">System Settings</span>
        </div>
        <div class="header-right">
          <button type="button" class="btn btn-secondary" [attr.aria-label]="'common.refresh' | t" (click)="loadAllSettings()" title="{{ 'common.refresh' | t }}">
            <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
            <span>{{ 'common.refresh' | t }}</span>
          </button>
        </div>
      </div>

      <!-- Tabs Navigation -->
      <div class="toolbar">
        <div class="status-tabs" role="tablist" aria-label="Разделы настроек">
          <button
            *ngIf="canManageSystemSettings()"
            id="settings-general-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'general'"
            [attr.aria-selected]="activeTab === 'general'"
            aria-controls="settings-general-panel"
            (click)="activeTab = 'general'"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">tune</span>
            <span>{{ 'settings.tab.general' | t }}</span>
          </button>

          <button
            *ngIf="canManageSystemSettings()"
            id="settings-security-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'security'"
            [attr.aria-selected]="activeTab === 'security'"
            aria-controls="settings-security-panel"
            (click)="activeTab = 'security'"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">security</span>
            <span>{{ 'settings.tab.security' | t }}</span>
          </button>

          <button
            *ngIf="canManageSystemSettings()"
            id="settings-storage-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'storage'"
            [attr.aria-selected]="activeTab === 'storage'"
            aria-controls="settings-storage-panel"
            (click)="activeTab = 'storage'"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">cloud</span>
            <span>{{ 'settings.tab.storage' | t }}</span>
          </button>

          <button
            id="settings-preferences-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'preferences'"
            [attr.aria-selected]="activeTab === 'preferences'"
            aria-controls="settings-preferences-panel"
            (click)="activeTab = 'preferences'"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">person</span>
            <span>{{ 'settings.tab.preferences' | t }}</span>
          </button>

          <button
            *ngIf="canManageSystemSettings()"
            id="settings-languages-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'languages'"
            [attr.aria-selected]="activeTab === 'languages'"
            aria-controls="settings-languages-panel"
            (click)="activeTab = 'languages'"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">language</span>
            <span>Языки и Локализация</span>
          </button>

          <button
            *ngIf="canManageSystemSettings()"
            id="settings-modules-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'modules'"
            [attr.aria-selected]="activeTab === 'modules'"
            aria-controls="settings-modules-panel"
            (click)="activeTab = 'modules'; loadCustomModules()"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">extension</span>
            <span>Модули и Расширения</span>
          </button>

          <button
            *ngIf="canManageSystemSettings()"
            id="settings-system-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'system'"
            [attr.aria-selected]="activeTab === 'system'"
            aria-controls="settings-system-panel"
            (click)="activeTab = 'system'"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">dns</span>
            <span>Система и Лицензия</span>
          </button>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 1: GENERAL SYSTEM SETTINGS -->
      <!-- =================================================================== -->
      <div id="settings-general-panel" class="tab-content" role="tabpanel" aria-labelledby="settings-general-tab" *ngIf="activeTab === 'general' && canManageSystemSettings()">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">corporate_fare</span>
              <div>
                <h3 class="card-title">Конфигурация компании</h3>
                <p class="card-desc">Глобальные параметры для всех сотрудников организации</p>
              </div>
            </div>
          </div>

          <div class="form-grid">
            <div class="form-group full-width">
              <label class="form-label" for="settings-company-name">{{ 'settings.company_name' | t }}</label>
              <input
                id="settings-company-name"
                name="settingsCompanyName"
                type="text"
                class="form-input"
                [(ngModel)]="systemSettings['system.company_name']"
                placeholder="Smartup DWH Platform"
              />
            </div>

            <div class="form-group">
              <label class="form-label" for="settings-default-language">{{ 'settings.default_language' | t }}</label>
              <select id="settings-default-language" name="settingsDefaultLanguage" class="form-select" [(ngModel)]="systemSettings['system.default_language']">
                <option value="ru">Русский (RU) 🇷🇺</option>
                <option value="uz">O'zbekcha (UZ) 🇺🇿</option>
                <option value="en">English (EN) 🇬🇧</option>
              </select>
            </div>

            <div class="form-group">
              <label class="form-label" for="settings-default-timezone">{{ 'settings.default_timezone' | t }}</label>
              <select id="settings-default-timezone" name="settingsDefaultTimezone" class="form-select" [(ngModel)]="systemSettings['system.default_timezone']">
                <option value="Asia/Tashkent">Asia/Tashkent (UTC+5)</option>
                <option value="Asia/Almaty">Asia/Almaty (UTC+5)</option>
                <option value="Europe/Moscow">Europe/Moscow (UTC+3)</option>
                <option value="UTC">UTC (GMT+0)</option>
              </select>
            </div>

            <div class="form-group">
              <label class="form-label" for="settings-date-format">{{ 'settings.date_format' | t }}</label>
              <select id="settings-date-format" name="settingsDateFormat" class="form-select" [(ngModel)]="systemSettings['system.date_format']">
                <option value="dd.MM.yyyy HH:mm">29.08.2026 14:30 (dd.MM.yyyy HH:mm)</option>
                <option value="yyyy-MM-dd HH:mm">2026-08-29 14:30 (yyyy-MM-dd HH:mm)</option>
                <option value="MM/dd/yyyy hh:mm a">08/29/2026 02:30 PM (MM/dd/yyyy)</option>
              </select>
            </div>
          </div>

          <div class="card-footer-actions">
            <ui-button [loading]="isSaving()" (onClick)="saveSystemSettings()">
              {{ 'common.save' | t }}
            </ui-button>
          </div>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 2: SECURITY POLICIES -->
      <!-- =================================================================== -->
      <div id="settings-security-panel" class="tab-content" role="tabpanel" aria-labelledby="settings-security-tab" *ngIf="activeTab === 'security' && canManageSystemSettings()">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">lock</span>
              <div>
                <h3 class="card-title">Политики безопасности и авторизации</h3>
                <p class="card-desc">Требования к паролям, 2FA и веб-сессиям</p>
              </div>
            </div>
          </div>

          <div class="form-grid">
            <div class="form-group">
              <label class="form-label" for="settings-password-length">{{ 'settings.min_password_len' | t }}</label>
              <input
                id="settings-password-length"
                name="settingsPasswordLength"
                type="number"
                min="8"
                max="32"
                class="form-input"
                aria-describedby="settings-password-length-hint"
                [(ngModel)]="systemSettings['security.min_password_length']"
              />
              <span id="settings-password-length-hint" class="hint-text">Рекомендуется не менее 10 символов</span>
            </div>

            <div class="form-group">
              <label class="form-label" for="settings-session-lifetime">{{ 'settings.session_lifetime' | t }}</label>
              <input
                id="settings-session-lifetime"
                name="settingsSessionLifetime"
                type="number"
                min="1"
                max="8760"
                class="form-input"
                aria-describedby="settings-session-lifetime-hint"
                [(ngModel)]="systemSettings['security.session_lifetime_hours']"
              />
              <span id="settings-session-lifetime-hint" class="hint-text">По умолчанию: 720 часов (30 дней)</span>
            </div>

            <div class="form-group full-width">
              <div class="toggle-row">
                <div class="toggle-info">
                  <span id="settings-require-2fa-label" class="toggle-title">{{ 'settings.require_2fa' | t }}</span>
                  <span class="toggle-desc">Принудительно требовать двухфакторную аутентификацию (OTP) для всех аккаунтов</span>
                </div>
                <label class="switch-toggle">
                  <input
                    id="settings-require-2fa"
                    name="settingsRequire2fa"
                    type="checkbox"
                    aria-labelledby="settings-require-2fa-label"
                    [checked]="systemSettings['security.require_2fa'] === 'true'"
                    (change)="toggleRequire2fa($event)"
                  />
                  <span class="toggle-slider" aria-hidden="true"></span>
                </label>
              </div>
            </div>
          </div>

          <div class="card-footer-actions">
            <ui-button [loading]="isSaving()" (onClick)="saveSystemSettings()">
              {{ 'common.save' | t }}
            </ui-button>
          </div>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 3: STORAGE QUOTAS -->
      <!-- =================================================================== -->
      <div id="settings-storage-panel" class="tab-content" role="tabpanel" aria-labelledby="settings-storage-tab" *ngIf="activeTab === 'storage' && canManageSystemSettings()">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">folder_shared</span>
              <div>
                <h3 class="card-title">Параметры хранилища и квоты</h3>
                <p class="card-desc">Лимиты дискового пространства для новых сотрудников</p>
              </div>
            </div>
          </div>

          <div class="form-grid">
            <div class="form-group">
              <label class="form-label" for="settings-user-quota">{{ 'settings.default_user_quota' | t }}</label>
              <input
                id="settings-user-quota"
                name="settingsUserQuota"
                type="number"
                min="100"
                max="102400"
                class="form-input"
                aria-describedby="settings-user-quota-hint"
                [(ngModel)]="systemSettings['storage.default_user_quota_mb']"
              />
              <span id="settings-user-quota-hint" class="hint-text">1024 MB = 1 GB на каждого сотрудника</span>
            </div>
          </div>

          <div class="card-footer-actions">
            <ui-button [loading]="isSaving()" (onClick)="saveSystemSettings()">
              {{ 'common.save' | t }}
            </ui-button>
          </div>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 4: USER PREFERENCES -->
      <!-- =================================================================== -->
      <div id="settings-preferences-panel" class="tab-content" role="tabpanel" aria-labelledby="settings-preferences-tab" *ngIf="activeTab === 'preferences'">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">palette</span>
              <div>
                <h3 class="card-title">Персональные предпочтения</h3>
                <p class="card-desc">Настройки внешнего вида и языка для вашей учётной записи</p>
              </div>
            </div>
          </div>

          <div class="form-grid">
            <div class="form-group">
              <label class="form-label" for="settings-interface-language">Язык интерфейса</label>
              <select id="settings-interface-language" name="settingsInterfaceLanguage" class="form-select" [ngModel]="i18n.currentLang()" (ngModelChange)="changePersonalLang($event)">
                <option value="ru">Русский (RU) 🇷🇺</option>
                <option value="uz">O'zbekcha (UZ) 🇺🇿</option>
                <option value="en">English (EN) 🇬🇧</option>
              </select>
            </div>

            <div class="form-group">
              <label class="form-label" for="settings-theme">{{ 'settings.theme' | t }}</label>
              <select id="settings-theme" name="settingsTheme" class="form-select" [(ngModel)]="userSettings['user.theme']">
                <option value="dark">Тёмная (Dark Premium) 🌙</option>
                <option value="light">Светлая (Light Clean) ☀️</option>
                <option value="system">Системная тема 💻</option>
              </select>
            </div>

            <div class="form-group full-width">
              <div class="toggle-row">
                <div class="toggle-info">
                  <span id="settings-notification-sound-label" class="toggle-title">{{ 'settings.notifications_sound' | t }}</span>
                  <span class="toggle-desc">Воспроизводить звуковой сигнал при получении нового уведомления</span>
                </div>
                <label class="switch-toggle">
                  <input
                    id="settings-notification-sound"
                    name="settingsNotificationSound"
                    type="checkbox"
                    aria-labelledby="settings-notification-sound-label"
                    [checked]="userSettings['user.notifications_sound'] !== 'false'"
                    (change)="toggleSound($event)"
                  />
                  <span class="toggle-slider" aria-hidden="true"></span>
                </label>
              </div>
            </div>
          </div>

          <div class="card-footer-actions">
            <ui-button [loading]="isSaving()" (onClick)="saveUserSettings()">
              {{ 'common.save' | t }}
            </ui-button>
          </div>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 5: SYSTEM & LICENSE STATUS -->
      <!-- =================================================================== -->
      <div id="settings-system-panel" class="tab-content" role="tabpanel" aria-labelledby="settings-system-tab" *ngIf="activeTab === 'system' && canManageSystemSettings()">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">dns</span>
              <div>
                <h3 class="card-title">Состояние системы и лицензии</h3>
                <p class="card-desc">Текущие параметры экземпляра, статус связи с Control Plane и версии ядра</p>
              </div>
            </div>
          </div>

          <div class="form-body">
            <div class="system-status-grid">
              <div class="status-card">
                <span class="status-card-label">Код экземпляра (Client Code)</span>
                <span class="status-card-val mono">{{ licenseInfo()?.clientCode || 'dev-local' }}</span>
              </div>
              <div class="status-card">
                <span class="status-card-label">Название организации</span>
                <span class="status-card-val">{{ licenseInfo()?.clientName || 'Local Development' }}</span>
              </div>
              <div class="status-card">
                <span class="status-card-label">Статус лицензии</span>
                <span class="status-badge" [class.badge-active]="licenseInfo()?.licenseStatus === 'ACTIVE'" [class.badge-suspended]="licenseInfo()?.licenseStatus === 'SUSPENDED'">
                  {{ licenseInfo()?.licenseStatus === 'ACTIVE' ? '🟢 АКТИВНА' : (licenseInfo()?.licenseStatus || '🟢 АКТИВНА') }}
                </span>
              </div>
              <div class="status-card">
                <span class="status-card-label">Профиль ресурсов (S/M/L)</span>
                <span class="status-card-val font-semibold">Профиль {{ licenseInfo()?.resourceProfile || 'S' }}</span>
              </div>
              <div class="status-card">
                <span class="status-card-label">Связь с Control Plane</span>
                <span class="status-card-val">
                  {{ licenseInfo()?.controlPlaneConfigured ? '🟢 Подключен (Heartbeat 5 мин)' : '⚪ Автономный режим' }}
                </span>
              </div>
              <div class="status-card">
                <span class="status-card-label">Версия ядра платформы</span>
                <span class="status-card-val mono">{{ licenseInfo()?.appVersion || '1.0.0' }}</span>
              </div>
              <div class="status-card">
                <span class="status-card-label">Версия схемы БД (Flyway)</span>
                <span class="status-card-val mono">v{{ licenseInfo()?.schemaVersion || '009' }}</span>
              </div>
              <div class="status-card">
                <span class="status-card-label">Режим работы</span>
                <span class="status-card-val font-semibold text-success">Полный доступ (Чтение / Запись)</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 6: LANGUAGES & TRANSLATIONS -->
      <!-- =================================================================== -->
      <div id="settings-languages-panel" class="tab-content" role="tabpanel" aria-labelledby="settings-languages-tab" *ngIf="activeTab === 'languages' && canManageSystemSettings()">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">translate</span>
              <div>
                <h3 class="card-title">Управление языковыми пакетами и локализацией</h3>
                <p class="card-desc">Динамическое добавление новых языков и импорт/экспорт JSON-словарей</p>
              </div>
            </div>
            <button type="button" class="btn btn-primary" (click)="openAddLangModal()">
              <span class="material-symbols-outlined" aria-hidden="true">add</span>
              <span>Добавить язык</span>
            </button>
          </div>

          <div class="table-card">
            <div class="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Код</th>
                    <th>Название языка</th>
                    <th>Тип</th>
                    <th>Статус</th>
                    <th style="text-align: right;">Действия</th>
                  </tr>
                </thead>
                <tbody>
                  <tr *ngFor="let lang of i18n.languages()">
                    <td><span class="badge badge-neutral mono">{{ lang.code.toUpperCase() }}</span></td>
                    <td class="font-medium">{{ lang.name }}</td>
                    <td>
                      <span class="badge" [class.badge-active]="!lang.isCustom" [class.badge-info]="lang.isCustom">
                        {{ lang.isCustom ? 'Пользовательский' : 'Встроенный' }}
                      </span>
                    </td>
                    <td>
                      <span class="badge badge-active" *ngIf="i18n.currentLang() === lang.code">Текущий активный</span>
                      <span class="badge badge-neutral" *ngIf="i18n.currentLang() !== lang.code">Доступен</span>
                    </td>
                    <td style="text-align: right;">
                      <div class="table-actions-right">
                        <button type="button" class="btn btn-secondary btn-sm" (click)="exportLangJson(lang.code)" title="Экспортировать JSON">
                          <span class="material-symbols-outlined" aria-hidden="true">download</span>
                          <span>JSON</span>
                        </button>
                        <button type="button" class="btn btn-primary btn-sm" *ngIf="i18n.currentLang() !== lang.code" (click)="i18n.setLanguage(lang.code)">
                          <span>Активировать</span>
                        </button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 7: CUSTOM CLIENT MODULES & SDK -->
      <!-- =================================================================== -->
      <div id="settings-modules-panel" class="tab-content" role="tabpanel" aria-labelledby="settings-modules-tab" *ngIf="activeTab === 'modules' && canManageSystemSettings()">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">extension</span>
              <div>
                <h3 class="card-title">Пользовательские модули и расширения (Plugin SDK)</h3>
                <p class="card-desc">Разработка микрофронтендов и интеграций с подтверждением Control Plane</p>
              </div>
            </div>
            <button type="button" class="btn btn-primary" (click)="openCreateModuleModal()">
              <span class="material-symbols-outlined" aria-hidden="true">add</span>
              <span>Создать манифест модуля</span>
            </button>
          </div>

          <div class="table-card">
            <div class="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Модуль</th>
                    <th>Код</th>
                    <th>Версия</th>
                    <th>Категория</th>
                    <th>Точка входа (URL)</th>
                    <th>Статус модерации</th>
                    <th style="text-align: right;">Действия</th>
                  </tr>
                </thead>
                <tbody>
                  <tr *ngFor="let mod of customModules()">
                    <td>
                      <div class="cell-entity">
                        <span class="material-symbols-outlined entity-icon" aria-hidden="true">{{ mod.icon || 'extension' }}</span>
                        <div>
                          <div class="entity-title font-medium">{{ mod.name }}</div>
                          <div class="entity-sub text-muted" *ngIf="mod.description">{{ mod.description }}</div>
                        </div>
                      </div>
                    </td>
                    <td><span class="badge badge-neutral mono">{{ mod.code }}</span></td>
                    <td><span class="mono">{{ mod.version }}</span></td>
                    <td>{{ mod.category }}</td>
                    <td><a [href]="mod.entrypointUrl" target="_blank" rel="noopener noreferrer" class="link mono text-xs">{{ mod.entrypointUrl }}</a></td>
                    <td>
                      <span class="badge badge-neutral" *ngIf="mod.status === 'DRAFT'">Черновик</span>
                      <span class="badge badge-warning" *ngIf="mod.status === 'PENDING_APPROVAL'">На модерации CP</span>
                      <span class="badge badge-active" *ngIf="mod.status === 'APPROVED'">Одобрен CP</span>
                      <span class="badge badge-danger" *ngIf="mod.status === 'REJECTED'" [title]="mod.rejectionReason || 'Отклонено'">Отклонен</span>
                    </td>
                    <td style="text-align: right;">
                      <div class="table-actions-right">
                        <button
                          *ngIf="mod.status === 'DRAFT' || mod.status === 'REJECTED'"
                          type="button"
                          class="btn btn-primary btn-sm"
                          (click)="submitModuleForApproval(mod.id)"
                        >
                          <span class="material-symbols-outlined" aria-hidden="true">send</span>
                          <span>В Control Plane</span>
                        </button>
                        <button
                          type="button"
                          class="btn btn-danger btn-sm"
                          (click)="deleteCustomModule(mod.id)"
                          title="Удалить модуль"
                        >
                          <span class="material-symbols-outlined" aria-hidden="true">delete</span>
                        </button>
                      </div>
                    </td>
                  </tr>
                  <tr *ngIf="customModules().length === 0">
                    <td colspan="7" class="text-center py-6 text-muted">
                      Кастомные модули еще не зарегистрированы. Нажмите «Создать манифест модуля».
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

    </div>

    <!-- Modal: Add New Custom Language -->
    <ui-modal
      *ngIf="isAddLangModalOpen()"
      title="Добавление нового языка"
      ariaLabel="Добавление нового языка"
      (close)="isAddLangModalOpen.set(false)"
    >
      <div class="form-grid">
        <div class="form-group">
          <label class="form-label" for="new-lang-code">Код языка (ISO 639-1)</label>
          <input id="new-lang-code" type="text" class="form-input" [(ngModel)]="newLangCode" placeholder="kk, ky, tg, de, tr" maxlength="10">
        </div>
        <div class="form-group">
          <label class="form-label" for="new-lang-name">Название языка</label>
          <input id="new-lang-name" type="text" class="form-input" [(ngModel)]="newLangName" placeholder="Қазақша, Deutsch, etc.">
        </div>
        <div class="form-group full-width">
          <label class="form-label" for="new-lang-json">JSON словарь переводов (опционально)</label>
          <textarea id="new-lang-json" class="form-input mono" rows="6" [(ngModel)]="newLangJson" placeholder='{ "nav.tasks": "Тапсырмалар", "common.save": "Сақтау" }'></textarea>
        </div>
      </div>
      <div modal-footer class="modal-footer-btns">
        <button type="button" class="btn btn-secondary" (click)="isAddLangModalOpen.set(false)">Отмена</button>
        <button type="button" class="btn btn-primary" (click)="saveNewLanguage()" [disabled]="!newLangCode || !newLangName">Сохранить язык</button>
      </div>
    </ui-modal>

    <!-- Modal: Create Custom Module Manifest -->
    <ui-modal
      *ngIf="isCreateModuleModalOpen()"
      title="Создание манифеста модуля (Plugin SDK)"
      ariaLabel="Создание манифеста модуля"
      (close)="isCreateModuleModalOpen.set(false)"
    >
      <div class="form-grid">
        <div class="form-group">
          <label class="form-label" for="mod-name">Название модуля</label>
          <input id="mod-name" type="text" class="form-input" [(ngModel)]="newModName" placeholder="HR Портал / Складской учет">
        </div>
        <div class="form-group">
          <label class="form-label" for="mod-code">Системный код модуля</label>
          <input id="mod-code" type="text" class="form-input mono" [(ngModel)]="newModCode" placeholder="hr_portal, warehouse_ops">
        </div>
        <div class="form-group">
          <label class="form-label" for="mod-version">Версия</label>
          <input id="mod-version" type="text" class="form-input mono" [(ngModel)]="newModVersion" placeholder="1.0.0">
        </div>
        <div class="form-group">
          <label class="form-label" for="mod-category">Категория</label>
          <select id="mod-category" class="form-select" [(ngModel)]="newModCategory">
            <option value="business">Бизнес-процессы</option>
            <option value="integration">Интеграция</option>
            <option value="analytics">Аналитика</option>
            <option value="custom">Пользовательский</option>
          </select>
        </div>
        <div class="form-group full-width">
          <label class="form-label" for="mod-entrypoint">Точка входа (Iframe / Microfrontend URL)</label>
          <input id="mod-entrypoint" type="url" class="form-input mono" [(ngModel)]="newModEntrypoint" placeholder="https://apps.company.com/hr-microfrontend/index.html">
        </div>
        <div class="form-group full-width">
          <label class="form-label" for="mod-desc">Описание назначения модуля</label>
          <textarea id="mod-desc" class="form-input" rows="3" [(ngModel)]="newModDesc" placeholder="Опишите функции модуля для модераторов Control Plane..."></textarea>
        </div>
      </div>
      <div modal-footer class="modal-footer-btns">
        <button type="button" class="btn btn-secondary" (click)="isCreateModuleModalOpen.set(false)">Отмена</button>
        <button type="button" class="btn btn-primary" (click)="saveCustomModule()" [disabled]="!newModName || !newModCode || !newModEntrypoint">Зарегистрировать манифест</button>
      </div>
    </ui-modal>
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

    .system-status-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 16px;
    }

    .status-card {
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 8px;
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .status-card-label {
      font-size: 12px;
      color: #94a3b8;
    }

    .status-card-val {
      font-size: 14px;
      color: #f1f5f9;
      font-weight: 500;
    }

    .status-badge {
      display: inline-block;
      padding: 3px 8px;
      border-radius: 4px;
      font-size: 12px;
      font-weight: 600;
      width: fit-content;
    }

    .badge-active { background: rgba(34, 197, 94, 0.15); color: #16a34a; }
    .badge-suspended { background: rgba(239, 68, 68, 0.15); color: #dc2626; }
    .text-success { color: #16a34a; }
  `]
})
export class SettingsComponent implements OnInit {
  activeTab: 'general' | 'security' | 'storage' | 'preferences' | 'system' | 'languages' | 'modules' = 'general';

  systemSettings: Record<string, string> = {};
  userSettings: Record<string, string> = {};
  readonly licenseInfo = signal<any>(null);
  readonly isSaving = signal<boolean>(false);

  // Languages Management
  readonly isAddLangModalOpen = signal<boolean>(false);
  newLangCode = '';
  newLangName = '';
  newLangJson = '';

  // Custom Modules Management
  readonly customModules = signal<any[]>([]);
  readonly isCreateModuleModalOpen = signal<boolean>(false);
  newModName = '';
  newModCode = '';
  newModVersion = '1.0.0';
  newModCategory = 'custom';
  newModEntrypoint = '';
  newModDesc = '';

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

      this.api.get<any>('/system/license-info').subscribe({
        next: res => this.licenseInfo.set(res),
        error: () => this.licenseInfo.set(null)
      });

      this.loadCustomModules();
    }

    this.api.get<Record<string, string>>('/settings/user').subscribe({
      next: res => this.userSettings = { ...res }
    });
  }

  loadCustomModules() {
    this.api.get<any[]>('/modules').subscribe({
      next: res => this.customModules.set(res || []),
      error: () => this.customModules.set([])
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

  changePersonalLang(lang: string) {
    this.i18n.setLanguage(lang);
    this.userSettings['user.language'] = lang;
  }

  toggleRequire2fa(event: any) {
    this.systemSettings['security.require_2fa'] = event.target.checked ? 'true' : 'false';
  }

  toggleSound(event: any) {
    this.userSettings['user.notifications_sound'] = event.target.checked ? 'true' : 'false';
  }

  // Language management methods
  openAddLangModal() {
    this.newLangCode = '';
    this.newLangName = '';
    this.newLangJson = '';
    this.isAddLangModalOpen.set(true);
  }

  saveNewLanguage() {
    if (!this.newLangCode || !this.newLangName) return;

    let dict: Record<string, string> = {};
    if (this.newLangJson) {
      try {
        dict = JSON.parse(this.newLangJson);
      } catch (e) {
        this.toast.error('Неверный формат JSON словаря');
        return;
      }
    }

    this.i18n.registerLanguage(this.newLangCode, this.newLangName, dict);
    this.isAddLangModalOpen.set(false);
    this.toast.success(`Язык ${this.newLangName} успешно добавлен в систему!`);
  }

  exportLangJson(langCode: string) {
    const json = this.i18n.exportDictionary(langCode);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `dwh-translations-${langCode}.json`;
    a.click();
    URL.revokeObjectURL(url);
    this.toast.info(`Словарь ${langCode.toUpperCase()} экспортирован в JSON`);
  }

  // Custom Module methods
  openCreateModuleModal() {
    this.newModName = '';
    this.newModCode = '';
    this.newModVersion = '1.0.0';
    this.newModCategory = 'custom';
    this.newModEntrypoint = '';
    this.newModDesc = '';
    this.isCreateModuleModalOpen.set(true);
  }

  saveCustomModule() {
    if (!this.newModName || !this.newModCode || !this.newModEntrypoint) return;

    this.api.post('/modules', {
      code: this.newModCode,
      name: this.newModName,
      version: this.newModVersion,
      category: this.newModCategory,
      entrypointUrl: this.newModEntrypoint,
      description: this.newModDesc,
      icon: 'extension'
    }).subscribe({
      next: () => {
        this.isCreateModuleModalOpen.set(false);
        this.toast.success(`Манифест модуля ${this.newModName} успешно создан!`);
        this.loadCustomModules();
      },
      error: err => this.toast.error(err.message || 'Ошибка создания модуля')
    });
  }

  submitModuleForApproval(id: number) {
    this.api.post(`/modules/${id}/submit`, {}).subscribe({
      next: () => {
        this.toast.success('Модуль отправлен на модерацию в Control Plane!');
        this.loadCustomModules();
      },
      error: err => this.toast.error(err.message || 'Ошибка отправки на модерацию')
    });
  }

  deleteCustomModule(id: number) {
    if (!confirm('Вы уверены, что хотите удалить этот модуль?')) return;

    this.api.delete(`/modules/${id}`).subscribe({
      next: () => {
        this.toast.success('Модуль удален');
        this.loadCustomModules();
      },
      error: err => this.toast.error(err.message || 'Ошибка удаления модуля')
    });
  }
}
