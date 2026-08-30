import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { Role, FormTreeItem, PermissionPair } from '../../../core/models/rbac.models';

interface FormActionItem {
  action: string;
  actionName: string;
}

interface GroupedForm {
  module: string;
  formCode: string;
  formName: string;
  actions: FormActionItem[];
}

interface ModuleGroup {
  moduleCode: string;
  moduleName: string;
  forms: GroupedForm[];
  isExpanded: boolean;
}

@Component({
  selector: 'app-roles',
  standalone: true,
  imports: [CommonModule, FormsModule, UiButtonComponent, UiModalComponent],
  template: `
    <div class="roles-page">
      <!-- Top Page Header -->
      <div class="page-header">
        <div class="header-left">
          <h1 class="page-title">Роли и матрица прав</h1>
          <span class="role-badge">{{ roles().length }} ролей</span>
        </div>
        <div class="header-right">
          <ui-button
            *ngIf="canCreateRole()"
            variant="primary"
            size="md"
            icon="add"
            (onClick)="openCreateModal()"
          >
            Новая роль
          </ui-button>
        </div>
      </div>

      <!-- Roles Horizontal Bar -->
      <div class="roles-strip-container">
        <div class="roles-strip-header">
          <span class="strip-title">Выберите роль для настройки прав:</span>
          <div class="roles-filter-box">
            <span class="material-symbols-outlined icon" aria-hidden="true">search</span>
            <label class="sr-only" for="role-search">Поиск ролей</label>
            <input
              id="role-search"
              name="roleSearch"
              type="text"
              class="roles-filter-input"
              placeholder="Фильтр ролей..."
              [(ngModel)]="roleSearchQuery"
            />
          </div>
        </div>

        <div class="roles-cards-grid">
          <div
            *ngFor="let r of filteredRoles()"
            class="role-card-btn"
            [class.active]="selectedRole()?.id === r.id"
          >
            <button
              type="button"
              class="role-select-btn"
              [attr.aria-label]="'Выбрать роль ' + r.name"
              [attr.aria-pressed]="selectedRole()?.id === r.id"
              (click)="selectRole(r)"
            >
              <span class="role-card-head">
                <span class="role-card-title">{{ r.name }}</span>
                <span class="role-sys-tag font-mono" *ngIf="r.pcode">{{ r.pcode }}</span>
                <span class="role-custom-tag" *ngIf="!r.pcode">Кастомная</span>
              </span>
              <div class="role-status-line">
                <span class="status-dot" aria-hidden="true" [class.active]="r.state === 'A'"></span>
                <span class="status-text">{{ r.state === 'A' ? 'Активна' : 'Отключена' }}</span>
              </div>
            </button>

            <div class="role-card-foot">
              <div class="role-btns">
                <button
                  type="button"
                  class="mini-btn"
                  [attr.aria-label]="'Редактировать роль ' + r.name"
                  title="Редактировать роль"
                  *ngIf="canUpdateRole()"
                  (click)="openEditRoleModal(r)"
                >
                  <span class="material-symbols-outlined" aria-hidden="true">edit</span>
                </button>
                <button
                  type="button"
                  class="mini-btn delete"
                  [attr.aria-label]="'Удалить роль ' + r.name"
                  title="Удалить роль"
                  *ngIf="!r.pcode && canDeleteRole()"
                  (click)="openDeleteRoleModal(r)"
                >
                  <span class="material-symbols-outlined" aria-hidden="true">delete</span>
                </button>
              </div>
            </div>
          </div>

          <button
            type="button"
            class="add-role-dashed-btn"
            *ngIf="canCreateRole()"
            (click)="openCreateModal()"
          >
            <span class="material-symbols-outlined" aria-hidden="true">add</span>
            <span>Создать роль</span>
          </button>
        </div>
      </div>

      <!-- Main Permission Matrix Section -->
      <div class="matrix-card" *ngIf="selectedRole() as role">
        <!-- Role Meta Header & Save Button -->
        <div class="matrix-header-bar">
          <div class="role-summary-box">
            <div class="role-name-row">
              <h2 class="role-name-text">{{ role.name }}</h2>
              <span class="role-code-badge font-mono" *ngIf="role.pcode">Системный код: {{ role.pcode }}</span>
              <span class="status-pill" [class.active]="role.state === 'A'">
                {{ role.state === 'A' ? 'Активна' : 'Отключена' }}
              </span>
            </div>

            <div class="role-meter-row">
              <span class="meter-text">
                Разрешено: <strong>{{ activePermissionsCount() }}</strong> из {{ totalActionsCount() }} действий
                ({{ permissionPercentage() }}%)
              </span>
              <div
                class="meter-track"
                role="progressbar"
                aria-label="Доля разрешённых действий"
                aria-valuemin="0"
                aria-valuemax="100"
                [attr.aria-valuenow]="permissionPercentage()"
              >
                <div class="meter-fill" [style.width.%]="permissionPercentage()"></div>
              </div>
            </div>
          </div>

          <div class="matrix-actions-box">
            <ui-button
              *ngIf="canGrant()"
              variant="primary"
              size="md"
              icon="save"
              [loading]="isSaving()"
              (onClick)="savePermissions()"
            >
              Сохранить права
            </ui-button>
          </div>
        </div>

        <!-- Superadmin Shield Banner -->
        <div *ngIf="role.pcode === 'admin'" class="admin-notice">
          <span class="material-symbols-outlined icon" aria-hidden="true">verified_user</span>
          <span>Роль суперадминистратора обладает абсолютными правами (100% покрытие системы по инварианту I-P4).</span>
        </div>

        <!-- Filter & Search Toolbar -->
        <div class="matrix-toolbar-box">
          <div class="search-and-expand-row">
            <div class="matrix-search-field">
              <span class="material-symbols-outlined icon" aria-hidden="true">search</span>
              <label class="sr-only" for="permission-search">Поиск по матрице прав</label>
              <input
                id="permission-search"
                name="permissionSearch"
                type="text"
                class="matrix-search-input"
                placeholder="Поиск по названию формы, действию или коду..."
                [(ngModel)]="matrixSearchQuery"
              />
              <button *ngIf="matrixSearchQuery" type="button" class="clear-search-btn" aria-label="Очистить поиск по матрице прав" (click)="matrixSearchQuery = ''">
                <span class="material-symbols-outlined" aria-hidden="true">close</span>
              </button>
            </div>

            <div class="expand-all-links">
              <button type="button" class="text-link" (click)="setAllModulesExpanded(true)">Развернуть все</button>
              <span class="link-sep">•</span>
              <button type="button" class="text-link" (click)="setAllModulesExpanded(false)">Свернуть все</button>
            </div>
          </div>

          <!-- Module Pill Selector -->
          <div class="module-filter-pills" role="group" aria-label="Фильтр модулей матрицы прав">
            <button
              type="button"
              class="mod-pill-btn"
              [class.active]="selectedModuleTab === 'all'"
              [attr.aria-pressed]="selectedModuleTab === 'all'"
              (click)="selectedModuleTab = 'all'"
            >
              Все разделы ({{ forms().length }})
            </button>
            <button
              *ngFor="let mod of moduleGroups"
              type="button"
              class="mod-pill-btn"
              [class.active]="selectedModuleTab === mod.moduleCode"
              [attr.aria-pressed]="selectedModuleTab === mod.moduleCode"
              (click)="selectedModuleTab = mod.moduleCode"
            >
              {{ mod.moduleName }} ({{ getModuleActionsCount(mod) }})
            </button>
          </div>
        </div>

        <!-- Modules List -->
        <div class="modules-stack">
          <div *ngFor="let mod of visibleModuleGroups()" class="mod-section-card">
            <!-- Module Section Header -->
            <div class="mod-section-header">
              <button
                type="button"
                class="mod-header-left mod-toggle-btn"
                [attr.aria-expanded]="mod.isExpanded"
                [attr.aria-controls]="'role-module-' + mod.moduleCode"
                (click)="toggleModuleExpand(mod)"
              >
                <span class="material-symbols-outlined chevron-icon" aria-hidden="true">
                  {{ mod.isExpanded ? 'expand_more' : 'chevron_right' }}
                </span>
                <span class="material-symbols-outlined mod-icon" aria-hidden="true">{{ getModuleIcon(mod.moduleCode) }}</span>
                <h3 class="mod-title">{{ mod.moduleName }}</h3>
                <span class="mod-count">({{ mod.forms.length }} форм)</span>
              </button>

              <div class="mod-header-right">
                <button
                  type="button"
                  class="batch-btn"
                  [disabled]="role.pcode === 'admin'"
                  (click)="toggleAllModule(mod, true)"
                >
                  ✓ Выбрать все
                </button>
                <span class="batch-divider">|</span>
                <button
                  type="button"
                  class="batch-btn"
                  [disabled]="role.pcode === 'admin'"
                  (click)="toggleAllModule(mod, false)"
                >
                  ✗ Снять все
                </button>
              </div>
            </div>

            <!-- Forms Table -->
            <div
              class="mod-section-body"
              *ngIf="mod.isExpanded"
              [id]="'role-module-' + mod.moduleCode"
              role="region"
              [attr.aria-label]="'Права модуля ' + mod.moduleName"
              tabindex="0"
            >
              <table class="forms-grid-table" [attr.aria-label]="'Права модуля ' + mod.moduleName">
                <tbody>
                  <tr *ngFor="let f of mod.forms" class="form-grid-row">
                    <td class="form-title-col">
                      <div class="form-title-wrap">
                        <span class="form-name-text">{{ f.formName }}</span>
                        <span class="form-code-text font-mono">{{ f.formCode }}</span>
                        <div class="form-quick-toggles" *ngIf="role.pcode !== 'admin'">
                          <button type="button" class="mini-toggle-btn" (click)="toggleAllForm(f, true)">все</button>
                          <span class="dot">•</span>
                          <button type="button" class="mini-toggle-btn" (click)="toggleAllForm(f, false)">снять</button>
                        </div>
                      </div>
                    </td>

                    <td class="form-actions-col">
                      <div class="actions-chips-wrap">
                        <label
                          *ngFor="let act of f.actions"
                          class="action-checkbox-card"
                          [class.checked]="hasPermission(f.formCode, act.action)"
                          [class.readonly]="role.pcode === 'admin'"
                          [title]="f.formCode + '.' + act.action"
                        >
                          <input
                            type="checkbox"
                            class="chk-input"
                            [checked]="hasPermission(f.formCode, act.action)"
                            [disabled]="role.pcode === 'admin'"
                            (change)="togglePermission(f.formCode, act.action, $event)"
                          />
                          <span class="chk-label">{{ act.actionName }}</span>
                        </label>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div *ngIf="visibleModuleGroups().length === 0" class="no-forms-box">
            <span class="material-symbols-outlined icon" aria-hidden="true">search_off</span>
            <p>Формы не найдены по запросу «{{ matrixSearchQuery }}»</p>
          </div>
        </div>
      </div>
    </div>

    <!-- ======================================================================= -->
    <!-- Create Role Modal                                                       -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="isCreateModalOpen()"
      title="Создание новой роли"
      size="sm"
      (close)="isCreateModalOpen.set(false)"
    >
      <div body class="modal-body-form">
        <div class="modal-field">
          <label class="modal-label" for="role-create-name">Название роли <span class="req">*</span></label>
          <input
            id="role-create-name"
            name="roleCreateName"
            type="text"
            class="modal-text-input"
            required
            [class.input-error]="isCreateSubmitted && !newRoleForm.name.trim()"
            [attr.aria-invalid]="isCreateSubmitted && !newRoleForm.name.trim()"
            [attr.aria-describedby]="isCreateSubmitted && !newRoleForm.name.trim() ? 'role-create-name-error' : null"
            [(ngModel)]="newRoleForm.name"
            placeholder="Например: Старший аналитик данных"
          />
          <span id="role-create-name-error" class="field-error" *ngIf="isCreateSubmitted && !newRoleForm.name.trim()">Укажите название роли</span>
        </div>
        <div class="modal-field">
          <label class="modal-label" for="role-create-order">Порядок отображения</label>
          <input
            id="role-create-order"
            name="roleCreateOrder"
            type="number"
            class="modal-text-input font-mono"
            [(ngModel)]="newRoleForm.orderNo"
            placeholder="0"
          />
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isCreateModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmittingRole()" (onClick)="submitCreateRole()">Создать</ui-button>
      </div>
    </ui-modal>

    <!-- ======================================================================= -->
    <!-- Edit Role Modal                                                         -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="isEditModalOpen()"
      title="Редактирование роли"
      size="sm"
      (close)="isEditModalOpen.set(false)"
    >
      <div body class="modal-body-form" *ngIf="editingRole as r">
        <div class="modal-field">
          <label class="modal-label" for="role-edit-name">Название роли <span class="req">*</span></label>
          <input id="role-edit-name" name="roleEditName" type="text" class="modal-text-input" required
            [class.input-error]="isEditSubmitted && !editRoleForm.name.trim()"
            [attr.aria-invalid]="isEditSubmitted && !editRoleForm.name.trim()"
            [attr.aria-describedby]="isEditSubmitted && !editRoleForm.name.trim() ? 'role-edit-name-error' : null"
            [(ngModel)]="editRoleForm.name" />
          <span id="role-edit-name-error" class="field-error" *ngIf="isEditSubmitted && !editRoleForm.name.trim()">Укажите название роли</span>
        </div>
        <div class="modal-field">
          <label class="modal-label" for="role-edit-state">Статус активности</label>
          <select id="role-edit-state" name="roleEditState" class="modal-text-input" [(ngModel)]="editRoleForm.state" [disabled]="r.pcode === 'admin'">
            <option value="A">Активна (A)</option>
            <option value="P">Отключена (P)</option>
          </select>
          <span class="modal-help" *ngIf="r.pcode === 'admin'">Роль суперадминистратора всегда активна.</span>
        </div>
        <div class="modal-field">
          <label class="modal-label" for="role-edit-order">Порядок отображения</label>
          <input id="role-edit-order" name="roleEditOrder" type="number" class="modal-text-input font-mono" [(ngModel)]="editRoleForm.orderNo" />
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isEditModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmittingRole()" (onClick)="submitEditRole()">Сохранить</ui-button>
      </div>
    </ui-modal>

    <!-- ======================================================================= -->
    <!-- Delete Role Modal                                                       -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="isDeleteModalOpen()"
      title="Удаление роли"
      size="sm"
      (close)="isDeleteModalOpen.set(false)"
    >
      <div body class="modal-delete-body" *ngIf="deletingRole as r">
        <p class="delete-title">
          Вы действительно хотите удалить пользовательскую роль <strong>{{ r.name }}</strong>?
        </p>
        <span class="delete-desc">Все назначенные права этой роли будут удалены. Если роль назначена пользователям, удаление будет отклонено.</span>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isDeleteModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="danger" size="md" [loading]="isSubmittingRole()" (onClick)="confirmDeleteRole()">Удалить</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .roles-page {
      display: flex;
      flex-direction: column;
      gap: 16px;
      width: 100%;
    }

    /* Page Header */
    .page-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 12px;
    }
    .header-left {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .page-title {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }
    .role-badge {
      font-size: 12px;
      color: var(--text-muted);
      background-color: var(--bg-hover);
      padding: 2px 8px;
      border-radius: 12px;
      font-weight: 500;
      border: 1px solid var(--border-color);
    }
    .header-right {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    /* Roles Strip Container */
    .roles-strip-container {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      padding: 12px 16px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .roles-strip-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 10px;
    }
    .strip-title {
      font-size: 12px;
      font-weight: 600;
      color: var(--text-muted);
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    .roles-filter-box {
      display: flex;
      align-items: center;
      gap: 6px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 3px 8px;
      width: 220px;
    }
    .roles-filter-box .icon { font-size: 15px; color: var(--text-muted); }
    .roles-filter-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 11px;
      color: var(--text-main);
      width: 100%;
    }

    .roles-cards-grid {
      display: flex;
      align-items: stretch;
      gap: 10px;
      flex-wrap: wrap;
    }
    .role-card-btn {
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 8px 12px;
      min-width: 180px;
      max-width: 260px;
      cursor: pointer;
      display: flex;
      flex-direction: column;
      gap: 6px;
      transition: all 0.12s ease;
      user-select: none;
    }
    .role-card-btn:hover {
      border-color: var(--primary);
      background-color: var(--bg-surface);
    }
    .role-card-btn.active {
      background-color: rgba(99,102,241,0.08);
      border-color: var(--primary);
      box-shadow: 0 0 0 1px var(--primary);
    }
    .role-select-btn {
      width: 100%;
      border: 0;
      background: transparent;
      color: inherit;
      cursor: pointer;
      display: flex;
      flex-direction: column;
      font: inherit;
      gap: 6px;
      padding: 0;
      text-align: left;
    }

    .role-card-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 6px;
    }
    .role-card-title { font-size: 13px; font-weight: 600; color: var(--text-main); }
    .role-sys-tag {
      font-size: 10px;
      background-color: rgba(99,102,241,0.1);
      color: var(--primary);
      padding: 1px 5px;
      border-radius: 4px;
    }
    .role-custom-tag { font-size: 10px; color: var(--text-muted); }

    .role-card-foot {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 6px;
      font-size: 11px;
    }
    .role-status-line { display: flex; align-items: center; gap: 5px; color: var(--text-muted); }
    .status-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background-color: var(--text-light);
    }
    .status-dot.active { background-color: var(--success); }
    .status-text { font-size: 11px; }

    .role-btns { display: flex; align-items: center; gap: 2px; }
    .mini-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      padding: 2px;
      border-radius: 3px;
      display: flex;
    }
    .mini-btn .material-symbols-outlined { font-size: 14px; }
    .mini-btn:hover { color: var(--text-main); background-color: var(--bg-surface); }
    .mini-btn.delete:hover { color: var(--danger); }

    .add-role-dashed-btn {
      border: 1px dashed var(--border-color);
      background-color: transparent;
      border-radius: var(--radius-sm);
      padding: 8px 14px;
      display: flex;
      align-items: center;
      gap: 6px;
      color: var(--primary);
      font-size: 12px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.12s ease;
    }
    .add-role-dashed-btn:hover {
      background-color: var(--bg-hover);
      border-color: var(--primary);
    }
    .add-role-dashed-btn .material-symbols-outlined { font-size: 16px; }

    /* Main Matrix Card */
    .matrix-card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .matrix-header-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 14px 18px;
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      gap: 16px;
      flex-wrap: wrap;
    }
    .role-summary-box { display: flex; flex-direction: column; gap: 6px; }
    .role-name-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .role-name-text { font-size: 17px; font-weight: 600; margin: 0; color: var(--text-main); }
    .role-code-badge {
      font-size: 11px;
      color: var(--primary);
      background: rgba(99,102,241,0.1);
      padding: 2px 7px;
      border-radius: 4px;
    }
    .status-pill {
      font-size: 11px;
      color: var(--text-muted);
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      padding: 2px 7px;
      border-radius: 4px;
    }
    .status-pill.active { color: var(--success); border-color: rgba(16,185,129,0.3); }

    .role-meter-row { display: flex; align-items: center; gap: 12px; }
    .meter-text { font-size: 12px; color: var(--text-muted); }
    .meter-track {
      width: 120px;
      height: 6px;
      background-color: var(--border-color);
      border-radius: 3px;
      overflow: hidden;
    }
    .meter-fill {
      height: 100%;
      background-color: var(--primary);
      transition: width 0.2s ease;
    }

    .matrix-actions-box { display: flex; align-items: center; }

    .admin-notice {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 10px 18px;
      background-color: rgba(99,102,241,0.06);
      border-bottom: 1px solid rgba(99,102,241,0.15);
      font-size: 12px;
      color: var(--primary);
    }
    .admin-notice .icon { font-size: 18px; flex-shrink: 0; }

    /* Matrix Toolbar */
    .matrix-toolbar-box {
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding: 12px 18px;
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-surface);
    }
    .search-and-expand-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
    }
    .matrix-search-field {
      display: flex;
      align-items: center;
      gap: 6px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 5px 10px;
      width: 340px;
      max-width: 100%;
    }
    .matrix-search-field .icon { font-size: 16px; color: var(--text-muted); }
    .matrix-search-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 12px;
      color: var(--text-main);
      width: 100%;
    }
    .clear-search-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      padding: 0;
    }
    .clear-search-btn .material-symbols-outlined { font-size: 14px; }

    .expand-all-links { display: flex; align-items: center; gap: 6px; font-size: 11px; }
    .text-link {
      background: transparent;
      border: none;
      color: var(--primary);
      font-size: 11px;
      cursor: pointer;
      padding: 0;
    }
    .text-link:hover { text-decoration: underline; }
    .link-sep { color: var(--text-light); }

    .module-filter-pills {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-wrap: wrap;
    }
    .mod-pill-btn {
      border: 1px solid var(--border-color);
      background: var(--bg-hover);
      padding: 4px 10px;
      border-radius: 20px;
      font-size: 11px;
      font-weight: 500;
      color: var(--text-muted);
      cursor: pointer;
      transition: all 0.1s ease;
      white-space: nowrap;
    }
    .mod-pill-btn:hover { color: var(--text-main); border-color: var(--text-muted); }
    .mod-pill-btn.active {
      color: #ffffff;
      background-color: var(--primary);
      border-color: var(--primary);
    }

    /* Modules Stack */
    .modules-stack {
      padding: 16px 18px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .mod-section-card {
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      overflow: hidden;
      background-color: var(--bg-surface);
    }
    .mod-section-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 14px;
      background-color: var(--bg-hover);
      border-bottom: 1px solid var(--border-color);
      user-select: none;
    }
    .mod-header-left { display: flex; align-items: center; gap: 8px; }
    .mod-toggle-btn {
      min-width: 0;
      border: 0;
      background: transparent;
      color: inherit;
      cursor: pointer;
      font: inherit;
      padding: 0;
      text-align: left;
    }
    .chevron-icon { font-size: 18px; color: var(--text-muted); }
    .mod-icon { font-size: 18px; color: var(--primary); }
    .mod-title { font-size: 13px; font-weight: 600; color: var(--text-main); margin: 0; }
    .mod-count { font-size: 11px; color: var(--text-muted); }

    .mod-header-right { display: flex; align-items: center; gap: 8px; }
    .batch-btn {
      background: transparent;
      border: none;
      color: var(--primary);
      font-size: 11px;
      cursor: pointer;
      padding: 0;
    }
    .batch-btn:hover { text-decoration: underline; }
    .batch-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .batch-divider { color: var(--text-light); font-size: 10px; }

    .mod-section-body {
      padding: 0;
      overflow-x: auto;
    }
    .forms-grid-table {
      width: 100%;
      border-collapse: collapse;
    }
    .form-grid-row {
      border-bottom: 1px solid var(--border-color);
    }
    .form-grid-row:last-child { border-bottom: none; }
    .form-grid-row:hover { background-color: var(--bg-hover); }

    .form-title-col {
      padding: 12px 16px;
      width: 250px;
      vertical-align: top;
      border-right: 1px solid var(--border-color);
    }
    .form-title-wrap { display: flex; flex-direction: column; gap: 3px; }
    .form-name-text { font-size: 13px; font-weight: 600; color: var(--text-main); }
    .form-code-text { font-size: 10px; color: var(--text-muted); }

    .form-quick-toggles {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 10px;
      color: var(--text-light);
      margin-top: 4px;
    }
    .mini-toggle-btn {
      background: transparent;
      border: none;
      font-size: 10px;
      color: var(--primary);
      cursor: pointer;
      padding: 0;
    }
    .mini-toggle-btn:hover { text-decoration: underline; }
    .dot { color: var(--text-light); font-size: 8px; }

    .form-actions-col {
      padding: 12px 16px;
      vertical-align: middle;
    }
    .actions-chips-wrap {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .action-checkbox-card {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 5px 12px;
      border-radius: var(--radius-xs);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      font-size: 12px;
      color: var(--text-muted);
      cursor: pointer;
      user-select: none;
      transition: all 0.1s ease;
    }
    .action-checkbox-card:hover { border-color: var(--text-muted); }
    .action-checkbox-card.checked {
      background-color: rgba(99,102,241,0.08);
      border-color: var(--primary);
      color: var(--text-main);
      font-weight: 500;
    }
    .action-checkbox-card.readonly { opacity: 0.9; cursor: not-allowed; }
    .chk-input { margin: 0; cursor: pointer; }
    .chk-label { font-size: 12px; }

    .no-forms-box {
      padding: 36px;
      text-align: center;
      color: var(--text-muted);
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
    }
    .no-forms-box .icon { font-size: 36px; color: var(--text-light); }

    /* Modals */
    .modal-body-form {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .modal-field { display: flex; flex-direction: column; gap: 4px; }
    .modal-label { font-size: 11px; font-weight: 500; color: var(--text-muted); }
    .modal-text-input {
      height: 34px;
      padding: 4px 10px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }
    .modal-text-input:focus { border-color: var(--primary); }
    .modal-text-input.input-error { border-color: var(--danger); }
    .modal-help { font-size: 11px; color: var(--text-muted); }
    .field-error { font-size: 10px; color: var(--danger); }

    .modal-delete-body { display: flex; flex-direction: column; gap: 6px; }
    .delete-title { font-size: 13px; margin: 0; }
    .delete-desc { font-size: 11px; color: var(--text-muted); }

    .req { color: var(--danger); }
    .font-mono { font-family: monospace; }
  `]
})
export class RolesComponent implements OnInit {
  readonly roles = signal<Role[]>([]);
  readonly forms = signal<FormTreeItem[]>([]);
  readonly selectedRole = signal<Role | null>(null);
  readonly rolePermissions = signal<Set<string>>(new Set());

