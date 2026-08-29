import { Component, OnInit, signal, computed } from '@angular/core';
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

type SortColumn = 'id' | 'name' | 'login' | 'createdAt';
type SortDirection = 'asc' | 'desc';

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
      <!-- Top Page Header -->
      <div class="page-header">
        <div>
          <div class="title-with-badge">
            <h2 class="page-title">Пользователи</h2>
            <span class="count-pill">{{ totalCountBadge() }}</span>
          </div>
          <p class="page-subtitle">Управление учётными записями, ролями RBAC, безопасностью 2FA и динамическими атрибутами</p>
        </div>
        <div class="header-actions">
          <ui-button
            variant="secondary"
            size="md"
            icon="file_download"
            title="Экспорт списка пользователей в CSV"
            (onClick)="exportToCsv()"
          >
            Экспорт
          </ui-button>
          <ui-button
            *ngIf="canCreateUser()"
            variant="primary"
            size="md"
            icon="person_add"
            (onClick)="openCreateModal()"
          >
            Создать пользователя
          </ui-button>
        </div>
      </div>

      <!-- Quick Filter Pills -->
      <div class="quick-filters-bar">
        <button
          type="button"
          class="quick-filter-chip"
          [class.active]="quickFilter() === 'ALL'"
          (click)="setQuickFilter('ALL')"
        >
          Все ({{ allUsersCount() }})
        </button>
        <button
          type="button"
          class="quick-filter-chip"
          [class.active]="quickFilter() === 'ACTIVE'"
          (click)="setQuickFilter('ACTIVE')"
        >
          <span class="status-dot green"></span> Активные ({{ activeUsersCount() }})
        </button>
        <button
          type="button"
          class="quick-filter-chip"
          [class.active]="quickFilter() === 'PASSIVE'"
          (click)="setQuickFilter('PASSIVE')"
        >
          <span class="status-dot red"></span> Заблокированные ({{ passiveUsersCount() }})
        </button>
        <button
          type="button"
          class="quick-filter-chip"
          [class.active]="quickFilter() === '2FA'"
          (click)="setQuickFilter('2FA')"
        >
          <span class="material-symbols-outlined chip-icon">verified_user</span> С 2FA ({{ twoFaUsersCount() }})
        </button>
        <button
          type="button"
          class="quick-filter-chip"
          [class.active]="quickFilter() === 'ADMINS'"
          (click)="setQuickFilter('ADMINS')"
        >
          <span class="material-symbols-outlined chip-icon">shield</span> Администраторы
        </button>
      </div>

      <!-- Advanced Filters & Toolbar Card -->
      <div class="card toolbar-card">
        <div class="toolbar-grid">
          <!-- Live Search Box -->
          <div class="search-box">
            <span class="material-symbols-outlined search-icon">search</span>
            <input
              type="text"
              class="toolbar-input"
              placeholder="Поиск по имени, логину, email или телефону..."
              [(ngModel)]="searchQuery"
              (input)="onSearchInput()"
              (keyup.enter)="loadUsers(true)"
            />
            <button *ngIf="searchQuery" type="button" class="clear-search-btn" (click)="clearSearch()">
              <span class="material-symbols-outlined">close</span>
            </button>
          </div>

          <!-- Role Filter -->
          <div class="filter-item">
            <label class="filter-label">Роль:</label>
            <select class="toolbar-select" [(ngModel)]="selectedRoleId" (change)="loadUsers(true)">
              <option [ngValue]="null">Все роли</option>
              <option *ngFor="let r of roles()" [ngValue]="r.id">{{ r.name }}</option>
            </select>
          </div>

          <!-- Status Filter -->
          <div class="filter-item">
            <label class="filter-label">Статус:</label>
            <select class="toolbar-select" [(ngModel)]="selectedState" (change)="loadUsers(true)">
              <option value="">Все статусы</option>
              <option value="A">Активные</option>
              <option value="P">Заблокированные</option>
            </select>
          </div>

          <!-- 2FA Filter -->
          <div class="filter-item">
            <label class="filter-label">2FA:</label>
            <select class="toolbar-select" [(ngModel)]="selected2fa" (change)="loadUsers(true)">
              <option [ngValue]="null">Все</option>
              <option [ngValue]="true">Включена</option>
              <option [ngValue]="false">Выключена</option>
            </select>
          </div>

          <!-- Actions: Reset & Refresh -->
          <div class="filter-buttons">
            <ui-button
              *ngIf="hasActiveFilters()"
              variant="secondary"
              size="sm"
              icon="filter_alt_off"
              title="Сбросить все фильтры"
              (onClick)="resetAllFilters()"
            >
              Сброс
            </ui-button>
            <ui-button
              variant="secondary"
              size="sm"
              icon="refresh"
              [loading]="isLoading()"
              title="Обновить список"
              (onClick)="loadUsers(true)"
            ></ui-button>
          </div>
        </div>
      </div>

      <!-- Users Grid -->
      <div class="card table-card">
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th class="sortable-th" style="width: 70px;" (click)="changeSort('id')">
                  <span>ID</span>
                  <span class="material-symbols-outlined sort-icon" *ngIf="sortColumn === 'id'">
                    {{ sortDirection === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                  </span>
                </th>
                <th class="sortable-th" (click)="changeSort('name')">
                  <span>Пользователь</span>
                  <span class="material-symbols-outlined sort-icon" *ngIf="sortColumn === 'name'">
                    {{ sortDirection === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                  </span>
                </th>
                <th class="sortable-th" (click)="changeSort('login')">
                  <span>Логин</span>
                  <span class="material-symbols-outlined sort-icon" *ngIf="sortColumn === 'login'">
                    {{ sortDirection === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                  </span>
                </th>
                <th>Контакты</th>
                <th>Руководитель</th>
                <th>Роли (RBAC)</th>
                <th style="text-align: center; width: 60px;">2FA</th>
                <th style="width: 120px;">Статус</th>
                <th class="sortable-th" style="width: 110px;" (click)="changeSort('createdAt')">
                  <span>Создан</span>
                  <span class="material-symbols-outlined sort-icon" *ngIf="sortColumn === 'createdAt'">
                    {{ sortDirection === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
                  </span>
                </th>
                <th class="text-right" style="width: 150px;">Действия</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let u of sortedUsers()" class="user-row" (dblclick)="openViewModal(u)">
                <td class="tabular-nums font-mono text-muted">#{{ u.id }}</td>
                <td>
                  <div class="user-cell" (click)="openViewModal(u)">
                    <div class="user-avatar-sm" [style.background-color]="getAvatarBgColor(u.name)">
                      {{ getUserInitial(u) }}
                    </div>
                    <div class="user-name-col">
                      <span class="user-fullname font-medium hover-link">{{ u.name }}</span>
                      <span class="user-tz-lang text-muted text-xs">
                        <span class="lang-tag">{{ (u.language || 'ru').toUpperCase() }}</span>
                        <span>{{ u.timezone || 'Asia/Tashkent' }}</span>
                      </span>
                    </div>
                  </div>
                </td>
                <td>
                  <span class="user-login-badge font-mono">&#64;{{ u.login }}</span>
                </td>
                <td>
                  <div class="contact-info">
                    <div class="text-sm font-sans">{{ u.email }}</div>
                    <div class="text-muted text-xs font-mono" *ngIf="u.phone">{{ u.phone }}</div>
                  </div>
                </td>
                <td>
                  <span class="text-sm" *ngIf="getManagerName(u) as mName">{{ mName }}</span>
                  <span class="text-muted text-xs" *ngIf="!getManagerName(u)">—</span>
                </td>
                <td>
                  <div class="roles-badges">
                    <span *ngFor="let rName of getUserRoleNames(u)" class="role-badge" [class.admin-role]="rName.toLowerCase().includes('админ') || rName.toLowerCase().includes('admin')">
                      {{ rName }}
                    </span>
                    <span *ngIf="getUserRoleNames(u).length === 0" class="text-muted text-xs">—</span>
                  </div>
                </td>
                <td style="text-align: center;">
                  <span
                    class="material-symbols-outlined twofa-icon"
                    [class.enabled]="u.is2faEnabled"
                    [title]="u.is2faEnabled ? '2FA активна (OTP)' : '2FA отключена'"
                  >
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
                    variant="ghost"
                    size="sm"
                    icon="visibility"
                    title="Просмотреть карточку пользователя"
                    (onClick)="openViewModal(u)"
                  ></ui-button>
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
                    (onClick)="openDeleteConfirmModal(u)"
                  ></ui-button>
                </td>
              </tr>

              <tr *ngIf="users().length === 0 && !isLoading()">
                <td colspan="10" class="empty-cell">
                  <div class="empty-state-box">
                    <span class="material-symbols-outlined empty-icon">person_off</span>
                    <span class="empty-title">Пользователи не найдены</span>
                    <span class="empty-desc">Попробуйте изменить параметры поиска или фильтров</span>
                    <ui-button *ngIf="hasActiveFilters()" variant="secondary" size="sm" (onClick)="resetAllFilters()">
                      Сбросить фильтры
                    </ui-button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Load More (Keyset) -->
        <div class="load-more-bar" *ngIf="hasMore()">
          <ui-button variant="secondary" size="md" [loading]="isLoading()" (onClick)="loadUsers(false)">
            Загрузить ещё записи
          </ui-button>
        </div>
      </div>
    </div>

    <!-- ========================================================================= -->
    <!-- View User Details Modal (Drawer)                                          -->
    <!-- ========================================================================= -->
    <ui-modal
      [isOpen]="isViewModalOpen()"
      title="Карточка пользователя"
      size="md"
      (close)="isViewModalOpen.set(false)"
    >
      <div body class="view-user-body" *ngIf="viewingUser as u">
        <div class="user-hero-card">
          <div class="user-avatar-large" [style.background-color]="getAvatarBgColor(u.name)">
            {{ getUserInitial(u) }}
          </div>
          <div class="user-hero-info">
            <h3 class="hero-name">{{ u.name }}</h3>
            <span class="hero-login font-mono">&#64;{{ u.login }}</span>
            <div class="hero-badges">
              <ui-badge [variant]="u.state === 'A' ? 'active' : 'passive'" [dot]="true">
                {{ u.state === 'A' ? 'Активен' : 'Заблокирован' }}
              </ui-badge>
              <ui-badge *ngIf="u.is2faEnabled" variant="active">
                <span class="material-symbols-outlined badge-inline-icon">verified_user</span> 2FA Активна
              </ui-badge>
              <ui-badge *ngIf="!u.is2faEnabled" variant="passive">2FA Отключена</ui-badge>
            </div>
          </div>
        </div>

        <div class="details-section">
          <h4 class="details-title">Контакты и Организация</h4>
          <div class="details-grid">
            <div class="detail-row">
              <span class="detail-label">Email:</span>
              <span class="detail-val font-mono">{{ u.email }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">Телефон:</span>
              <span class="detail-val font-mono">{{ u.phone || 'Не указан' }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">Руководитель:</span>
              <span class="detail-val">{{ getManagerName(u) || 'Нет руководителя' }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">Язык интерфейса:</span>
              <span class="detail-val">{{ getLanguageLabel(u.language) }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">Часовой пояс:</span>
              <span class="detail-val">{{ u.timezone || 'Asia/Tashkent' }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">Дата регистрации:</span>
              <span class="detail-val">{{ u.createdAt | date:'dd.MM.yyyy HH:mm' }}</span>
            </div>
          </div>
        </div>

        <div class="details-section">
          <h4 class="details-title">Назначенные роли (RBAC)</h4>
          <div class="roles-badges-view">
            <span *ngFor="let rName of getUserRoleNames(u)" class="role-badge large">
              <span class="material-symbols-outlined role-icon">shield</span>
              {{ rName }}
            </span>
            <span *ngIf="getUserRoleNames(u).length === 0" class="text-muted text-sm">Роли не назначены</span>
          </div>
        </div>

        <div class="details-section" *ngIf="hasAttributes(u)">
          <h4 class="details-title">Динамические атрибуты</h4>
          <div class="details-grid">
            <div class="detail-row" *ngFor="let entry of getAttributesList(u)">
              <span class="detail-label">{{ entry.key }}:</span>
              <span class="detail-val font-mono">{{ entry.value }}</span>
            </div>
          </div>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isViewModalOpen.set(false)">Закрыть</ui-button>
        <ui-button
          *ngIf="canUpdateUser() && viewingUser"
          variant="primary"
          size="md"
          icon="edit"
          (onClick)="openEditFromView()"
        >
          Редактировать
        </ui-button>
      </div>
    </ui-modal>

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
          <span>1. Основные учётные данные</span>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">ФИО пользователя <span class="req">*</span></label>
            <input type="text" class="form-input" [(ngModel)]="createForm.name" placeholder="Иванов Иван Иванович" />
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Логин в системе <span class="req">*</span></label>
            <input type="text" class="form-input font-mono" [(ngModel)]="createForm.login" placeholder="ivanov" />
            <span class="field-hint">От 3 до 50 символов (латиница, цифры, знаки)</span>
          </div>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">Рабочий Email <span class="req">*</span></label>
            <input type="email" class="form-input font-mono" [(ngModel)]="createForm.email" placeholder="ivanov@company.local" />
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Номер телефона</label>
            <input type="text" class="form-input font-mono" [(ngModel)]="createForm.phone" placeholder="+998901234567" />
          </div>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">Руководитель (Менеджер)</label>
            <select class="form-input" [(ngModel)]="createForm.managerId">
              <option [ngValue]="null">Без руководителя</option>
              <option *ngFor="let u of users()" [ngValue]="u.id">{{ u.name }} (&#64;{{ u.login }})</option>
            </select>
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Временный пароль <span class="req">*</span></label>
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
            <div class="password-strength-bar" *ngIf="createForm.password">
              <div class="strength-indicator" [class]="getPasswordStrengthClass(createForm.password)"></div>
              <span class="strength-text">{{ getPasswordStrengthText(createForm.password) }}</span>
            </div>
          </div>
        </div>

        <!-- Section 2: Regional & Security -->
        <div class="form-section-title">
          <span class="material-symbols-outlined">settings</span>
          <span>2. Настройки интерфейса и безопасность</span>
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
              <option value="Asia/Tashkent">Asia/Tashkent (UTC+5 - Ташкент)</option>
              <option value="Europe/Moscow">Europe/Moscow (UTC+3 - Москва)</option>
              <option value="UTC">UTC (UTC+0 - Гринвич)</option>
              <option value="Asia/Almaty">Asia/Almaty (UTC+5 - Алматы)</option>
              <option value="Asia/Dubai">Asia/Dubai (UTC+4 - Дубай)</option>
              <option value="America/New_York">America/New_York (UTC-5 - Нью-Йорк)</option>
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
        <div class="form-section-title">
          <span class="material-symbols-outlined">security</span>
          <span>3. Назначение ролей (RBAC)</span>
        </div>

        <div class="roles-grid" *ngIf="roles().length > 0">
          <label
            *ngFor="let role of roles()"
            class="role-checkbox-card"
            [class.selected]="isRoleSelectedInCreate(role.id)"
          >
            <input
              type="checkbox"
              [checked]="isRoleSelectedInCreate(role.id)"
              (change)="toggleRoleInCreate(role.id)"
            />
            <div class="role-checkbox-info">
              <span class="role-checkbox-name">{{ role.name }}</span>
              <span class="role-checkbox-pcode font-mono">{{ role.pcode || 'custom' }}</span>
            </div>
          </label>
        </div>

        <div *ngIf="roles().length === 0" class="text-muted text-xs p-2">
          Загрузка доступных системных ролей...
        </div>

        <!-- Section 4: Dynamic Custom Fields -->
        <div class="custom-fields-section" *ngIf="customFields().length > 0">
          <div class="form-section-title">
            <span class="material-symbols-outlined">tune</span>
            <span>4. Дополнительные динамические атрибуты</span>
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
      title="Редактирование пользователя"
      size="lg"
      (close)="isEditModalOpen.set(false)"
    >
      <div body class="modal-form" *ngIf="editingUser as u">
        <!-- Section 1: Basic info -->
        <div class="form-section-title">
          <span class="material-symbols-outlined">badge</span>
          <span>1. Основные данные (&#64;{{ u.login }})</span>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">ФИО пользователя <span class="req">*</span></label>
            <input type="text" class="form-input" [(ngModel)]="editForm.name" placeholder="Иванов Иван Иванович" />
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Номер телефона</label>
            <input type="text" class="form-input font-mono" [(ngModel)]="editForm.phone" placeholder="+998901234567" />
          </div>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">Email (системный идентификатор)</label>
            <input type="email" class="form-input text-muted font-mono" [value]="u.email" disabled />
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
          <span>2. Настройки интерфейса и безопасность</span>
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
              <option value="Asia/Tashkent">Asia/Tashkent (UTC+5 - Ташкент)</option>
              <option value="Europe/Moscow">Europe/Moscow (UTC+3 - Москва)</option>
              <option value="UTC">UTC (UTC+0 - Гринвич)</option>
              <option value="Asia/Almaty">Asia/Almaty (UTC+5 - Алматы)</option>
              <option value="Asia/Dubai">Asia/Dubai (UTC+4 - Дубай)</option>
              <option value="America/New_York">America/New_York (UTC-5 - Нью-Йорк)</option>
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
        <div class="form-section-title">
          <span class="material-symbols-outlined">security</span>
          <span>3. Роли пользователя (RBAC)</span>
        </div>

        <div class="roles-grid" *ngIf="roles().length > 0">
          <label
            *ngFor="let role of roles()"
            class="role-checkbox-card"
            [class.selected]="isRoleSelectedInEdit(role.id)"
            [class.locked]="u.login === 'admin' && role.pcode === 'admin'"
          >
            <input
              type="checkbox"
              [checked]="isRoleSelectedInEdit(role.id)"
              (change)="toggleRoleInEdit(role.id)"
              [disabled]="u.login === 'admin' && role.pcode === 'admin'"
            />
            <div class="role-checkbox-info">
              <div class="role-title-line">
                <span class="role-checkbox-name">{{ role.name }}</span>
                <span *ngIf="u.login === 'admin' && role.pcode === 'admin'" class="material-symbols-outlined lock-icon" title="Роль суперадминистратора защищена">lock</span>
              </div>
              <span class="role-checkbox-pcode font-mono">{{ role.pcode || 'custom' }}</span>
            </div>
          </label>
        </div>

        <!-- Section 4: Dynamic Custom Fields -->
        <div class="custom-fields-section" *ngIf="customFields().length > 0">
          <div class="form-section-title">
            <span class="material-symbols-outlined">tune</span>
            <span>4. Дополнительные динамические атрибуты</span>
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

    <!-- ========================================================================= -->
    <!-- Delete Confirmation Modal                                                 -->
    <!-- ========================================================================= -->
    <ui-modal
      [isOpen]="isDeleteModalOpen()"
      title="Подтверждение удаления пользователя"
      size="sm"
      (close)="isDeleteModalOpen.set(false)"
    >
      <div body class="delete-modal-body" *ngIf="deletingUser as u">
        <div class="delete-warning-icon">
          <span class="material-symbols-outlined">warning</span>
        </div>
        <h4 class="delete-confirm-title">Удалить и анонимизировать пользователя?</h4>
        <p class="delete-confirm-desc">
          Пользователь <strong>{{ u.name }}</strong> (&#64;{{ u.login }}) будет переведен в статус заблокирован, его персональные данные стёрты (GDPR), а все активные сессии и токены аннулированы.
        </p>
        <div class="delete-danger-note">
          <span class="material-symbols-outlined">info</span>
          <span>Это действие необратимо и будет записано в журнал аудита.</span>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isDeleteModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="danger" size="md" [loading]="isSubmitting()" (onClick)="confirmDeleteUser()">
          Да, удалить
        </ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .users-container {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 1440px;
    }

    .page-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
    }

    .title-with-badge {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .page-title {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-main);
      margin: 0;
    }

    .count-pill {
      background-color: var(--bg-hover);
      color: var(--primary);
      font-size: 12px;
      font-weight: 600;
      padding: 2px 8px;
      border-radius: 12px;
      border: 1px solid var(--border-color);
    }

    .page-subtitle {
      font-size: 13px;
      color: var(--text-muted);
      margin-top: 2px;
    }

    .header-actions {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    /* Quick Filter Chips */
    .quick-filters-bar {
      display: flex;
      align-items: center;
      gap: 8px;
      overflow-x: auto;
      padding-bottom: 2px;
    }

    .quick-filter-chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 6px 12px;
      border-radius: 20px;
      font-size: 12px;
      font-weight: 500;
      color: var(--text-muted);
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      cursor: pointer;
      white-space: nowrap;
      transition: all 0.15s ease;
    }
    .quick-filter-chip:hover {
      background-color: var(--bg-hover);
      color: var(--text-main);
    }
    .quick-filter-chip.active {
      background-color: rgba(99, 102, 241, 0.12);
      border-color: var(--primary);
      color: var(--primary);
      font-weight: 600;
    }

    .status-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
    }
    .status-dot.green { background-color: var(--success); }
    .status-dot.red { background-color: var(--danger); }

    .chip-icon {
      font-size: 15px;
    }

    .card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
    }

    /* Toolbar & Search */
    .toolbar-card {
      padding: 12px 16px;
    }

    .toolbar-grid {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 12px;
    }

    .search-box {
      display: flex;
      align-items: center;
      gap: 8px;
      flex: 1;
      min-width: 280px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      padding: 6px 10px;
      color: var(--text-muted);
      position: relative;
    }

    .search-icon { font-size: 18px; }

    .toolbar-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 13px;
      font-family: inherit;
      color: var(--text-main);
      width: 100%;
    }

    .clear-search-btn {
      background: transparent;
      border: none;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      align-items: center;
      padding: 0;
    }
    .clear-search-btn:hover { color: var(--text-main); }

    .filter-item {
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .filter-label {
      font-size: 12px;
      font-weight: 500;
      color: var(--text-muted);
    }

    .toolbar-select {
      height: 36px;
      padding: 4px 10px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }
    .toolbar-select:focus { border-color: var(--primary); }

    .filter-buttons {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-left: auto;
    }

    /* Table */
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
      white-space: nowrap;
      user-select: none;
    }

    .sortable-th {
      cursor: pointer;
      transition: color 0.15s ease;
    }
    .sortable-th:hover {
      color: var(--text-main);
    }

    .sort-icon {
      font-size: 14px;
      vertical-align: middle;
      margin-left: 4px;
      color: var(--primary);
    }

    .data-table td {
      padding: 10px 14px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
      vertical-align: middle;
    }

    .user-row {
      transition: background-color 0.15s ease;
    }
    .user-row:hover {
      background-color: var(--bg-hover);
    }

    .user-cell {
      display: flex;
      align-items: center;
      gap: 10px;
      cursor: pointer;
    }

    .user-avatar-sm {
      width: 34px;
      height: 34px;
      border-radius: 50%;
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

    .hover-link:hover {
      color: var(--primary);
      text-decoration: underline;
    }

    .user-tz-lang {
      display: flex;
      align-items: center;
      gap: 4px;
    }

    .lang-tag {
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      padding: 0 4px;
      border-radius: 3px;
      font-size: 10px;
      font-weight: 600;
    }

    .user-login-badge {
      display: inline-block;
      padding: 2px 6px;
      background-color: var(--bg-hover);
      border-radius: 4px;
      font-size: 12px;
      color: var(--text-main);
    }

    .roles-badges {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
    }

    .role-badge {
      display: inline-flex;
      align-items: center;
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 500;
      background-color: var(--bg-hover);
      color: var(--text-main);
      border: 1px solid var(--border-color);
    }
    .role-badge.admin-role {
      background-color: rgba(239, 68, 68, 0.1);
      border-color: rgba(239, 68, 68, 0.3);
      color: var(--danger);
      font-weight: 600;
    }
    .role-badge.large {
      padding: 4px 10px;
      font-size: 12px;
      gap: 4px;
    }

    .role-icon { font-size: 16px; color: var(--primary); }

    .text-right { text-align: right; }
    .text-muted { color: var(--text-muted); }
    .text-sm { font-size: 13px; }
    .text-xs { font-size: 11px; }
    .font-mono { font-family: monospace; }
    .font-sans { font-family: inherit; }
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
      padding: 40px !important;
    }

    .empty-state-box {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
    }
    .empty-icon { font-size: 48px; color: var(--text-muted); }
    .empty-title { font-size: 15px; font-weight: 600; color: var(--text-main); }
    .empty-desc { font-size: 13px; color: var(--text-muted); }

    /* Modals */
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
      margin-top: 4px;
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
    .form-input:focus { border-color: var(--primary); }
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
    .pwd-toggle-btn:hover { color: var(--text-main); }

    .password-strength-bar {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-top: 4px;
    }
    .strength-indicator {
      flex: 1;
      height: 4px;
      border-radius: 2px;
      background-color: var(--border-color);
    }
    .strength-indicator.weak { background-color: var(--danger); width: 33%; }
    .strength-indicator.medium { background-color: #f59e0b; width: 66%; }
    .strength-indicator.strong { background-color: var(--success); width: 100%; }
    .strength-text { font-size: 11px; color: var(--text-muted); }

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
    .checkbox-label input[type="checkbox"] { margin-top: 3px; }
    .checkbox-text { display: flex; flex-direction: column; }
    .checkbox-title { font-size: 13px; font-weight: 500; color: var(--text-main); }
    .checkbox-desc { font-size: 11px; color: var(--text-muted); }

    .roles-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
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
    .role-checkbox-card.locked {
      opacity: 0.8;
      cursor: not-allowed;
    }
    .role-title-line {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .lock-icon { font-size: 14px; color: var(--text-muted); }
    .role-checkbox-info { display: flex; flex-direction: column; }
    .role-checkbox-name { font-size: 13px; font-weight: 500; color: var(--text-main); }
    .role-checkbox-pcode { font-size: 11px; color: var(--text-muted); }

    .custom-fields-section {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    /* View Modal Details */
    .view-user-body {
      display: flex;
      flex-direction: column;
      gap: 18px;
    }

    .user-hero-card {
      display: flex;
      align-items: center;
      gap: 16px;
      padding: 14px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
    }

    .user-avatar-large {
      width: 56px;
      height: 56px;
      border-radius: 50%;
      color: #ffffff;
      font-size: 22px;
      font-weight: 700;
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .user-hero-info {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .hero-name { font-size: 16px; font-weight: 600; color: var(--text-main); margin: 0; }
    .hero-login { font-size: 13px; color: var(--text-muted); }
    .hero-badges { display: flex; gap: 6px; margin-top: 4px; }
    .badge-inline-icon { font-size: 14px; vertical-align: middle; }

    .details-section {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .details-title {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-muted);
      text-transform: uppercase;
      letter-spacing: 0.5px;
      margin: 0;
    }

    .details-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px 16px;
      background-color: var(--bg-surface);
      padding: 12px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
    }

    .detail-row {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .detail-label { font-size: 11px; color: var(--text-muted); }
    .detail-val { font-size: 13px; color: var(--text-main); font-weight: 500; }

    .roles-badges-view {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }

    /* Delete Modal */
    .delete-modal-body {
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      gap: 12px;
      padding: 10px 0;
    }

    .delete-warning-icon {
      width: 48px;
      height: 48px;
      border-radius: 50%;
      background-color: rgba(239, 68, 68, 0.1);
      color: var(--danger);
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .delete-warning-icon .material-symbols-outlined { font-size: 28px; }
    .delete-confirm-title { font-size: 15px; font-weight: 600; color: var(--text-main); margin: 0; }
    .delete-confirm-desc { font-size: 13px; color: var(--text-muted); line-height: 1.4; margin: 0; }
    .delete-danger-note {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 12px;
      color: var(--danger);
      background-color: rgba(239, 68, 68, 0.06);
      padding: 8px 12px;
      border-radius: var(--radius-sm);
      margin-top: 4px;
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

  // Search & Filter state
  searchQuery = '';
  selectedState = '';
  selectedRoleId: number | null = null;
  selected2fa: boolean | null = null;
  quickFilter = signal<'ALL' | 'ACTIVE' | 'PASSIVE' | '2FA' | 'ADMINS'>('ALL');

  // Sorting
  sortColumn: SortColumn = 'id';
  sortDirection: SortDirection = 'asc';

  private searchDebounceTimer: any = null;

  // Modals
  readonly isCreateModalOpen = signal<boolean>(false);
  readonly isEditModalOpen = signal<boolean>(false);
  readonly isViewModalOpen = signal<boolean>(false);
  readonly isDeleteModalOpen = signal<boolean>(false);

  viewingUser: User | null = null;
  editingUser: User | null = null;
  deletingUser: User | null = null;

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
    this.loadRoles();
    this.loadUsers(true);
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

  // Loaders
  loadUsers(reset: boolean = false) {
    if (reset) {
      this.nextCursor = null;
    }

    this.isLoading.set(true);

    const params: any = {
      limit: 20,
      cursor: this.nextCursor || undefined,
      search: this.searchQuery ? this.searchQuery.trim() : undefined,
      state: this.selectedState || undefined,
      role_id: this.selectedRoleId || undefined,
      is_2fa_enabled: this.selected2fa !== null ? this.selected2fa : undefined
    };

    this.api.get<KeysetPage<User>>('/iam/users', params).subscribe({
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
    this.api.get<Role[]>('/rbac/roles').subscribe({
      next: res => {
        this.roles.set(res || []);
      },
      error: () => {
        this.api.get<Role[]>('/iam/roles').subscribe({
          next: res => this.roles.set(res || []),
          error: () => {}
        });
      }
    });
  }

  loadCustomFields() {
    this.api.get<CustomField[]>('/custom-fields', { entity_type: 'USER' }).subscribe({
      next: res => this.customFields.set(res || []),
      error: () => {}
    });
  }

  // Computed & Filters
  allUsersCount = computed(() => this.users().length);
  activeUsersCount = computed(() => this.users().filter(u => u.state === 'A').length);
  passiveUsersCount = computed(() => this.users().filter(u => u.state === 'P').length);
  twoFaUsersCount = computed(() => this.users().filter(u => u.is2faEnabled).length);

  totalCountBadge(): string {
    const total = this.users().length;
    return `${total} ${this.pluralizeUsers(total)}`;
  }

  private pluralizeUsers(n: number): string {
    if (n % 10 === 1 && n % 100 !== 11) return 'пользователь';
    if ([2, 3, 4].includes(n % 10) && ![12, 13, 14].includes(n % 100)) return 'пользователя';
    return 'пользователей';
  }

  setQuickFilter(mode: 'ALL' | 'ACTIVE' | 'PASSIVE' | '2FA' | 'ADMINS') {
    this.quickFilter.set(mode);
    if (mode === 'ALL') {
      this.selectedState = '';
      this.selected2fa = null;
      this.selectedRoleId = null;
    } else if (mode === 'ACTIVE') {
      this.selectedState = 'A';
      this.selected2fa = null;
    } else if (mode === 'PASSIVE') {
      this.selectedState = 'P';
      this.selected2fa = null;
    } else if (mode === '2FA') {
      this.selectedState = '';
      this.selected2fa = true;
    } else if (mode === 'ADMINS') {
      const adminRole = this.roles().find(r => r.pcode === 'admin');
      this.selectedRoleId = adminRole ? adminRole.id : null;
    }
    this.loadUsers(true);
  }

  hasActiveFilters(): boolean {
    return !!this.searchQuery || !!this.selectedState || this.selectedRoleId !== null || this.selected2fa !== null;
  }

  resetAllFilters() {
    this.searchQuery = '';
    this.selectedState = '';
    this.selectedRoleId = null;
    this.selected2fa = null;
    this.quickFilter.set('ALL');
    this.loadUsers(true);
  }

  onSearchInput() {
    clearTimeout(this.searchDebounceTimer);
    this.searchDebounceTimer = setTimeout(() => {
      this.loadUsers(true);
    }, 300);
  }

  clearSearch() {
    this.searchQuery = '';
    this.loadUsers(true);
  }

  changeSort(col: SortColumn) {
    if (this.sortColumn === col) {
      this.sortDirection = this.sortDirection === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortColumn = col;
      this.sortDirection = 'asc';
    }
  }

  sortedUsers(): User[] {
    const list = [...this.users()];
    const dir = this.sortDirection === 'asc' ? 1 : -1;

    return list.sort((a, b) => {
      if (this.sortColumn === 'id') {
        return (a.id - b.id) * dir;
      }
      if (this.sortColumn === 'name') {
        return (a.name.localeCompare(b.name)) * dir;
      }
      if (this.sortColumn === 'login') {
        return (a.login.localeCompare(b.login)) * dir;
      }
      if (this.sortColumn === 'createdAt') {
        return (new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()) * dir;
      }
      return 0;
    });
  }

  // Helpers
  getUserInitial(user: User): string {
    return user.name ? user.name.trim().charAt(0).toUpperCase() : 'U';
  }

  getAvatarBgColor(name: string): string {
    const colors = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#ec4899', '#8b5cf6', '#14b8a6'];
    let hash = 0;
    for (let i = 0; i < (name || '').length; i++) {
      hash = name.charCodeAt(i) + ((hash << 5) - hash);
    }
    return colors[Math.abs(hash) % colors.length];
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

  getLanguageLabel(code: string): string {
    if (code === 'ru') return 'Русский (ru)';
    if (code === 'uz') return "O'zbekcha (uz)";
    if (code === 'en') return 'English (en)';
    return code || 'Русский (ru)';
  }

  hasAttributes(user: User): boolean {
    return !!user.attributes && Object.keys(user.attributes).length > 0;
  }

  getAttributesList(user: User): { key: string; value: any }[] {
    if (!user.attributes) return [];
    return Object.entries(user.attributes).map(([key, value]) => ({
      key,
      value: typeof value === 'object' ? JSON.stringify(value) : value
    }));
  }

  getPasswordStrengthClass(password: string): string {
    if (!password || password.length < 10) return 'weak';
    if (password.length >= 14 && /[A-Z]/.test(password) && /[0-9]/.test(password)) return 'strong';
    return 'medium';
  }

  getPasswordStrengthText(password: string): string {
    if (!password) return '';
    if (password.length < 10) return 'Слишком короткий (минимум 10)';
    if (password.length >= 14 && /[A-Z]/.test(password) && /[0-9]/.test(password)) return 'Надёжный пароль';
    return 'Средняя сложность';
  }

  // Modals operations
  openViewModal(user: User) {
    this.viewingUser = user;
    this.isViewModalOpen.set(true);
  }

  openEditFromView() {
    if (this.viewingUser) {
      const u = this.viewingUser;
      this.isViewModalOpen.set(false);
      this.openEditModal(u);
    }
  }

  openCreateModal() {
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

  openDeleteConfirmModal(user: User) {
    this.deletingUser = user;
    this.isDeleteModalOpen.set(true);
  }

  confirmDeleteUser() {
    if (!this.deletingUser) return;

    this.isSubmitting.set(true);
    this.api.delete(`/iam/users/${this.deletingUser.id}`).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isDeleteModalOpen.set(false);
        this.toast.success(`Пользователь "${this.deletingUser?.name}" успешно удалён и анонимизирован`);
        this.loadUsers(true);
      },
      error: (err: any) => {
        this.isSubmitting.set(false);
        this.toast.error(err?.error?.detail || 'Ошибка при удалении пользователя');
      }
    });
  }

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

  exportToCsv() {
    const list = this.sortedUsers();
    if (list.length === 0) {
      this.toast.info('Нет данных для экспорта');
      return;
    }

    const headers = ['ID', 'Имя', 'Логин', 'Email', 'Телефон', 'Статус', '2FA', 'Язык', 'Часовой пояс', 'Создан'];
    const rows = list.map(u => [
      u.id,
      `"${(u.name || '').replace(/"/g, '""')}"`,
      `"${u.login}"`,
      `"${u.email}"`,
      `"${u.phone || ''}"`,
      u.state === 'A' ? 'Активен' : 'Заблокирован',
      u.is2faEnabled ? 'Да' : 'Нет',
      u.language || 'ru',
      u.timezone || 'Asia/Tashkent',
      u.createdAt
    ]);

    const csvContent = '\uFEFF' + [headers.join(';'), ...rows.map(e => e.join(';'))].join('\n');
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.setAttribute('href', url);
    link.setAttribute('download', `dwh_users_${new Date().toISOString().slice(0, 10)}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    this.toast.success('Список пользователей экспортирован в CSV');
  }
}
