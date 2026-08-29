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
    <div class="roles-container">
      <!-- Top Page Header -->
      <div class="page-header">
        <div class="header-left">
          <h1 class="page-title">Роли и матрица прав</h1>
          <span class="roles-count-pill">{{ roles().length }} ролей</span>
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

      <!-- Master-Detail 2-Column Container -->
      <div class="rbac-columns">
        <!-- Left Column: Roles Sidebar -->
        <div class="roles-sidebar-box">
          <div class="sidebar-top">
            <div class="sidebar-search-box">
              <span class="material-symbols-outlined search-ico">search</span>
              <input
                type="text"
                class="sidebar-search-input"
                placeholder="Поиск роли..."
                [(ngModel)]="roleSearchQuery"
              />
            </div>
            <button
              class="add-role-btn"
              *ngIf="canCreateRole()"
              (click)="openCreateModal()"
              title="Создать новую роль"
            >
              <span class="material-symbols-outlined">add</span>
            </button>
          </div>

          <div class="roles-scroll-list">
            <div
              *ngFor="let r of filteredRoles()"
              class="role-item-card"
              [class.active]="selectedRole()?.id === r.id"
              (click)="selectRole(r)"
            >
              <div class="role-item-main">
                <span class="role-item-name">{{ r.name }}</span>
                <span class="role-pcode-tag font-mono" *ngIf="r.pcode">{{ r.pcode }}</span>
                <span class="role-custom-tag" *ngIf="!r.pcode">Пользовательская</span>
              </div>

              <div class="role-item-sub">
                <span class="role-status-dot" [class.active]="r.state === 'A'"></span>
                <span class="role-status-text">{{ r.state === 'A' ? 'Активна' : 'Отключена' }}</span>

                <div class="role-item-actions" (click)="$event.stopPropagation()">
                  <button
                    type="button"
                    class="role-action-btn"
                    title="Редактировать роль"
                    *ngIf="canUpdateRole()"
                    (click)="openEditRoleModal(r)"
                  >
                    <span class="material-symbols-outlined">edit</span>
                  </button>
                  <button
                    type="button"
                    class="role-action-btn delete"
                    title="Удалить роль"
                    *ngIf="!r.pcode && canDeleteRole()"
                    (click)="openDeleteRoleModal(r)"
                  >
                    <span class="material-symbols-outlined">delete</span>
                  </button>
                </div>
              </div>
            </div>

            <div *ngIf="filteredRoles().length === 0" class="roles-empty-box">
              <span>Роли не найдены</span>
            </div>
          </div>
        </div>

        <!-- Right Column: Permission Matrix -->
        <div class="matrix-main-box" *ngIf="selectedRole() as role">
          <!-- Selected Role Info Banner -->
          <div class="matrix-role-header">
            <div class="matrix-role-info">
              <div class="matrix-title-line">
                <h2 class="role-heading">{{ role.name }}</h2>
                <span class="system-code-pill font-mono" *ngIf="role.pcode">Системная: {{ role.pcode }}</span>
                <span class="role-active-badge" [class.active]="role.state === 'A'">
                  {{ role.state === 'A' ? 'Активна' : 'Отключена' }}
                </span>
              </div>
              <div class="matrix-stat-line">
                <span class="matrix-stat-text">
                  Разрешено: <strong>{{ activePermissionsCount() }}</strong> из {{ totalActionsCount() }} действий
                </span>
                <div class="matrix-stat-bar">
                  <div class="matrix-stat-fill" [style.width.%]="permissionPercentage()"></div>
                </div>
              </div>
            </div>

            <div class="matrix-save-box">
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

          <!-- Admin notice -->
          <div *ngIf="role.pcode === 'admin'" class="admin-shield-banner">
            <span class="material-symbols-outlined banner-ico">verified_user</span>
            <span>Роль администратора обладает абсолютными правами (100% покрытие каталога системы по инварианту I-P4).</span>
          </div>

          <!-- Matrix Controls: Search & Module Tabs -->
          <div class="matrix-controls-bar">
            <div class="controls-search-row">
              <div class="matrix-search-box">
                <span class="material-symbols-outlined icon">search</span>
                <input
                  type="text"
                  class="matrix-search-input"
                  placeholder="Поиск по названию формы, действию или коду..."
                  [(ngModel)]="matrixSearchQuery"
                />
                <button *ngIf="matrixSearchQuery" class="matrix-clear-btn" (click)="matrixSearchQuery = ''">
                  <span class="material-symbols-outlined">close</span>
                </button>
              </div>

              <div class="matrix-global-actions">
                <button type="button" class="action-link" (click)="setAllModulesExpanded(true)">Развернуть все</button>
                <span class="action-sep">•</span>
                <button type="button" class="action-link" (click)="setAllModulesExpanded(false)">Свернуть все</button>
              </div>
            </div>

            <div class="module-pills-wrap">
              <button
                type="button"
                class="module-pill-btn"
                [class.active]="selectedModuleTab === 'all'"
                (click)="selectedModuleTab = 'all'"
              >
                Все разделы ({{ forms().length }})
              </button>
              <button
                *ngFor="let mod of moduleGroups"
                type="button"
                class="module-pill-btn"
                [class.active]="selectedModuleTab === mod.moduleCode"
                (click)="selectedModuleTab = mod.moduleCode"
              >
                {{ mod.moduleName }} ({{ getModuleActionsCount(mod) }})
              </button>
            </div>
          </div>

          <!-- Matrix Accordion & Tables -->
          <div class="matrix-body-scroll">
            <div *ngFor="let mod of visibleModuleGroups()" class="mod-group-card">
              <!-- Module Section Header -->
              <div class="mod-group-header" (click)="toggleModuleExpand(mod)">
                <div class="mod-header-left">
                  <span class="material-symbols-outlined mod-arrow-ico">
                    {{ mod.isExpanded ? 'expand_more' : 'chevron_right' }}
                  </span>
                  <span class="material-symbols-outlined mod-type-ico">{{ getModuleIcon(mod.moduleCode) }}</span>
                  <h3 class="mod-header-title">{{ mod.moduleName }}</h3>
                  <span class="mod-header-forms-count">({{ mod.forms.length }} форм)</span>
                </div>

                <div class="mod-header-right" (click)="$event.stopPropagation()">
                  <button
                    type="button"
                    class="mod-batch-btn"
                    [disabled]="role.pcode === 'admin'"
                    (click)="toggleAllModule(mod, true)"
                  >
                    ✓ Выбрать все
                  </button>
                  <span class="mod-batch-sep">|</span>
                  <button
                    type="button"
                    class="mod-batch-btn"
                    [disabled]="role.pcode === 'admin'"
                    (click)="toggleAllModule(mod, false)"
                  >
                    ✗ Снять все
                  </button>
                </div>
              </div>

              <!-- Module Forms Table -->
              <div class="mod-group-body" *ngIf="mod.isExpanded">
                <table class="rbac-matrix-table">
                  <tbody>
                    <tr *ngFor="let f of mod.forms" class="matrix-form-row">
                      <td class="col-form-info">
                        <div class="form-title-block">
                          <span class="form-display-name">{{ f.formName }}</span>
                          <span class="form-system-code font-mono">{{ f.formCode }}</span>
                          <div class="form-quick-links" *ngIf="role.pcode !== 'admin'">
                            <button type="button" class="link-sm" (click)="toggleAllForm(f, true)">все</button>
                            <span class="link-dot">•</span>
                            <button type="button" class="link-sm" (click)="toggleAllForm(f, false)">снять</button>
                          </div>
                        </div>
                      </td>

                      <td class="col-form-actions">
                        <div class="action-badges-grid">
                          <label
                            *ngFor="let act of f.actions"
                            class="action-toggle-card"
                            [class.active]="hasPermission(f.formCode, act.action)"
                            [class.admin-locked]="role.pcode === 'admin'"
                            [title]="f.formCode + '.' + act.action"
                          >
                            <input
                              type="checkbox"
                              class="action-chk"
                              [checked]="hasPermission(f.formCode, act.action)"
                              [disabled]="role.pcode === 'admin'"
                              (change)="togglePermission(f.formCode, act.action, $event)"
                            />
                            <span class="action-chk-label">{{ act.actionName }}</span>
                          </label>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            <div *ngIf="visibleModuleGroups().length === 0" class="matrix-no-results">
              <span class="material-symbols-outlined icon">search_off</span>
              <p>Формы не найдены по запросу «{{ matrixSearchQuery }}»</p>
            </div>
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
      <div body class="modal-form-grid">
        <div class="form-row-group">
          <label class="modal-lbl">Название роли <span class="req">*</span></label>
          <input
            type="text"
            class="modal-input"
            [(ngModel)]="newRoleForm.name"
            placeholder="Например: Старший инженер данных"
          />
        </div>
        <div class="form-row-group">
          <label class="modal-lbl">Порядок отображения</label>
          <input
            type="number"
            class="modal-input font-mono"
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
      <div body class="modal-form-grid" *ngIf="editingRole as r">
        <div class="form-row-group">
          <label class="modal-lbl">Название роли <span class="req">*</span></label>
          <input type="text" class="modal-input" [(ngModel)]="editRoleForm.name" />
        </div>
        <div class="form-row-group">
          <label class="modal-lbl">Статус активности</label>
          <select class="modal-input" [(ngModel)]="editRoleForm.state" [disabled]="r.pcode === 'admin'">
            <option value="A">Активна (A)</option>
            <option value="P">Отключена (P)</option>
          </select>
          <span class="modal-hint" *ngIf="r.pcode === 'admin'">Роль суперадминистратора всегда активна.</span>
        </div>
        <div class="form-row-group">
          <label class="modal-lbl">Порядок отображения</label>
          <input type="number" class="modal-input font-mono" [(ngModel)]="editRoleForm.orderNo" />
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
      <div body class="delete-warning-body" *ngIf="deletingRole as r">
        <p class="delete-txt">
          Вы действительно хотите удалить пользовательскую роль <strong>{{ r.name }}</strong>?
        </p>
        <span class="delete-sub-txt">Все назначенные права этой роли будут удалены. Если роль назначена пользователям, удаление будет отклонено.</span>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isDeleteModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="danger" size="md" [loading]="isSubmittingRole()" (onClick)="confirmDeleteRole()">Удалить</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .roles-container {
      display: flex;
      flex-direction: column;
      gap: 14px;
      width: 100%;
      min-width: 0;
    }

    /* Page Header */
    .page-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 10px;
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
    .roles-count-pill {
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

    /* Master-Detail 2-Column Layout */
    .rbac-columns {
      display: flex;
      gap: 16px;
      align-items: stretch;
      width: 100%;
      min-width: 0;
      height: calc(100vh - 150px);
    }

    @media (max-width: 900px) {
      .rbac-columns {
        flex-direction: column;
        height: auto;
      }
    }

    /* Left Sidebar */
    .roles-sidebar-box {
      width: 260px;
      min-width: 240px;
      flex-shrink: 0;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .sidebar-top {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 8px 10px;
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
    }
    .sidebar-search-box {
      display: flex;
      align-items: center;
      gap: 6px;
      flex: 1;
      min-width: 0;
    }
    .search-ico { font-size: 16px; color: var(--text-muted); }
    .sidebar-search-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 12px;
      color: var(--text-main);
      width: 100%;
    }
    .add-role-btn {
      border: 1px solid var(--border-color);
      background: var(--bg-surface);
      color: var(--primary);
      border-radius: 4px;
      width: 26px;
      height: 26px;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      flex-shrink: 0;
    }
    .add-role-btn .material-symbols-outlined { font-size: 16px; }
    .add-role-btn:hover { background-color: var(--bg-hover); }

    .roles-scroll-list {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow-y: auto;
    }
    .role-item-card {
      padding: 10px 12px;
      border-bottom: 1px solid var(--border-color);
      cursor: pointer;
      display: flex;
      flex-direction: column;
      gap: 5px;
      transition: all 0.1s ease;
    }
    .role-item-card:last-child { border-bottom: none; }
    .role-item-card:hover { background-color: var(--bg-hover); }
    .role-item-card.active {
      background-color: rgba(99,102,241,0.08);
      border-left: 3px solid var(--primary);
    }

    .role-item-main {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 6px;
    }
    .role-item-name { font-size: 13px; font-weight: 500; color: var(--text-main); }
    .role-pcode-tag {
      font-size: 10px;
      background-color: var(--bg-hover);
      color: var(--primary);
      padding: 1px 5px;
      border-radius: 4px;
      border: 1px solid var(--border-color);
    }
    .role-custom-tag { font-size: 10px; color: var(--text-muted); }

    .role-item-sub {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 11px;
      color: var(--text-muted);
    }
    .role-status-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background-color: var(--text-light);
    }
    .role-status-dot.active { background-color: var(--success); }
    .role-status-text { flex: 1; }

    .role-item-actions { display: flex; align-items: center; gap: 3px; }
    .role-action-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      padding: 2px;
      border-radius: 3px;
      display: flex;
    }
    .role-action-btn .material-symbols-outlined { font-size: 15px; }
    .role-action-btn:hover { color: var(--text-main); background-color: var(--bg-hover); }
    .role-action-btn.delete:hover { color: var(--danger); }

    .roles-empty-box {
      padding: 24px 12px;
      text-align: center;
      color: var(--text-muted);
      font-size: 12px;
    }

    /* Right Matrix Main Box */
    .matrix-main-box {
      flex: 1;
      min-width: 0;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .matrix-role-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 16px;
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      gap: 12px;
      flex-wrap: wrap;
    }
    .matrix-role-info { display: flex; flex-direction: column; gap: 4px; }
    .matrix-title-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .role-heading { font-size: 16px; font-weight: 600; margin: 0; color: var(--text-main); }
    .system-code-pill {
      font-size: 11px;
      color: var(--primary);
      background: rgba(99,102,241,0.1);
      padding: 1px 6px;
      border-radius: 4px;
    }
    .role-active-badge {
      font-size: 11px;
      color: var(--text-muted);
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      padding: 1px 6px;
      border-radius: 4px;
    }
    .role-active-badge.active { color: var(--success); border-color: rgba(16,185,129,0.3); }

    .matrix-stat-line { display: flex; align-items: center; gap: 10px; }
    .matrix-stat-text { font-size: 11px; color: var(--text-muted); }
    .matrix-stat-bar {
      width: 100px;
      height: 4px;
      background-color: var(--border-color);
      border-radius: 2px;
      overflow: hidden;
    }
    .matrix-stat-fill {
      height: 100%;
      background-color: var(--primary);
      transition: width 0.2s ease;
    }

    .admin-shield-banner {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 16px;
      background-color: rgba(99,102,241,0.06);
      border-bottom: 1px solid rgba(99,102,241,0.15);
      font-size: 12px;
      color: var(--primary);
    }
    .admin-shield-banner .banner-ico { font-size: 18px; flex-shrink: 0; }

    /* Matrix Controls */
    .matrix-controls-bar {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 10px 16px;
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      min-width: 0;
    }

    .controls-search-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
    }
    .matrix-search-box {
      display: flex;
      align-items: center;
      gap: 6px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 4px 10px;
      width: 320px;
      max-width: 100%;
    }
    .matrix-search-box .icon { font-size: 16px; color: var(--text-muted); }
    .matrix-search-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 12px;
      color: var(--text-main);
      width: 100%;
    }
    .matrix-clear-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      padding: 0;
    }
    .matrix-clear-btn .material-symbols-outlined { font-size: 14px; }

    .matrix-global-actions {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 11px;
    }
    .action-link {
      background: transparent;
      border: none;
      color: var(--primary);
      font-size: 11px;
      cursor: pointer;
      padding: 0;
    }
    .action-link:hover { text-decoration: underline; }
    .action-sep { color: var(--text-light); }

    .module-pills-wrap {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-wrap: wrap;
      min-width: 0;
    }
    .module-pill-btn {
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
    .module-pill-btn:hover { color: var(--text-main); border-color: var(--text-muted); }
    .module-pill-btn.active {
      color: #ffffff;
      background-color: var(--primary);
      border-color: var(--primary);
    }

    /* Matrix Body Scroll */
    .matrix-body-scroll {
      flex: 1;
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 14px;
      overflow-y: auto;
      min-width: 0;
    }

    /* Module Group Card */
    .mod-group-card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      overflow: hidden;
    }
    .mod-group-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 12px;
      background-color: var(--bg-hover);
      border-bottom: 1px solid var(--border-color);
      cursor: pointer;
      user-select: none;
    }
    .mod-header-left {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .mod-arrow-ico { font-size: 18px; color: var(--text-muted); }
    .mod-type-ico { font-size: 16px; color: var(--primary); }
    .mod-header-title { font-size: 13px; font-weight: 600; color: var(--text-main); margin: 0; }
    .mod-header-forms-count { font-size: 11px; color: var(--text-muted); }

    .mod-header-right {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .mod-batch-btn {
      background: transparent;
      border: none;
      color: var(--primary);
      font-size: 11px;
      cursor: pointer;
      padding: 0;
    }
    .mod-batch-btn:hover { text-decoration: underline; }
    .mod-batch-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .mod-batch-sep { color: var(--text-light); font-size: 10px; }

    .mod-group-body {
      padding: 0;
      overflow-x: auto;
    }
    .rbac-matrix-table {
      width: 100%;
      border-collapse: collapse;
    }
    .matrix-form-row {
      border-bottom: 1px solid var(--border-color);
    }
    .matrix-form-row:last-child { border-bottom: none; }
    .matrix-form-row:hover { background-color: var(--bg-hover); }

    .col-form-info {
      padding: 10px 14px;
      width: 240px;
      vertical-align: top;
      border-right: 1px solid var(--border-color);
    }
    .form-title-block {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .form-display-name { font-size: 12px; font-weight: 600; color: var(--text-main); }
    .form-system-code { font-size: 10px; color: var(--text-muted); }

    .form-quick-links {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 10px;
      color: var(--text-light);
      margin-top: 4px;
    }
    .link-sm {
      background: transparent;
      border: none;
      font-size: 10px;
      color: var(--primary);
      cursor: pointer;
      padding: 0;
    }
    .link-sm:hover { text-decoration: underline; }
    .link-dot { color: var(--text-light); font-size: 8px; }

    .col-form-actions {
      padding: 10px 14px;
      vertical-align: middle;
    }
    .action-badges-grid {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .action-toggle-card {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: var(--radius-xs);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      font-size: 11px;
      color: var(--text-muted);
      cursor: pointer;
      user-select: none;
      transition: all 0.1s ease;
    }
    .action-toggle-card:hover { border-color: var(--text-muted); }
    .action-toggle-card.active {
      background-color: rgba(99,102,241,0.08);
      border-color: var(--primary);
      color: var(--text-main);
      font-weight: 500;
    }
    .action-toggle-card.admin-locked { opacity: 0.9; cursor: not-allowed; }
    .action-chk { margin: 0; cursor: pointer; }
    .action-chk-label { font-size: 11px; }

    .matrix-no-results {
      padding: 32px;
      text-align: center;
      color: var(--text-muted);
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
    }
    .matrix-no-results .icon { font-size: 32px; color: var(--text-light); }

    /* Modals */
    .modal-form-grid {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .form-row-group { display: flex; flex-direction: column; gap: 4px; }
    .modal-lbl { font-size: 11px; font-weight: 500; color: var(--text-muted); }
    .modal-input {
      height: 32px;
      padding: 4px 8px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }
    .modal-input:focus { border-color: var(--primary); }
    .modal-hint { font-size: 11px; color: var(--text-muted); }

    .delete-warning-body { display: flex; flex-direction: column; gap: 6px; }
    .delete-txt { font-size: 13px; margin: 0; }
    .delete-sub-txt { font-size: 11px; color: var(--text-muted); }

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
    this.isCreateModalOpen.set(true);
  }

  submitCreateRole() {
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
    this.isEditModalOpen.set(true);
  }

  submitEditRole() {
    if (!this.editingRole) return;
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