  readonly isLoading = signal<boolean>(false);
  readonly isSaving = signal<boolean>(false);
  readonly isSubmittingRole = signal<boolean>(false);

  roleSearchQuery = '';
  matrixSearchQuery = '';
  selectedModuleTab = 'all';

  moduleGroups: ModuleGroup[] = [];

  // Modals
  readonly isCreateModalOpen = signal<boolean>(false);
  readonly isEditModalOpen = signal<boolean>(false);
  readonly isDeleteModalOpen = signal<boolean>(false);
  isCreateSubmitted = false;
  isEditSubmitted = false;

  editingRole: Role | null = null;
  deletingRole: Role | null = null;

  newRoleForm = {
    name: '',
    orderNo: 0
  };

  editRoleForm = {
    name: '',
    state: 'A',
    orderNo: 0
  };

  constructor(
    public permService: PermissionService,
    private api: ApiService,
    private toast: ToastService
  ) {}

  ngOnInit() {
    this.loadForms();
    this.loadRoles();
  }

  canCreateRole(): boolean {
    return this.permService.canCreate('rbac.roles') || this.permService.canCreate('iam.roles') || this.permService.canCreate('md_roles');
  }

  canUpdateRole(): boolean {
    return this.permService.canUpdate('rbac.roles') || this.permService.canUpdate('iam.roles') || this.permService.canUpdate('md_roles');
  }

