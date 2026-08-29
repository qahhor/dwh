import { Component, OnInit, signal, computed } from '@angular/core';
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
    <div class="roles-view">
      <!-- Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">Роли и матрица прав</h1>
          <span class="role-count">{{ roles().length }}</span>
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

      <!-- Master-Detail Layout -->
      <div class="rbac-layout">
        <!-- Sidebar: Roles List -->
        <div class="roles-sidebar">
          <div class="sidebar-header">
            <div class="sidebar-search">
              <span class="material-symbols-outlined icon">search</span>
              <input
                type="text"
                class="search-input"
                placeholder="Поиск роли..."
                [(ngModel)]="roleSearchQuery"
              />
            </div>
            <button class="add-role-mini-btn" *ngIf="canCreateRole()" (click)="openCreateModal()" title="Создать роль">
              <span class="material-symbols-outlined">add</span>
            </button>
          </div>

          <div class="roles-list">
            <div
              *ngFor="let r of filteredRoles()"
              class="role-card"
              [class.active]="selectedRole()?.id === r.id"
              (click)="selectRole(r)"
            >
              <div class="role-card-top">
                <span class="role-name">{{ r.name }}</span>
                <span class="system-tag font-mono" *ngIf="r.pcode">{{ r.pcode }}</span>
                <span class="custom-tag" *ngIf="!r.pcode">Кастомная</span>
              </div>

              <div class="role-card-bot">
                <span class="status-dot" [class.active]="r.state === 'A'"></span>
                <span class="status-lbl">{{ r.state === 'A' ? 'Активна' : 'Отключена' }}</span>

                <div class="role-card-actions" (click)="$event.stopPropagation()">
                  <button
                    type="button"
                    class="action-mini-btn"
                    title="Редактировать параметры роли"
                    *ngIf="canUpdateRole()"
                    (click)="openEditRoleModal(r)"
                  >
                    <span class="material-symbols-outlined">edit</span>
                  </button>
                  <button
                    type="button"
                    class="action-mini-btn delete"
                    title="Удалить роль"
                    *ngIf="!r.pcode && canDeleteRole()"
                    (click)="openDeleteRoleModal(r)"
                  >
                    <span class="material-symbols-outlined">delete</span>
                  </button>
                </div>
              </div>
            </div>

            <div *ngIf="filteredRoles().length === 0" class="sidebar-empty">
              <span>Роли не найдены</span>
            </div>
          </div>
        </div>

        <!-- Main Panel: Permission Matrix -->
        <div class="matrix-panel" *ngIf="selectedRole() as role">
          <!-- Role Details Header -->
          <div class="matrix-topbar">
            <div class="role-headline">
              <div class="role-title-row">
                <h2 class="selected-role-name">{{ role.name }}</h2>
                <span class="system-badge font-mono" *ngIf="role.pcode">Системная: {{ role.pcode }}</span>
                <span class="status-pill" [class.active]="role.state === 'A'">
                  {{ role.state === 'A' ? 'Активна' : 'Отключена' }}
                </span>
              </div>
              <div class="role-stats-row">
                <span class="perm-counter">
                  Разрешено: <strong>{{ activePermissionsCount() }}</strong> из {{ totalActionsCount() }} действий
                </span>
                <div class="perm-progress-bar">
                  <div class="perm-progress-fill" [style.width.%]="permissionPercentage()"></div>
                </div>
              </div>
            </div>

            <div class="topbar-save-actions">
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

          <!-- Module Navigation Pills & Search Toolbar -->
          <div class="matrix-toolbar">
            <div class="module-tabs">
              <button
                type="button"
                class="mod-tab"
                [class.active]="selectedModuleTab === 'all'"
                (click)="selectedModuleTab = 'all'"
              >
                Все разделы ({{ forms().length }})
              </button>
              <button
                *ngFor="let mod of moduleGroups"
                type="button"
                class="mod-tab"
                [class.active]="selectedModuleTab === mod.moduleCode"
                (click)="selectedModuleTab = mod.moduleCode"
              >
                {{ mod.moduleName }} ({{ mod.forms.length }})
              </button>
            </div>

            <div class="toolbar-search">
              <span class="material-symbols-outlined icon">search</span>
              <input
                type="text"
                class="search-input"
                placeholder="Поиск по названию формы, действию или коду..."
                [(ngModel)]="matrixSearchQuery"
              />
              <button *ngIf="matrixSearchQuery" class="clear-search" (click)="matrixSearchQuery = ''">
                <span class="material-symbols-outlined">close</span>
              </button>
            </div>
          </div>

          <!-- Permission Matrix Content Area -->
          <div class="matrix-content-scroll">
            <div *ngIf="role.pcode === 'admin'" class="admin-notice-banner">
              <span class="material-symbols-outlined">verified_user</span>
              <span>Роль администратора обладает абсолютными правами (100% покрытие каталога системы по инварианту I-P4).</span>
            </div>

            <div *ngFor="let mod of visibleModuleGroups()" class="module-card">
              <!-- Module Section Header -->
              <div class="module-card-header" (click)="toggleModuleExpand(mod)">
                <div class="mod-title-box">
                  <span class="material-symbols-outlined expand-arrow">
                    {{ mod.isExpanded ? 'expand_more' : 'chevron_right' }}
                  </span>
                  <span class="material-symbols-outlined mod-icon">{{ getModuleIcon(mod.moduleCode) }}</span>
                  <h3 class="mod-title-text">{{ mod.moduleName }}</h3>
                  <span class="mod-form-count">({{ mod.forms.length }} форм)</span>
                </div>

                <div class="mod-header-actions" (click)="$event.stopPropagation()">
                  <button
                    type="button"
                    class="batch-link"
                    [disabled]="role.pcode === 'admin'"
                    (click)="toggleAllModule(mod, true)"
                  >
                    ✓ Выбрать все
                  </button>
                  <span class="action-divider">|</span>
                  <button
                    type="button"
                    class="batch-link"
                    [disabled]="role.pcode === 'admin'"
                    (click)="toggleAllModule(mod, false)"
                  >
                    ✗ Снять все
                  </button>
                </div>
              </div>

              <!-- Module Forms Body -->
              <div class="module-card-body" *ngIf="mod.isExpanded">
                <div class="forms-table">
                  <div *ngFor="let f of mod.forms" class="form-row">
                    <div class="form-meta">
                      <span class="form-title">{{ f.formName }}</span>
                      <span class="form-code font-mono">{{ f.formCode }}</span>
                      <div class="form-row-quick-btns" *ngIf="role.pcode !== 'admin'">
                        <button type="button" class="mini-txt-btn" (click)="toggleAllForm(f, true)">все</button>
                        <span>•</span>
                        <button type="button" class="mini-txt-btn" (click)="toggleAllForm(f, false)">снять</button>
                      </div>
                    </div>

                    <div class="actions-pill-grid">
                      <label
                        *ngFor="let act of f.actions"
                        class="action-pill"
                        [class.granted]="hasPermission(f.formCode, act.action)"
                        [class.disabled]="role.pcode === 'admin'"
                      >
                        <input
                          type="checkbox"
                          class="action-checkbox"
                          [checked]="hasPermission(f.formCode, act.action)"
                          [disabled]="role.pcode === 'admin'"
                          (change)="togglePermission(f.formCode, act.action, $event)"
                        />
                        <span class="action-name">{{ act.actionName }}</span>
                        <span class="action-tag font-mono">{{ act.action }}</span>
                      </label>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div *ngIf="visibleModuleGroups().length === 0" class="empty-matrix-results">
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
      <div body class="modal-form">
        <div class="form-group">
          <label class="clean-label">Название роли <span class="req">*</span></label>
          <input
            type="text"
            class="clean-input"
            [(ngModel)]="newRoleForm.name"
            placeholder="Например: Старший инженер данных"
          />
        </div>
        <div class="form-group">
          <label class="clean-label">Порядок отображения</label>
          <input
            type="number"
            class="clean-input font-mono"
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
      <div body class="modal-form" *ngIf="editingRole as r">
        <div class="form-group">
          <label class="clean-label">Название роли <span class="req">*</span></label>
          <input type="text" class="clean-input" [(ngModel)]="editRoleForm.name" />
        </div>
        <div class="form-group">
          <label class="clean-label">Статус активности</label>
          <select class="clean-input" [(ngModel)]="editRoleForm.state" [disabled]="r.pcode === 'admin'">
            <option value="A">Активна (A)</option>
            <option value="P">Отключена (P)</option>
          </select>
          <span class="field-hint" *ngIf="r.pcode === 'admin'">Роль суперадминистратора всегда активна.</span>
        </div>
        <div class="form-group">
          <label class="clean-label">Порядок отображения</label>
          <input type="number" class="clean-input font-mono" [(ngModel)]="editRoleForm.orderNo" />
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
      <div body class="modal-delete" *ngIf="deletingRole as r">
        <p class="delete-msg">
          Вы действительно хотите удалить пользовательскую роль <strong>{{ r.name }}</strong>?
        </p>
        <span class="delete-sub">Все назначенные права этой роли будут удалены. Если роль назначена пользователям, удаление будет отклонено.</span>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isDeleteModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="danger" size="md" [loading]="isSubmittingRole()" (onClick)="confirmDeleteRole()">Удалить</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .roles-view {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 1400px;
    }

    /* Header */
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
    .role-count {
      font-size: 12px;
      color: var(--text-muted);
      background-color: var(--bg-hover);
      padding: 1px 7px;
      border-radius: 10px;
      font-weight: 500;
    }

    /* Layout */
    .rbac-layout {
      display: grid;
      grid-template-columns: 280px 1fr;
      gap: 16px;
      align-items: start;
    }

    @media (max-width: 960px) {
      .rbac-layout { grid-template-columns: 1fr; }
    }

    /* Sidebar */
    .roles-sidebar {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .sidebar-header {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 8px 10px;
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
    }
    .sidebar-search {
      display: flex;
      align-items: center;
      gap: 6px;
      flex: 1;
    }
    .sidebar-search .icon { font-size: 16px; color: var(--text-muted); }
    .sidebar-search .search-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 12px;
      color: var(--text-main);
      width: 100%;
    }
    .add-role-mini-btn {
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
    }
    .add-role-mini-btn .material-symbols-outlined { font-size: 16px; }
    .add-role-mini-btn:hover { background-color: var(--bg-hover); }

    .roles-list {
      display: flex;
      flex-direction: column;
      max-height: calc(100vh - 220px);
      overflow-y: auto;
    }
    .role-card {
      padding: 10px 12px;
      border-bottom: 1px solid var(--border-color);
      cursor: pointer;
      display: flex;
      flex-direction: column;
      gap: 5px;
      transition: all 0.1s ease;
    }
    .role-card:last-child { border-bottom: none; }
    .role-card:hover { background-color: var(--bg-hover); }
    .role-card.active {
      background-color: rgba(99,102,241,0.08);
      border-left: 3px solid var(--primary);
    }

    .role-card-top {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 6px;
    }
    .role-name { font-size: 13px; font-weight: 500; color: var(--text-main); }
    .system-tag {
      font-size: 10px;
      background-color: var(--bg-hover);
      color: var(--primary);
      padding: 1px 5px;
      border-radius: 4px;
      border: 1px solid var(--border-color);
    }
    .custom-tag { font-size: 10px; color: var(--text-muted); }

    .role-card-bot {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 11px;
      color: var(--text-muted);
    }
    .status-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background-color: var(--text-light);
    }
    .status-dot.active { background-color: var(--success); }
    .status-lbl { flex: 1; }

    .role-card-actions { display: flex; align-items: center; gap: 3px; }
    .action-mini-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      padding: 2px;
      border-radius: 3px;
      display: flex;
    }
    .action-mini-btn .material-symbols-outlined { font-size: 15px; }
    .action-mini-btn:hover { color: var(--text-main); background-color: var(--bg-hover); }
    .action-mini-btn.delete:hover { color: var(--danger); }

    .sidebar-empty {
      padding: 24px 12px;
      text-align: center;
      color: var(--text-muted);
      font-size: 12px;
    }

    /* Matrix Main Panel */
    .matrix-panel {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .matrix-topbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 16px;
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      gap: 12px;
      flex-wrap: wrap;
    }
    .role-headline { display: flex; flex-direction: column; gap: 4px; }
    .role-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .selected-role-name { font-size: 16px; font-weight: 600; margin: 0; color: var(--text-main); }
    .system-badge {
      font-size: 11px;
      color: var(--primary);
      background: rgba(99,102,241,0.1);
      padding: 1px 6px;
      border-radius: 4px;
    }
    .status-pill {
      font-size: 11px;
      color: var(--text-muted);
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      padding: 1px 6px;
      border-radius: 4px;
    }
    .status-pill.active { color: var(--success); border-color: rgba(16,185,129,0.3); }

    .role-stats-row {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .perm-counter { font-size: 11px; color: var(--text-muted); }
    .perm-progress-bar {
      width: 100px;
      height: 4px;
      background-color: var(--border-color);
      border-radius: 2px;
      overflow: hidden;
    }
    .perm-progress-fill {
      height: 100%;
      background-color: var(--primary);
      transition: width 0.2s ease;
    }

    /* Toolbar: Tabs & Search */
    .matrix-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 16px;
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      gap: 12px;
      flex-wrap: wrap;
    }
    .module-tabs {
      display: flex;
      align-items: center;
      gap: 4px;
      overflow-x: auto;
    }
    .mod-tab {
      border: 1px solid transparent;
      background: transparent;
      padding: 4px 8px;
      border-radius: var(--radius-sm);
      font-size: 11px;
      font-weight: 500;
      color: var(--text-muted);
      cursor: pointer;
      white-space: nowrap;
      transition: all 0.1s ease;
    }
    .mod-tab:hover { color: var(--text-main); background-color: var(--bg-hover); }
    .mod-tab.active {
      color: var(--primary);
      background-color: rgba(99,102,241,0.08);
      border-color: rgba(99,102,241,0.2);
    }

    .toolbar-search {
      display: flex;
      align-items: center;
      gap: 6px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 3px 8px;
      width: 240px;
    }
    .toolbar-search .icon { font-size: 16px; color: var(--text-muted); }
    .toolbar-search .search-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 11px;
      color: var(--text-main);
      width: 100%;
    }
    .clear-search {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      padding: 0;
    }
    .clear-search .material-symbols-outlined { font-size: 14px; }

    /* Matrix Content Scroll */
    .matrix-content-scroll {
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 14px;
      max-height: calc(100vh - 240px);
      overflow-y: auto;
    }

    .admin-notice-banner {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 12px;
      background-color: rgba(99,102,241,0.06);
      border: 1px solid rgba(99,102,241,0.2);
      border-radius: var(--radius-sm);
      font-size: 12px;
      color: var(--primary);
    }

    /* Module Card */
    .module-card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      overflow: hidden;
    }
    .module-card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 12px;
      background-color: var(--bg-hover);
      border-bottom: 1px solid var(--border-color);
      cursor: pointer;
      user-select: none;
    }
    .mod-title-box {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .expand-arrow { font-size: 18px; color: var(--text-muted); }
    .mod-icon { font-size: 16px; color: var(--primary); }
    .mod-title-text { font-size: 13px; font-weight: 600; color: var(--text-main); margin: 0; }
    .mod-form-count { font-size: 11px; color: var(--text-muted); }

    .mod-header-actions {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .batch-link {
      background: transparent;
      border: none;
      color: var(--primary);
      font-size: 11px;
      cursor: pointer;
      padding: 0;
    }
    .batch-link:hover { text-decoration: underline; }
    .batch-link:disabled { opacity: 0.5; cursor: not-allowed; }
    .action-divider { color: var(--text-light); font-size: 10px; }

    /* Module Forms Body */
    .module-card-body {
      padding: 0;
    }
    .forms-table {
      display: flex;
      flex-direction: column;
    }
    .form-row {
      display: grid;
      grid-template-columns: 240px 1fr;
      gap: 12px;
      padding: 10px 14px;
      border-bottom: 1px solid var(--border-color);
      align-items: center;
    }
    .form-row:last-child { border-bottom: none; }

    @media (max-width: 800px) {
      .form-row { grid-template-columns: 1fr; gap: 6px; }
    }

    .form-meta {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .form-title { font-size: 12px; font-weight: 600; color: var(--text-main); }
    .form-code { font-size: 10px; color: var(--text-muted); }

    .form-row-quick-btns {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 10px;
      color: var(--text-light);
      margin-top: 2px;
    }
    .mini-txt-btn {
      background: transparent;
      border: none;
      font-size: 10px;
      color: var(--primary);
      cursor: pointer;
      padding: 0;
    }
    .mini-txt-btn:hover { text-decoration: underline; }

    /* Actions Pill Grid */
    .actions-pill-grid {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .action-pill {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      padding: 3px 8px;
      border-radius: var(--radius-xs);
      border: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      font-size: 11px;
      color: var(--text-muted);
      cursor: pointer;
      user-select: none;
      transition: all 0.1s ease;
    }
    .action-pill:hover { border-color: var(--text-muted); }
    .action-pill.granted {
      background-color: rgba(99,102,241,0.08);
      border-color: var(--primary);
      color: var(--text-main);
      font-weight: 500;
    }
    .action-pill.disabled { opacity: 0.85; cursor: not-allowed; }
    .action-checkbox { margin: 0; }
    .action-name { font-size: 11px; }
    .action-tag { font-size: 9px; color: var(--text-light); }

    .empty-matrix-results {
      padding: 32px;
      text-align: center;
      color: var(--text-muted);
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
    }
    .empty-matrix-results .icon { font-size: 32px; color: var(--text-light); }

    /* Modals */
    .modal-form {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .form-group { display: flex; flex-direction: column; gap: 4px; }
    .clean-label { font-size: 11px; font-weight: 500; color: var(--text-muted); }
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
    .field-hint { font-size: 11px; color: var(--text-muted); }

    .modal-delete { display: flex; flex-direction: column; gap: 6px; }
    .delete-msg { font-size: 13px; margin: 0; }
    .delete-sub { font-size: 11px; color: var(--text-muted); }

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

  toggleModuleExpand(mod: ModuleGroup) {
    mod.isExpanded = !mod.isExpanded;
  }

  getModuleDisplayName(mod: string): string {
    const map: Record<string, string> = {
      'md': 'IAM & Настройки экземпляра',
      'iam': 'IAM & Безопасность',
      'ms.task': 'Управление задачами и проектами',
      'ms.notify': 'Оповещения и события',
      'platform': 'Системная платформа',
      'audit': 'Аудит и безопасность'
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
      'audit': 'history'
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
