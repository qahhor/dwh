import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiBadgeComponent } from '../../../shared/ui/ui-badge.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { UiCustomFieldsComponent } from '../../../shared/ui/ui-custom-fields.component';
import { User } from '../../../core/models/auth.models';
import { Role } from '../../../core/models/rbac.models';
import { CustomField } from '../../../core/models/custom-field.models';
import { KeysetPage } from '../../../core/models/common.models';

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    UiButtonComponent,
    UiBadgeComponent,
    UiModalComponent,
    UiCustomFieldsComponent
  ],
  template: `
    <div class="users-container">
      <div class="page-header">
        <div>
          <h2 class="page-title">Пользователи</h2>
          <p class="page-subtitle">Управление учётными записями, ролями и динамическими полями</p>
        </div>
        <ui-button
          *ngIf="canCreateUser()"
          variant="primary"
          icon="person_add"
          (onClick)="openCreateModal()"
        >
          Создать пользователя
        </ui-button>
      </div>

      <!-- Filters & Search -->
      <div class="card toolbar-card">
        <div class="search-box">
          <span class="material-symbols-outlined">search</span>
          <input
            type="text"
            class="toolbar-input"
            placeholder="Поиск по имени, логину или email..."
            [(ngModel)]="searchQuery"
            (keyup.enter)="loadUsers(true)"
          />
        </div>

        <div class="filter-box">
          <select class="toolbar-select" [(ngModel)]="selectedState" (change)="loadUsers(true)">
            <option value="">Все статусы</option>
            <option value="A">Активные</option>
            <option value="P">Заблокированные / Удаленные</option>
          </select>
          <ui-button variant="secondary" size="md" (onClick)="loadUsers(true)">Применить</ui-button>
        </div>
      </div>

      <!-- Users Grid -->
      <div class="card table-card">
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 60px;">ID</th>
                <th>Пользователь</th>
                <th>Логин</th>
                <th>Контакты</th>
                <th>Руководитель</th>
                <th>Роли</th>
                <th style="text-align: center; width: 60px;">2FA</th>
                <th style="width: 110px;">Статус</th>
                <th style="width: 100px;">Создан</th>
                <th class="text-right" style="width: 140px;">Действия</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let u of users()">
                <td class="tabular-nums font-mono text-muted">#{{ u.id }}</td>
                <td>
                  <div class="user-cell">
                    <div class="user-avatar-sm">{{ getUserInitial(u) }}</div>
                    <div class="user-name-col">
                      <span class="user-fullname font-medium">{{ u.name }}</span>
                      <span class="user-tz-lang text-muted text-xs">{{ u.language || 'ru' }} &bull; {{ u.timezone || 'Asia/Tashkent' }}</span>
                    </div>
                  </div>
                </td>
                <td class="tabular-nums font-mono text-sm">&#64;{{ u.login }}</td>
                <td>
                  <div class="contact-info">
                    <div class="text-sm">{{ u.email }}</div>
                    <div class="text-muted text-xs font-mono" *ngIf="u.phone">{{ u.phone }}</div>
                  </div>
                </td>
                <td>
                  <span class="text-sm" *ngIf="getManagerName(u) as mName">{{ mName }}</span>
                  <span class="text-muted text-xs" *ngIf="!getManagerName(u)">—</span>
                </td>
                <td>
                  <div class="roles-badges">
                    <span *ngFor="let rName of getUserRoleNames(u)" class="role-badge">
                      {{ rName }}
                    </span>
                    <span *ngIf="getUserRoleNames(u).length === 0" class="text-muted text-xs">—</span>
                  </div>
                </td>
                <td style="text-align: center;">
                  <span class="material-symbols-outlined twofa-icon" [class.enabled]="u.is2faEnabled" [title]="u.is2faEnabled ? '2FA включена' : '2FA выключена'">
                    {{ u.is2faEnabled ? 'verified_user' : 'gpp_maybe' }}
                  </span>
                </td>
                <td>
                  <ui-badge [variant]="u.state === 'A' ? 'active' : 'passive'" [dot]="true">
                    {{ u.state === 'A' ? 'Активен' : 'Заблокирован' }}
                  </ui-badge>
                </td>
                <td class="tabular-nums text-muted text-xs">{{ u.createdAt | date:'dd.MM.yyyy' }}</td>
                <td class="text-right actions-cell">
                  <ui-button
                    *ngIf="canUpdateUser()"
                    variant="ghost"
                    size="sm"
                    icon="edit"
                    title="Редактировать"
                    (onClick)="openEditModal(u)"
                  ></ui-button>
                  <ui-button
                    *ngIf="u.state === 'A' && canBlockUser()"
                    variant="ghost"
                    size="sm"
                    icon="lock"
                    title="Заблокировать"
                    (onClick)="toggleUserState(u, 'block')"
                  ></ui-button>
                  <ui-button
                    *ngIf="u.state === 'P' && canUnblockUser()"
                    variant="ghost"
                    size="sm"
                    icon="lock_open"
                    title="Разблокировать"
                    (onClick)="toggleUserState(u, 'unblock')"
                  ></ui-button>
                  <ui-button
                    *ngIf="u.login !== 'admin' && canDeleteUser()"
                    variant="ghost"
                    size="sm"
                    icon="delete"
                    title="Удалить (анонимизировать)"
                    (onClick)="deleteUser(u)"
                  ></ui-button>
                </td>
              </tr>

              <tr *ngIf="users().length === 0 && !isLoading()">
                <td colspan="10" class="empty-cell">Пользователи не найдены</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Load More (Keyset) -->
        <div class="load-more-bar" *ngIf="hasMore()">
          <ui-button variant="secondary" size="md" [loading]="isLoading()" (onClick)="loadUsers(false)">
            Загрузить ещё
          </ui-button>
        </div>
      </div>
    </div>

    <!-- ========================================================================= -->
    <!-- Create User Modal                                                         -->
    <!-- ========================================================================= -->
    <ui-modal
      [isOpen]="isCreateModalOpen()"
      title="Создание нового пользователя"
      size="lg"
      (close)="isCreateModalOpen.set(false)"
    >
      <div body class="modal-form">
        <!-- Section 1: Basic credentials -->
        <div class="form-section-title">
          <span class="material-symbols-outlined">person</span>
          <span>Учётные данные</span>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">ФИО <span class="req">*</span></label>
            <input type="text" class="form-input" [(ngModel)]="createForm.name" placeholder="Иванов Иван Иванович" />
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Логин <span class="req">*</span></label>
            <input type="text" class="form-input font-mono" [(ngModel)]="createForm.login" placeholder="ivanov" />
            <span class="field-hint">Используется для авторизации (от 3 до 50 символов)</span>
          </div>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">Email <span class="req">*</span></label>
            <input type="email" class="form-input" [(ngModel)]="createForm.email" placeholder="ivanov@company.local" />
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Номер телефона</label>
            <input type="text" class="form-input font-mono" [(ngModel)]="createForm.phone" placeholder="+998901234567" />
          </div>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">Пароль <span class="req">*</span></label>
            <div class="password-input-wrapper">
              <input
                [type]="showPassword() ? 'text' : 'password'"
                class="form-input font-mono"
                [(ngModel)]="createForm.password"
                placeholder="Минимум 10 символов"
              />
              <button type="button" class="pwd-toggle-btn" (click)="showPassword.update(v => !v)">
                <span class="material-symbols-outlined">{{ showPassword() ? 'visibility_off' : 'visibility' }}</span>
              </button>
            </div>
            <span class="field-hint">Не менее 10 символов, не совпадает с логином и не из черного списка</span>
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Руководитель (Менеджер)</label>
            <select class="form-input" [(ngModel)]="createForm.managerId">
              <option [ngValue]="null">Без руководителя</option>
              <option *ngFor="let u of users()" [ngValue]="u.id">{{ u.name }} (&#64;{{ u.login }})</option>
            </select>
          </div>
        </div>

        <!-- Section 2: Regional & Security -->
        <div class="form-section-title">
          <span class="material-symbols-outlined">settings</span>
          <span>Настройки интерфейса и безопасность</span>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">Язык интерфейса</label>
            <select class="form-input" [(ngModel)]="createForm.language">
              <option value="ru">Русский (ru)</option>
              <option value="uz">O'zbekcha (uz)</option>
              <option value="en">English (en)</option>
            </select>
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Часовой пояс</label>
            <select class="form-input" [(ngModel)]="createForm.timezone">
              <option value="Asia/Tashkent">Asia/Tashkent (UTC+5)</option>
              <option value="Europe/Moscow">Europe/Moscow (UTC+3)</option>
              <option value="UTC">UTC (UTC+0)</option>
              <option value="Asia/Almaty">Asia/Almaty (UTC+5)</option>
              <option value="Asia/Dubai">Asia/Dubai (UTC+4)</option>
              <option value="America/New_York">America/New_York (UTC-5)</option>
            </select>
          </div>
        </div>

        <div class="form-group checkbox-card">
          <label class="checkbox-label">
            <input type="checkbox" [(ngModel)]="createForm.is2faEnabled" />
            <div class="checkbox-text">
              <span class="checkbox-title">Двухфакторная аутентификация (2FA OTP)</span>
              <span class="checkbox-desc">Запрашивать 6-значный OTP код при входе в систему</span>
            </div>
          </label>
        </div>

        <!-- Section 3: Roles Assignment -->
        <div class="form-section-title" *ngIf="roles().length > 0">
          <span class="material-symbols-outlined">security</span>
          <span>Назначение ролей (RBAC)</span>
        </div>

        <div class="roles-grid" *ngIf="roles().length > 0">
          <label *ngFor="let role of roles()" class="role-checkbox-card" [class.selected]="isRoleSelectedInCreate(role.id)">
            <input
              type="checkbox"
              [checked]="isRoleSelectedInCreate(role.id)"
              (change)="toggleRoleInCreate(role.id)"
            />
            <div class="role-checkbox-info">
              <span class="role-checkbox-name">{{ role.name }}</span>
              <span class="role-checkbox-pcode font-mono" *ngIf="role.pcode">{{ role.pcode }}</span>
            </div>
          </label>
        </div>

        <!-- Section 4: Dynamic Custom Fields -->
        <div class="custom-fields-section" *ngIf="customFields().length > 0">
          <div class="form-section-title">
            <span class="material-symbols-outlined">tune</span>
            <span>Дополнительные атрибуты</span>
          </div>
          <ui-custom-fields
            [fields]="customFields()"
            [(values)]="createForm.attributes"
          ></ui-custom-fields>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isCreateModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitCreateUser()">Создать пользователя</ui-button>
      </div>
    </ui-modal>

    <!-- ========================================================================= -->
    <!-- Edit User Modal                                                           -->
    <!-- ========================================================================= -->
    <ui-modal
      [isOpen]="isEditModalOpen()"
      title="Редактирование профиля пользователя"
      size="lg"
      (close)="isEditModalOpen.set(false)"
    >
      <div body class="modal-form" *ngIf="editingUser as u">
        <!-- Section 1: Basic info -->
        <div class="form-section-title">
          <span class="material-symbols-outlined">badge</span>
          <span>Основные данные (&#64;{{ u.login }})</span>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">ФИО <span class="req">*</span></label>
            <input type="text" class="form-input" [(ngModel)]="editForm.name" placeholder="Иванов Иван Иванович" />
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Номер телефона</label>
            <input type="text" class="form-input font-mono" [(ngModel)]="editForm.phone" placeholder="+998901234567" />
          </div>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">Email (только чтение)</label>
            <input type="email" class="form-input text-muted" [value]="u.email" disabled />
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Руководитель (Менеджер)</label>
            <select class="form-input" [(ngModel)]="editForm.managerId">
              <option [ngValue]="null">Без руководителя</option>
              <option *ngFor="let manager of getAvailableManagers(u.id)" [ngValue]="manager.id">
                {{ manager.name }} (&#64;{{ manager.login }})
              </option>
            </select>
          </div>
        </div>

        <!-- Section 2: Regional & Security -->
        <div class="form-section-title">
          <span class="material-symbols-outlined">settings</span>
          <span>Настройки интерфейса и безопасность</span>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">Язык интерфейса</label>
            <select class="form-input" [(ngModel)]="editForm.language">
              <option value="ru">Русский (ru)</option>
              <option value="uz">O'zbekcha (uz)</option>
              <option value="en">English (en)</option>
            </select>
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Часовой пояс</label>
            <select class="form-input" [(ngModel)]="editForm.timezone">
              <option value="Asia/Tashkent">Asia/Tashkent (UTC+5)</option>
              <option value="Europe/Moscow">Europe/Moscow (UTC+3)</option>
              <option value="UTC">UTC (UTC+0)</option>
              <option value="Asia/Almaty">Asia/Almaty (UTC+5)</option>
              <option value="Asia/Dubai">Asia/Dubai (UTC+4)</option>
              <option value="America/New_York">America/New_York (UTC-5)</option>
            </select>
          </div>
        </div>

        <div class="form-group checkbox-card">
          <label class="checkbox-label">
            <input type="checkbox" [(ngModel)]="editForm.is2faEnabled" />
            <div class="checkbox-text">
              <span class="checkbox-title">Двухфакторная аутентификация (2FA OTP)</span>
              <span class="checkbox-desc">Запрашивать 6-значный OTP код при входе в систему</span>
            </div>
          </label>
        </div>

        <!-- Section 3: Roles Assignment -->
        <div class="form-section-title" *ngIf="roles().length > 0">
          <span class="material-symbols-outlined">security</span>
          <span>Роли пользователя (RBAC)</span>
        </div>

        <div class="roles-grid" *ngIf="roles().length > 0">
          <label *ngFor="let role of roles()" class="role-checkbox-card" [class.selected]="isRoleSelectedInEdit(role.id)">
            <input
              type="checkbox"
              [checked]="isRoleSelectedInEdit(role.id)"
              (change)="toggleRoleInEdit(role.id)"
              [disabled]="u.login === 'admin' && role.pcode === 'admin'"
            />
            <div class="role-checkbox-info">
              <span class="role-checkbox-name">{{ role.name }}</span>
              <span class="role-checkbox-pcode font-mono" *ngIf="role.pcode">{{ role.pcode }}</span>
            </div>
          </label>
        </div>

        <!-- Section 4: Dynamic Custom Fields -->
        <div class="custom-fields-section" *ngIf="customFields().length > 0">
          <div class="form-section-title">
            <span class="material-symbols-outlined">tune</span>
            <span>Дополнительные атрибуты</span>
          </div>
          <ui-custom-fields
            [fields]="customFields()"
            [(values)]="editForm.attributes"
          ></ui-custom-fields>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isEditModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitEditUser()">Сохранить изменения</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .users-container {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 1400px;
    }

    .page-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
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
    }

    .toolbar-card {
      padding: 12px 16px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
    }

    .search-box {
      display: flex;
      align-items: center;
      gap: 8px;
      flex: 1;
      max-width: 420px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      padding: 4px 10px;
      color: var(--text-muted);
    }

    .toolbar-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 13px;
      font-family: inherit;
      color: var(--text-main);
      width: 100%;
    }

    .filter-box {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .toolbar-select {
      height: 34px;
      padding: 4px 10px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }

    .table-card {
      padding: 0;
      overflow: hidden;
    }

    .table-wrapper {
      overflow-x: auto;
    }

    .data-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
    }

    .data-table th {
      text-align: left;
      padding: 10px 14px;
      font-weight: 600;
      color: var(--text-muted);
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      font-size: 12px;
    }

    .data-table td {
      padding: 10px 14px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
      vertical-align: middle;
    }

    .user-cell {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .user-avatar-sm {
      width: 32px;
      height: 32px;
      border-radius: 50%;
      background-color: var(--primary);
      color: #ffffff;
      font-size: 13px;
      font-weight: 600;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }

    .user-name-col {
      display: flex;
      flex-direction: column;
    }

    .roles-badges {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
    }

    .role-badge {
      display: inline-flex;
      align-items: center;
      padding: 2px 7px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 500;
      background-color: var(--bg-hover);
      color: var(--text-main);
      border: 1px solid var(--border-color);
    }

    .text-right { text-align: right; }
    .text-muted { color: var(--text-muted); }
    .text-sm { font-size: 13px; }
    .text-xs { font-size: 11px; }
    .font-mono { font-family: monospace; }
    .font-medium { font-weight: 500; }
    .actions-cell { white-space: nowrap; }

    .twofa-icon {
      font-size: 20px;
      color: var(--text-light);
    }
    .twofa-icon.enabled {
      color: var(--success);
    }

    .load-more-bar {
      padding: 12px;
      display: flex;
      justify-content: center;
      background-color: var(--bg-hover);
      border-top: 1px solid var(--border-color);
    }

    .empty-cell {
      text-align: center;
      color: var(--text-muted);
      padding: 32px !important;
    }

    .modal-form {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-height: 70vh;
      overflow-y: auto;
      padding-right: 4px;
    }

    .form-section-title {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      font-weight: 600;
      color: var(--text-main);
      padding-bottom: 6px;
      border-bottom: 1px solid var(--border-color);
      margin-top: 6px;
    }
    .form-section-title .material-symbols-outlined {
      font-size: 18px;
      color: var(--primary);
    }

    .form-row {
      display: flex;
      gap: 14px;
    }

    .flex-1 { flex: 1; }

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
      line-height: 1.3;
    }

    .form-input {
      height: 36px;
      padding: 6px 10px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
      transition: border-color 0.15s ease;
    }
    .form-input:focus {
      border-color: var(--primary);
    }
    .form-input:disabled {
      background-color: var(--bg-hover);
      cursor: not-allowed;
    }

    .password-input-wrapper {
      position: relative;
      display: flex;
      align-items: center;
    }
    .password-input-wrapper .form-input {
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
    .pwd-toggle-btn:hover {
      color: var(--text-main);
    }

    .checkbox-card {
      padding: 10px 14px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
    }

    .checkbox-label {
      display: flex;
      align-items: flex-start;
      gap: 10px;
      cursor: pointer;
    }
    .checkbox-label input[type="checkbox"] {
      margin-top: 3px;
    }
    .checkbox-text {
      display: flex;
      flex-direction: column;
    }
    .checkbox-title {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-main);
    }
    .checkbox-desc {
      font-size: 11px;
      color: var(--text-muted);
    }

    .roles-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: 8px;
    }

    .role-checkbox-card {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 12px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .role-checkbox-card:hover {
      border-color: var(--primary);
      background-color: var(--bg-hover);
    }
    .role-checkbox-card.selected {
      border-color: var(--primary);
      background-color: rgba(99, 102, 241, 0.08);
    }
    .role-checkbox-info {
      display: flex;
      flex-direction: column;
    }
    .role-checkbox-name {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-main);
    }
    .role-checkbox-pcode {
      font-size: 11px;
      color: var(--text-muted);
    }

    .custom-fields-section {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
  `]
})
export class UsersComponent implements OnInit {
  readonly users = signal<User[]>([]);
  readonly roles = signal<Role[]>([]);
  readonly customFields = signal<CustomField[]>([]);
  readonly isLoading = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);
  readonly hasMore = signal<boolean>(false);
  readonly showPassword = signal<boolean>(false);
  nextCursor: string | null = null;

  searchQuery = '';
  selectedState = '';

  // Create User
  readonly isCreateModalOpen = signal<boolean>(false);
  createForm: any = {
    name: '',
    login: '',
    email: '',
    phone: '',
    password: '',
    managerId: null,
    language: 'ru',
    timezone: 'Asia/Tashkent',
    is2faEnabled: false,
    roleIds: [] as number[],
    attributes: {}
  };

  // Edit User
  readonly isEditModalOpen = signal<boolean>(false);
  editingUser: User | null = null;
  editForm: any = {
    name: '',
    phone: '',
    managerId: null,
    language: 'ru',
    timezone: 'Asia/Tashkent',
    is2faEnabled: false,
    roleIds: [] as number[],
    attributes: {}
  };

  constructor(
    public permService: PermissionService,
    private api: ApiService,
    private toast: ToastService
  ) {}

  ngOnInit() {
    this.loadUsers(true);
    this.loadRoles();
    this.loadCustomFields();
  }

  canCreateUser(): boolean {
    return this.permService.canCreate('iam.users') || this.permService.canCreate('md_users');
  }

  canUpdateUser(): boolean {
    return this.permService.canUpdate('iam.users') || this.permService.canUpdate('md_users');
  }

  canDeleteUser(): boolean {
    return this.permService.canDelete('iam.users') || this.permService.canDelete('md_users');
  }

  canBlockUser(): boolean {
    return this.permService.hasPermission('iam.users', 'block') || this.permService.hasPermission('md_users', 'block');
  }

  canUnblockUser(): boolean {
    return this.permService.hasPermission('iam.users', 'unblock') || this.permService.hasPermission('md_users', 'unblock');
  }

  loadUsers(reset: boolean = false) {
    if (reset) {
      this.nextCursor = null;
    }

    this.isLoading.set(true);
    this.api.get<KeysetPage<User>>('/iam/users', {
      limit: 20,
      cursor: this.nextCursor || undefined,
      search: this.searchQuery || undefined,
      state: this.selectedState || undefined
    }).subscribe({
      next: res => {
        this.isLoading.set(false);
        if (reset) {
          this.users.set(res.items || []);
        } else {
          this.users.update(cur => [...cur, ...(res.items || [])]);
        }
        this.nextCursor = res.nextCursor;
        this.hasMore.set(res.hasMore);
      },
      error: (err: any) => {
        this.isLoading.set(false);
        this.toast.error(err?.error?.detail || 'Ошибка при загрузке пользователей');
      }
    });
  }

  loadRoles() {
    this.api.get<Role[]>('/iam/roles').subscribe({
      next: res => this.roles.set(res || []),
      error: () => {}
    });
  }

  loadCustomFields() {
    this.api.get<CustomField[]>('/custom-fields', { entity_type: 'USER' }).subscribe({
      next: res => this.customFields.set(res || []),
      error: () => {}
    });
  }

  getUserInitial(user: User): string {
    return user.name ? user.name.trim().charAt(0).toUpperCase() : 'U';
  }

  getManagerName(user: User): string | null {
    if (!user.managerId) return null;
    const m = this.users().find(u => u.id === user.managerId);
    return m ? m.name : `ID: #${user.managerId}`;
  }

  getUserRoleNames(user: User): string[] {
    if (!user.roleIds || user.roleIds.length === 0) return [];
    const allRoles = this.roles();
    return user.roleIds
      .map(id => allRoles.find(r => r.id === id)?.name)
      .filter((name): name is string => !!name);
  }

  getAvailableManagers(currentUserId: number): User[] {
    return this.users().filter(u => u.id !== currentUserId && u.state === 'A');
  }

  // Create Modal Methods
  openCreateModal() {
    // Default to 'user' role if found
    const defaultUserRole = this.roles().find(r => r.pcode === 'user');
    const defaultRoleIds = defaultUserRole ? [defaultUserRole.id] : [];

    this.createForm = {
      name: '',
      login: '',
      email: '',
      phone: '',
      password: '',
      managerId: null,
      language: 'ru',
      timezone: 'Asia/Tashkent',
      is2faEnabled: false,
      roleIds: defaultRoleIds,
      attributes: {}
    };
    this.showPassword.set(false);
    this.isCreateModalOpen.set(true);
  }

  isRoleSelectedInCreate(roleId: number): boolean {
    return (this.createForm.roleIds || []).includes(roleId);
  }

  toggleRoleInCreate(roleId: number) {
    const list = this.createForm.roleIds || [];
    if (list.includes(roleId)) {
      this.createForm.roleIds = list.filter((id: number) => id !== roleId);
    } else {
      this.createForm.roleIds = [...list, roleId];
    }
  }

  submitCreateUser() {
    if (!this.createForm.name || !this.createForm.login || !this.createForm.email || !this.createForm.password) {
      this.toast.warning('Заполните обязательные поля: ФИО, логин, email и пароль');
      return;
    }

    if (this.createForm.password.length < 10) {
      this.toast.warning('Пароль должен содержать минимум 10 символов');
      return;
    }

    this.isSubmitting.set(true);
    this.api.post('/iam/users', this.createForm).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isCreateModalOpen.set(false);
        this.toast.success('Пользователь успешно создан');
        this.loadUsers(true);
      },
      error: (err: any) => {
        this.isSubmitting.set(false);
        this.toast.error(err?.error?.detail || 'Ошибка при создании пользователя');
      }
    });
  }

  // Edit Modal Methods
  openEditModal(user: User) {
    this.editingUser = user;
    this.editForm = {
      name: user.name,
      phone: user.phone || '',
      managerId: user.managerId || null,
      language: user.language || 'ru',
      timezone: user.timezone || 'Asia/Tashkent',
      is2faEnabled: !!user.is2faEnabled,
      roleIds: user.roleIds ? [...user.roleIds] : [],
      attributes: { ...(user.attributes || {}) }
    };
    this.isEditModalOpen.set(true);
  }

  isRoleSelectedInEdit(roleId: number): boolean {
    return (this.editForm.roleIds || []).includes(roleId);
  }

  toggleRoleInEdit(roleId: number) {
    const list = this.editForm.roleIds || [];
    if (list.includes(roleId)) {
      this.editForm.roleIds = list.filter((id: number) => id !== roleId);
    } else {
      this.editForm.roleIds = [...list, roleId];
    }
  }

  submitEditUser() {
    if (!this.editingUser) return;
    if (!this.editForm.name) {
      this.toast.warning('Имя пользователя обязательно для заполнения');
      return;
    }

    this.isSubmitting.set(true);
    this.api.patch(`/iam/users/${this.editingUser.id}`, this.editForm).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isEditModalOpen.set(false);
        this.toast.success('Данные пользователя успешно сохранены');
        this.loadUsers(true);
      },
      error: (err: any) => {
        this.isSubmitting.set(false);
        this.toast.error(err?.error?.detail || 'Ошибка при сохранении пользователя');
      }
    });
  }

  // State / Delete Actions
  toggleUserState(user: User, action: 'block' | 'unblock') {
    this.api.post(`/iam/users/${user.id}/${action}`).subscribe({
      next: () => {
        this.toast.success(action === 'block' ? 'Пользователь заблокирован' : 'Пользователь разблокирован');
        this.loadUsers(true);
      },
      error: (err: any) => {
        this.toast.error(err?.error?.detail || 'Ошибка при изменении статуса');
      }
    });
  }

  deleteUser(user: User) {
    if (confirm(`Вы действительно хотите удалить и анонимизировать пользователя "${user.name}" (@${user.login})?\n\nВнимание: Персональные данные будут стёрты, а все активные сессии аннулированы. Это действие необратимо.`)) {
      this.api.delete(`/iam/users/${user.id}`).subscribe({
        next: () => {
          this.toast.success(`Пользователь "${user.name}" успешно удалён и анонимизирован`);
          this.loadUsers(true);
        },
        error: (err: any) => {
          this.toast.error(err?.error?.detail || 'Ошибка при удалении пользователя');
        }
      });
    }
  }
}