  canDeleteRole(): boolean {
    return this.permService.canDelete('rbac.roles') || this.permService.canDelete('iam.roles') || this.permService.canDelete('md_roles');
  }

  canGrant(): boolean {
    return this.permService.hasPermission('rbac.roles', 'grant') ||
           this.permService.hasPermission('iam.roles', 'grant') ||
           this.permService.canUpdate('rbac.roles');
  }

  loadRoles() {
    this.api.get<Role[]>('/rbac/roles').subscribe({
      next: res => {
        const list = res || [];
        this.roles.set(list);
        if (!this.selectedRole() && list.length > 0) {
          this.selectRole(list[0]);
        }
      },
      error: () => {
        this.api.get<Role[]>('/iam/roles').subscribe({
          next: res => {
            const list = res || [];
            this.roles.set(list);
            if (!this.selectedRole() && list.length > 0) {
              this.selectRole(list[0]);
            }
          }
        });
      }
    });
  }

  loadForms() {
    this.api.get<FormTreeItem[]>('/rbac/forms').subscribe(res => {
      const items = res || [];
      this.forms.set(items);
      this.buildModuleGroups(items);
    });
  }

  buildModuleGroups(items: FormTreeItem[]) {
    const groupedMap = new Map<string, Map<string, GroupedForm>>();

    for (const item of items) {
      if (!groupedMap.has(item.module)) {
        groupedMap.set(item.module, new Map());
      }
      const moduleMap = groupedMap.get(item.module)!;

      if (!moduleMap.has(item.formCode)) {
        moduleMap.set(item.formCode, {
          module: item.module,
          formCode: item.formCode,
          formName: item.formName,
          actions: []
        });
      }

      moduleMap.get(item.formCode)!.actions.push({
        action: item.action,
        actionName: item.actionName
      });
    }

    const groups: ModuleGroup[] = [];
    groupedMap.forEach((formMap, modCode) => {
      groups.push({
        moduleCode: modCode,
        moduleName: this.getModuleDisplayName(modCode),
        forms: Array.from(formMap.values()),
        isExpanded: true
      });
    });

    this.moduleGroups = groups;
  }

