import { Component, OnInit, signal, computed, HostListener, ElementRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiBadgeComponent } from '../../../shared/ui/ui-badge.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { UiCustomFieldsComponent } from '../../../shared/ui/ui-custom-fields.component';
import { UiPaginationComponent } from '../../../shared/ui/ui-pagination.component';
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
    UiModalComponent,
    UiCustomFieldsComponent,
    UiPaginationComponent
  ],


  template: `
    <div class="users-view">
      <!-- Minimal Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">Пользователи</h1>
          <span class="count-badge">{{ users().length }}</span>
        </div>
        <div class="header-right">
          <ui-button
            variant="secondary"
            size="md"
            icon="file_download"
            title="Экспорт в CSV"
            (onClick)="exportToCsv()"
          >
            Экспорт
          </ui-button>
          <ui-button
            *ngIf="canCreateUser()"
            variant="primary"
            size="md"
            icon="add"
            (onClick)="openCreateModal()"
          >
            Новый пользователь
          </ui-button>
        </div>
      </div>

      <!-- Compact Single-Line Toolbar -->
      <div class="toolbar">
        <div class="search-field">
          <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
          <label class="sr-only" for="user-search">Поиск пользователей</label>
          <input
            id="user-search"
            name="userSearch"
            type="text"
            class="search-input"
            placeholder="Поиск по имени, логину, email..."
            [(ngModel)]="searchQuery"
            (input)="onSearchInput()"
          />
          <button *ngIf="searchQuery" type="button" class="btn-icon" style="position: absolute; right: 6px;" aria-label="Очистить поиск пользователей" (click)="clearSearch()">
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">close</span>
          </button>
        </div>

        <div class="toolbar-controls">
          <!-- Segmented Status Switcher -->
          <div class="status-tabs" role="group" aria-label="Фильтр пользователей по статусу">
            <button
              type="button"
              class="status-tab"
              [class.active]="selectedState === ''"
              [attr.aria-pressed]="selectedState === ''"
              (click)="setStateFilter('')"
            >
              Все
            </button>
            <button
              type="button"
              class="status-tab"
              [class.active]="selectedState === 'A'"
              [attr.aria-pressed]="selectedState === 'A'"
              (click)="setStateFilter('A')"
            >
              <span class="status-tab-dot" style="background-color: var(--success);" aria-hidden="true"></span>
              Активные
            </button>
            <button
              type="button"
              class="status-tab"
              [class.active]="selectedState === 'P'"
              [attr.aria-pressed]="selectedState === 'P'"
              (click)="setStateFilter('P')"
            >
              <span class="status-tab-dot" style="background-color: var(--danger);" aria-hidden="true"></span>
              Заблокированные
            </button>
          </div>

          <!-- Grouped Filter Popover Trigger -->
          <div class="filter-popover-wrapper">
            <button
              #filterTrigger
              type="button"
              class="filter-trigger-btn"
              aria-haspopup="dialog"
              [attr.aria-expanded]="isFilterMenuOpen()"
              aria-controls="user-extra-filters"
              [class.has-filters]="hasExtraFilters()"
              [class.open]="isFilterMenuOpen()"
              (click)="toggleFilterMenu($event)"
            >
              <span class="material-symbols-outlined icon" aria-hidden="true">tune</span>
              <span>Фильтры</span>
              <span class="filter-dot" *ngIf="hasExtraFilters()"></span>
            </button>

            <!-- Filter Dropdown Panel -->
            <div id="user-extra-filters" class="filter-dropdown" role="dialog" aria-label="Дополнительные фильтры пользователей" *ngIf="isFilterMenuOpen()" (click)="$event.stopPropagation()">
              <div class="filter-dropdown-header">
                <span class="dropdown-title">Дополнительные фильтры</span>
                <button type="button" class="reset-link" *ngIf="hasExtraFilters()" (click)="resetExtraFilters()">
                  Сбросить
                </button>
              </div>

              <div class="filter-dropdown-body">
                <div class="filter-group">
                  <label class="filter-caption" for="user-role-filter">Роль пользователя</label>
                  <select id="user-role-filter" name="userRoleFilter" class="filter-select" [(ngModel)]="selectedRoleId" (change)="loadUsers(true)">
                    <option [ngValue]="null">Все роли</option>
                    <option *ngFor="let r of roles()" [ngValue]="r.id">{{ r.name }}</option>
                  </select>
                </div>

                <div class="filter-group">
                  <label class="filter-caption" for="user-2fa-filter">Двухфакторная защита (2FA)</label>
                  <select id="user-2fa-filter" name="user2faFilter" class="filter-select" [(ngModel)]="selected2fa" (change)="loadUsers(true)">
                    <option [ngValue]="null">Любой статус 2FA</option>
                    <option [ngValue]="true">Только с 2FA</option>
                    <option [ngValue]="false">Без 2FA</option>
                  </select>
                </div>
              </div>
            </div>
          </div>

          <ui-button
            variant="ghost"
            size="sm"
            icon="refresh"
            ariaLabel="Обновить список пользователей"
            [loading]="isLoading()"
            title="Обновить"
            (onClick)="loadUsers(true)"
          ></ui-button>
        </div>
      </div>

      <!-- Minimal Data Table -->
      <div class="table-container" role="region" aria-label="Таблица пользователей" tabindex="0">
        <table class="clean-table" aria-label="Список пользователей">
          <thead>
            <tr>
              <th class="th-sort">
                <button type="button" class="sort-button" (click)="changeSort('name')" [attr.aria-pressed]="sortColumn === 'name'">
                  Пользователь
                  <span class="material-symbols-outlined sort-ico" aria-hidden="true" *ngIf="sortColumn === 'name'">
                    {{ sortDirection === 'asc' ? 'north' : 'south' }}
                  </span>
                </button>
              </th>
              <th>Контакты</th>
              <th>Роли</th>
              <th>Руководитель</th>
              <th class="text-center" style="width: 70px;">2FA</th>
              <th style="width: 110px;">Статус</th>
              <th class="th-sort text-right" style="width: 110px;">
                <button type="button" class="sort-button align-right" (click)="changeSort('createdAt')" [attr.aria-pressed]="sortColumn === 'createdAt'">
                  Создан
                  <span class="material-symbols-outlined sort-ico" aria-hidden="true" *ngIf="sortColumn === 'createdAt'">
                    {{ sortDirection === 'asc' ? 'north' : 'south' }}
                  </span>
                </button>
              </th>
              <th class="text-right" style="width: 140px;"></th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let u of paginatedUsers()" class="table-row">
              <td>
                <button type="button" class="user-identity" (click)="openViewModal(u)" [attr.aria-label]="'Открыть профиль пользователя ' + u.name">
                  <div class="avatar" [style.background-color]="getAvatarBgColor(u.name)">
                    {{ getUserInitial(u) }}
                  </div>
                  <div class="identity-info">
                    <span class="full-name">{{ u.name }}</span>
                    <span class="login-handle font-mono">&#64;{{ u.login }}</span>
                  </div>
                </button>
              </td>
              <td>
                <div class="contacts-cell">
                  <span class="contact-email">{{ u.email }}</span>
                  <span class="contact-phone font-mono" *ngIf="u.phone">{{ u.phone }}</span>
                </div>
              </td>
              <td>
                <div class="roles-wrap">
                  <span *ngFor="let rName of getUserRoleNames(u)" class="role-pill">
                    {{ rName }}
                  </span>
                  <span *ngIf="getUserRoleNames(u).length === 0" class="muted-dash">—</span>
                </div>
              </td>
              <td>
                <span class="manager-text" *ngIf="getManagerName(u) as mName">{{ mName }}</span>
                <span class="muted-dash" *ngIf="!getManagerName(u)">—</span>
              </td>
              <td class="text-center">
                <span
                  class="material-symbols-outlined twofa-dot"
                  [class.active]="u.is2faEnabled"
                  [title]="u.is2faEnabled ? '2FA включена' : '2FA выключена'"
                  [attr.aria-label]="u.is2faEnabled ? '2FA включена' : '2FA выключена'"
                >
                  {{ u.is2faEnabled ? 'check_circle' : 'remove' }}
                </span>
              </td>
              <td>
                <span class="status-indicator" [class.active]="u.state === 'A'">
                  <span class="dot"></span>
                  {{ u.state === 'A' ? 'Активен' : 'Отключен' }}
                </span>
              </td>
              <td class="text-right text-muted font-mono text-xs">{{ u.createdAt | date:'dd.MM.yyyy' }}</td>
              <td class="text-right row-actions">
                <ui-button
                  variant="ghost"
                  size="sm"
                  icon="visibility"
                  [ariaLabel]="'Просмотреть пользователя ' + u.name"
                  title="Просмотр"
                  (onClick)="openViewModal(u)"
                ></ui-button>
                <ui-button
                  *ngIf="canUpdateUser()"
                  variant="ghost"
                  size="sm"
                  icon="edit"
                  [ariaLabel]="'Редактировать пользователя ' + u.name"
                  title="Редактировать"
                  (onClick)="openEditModal(u)"
                ></ui-button>
                <ui-button
                  *ngIf="u.state === 'A' && canBlockUser()"
                  variant="ghost"
                  size="sm"
                  icon="lock"
                  [ariaLabel]="'Заблокировать пользователя ' + u.name"
                  title="Заблокировать"
                  (onClick)="toggleUserState(u, 'block')"
                ></ui-button>
                <ui-button
                  *ngIf="u.state === 'P' && canUnblockUser()"
                  variant="ghost"
                  size="sm"
                  icon="lock_open"
                  [ariaLabel]="'Разблокировать пользователя ' + u.name"
                  title="Разблокировать"
                  (onClick)="toggleUserState(u, 'unblock')"
                ></ui-button>
                <ui-button
                  *ngIf="u.login !== 'admin' && canDeleteUser()"
                  variant="ghost"
                  size="sm"
                  icon="delete"
                  [ariaLabel]="'Удалить пользователя ' + u.name"
                  title="Удалить"
                  (onClick)="openDeleteConfirmModal(u)"
                ></ui-button>
              </td>
            </tr>

            <tr *ngIf="users().length === 0 && !isLoading()">
              <td colspan="8" class="empty-state">
                <span class="material-symbols-outlined empty-ico" aria-hidden="true">search_off</span>
                <p class="empty-text">Пользователи не найдены</p>
              </td>
            </tr>
          </tbody>
        </table>

        <!-- Pagination -->
        <ui-pagination
          [totalItems]="sortedUsers().length"
          [currentPage]="currentPage"
          [pageSize]="pageSize"
          (pageChange)="currentPage = $event"
          (pageSizeChange)="pageSize = $event; currentPage = 1"
        ></ui-pagination>
      </div>

    </div>

    <!-- ========================================================================= -->
    <!-- Create User Modal (Clean Minimalist Form)                                 -->
    <!-- ========================================================================= -->
    <ui-modal
      [isOpen]="isCreateModalOpen()"
      title="Создать пользователя"
      size="md"
      (close)="isCreateModalOpen.set(false)"
    >
      <div body class="clean-modal-body">
        <div class="form-grid">
          <div class="form-group span-2">
            <label class="clean-label" for="user-create-name">ФИО <span class="req">*</span></label>
            <input id="user-create-name" name="userCreateName" type="text" class="clean-input" required
              [attr.aria-invalid]="isCreateSubmitted && !createForm.name.trim()"
              [attr.aria-describedby]="isCreateSubmitted && !createForm.name.trim() ? 'user-create-name-error' : null"
              [(ngModel)]="createForm.name" placeholder="Иванов Иван Иванович" />
            <span id="user-create-name-error" class="field-error" *ngIf="isCreateSubmitted && !createForm.name.trim()">Укажите ФИО пользователя</span>
          </div>

          <div class="form-group">
            <label class="clean-label" for="user-create-login">Логин <span class="req">*</span></label>
            <input id="user-create-login" name="userCreateLogin" type="text" class="clean-input font-mono" required autocomplete="username"
              [attr.aria-invalid]="isCreateSubmitted && !createForm.login.trim()"
              [attr.aria-describedby]="isCreateSubmitted && !createForm.login.trim() ? 'user-create-login-error' : null"
              [(ngModel)]="createForm.login" placeholder="ivanov" />
            <span id="user-create-login-error" class="field-error" *ngIf="isCreateSubmitted && !createForm.login.trim()">Укажите логин</span>
          </div>

          <div class="form-group">
            <label class="clean-label" for="user-create-email">Email <span class="req">*</span></label>
            <input id="user-create-email" name="userCreateEmail" type="email" class="clean-input font-mono" required autocomplete="email"
              [attr.aria-invalid]="isCreateSubmitted && !createForm.email.trim()"
              [attr.aria-describedby]="isCreateSubmitted && !createForm.email.trim() ? 'user-create-email-error' : null"
              [(ngModel)]="createForm.email" placeholder="ivanov@company.local" />
            <span id="user-create-email-error" class="field-error" *ngIf="isCreateSubmitted && !createForm.email.trim()">Укажите email</span>
          </div>

          <div class="form-group">
            <label class="clean-label" for="user-create-phone">Телефон</label>
            <input id="user-create-phone" name="userCreatePhone" type="tel" class="clean-input font-mono" autocomplete="tel" [(ngModel)]="createForm.phone" placeholder="+998901234567" />
          </div>

          <div class="form-group">
            <label class="clean-label" for="user-create-manager">Руководитель</label>
            <select id="user-create-manager" name="userCreateManager" class="clean-input" [(ngModel)]="createForm.managerId">
              <option [ngValue]="null">Без руководителя</option>
              <option *ngFor="let u of users()" [ngValue]="u.id">{{ u.name }} (&#64;{{ u.login }})</option>
            </select>
          </div>

          <div class="form-group span-2">
            <label class="clean-label" for="user-create-password">Временный пароль <span class="req">*</span></label>
            <div class="pwd-wrapper">
              <input
                id="user-create-password"
                name="userCreatePassword"
                [type]="showPassword() ? 'text' : 'password'"
                class="clean-input font-mono"
                required
                minlength="10"
                autocomplete="new-password"
                [attr.aria-invalid]="isCreateSubmitted && createForm.password.length < 10"
                [attr.aria-describedby]="isCreateSubmitted && createForm.password.length < 10 ? 'user-create-password-error user-create-password-hint' : 'user-create-password-hint'"
                [(ngModel)]="createForm.password"
                placeholder="Минимум 10 символов"
              />
              <button type="button" class="pwd-btn" [attr.aria-label]="showPassword() ? 'Скрыть пароль' : 'Показать пароль'" [attr.aria-pressed]="showPassword()" (click)="showPassword.update(v => !v)">
                <span class="material-symbols-outlined" aria-hidden="true">{{ showPassword() ? 'visibility_off' : 'visibility' }}</span>
              </button>
            </div>
            <span id="user-create-password-hint" class="clean-hint">Не менее 10 символов, без совпадений с логином</span>
            <span id="user-create-password-error" class="field-error" *ngIf="isCreateSubmitted && createForm.password.length < 10">Пароль должен содержать не менее 10 символов</span>
          </div>

          <div class="form-group">
            <label class="clean-label" for="user-create-language">Язык</label>
            <select id="user-create-language" name="userCreateLanguage" class="clean-input" [(ngModel)]="createForm.language">
              <option value="ru">Русский (ru)</option>
              <option value="uz">O'zbekcha (uz)</option>
              <option value="en">English (en)</option>
            </select>
          </div>

          <div class="form-group">
            <label class="clean-label" for="user-create-timezone">Часовой пояс</label>
            <select id="user-create-timezone" name="userCreateTimezone" class="clean-input" [(ngModel)]="createForm.timezone">
              <option value="Asia/Tashkent">Asia/Tashkent (UTC+5)</option>
              <option value="Europe/Moscow">Europe/Moscow (UTC+3)</option>
              <option value="UTC">UTC (UTC+0)</option>
              <option value="Asia/Almaty">Asia/Almaty (UTC+5)</option>
              <option value="Asia/Dubai">Asia/Dubai (UTC+4)</option>
            </select>
          </div>

          <div class="form-group span-2">
            <label class="clean-checkbox">
              <input name="userCreate2fa" type="checkbox" [(ngModel)]="createForm.is2faEnabled" />
              <span>Включить двухфакторную защиту (2FA OTP)</span>
            </label>
          </div>

          <!-- Roles -->
          <div class="form-group span-2" *ngIf="roles().length > 0">
            <span class="clean-label">Роли доступа (RBAC)</span>
            <div class="roles-chips">
              <label
                *ngFor="let role of roles()"
                class="role-chip"
                [class.selected]="isRoleSelectedInCreate(role.id)"
              >
                <input
                  type="checkbox"
                  [checked]="isRoleSelectedInCreate(role.id)"
                  (change)="toggleRoleInCreate(role.id)"
                />
                <span>{{ role.name }}</span>
              </label>
            </div>
          </div>

          <!-- Custom Fields -->
          <div class="form-group span-2" *ngIf="customFields().length > 0">
            <span class="clean-label">Дополнительные поля</span>
            <ui-custom-fields
              [fields]="customFields()"
              [(values)]="createForm.attributes"
            ></ui-custom-fields>
          </div>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isCreateModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitCreateUser()">Создать</ui-button>
      </div>
    </ui-modal>

    <!-- ========================================================================= -->
    <!-- Edit User Modal                                                           -->
    <!-- ========================================================================= -->
    <ui-modal
      [isOpen]="isEditModalOpen()"
      title="Редактировать пользователя"
      size="md"
      (close)="isEditModalOpen.set(false)"
    >
      <div body class="clean-modal-body" *ngIf="editingUser as u">
        <div class="form-grid">
          <div class="form-group span-2">
            <label class="clean-label" for="user-edit-name">ФИО <span class="req">*</span></label>
            <input id="user-edit-name" name="userEditName" type="text" class="clean-input" required
              [attr.aria-invalid]="isEditSubmitted && !editForm.name.trim()"
              [attr.aria-describedby]="isEditSubmitted && !editForm.name.trim() ? 'user-edit-name-error' : null"
              [(ngModel)]="editForm.name" placeholder="Иванов Иван Иванович" />
            <span id="user-edit-name-error" class="field-error" *ngIf="isEditSubmitted && !editForm.name.trim()">Укажите ФИО пользователя</span>
          </div>

          <div class="form-group">
            <label class="clean-label" for="user-edit-login">Логин (чтение)</label>
            <input id="user-edit-login" type="text" class="clean-input font-mono disabled" [value]="u.login" disabled />
          </div>

          <div class="form-group">
            <label class="clean-label" for="user-edit-email">Email (чтение)</label>
            <input id="user-edit-email" type="email" class="clean-input font-mono disabled" [value]="u.email" disabled />
          </div>

          <div class="form-group">
            <label class="clean-label" for="user-edit-phone">Телефон</label>
            <input id="user-edit-phone" name="userEditPhone" type="tel" class="clean-input font-mono" autocomplete="tel" [(ngModel)]="editForm.phone" placeholder="+998901234567" />
          </div>

          <div class="form-group">
            <label class="clean-label" for="user-edit-manager">Руководитель</label>
            <select id="user-edit-manager" name="userEditManager" class="clean-input" [(ngModel)]="editForm.managerId">
              <option [ngValue]="null">Без руководителя</option>
              <option *ngFor="let m of getAvailableManagers(u.id)" [ngValue]="m.id">
                {{ m.name }} (&#64;{{ m.login }})
              </option>
            </select>
          </div>

          <div class="form-group">
            <label class="clean-label" for="user-edit-language">Язык</label>
            <select id="user-edit-language" name="userEditLanguage" class="clean-input" [(ngModel)]="editForm.language">
              <option value="ru">Русский (ru)</option>
              <option value="uz">O'zbekcha (uz)</option>
              <option value="en">English (en)</option>
            </select>
          </div>

          <div class="form-group">
            <label class="clean-label" for="user-edit-timezone">Часовой пояс</label>
            <select id="user-edit-timezone" name="userEditTimezone" class="clean-input" [(ngModel)]="editForm.timezone">
              <option value="Asia/Tashkent">Asia/Tashkent (UTC+5)</option>
              <option value="Europe/Moscow">Europe/Moscow (UTC+3)</option>
              <option value="UTC">UTC (UTC+0)</option>
              <option value="Asia/Almaty">Asia/Almaty (UTC+5)</option>
              <option value="Asia/Dubai">Asia/Dubai (UTC+4)</option>
            </select>
          </div>

          <div class="form-group span-2">
            <label class="clean-checkbox">
              <input name="userEdit2fa" type="checkbox" [(ngModel)]="editForm.is2faEnabled" />
              <span>Включить двухфакторную защиту (2FA OTP)</span>
            </label>
          </div>

          <!-- Roles -->
          <div class="form-group span-2" *ngIf="roles().length > 0">
            <span class="clean-label">Роли доступа (RBAC)</span>
            <div class="roles-chips">
              <label
                *ngFor="let role of roles()"
                class="role-chip"
                [class.selected]="isRoleSelectedInEdit(role.id)"
                [class.locked]="u.login === 'admin' && role.pcode === 'admin'"
              >
                <input
                  type="checkbox"
                  [checked]="isRoleSelectedInEdit(role.id)"
                  (change)="toggleRoleInEdit(role.id)"
                  [disabled]="u.login === 'admin' && role.pcode === 'admin'"
                />
                <span>{{ role.name }}</span>
                <span *ngIf="u.login === 'admin' && role.pcode === 'admin'" class="material-symbols-outlined lock-ico" title="Защищено">lock</span>
              </label>
            </div>
          </div>

          <!-- Custom Fields -->
          <div class="form-group span-2" *ngIf="customFields().length > 0">
            <span class="clean-label">Дополнительные поля</span>
            <ui-custom-fields
              [fields]="customFields()"
              [(values)]="editForm.attributes"
            ></ui-custom-fields>
          </div>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isEditModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitEditUser()">Сохранить</ui-button>
      </div>
    </ui-modal>

    <!-- ========================================================================= -->
    <!-- View User Modal (Clean Info Modal)                                        -->
    <!-- ========================================================================= -->
    <ui-modal
      [isOpen]="isViewModalOpen()"
      title="Профиль пользователя"
      size="sm"
      (close)="isViewModalOpen.set(false)"
    >
      <div body class="view-body" *ngIf="viewingUser as u">
        <div class="view-header-card">
          <div class="avatar lg" [style.background-color]="getAvatarBgColor(u.name)">
            {{ getUserInitial(u) }}
          </div>
          <div class="info">
            <h3 class="name">{{ u.name }}</h3>
            <span class="handle font-mono">&#64;{{ u.login }}</span>
          </div>
        </div>

        <div class="info-list">
          <div class="info-row">
            <span class="lbl">Email</span>
            <span class="val font-mono">{{ u.email }}</span>
          </div>
          <div class="info-row">
            <span class="lbl">Телефон</span>
            <span class="val font-mono">{{ u.phone || '—' }}</span>
          </div>
          <div class="info-row">
            <span class="lbl">Руководитель</span>
            <span class="val">{{ getManagerName(u) || '—' }}</span>
          </div>
          <div class="info-row">
            <span class="lbl">Роли</span>
            <span class="val">{{ getUserRoleNames(u).join(', ') || '—' }}</span>
          </div>
          <div class="info-row">
            <span class="lbl">2FA Защита</span>
            <span class="val">{{ u.is2faEnabled ? 'Включена' : 'Отключена' }}</span>
          </div>
          <div class="info-row">
            <span class="lbl">Статус</span>
            <span class="val">{{ u.state === 'A' ? 'Активен' : 'Заблокирован' }}</span>
          </div>
          <div class="info-row">
            <span class="lbl">Создан</span>
            <span class="val font-mono">{{ u.createdAt | date:'dd.MM.yyyy' }}</span>
          </div>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isViewModalOpen.set(false)">Закрыть</ui-button>
        <ui-button *ngIf="canUpdateUser()" variant="primary" size="md" (onClick)="openEditFromView()">Редактировать</ui-button>
      </div>
    </ui-modal>

    <!-- ========================================================================= -->
    <!-- Delete Confirmation Modal                                                 -->
    <!-- ========================================================================= -->
    <ui-modal
      [isOpen]="isDeleteModalOpen()"
      title="Удаление пользователя"
      size="sm"
      (close)="isDeleteModalOpen.set(false)"
    >
      <div body class="delete-body" *ngIf="deletingUser as u">
        <p class="delete-msg">
          Вы уверены, что хотите удалить и анонимизировать пользователя <strong>{{ u.name }}</strong> (&#64;{{ u.login }})?
        </p>
        <span class="delete-sub">Персональные данные будут стёрты, а активные сессии закрыты.</span>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isDeleteModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="danger" size="md" [loading]="isSubmitting()" (onClick)="confirmDeleteUser()">Удалить</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .users-view {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 1400px;
    }

    /* Minimal Header */
    .view-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .header-left {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .view-title {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }
    .user-count {
      font-size: 12px;
      color: var(--text-muted);
      background-color: var(--bg-hover);
      padding: 1px 7px;
      border-radius: 10px;
      font-weight: 500;
    }
    .header-right {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    /* Compact Toolbar */
    .toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
    }
    .search-field {
      display: flex;
      align-items: center;
      gap: 6px;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      padding: 4px 10px;
      width: 320px;
      max-width: 100%;
    }
    .search-icon {
      font-size: 17px;
      color: var(--text-muted);
    }
    .search-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 13px;
      color: var(--text-main);
      width: 100%;
    }
    .clear-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      padding: 0;
    }

    .toolbar-controls {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    /* Segmented Switcher */
    .segmented-control {
      display: inline-flex;
      background-color: var(--bg-hover);
      padding: 2px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
    }
    .seg-btn {
      border: none;
      background: transparent;
      padding: 4px 10px;
      border-radius: var(--radius-xs);
      font-size: 12px;
      font-weight: 500;
      color: var(--text-muted);
      cursor: pointer;
      transition: all 0.1s ease;
    }
    .seg-btn.active {
      background-color: var(--bg-surface);
      color: var(--text-main);
      box-shadow: 0 1px 2px rgba(0,0,0,0.05);
    }

    /* Popover Filter */
    .filter-popover-wrapper {
      position: relative;
    }
    .filter-trigger-btn {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      height: 32px;
      padding: 0 10px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-muted);
      font-size: 12px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .filter-trigger-btn .icon { font-size: 16px; }
    .filter-trigger-btn:hover, .filter-trigger-btn.open {
      color: var(--text-main);
      border-color: var(--text-muted);
    }
    .filter-trigger-btn.has-filters {
      color: var(--primary);
      border-color: var(--primary);
    }
    .filter-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background-color: var(--primary);
    }

    .filter-dropdown {
      position: absolute;
      top: calc(100% + 4px);
      right: 0;
      width: 260px;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      box-shadow: 0 6px 16px rgba(0,0,0,0.1);
      padding: 12px;
      z-index: 100;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .filter-dropdown-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid var(--border-color);
      padding-bottom: 6px;
    }
    .dropdown-title { font-size: 12px; font-weight: 600; color: var(--text-main); }
    .reset-link {
      background: transparent;
      border: none;
      font-size: 11px;
      color: var(--primary);
      cursor: pointer;
      padding: 0;
    }
    .filter-dropdown-body {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .filter-group {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .filter-caption { font-size: 11px; color: var(--text-muted); }
    .filter-select {
      height: 30px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 12px;
      padding: 2px 6px;
      outline: none;
    }

    /* Minimal Table */
    .table-container {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      overflow-x: auto;
    }
    .clean-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
      text-align: left;
    }
    .clean-table th {
      padding: 8px 12px;
      font-weight: 600;
      color: var(--text-muted);
      font-size: 11px;
      text-transform: uppercase;
      letter-spacing: 0.3px;
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      user-select: none;
      white-space: nowrap;
    }
    .th-sort { padding: 0 !important; }
    .th-sort:hover { color: var(--text-main); }
    .sort-button {
      width: 100%;
      border: 0;
      background: transparent;
      color: inherit;
      cursor: pointer;
      font: inherit;
      letter-spacing: inherit;
      padding: 8px 12px;
      text-align: left;
      text-transform: inherit;
    }
    .sort-button.align-right { text-align: right; }
    .sort-ico { font-size: 13px; vertical-align: middle; margin-left: 2px; }

    .clean-table td {
      padding: 8px 12px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
      vertical-align: middle;
    }
    .table-row:last-child td { border-bottom: none; }
    .table-row:hover { background-color: var(--bg-hover); }

    /* Identity */
    .user-identity {
      width: 100%;
      border: 0;
      background: transparent;
      color: inherit;
      display: flex;
      align-items: center;
      gap: 8px;
      cursor: pointer;
      font: inherit;
      padding: 0;
      text-align: left;
    }
    .avatar {
      width: 28px;
      height: 28px;
      border-radius: 50%;
      color: #fff;
      font-size: 11px;
      font-weight: 600;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }
    .avatar.lg {
      width: 44px;
      height: 44px;
      font-size: 18px;
    }
    .identity-info {
      display: flex;
      flex-direction: column;
    }
    .full-name { font-weight: 500; }
    .login-handle { font-size: 11px; color: var(--text-muted); }

    .contacts-cell {
      display: flex;
      flex-direction: column;
    }
    .contact-email { font-size: 12px; }
    .contact-phone { font-size: 11px; color: var(--text-muted); }

    .roles-wrap {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
    }
    .role-pill {
      font-size: 11px;
      padding: 1px 6px;
      border-radius: 4px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      color: var(--text-main);
    }
    .manager-text { font-size: 12px; }
    .muted-dash { color: var(--text-light); }

    .twofa-dot {
      font-size: 16px;
      color: var(--text-light);
    }
    .twofa-dot.active { color: var(--success); }

    .status-indicator {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      font-size: 12px;
      color: var(--text-muted);
    }
    .status-indicator .dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background-color: var(--text-light);
    }
    .status-indicator.active { color: var(--text-main); }
    .status-indicator.active .dot { background-color: var(--success); }

    .row-actions { white-space: nowrap; }

    .empty-state {
      text-align: center;
      padding: 32px 12px;
      color: var(--text-muted);
    }
    .empty-ico { font-size: 32px; color: var(--text-light); margin-bottom: 4px; }
    .empty-text { font-size: 13px; margin: 0; }

    .load-more {
      padding: 8px;
      display: flex;
      justify-content: center;
      border-top: 1px solid var(--border-color);
      background-color: var(--bg-hover);
    }

    /* Minimal Modals */
    .clean-modal-body {
      padding: 4px 0;
    }
    .form-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
    }
    .span-2 { grid-column: 1 / -1; }

    @media (max-width: 640px) {
      .view-header {
        align-items: flex-start;
        flex-direction: column;
      }
      .header-right,
      .toolbar-controls {
        width: 100%;
        flex-wrap: wrap;
      }
      .toolbar-controls { min-width: 0; }
      .status-tabs {
        max-width: 100%;
        overflow-x: auto;
      }
      .modal-form,
      .form-group { min-width: 0; }
      .form-grid { grid-template-columns: minmax(0, 1fr); }
      .span-2 { grid-column: auto; }
    }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .clean-label {
      font-size: 11px;
      font-weight: 500;
      color: var(--text-muted);
    }
    .clean-input {
      height: 32px;
      padding: 4px 8px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }
    .clean-input:focus { border-color: var(--primary); }
    .clean-input.disabled { background-color: var(--bg-hover); color: var(--text-muted); }

    .pwd-wrapper {
      position: relative;
      display: flex;
      align-items: center;
    }
    .pwd-wrapper .clean-input { width: 100%; padding-right: 32px; }
    .pwd-btn {
      position: absolute;
      right: 4px;
      background: transparent;
      border: none;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      padding: 2px;
    }
    .clean-hint { font-size: 10px; color: var(--text-muted); }
    .field-error { font-size: 10px; color: var(--danger); }

    .clean-checkbox {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: 12px;
      color: var(--text-main);
      cursor: pointer;
    }

    .roles-chips {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .role-chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      font-size: 12px;
      color: var(--text-main);
      cursor: pointer;
    }
    .role-chip.selected {
      border-color: var(--primary);
      background-color: rgba(99,102,241,0.06);
    }
    .role-chip.locked { opacity: 0.8; cursor: not-allowed; }
    .lock-ico { font-size: 13px; color: var(--text-muted); }

    .view-body {
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    .view-header-card {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 10px;
      background-color: var(--bg-hover);
      border-radius: var(--radius-sm);
    }
    .view-header-card .info { display: flex; flex-direction: column; }
    .view-header-card .name { font-size: 15px; font-weight: 600; margin: 0; }
    .view-header-card .handle { font-size: 12px; color: var(--text-muted); }

    .info-list {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .info-row {
      display: flex;
      justify-content: space-between;
      padding: 4px 0;
      border-bottom: 1px solid var(--border-color);
      font-size: 12px;
    }
    .info-row:last-child { border-bottom: none; }
    .info-row .lbl { color: var(--text-muted); }
    .info-row .val { font-weight: 500; }

    .delete-body {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .delete-msg { font-size: 13px; margin: 0; line-height: 1.4; }
    .delete-sub { font-size: 11px; color: var(--text-muted); }

    .text-center { text-align: center; }
    .text-right { text-align: right; }
    .text-muted { color: var(--text-muted); }
    .font-mono { font-family: monospace; }
    .text-xs { font-size: 11px; }
    .req { color: var(--danger); }
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
  readonly isFilterMenuOpen = signal<boolean>(false);
  isCreateSubmitted = false;
  isEditSubmitted = false;
  nextCursor: string | null = null;

  // Filter state
  searchQuery = '';
  selectedState = '';
  selectedRoleId: number | null = null;
  selected2fa: boolean | null = null;
  currentPage = 1;
  pageSize = 10;


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
    private toast: ToastService,
    private elementRef: ElementRef
  ) {}

  @ViewChild('filterTrigger') private filterTrigger?: ElementRef<HTMLButtonElement>;

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    if (!this.elementRef.nativeElement.contains(event.target)) {
      this.isFilterMenuOpen.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (!this.isFilterMenuOpen()) return;
    this.isFilterMenuOpen.set(false);
    queueMicrotask(() => this.filterTrigger?.nativeElement.focus());
  }

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
      error: () => {
        this.isLoading.set(false);
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
    this.api.get<CustomField[]>('/custom-fields', { entity_type: 'USER' }).subscribe(res => {
      this.customFields.set(res || []);
    });
  }

  hasExtraFilters(): boolean {
    return this.selectedRoleId !== null || this.selected2fa !== null;
  }

  resetExtraFilters() {
    this.selectedRoleId = null;
    this.selected2fa = null;
    this.loadUsers(true);
  }

  toggleFilterMenu(event: MouseEvent) {
    event.stopPropagation();
    this.isFilterMenuOpen.update(v => !v);
  }

  setStateFilter(state: string) {
    this.selectedState = state;
    this.loadUsers(true);
  }

  onSearchInput() {
    clearTimeout(this.searchDebounceTimer);
    this.searchDebounceTimer = setTimeout(() => {
      this.loadUsers(true);
    }, 250);
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
      if (this.sortColumn === 'id') return (a.id - b.id) * dir;
      if (this.sortColumn === 'name') return (a.name.localeCompare(b.name)) * dir;
      if (this.sortColumn === 'login') return (a.login.localeCompare(b.login)) * dir;
      if (this.sortColumn === 'createdAt') {
        return (new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()) * dir;
      }
      return 0;
    });
  }

  paginatedUsers(): User[] {
    const list = this.sortedUsers();
    const start = (this.currentPage - 1) * this.pageSize;
    return list.slice(start, start + this.pageSize);
  }


  getUserInitial(user: User): string {
    return user.name ? user.name.trim().charAt(0).toUpperCase() : 'U';
  }

  getAvatarBgColor(name: string): string {
    const colors = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#8b5cf6'];
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
    this.isCreateSubmitted = false;
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
    this.isCreateSubmitted = true;
    if (!this.createForm.name || !this.createForm.login || !this.createForm.email || !this.createForm.password) {
      this.toast.warning('Заполните обязательные поля');
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
      error: () => {
        this.isSubmitting.set(false);
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
    this.isEditSubmitted = false;
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
    this.isEditSubmitted = true;
    if (!this.editForm.name) {
      this.toast.warning('Имя пользователя обязательно');
      return;
    }

    this.isSubmitting.set(true);
    this.api.patch(`/iam/users/${this.editingUser.id}`, this.editForm).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isEditModalOpen.set(false);
        this.toast.success('Данные сохранены');
        this.loadUsers(true);
      },
      error: () => {
        this.isSubmitting.set(false);
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
        this.toast.success('Пользователь успешно удалён');
        this.loadUsers(true);
      },
      error: () => {
        this.isSubmitting.set(false);
      }
    });
  }

  toggleUserState(user: User, action: 'block' | 'unblock') {
    this.api.post(`/iam/users/${user.id}/${action}`).subscribe({
      next: () => {
        this.toast.success(action === 'block' ? 'Пользователь заблокирован' : 'Пользователь разблокирован');
        this.loadUsers(true);
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
    link.setAttribute('download', `users_${new Date().toISOString().slice(0, 10)}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    this.toast.success('Экспорт выполнен');
  }
}