  selectRole(role: Role) {
    this.selectedRole.set(role);
    this.api.get<string[]>(`/rbac/roles/${role.id}/permissions`).subscribe(res => {
      this.rolePermissions.set(new Set(res || []));
    });
  }

  filteredRoles(): Role[] {
    const q = this.roleSearchQuery.trim().toLowerCase();
    if (!q) return this.roles();
    return this.roles().filter(r =>
      r.name.toLowerCase().includes(q) || (r.pcode && r.pcode.toLowerCase().includes(q))
    );
  }

  visibleModuleGroups(): ModuleGroup[] {
    const q = this.matrixSearchQuery.trim().toLowerCase();
    const activeTab = this.selectedModuleTab;

    return this.moduleGroups
      .filter(mod => activeTab === 'all' || mod.moduleCode === activeTab)
      .map(mod => {
        if (!q) return mod;
        const matchingForms = mod.forms.filter(f =>
          f.formName.toLowerCase().includes(q) ||
          f.formCode.toLowerCase().includes(q) ||
          f.actions.some(a => a.actionName.toLowerCase().includes(q) || a.action.toLowerCase().includes(q))
        );
        return {
          ...mod,
          forms: matchingForms
        };
      })
      .filter(mod => mod.forms.length > 0);
  }

  getModuleActionsCount(mod: ModuleGroup): number {
    return mod.forms.reduce((sum, f) => sum + f.actions.length, 0);
  }

  setAllModulesExpanded(expanded: boolean) {
    for (const mod of this.moduleGroups) {
      mod.isExpanded = expanded;
    }
  }

  toggleModuleExpand(mod: ModuleGroup) {
    mod.isExpanded = !mod.isExpanded;
  }

  getModuleDisplayName(mod: string): string {
    const map: Record<string, string> = {
      'md': 'Пользователи и безопасность (IAM)',
      'iam': 'Учетные записи и профиль (IAM)',
      'ms.task': 'Управление задачами (TASK)',
      'ms.notify': 'Оповещения и события (NOTIF)',
      'platform': 'Системная платформа (PLATFORM)',
      'audit': 'Журнал аудита (AUDIT)',
      'mf': 'Файловое хранилище (FILE)',
      'kwh': 'Хранилище данных (DWH)'
    };
    return map[mod] || `Модуль: ${mod.toUpperCase()}`;
  }

  getModuleIcon(mod: string): string {
    const map: Record<string, string> = {
      'md': 'admin_panel_settings',
      'iam': 'security',
      'ms.task': 'task_alt',
      'ms.notify': 'notifications',
      'platform': 'hub',
      'audit': 'history',
      'mf': 'folder_open',
      'kwh': 'database'
    };
    return map[mod] || 'folder';
  }

  totalActionsCount(): number {
    return this.forms().length;
  }

  activePermissionsCount(): number {
    if (this.selectedRole()?.pcode === 'admin') {
      return this.totalActionsCount();
    }
    return this.rolePermissions().size;
  }

  permissionPercentage(): number {
    const total = this.totalActionsCount();
    if (total === 0) return 0;
    return Math.round((this.activePermissionsCount() / total) * 100);
  }

  hasPermission(formCode: string, action: string): boolean {
    if (this.selectedRole()?.pcode === 'admin') return true;
    return this.rolePermissions().has(`${formCode}.${action}`);
  }

  togglePermission(formCode: string, action: string, event: Event) {
    if (this.selectedRole()?.pcode === 'admin') return;

    const checked = (event.target as HTMLInputElement).checked;
    const current = new Set(this.rolePermissions());
    const key = `${formCode}.${action}`;

    if (checked) {
      current.add(key);
    } else {
      current.delete(key);
    }
    this.rolePermissions.set(current);
  }

  toggleAllForm(form: GroupedForm, grant: boolean) {
    if (this.selectedRole()?.pcode === 'admin') return;

    const current = new Set(this.rolePermissions());
    for (const act of form.actions) {
      const key = `${form.formCode}.${act.action}`;
      if (grant) {
        current.add(key);
      } else {
        current.delete(key);
      }
    }
    this.rolePermissions.set(current);
  }

  toggleAllModule(moduleGroup: ModuleGroup, grant: boolean) {
    if (this.selectedRole()?.pcode === 'admin') return;

    const current = new Set(this.rolePermissions());
    for (const f of moduleGroup.forms) {
      for (const act of f.actions) {
        const key = `${f.formCode}.${act.action}`;
        if (grant) {
          current.add(key);
        } else {
          current.delete(key);
        }
      }
    }
    this.rolePermissions.set(current);
  }

  savePermissions() {
    const role = this.selectedRole();
    if (!role) return;

    this.isSaving.set(true);
    const pairs: PermissionPair[] = Array.from(this.rolePermissions()).map(p => {
      const parts = p.split('.');
      const action = parts.pop() || '';
      const formCode = parts.join('.');
      return { formCode, action };
    });

    this.api.put(`/rbac/roles/${role.id}/permissions`, pairs).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.toast.success('Матрица прав успешно сохранена');
      },
      error: () => {
        this.isSaving.set(false);
      }
    });
  }

  openCreateModal() {
    this.newRoleForm = { name: '', orderNo: 0 };
    this.isCreateSubmitted = false;
    this.isCreateModalOpen.set(true);
  }

  submitCreateRole() {
    this.isCreateSubmitted = true;
    if (!this.newRoleForm.name.trim()) {
      this.toast.warning('Введите название роли');
      return;
    }

    this.isSubmittingRole.set(true);
    this.api.post<Role>('/rbac/roles', {
      name: this.newRoleForm.name.trim(),
      orderNo: this.newRoleForm.orderNo || 0
    }).subscribe({
      next: newRole => {
        this.isSubmittingRole.set(false);
        this.isCreateModalOpen.set(false);
        this.toast.success('Роль успешно создана');
        this.loadRoles();
        this.selectRole(newRole);
      },
      error: () => {
        this.isSubmittingRole.set(false);
      }
    });
  }

  openEditRoleModal(role: Role) {
    this.editingRole = role;
    this.editRoleForm = {
      name: role.name,
      state: role.state,
      orderNo: role.orderNo
    };
    this.isEditSubmitted = false;
    this.isEditModalOpen.set(true);
  }

  submitEditRole() {
    if (!this.editingRole) return;
    this.isEditSubmitted = true;
    if (!this.editRoleForm.name.trim()) {
      this.toast.warning('Название роли обязательно');
      return;
    }

    this.isSubmittingRole.set(true);
    this.api.patch(`/rbac/roles/${this.editingRole.id}`, this.editRoleForm).subscribe({
      next: () => {
        this.isSubmittingRole.set(false);
        this.isEditModalOpen.set(false);
        this.toast.success('Данные роли обновлены');
        this.loadRoles();
      },
      error: () => {
        this.isSubmittingRole.set(false);
      }
    });
  }

  openDeleteRoleModal(role: Role) {
    this.deletingRole = role;
    this.isDeleteModalOpen.set(true);
  }

  confirmDeleteRole() {
    if (!this.deletingRole) return;

    this.isSubmittingRole.set(true);
    this.api.delete(`/rbac/roles/${this.deletingRole.id}`).subscribe({
      next: () => {
        this.isSubmittingRole.set(false);
        this.isDeleteModalOpen.set(false);
        this.toast.success('Роль удалена');
        this.selectedRole.set(null);
        this.loadRoles();
      },
      error: () => {
        this.isSubmittingRole.set(false);
      }
    });
  }
}
