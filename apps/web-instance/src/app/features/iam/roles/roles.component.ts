import { Component, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { Role, FormTreeItem, PermissionPair } from '../../../core/models/rbac.models';

interface GroupedForm {
  module: string;
  formCode: string;
  formName: string;
  actions: Array<{ action: string; actionName: string }>;
}

interface ModuleGroup {
  moduleName: string;
  forms: GroupedForm[];
}

@Component({
  selector: 'app-roles',
  standalone: true,
  imports: [CommonModule, FormsModule, UiButtonComponent, UiModalComponent],
  template: `
    <div class="roles-view">
      <!-- Minimal Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">Роли и матрица прав</h1>
          <span class="role-count">{{ roles().length }}</span>
        </div>
        <ui-button
          *ngIf="canCreateRole()"
          variant="primary"
          size="md"
          icon="add"
          (onClick)="openCreateModal()"
        >
          Создать роль
        </ui-button>
      </div>

      <!-- Master-Detail RBAC Layout -->
      <div class="rbac-grid">
        <!-- Roles List Sidebar -->
        <div class="roles-sidebar">
          <div class="sidebar-search">
            <span class="material-symbols-outlined icon">search</span>
            <input
              type="text"
              class="search-input"
              placeholder="Поиск ролей..."
              [(ngModel)]="roleSearchQuery"
            />
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
                <span class="system-tag" *ngIf="r.pcode">{{ r.pcode }}</span>
                <span class="custom-tag" *ngIf="!r.pcode">Пользовательская</span>
              </div>
              <div class="role-card-bot">
                <span class="status-dot" [class.active]="r.state === 'A'"></span>
                <span class="status-lbl">{{ r.state === 'A' ? 'Активна' : 'Отключена' }}</span>
                <div class="role-actions" *ngIf="!r.pcode" (click)="$event.stopPropagation()">
                  <button type="button" class="icon-btn" title="Редактировать роль" (click)="openEditRoleModal(r)">
                    <span class="material-symbols-outlined">edit</span>
                  </button>
                  <button type="button" class="icon-btn" title="Удалить роль" (click)="openDeleteRoleModal(r)">
                    <span class="material-symbols-outlined">delete</span>
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Permission Matrix Main View -->
        <div class="matrix-panel" *ngIf="selectedRole() as role">
          <!-- Role Details Header Bar -->
          <div class="matrix-topbar">
            <div class="role-info">
              <div class="title-row">
                <h2 class="selected-role-name">{{ role.name }}</h2>
                <span class="system-pill" *ngIf="role.pcode">Системная роль: {{ role.pcode }}</span>
              </div>
              <span class="role-desc">
                {{ role.pcode === 'admin' ? 'Полный доступ ко всем модулям системы (I-P4 Invariant)' : 'Настройте доступ к формам и операциям ниже' }}
              </span>
            </div>

            <div class="topbar-actions">
              <div class="form-search">
                <span class="material-symbols-outlined icon">search</span>
                <input
                  type="text"
                  class="search-input"
                  placeholder="Фильтр форм и действий..."
                  [(ngModel)]="matrixSearchQuery"
                />
              </div>
              <ui-button
                *ngIf="canGrant()"
                variant="primary"
                size="md"
                icon="save"
                [loading]="isSaving()"
                (onClick)="savePermissions()"
              >
                Сохранить матрицу
              </ui-button>
            </div>
          </div>

          <!-- Grouped Modules & Forms Matrix -->
          <div class="matrix-scroll-area">
            <div *ngFor="let mod of filteredModuleGroups()" class="module-section">
              <div class="module-header">
                <span class="material-symbols-outlined mod-icon">{{ getModuleIcon(mod.moduleName) }}</span>
                <h3 class="module-title">{{ getModuleDisplayName(mod.moduleName) }}</h3>
                <div class="module-quick-actions">
                  <button type="button" class="text-action-btn" (click)="toggleAllModule(mod, true)">Выбрать все</button>
                  <span class="divider">/</span>
                  <button type="button" class="text-action-btn" (click)="toggleAllModule(mod, false)">Снять все</button>
                </div>
              </div>

              <div class="forms-grid">
                <div *ngFor="let f of mod.forms" class="form-card">
                  <div class="form-card-header">
                    <div class="form-title-box">
                      <span class="form-name">{{ f.formName }}</span>
                      <span class="form-code font-mono">{{ f.formCode }}</span>
                    </div>
                    <div class="form-quick-actions">
                      <button type="button" class="form-mini-btn" (click)="toggleAllForm(f, true)" title="Выбрать все">✓ Все</button>
                      <button type="button" class="form-mini-btn" (click)="toggleAllForm(f, false)" title="Снять все">✗ Снять</button>
                    </div>
                  </div>

                  <div class="actions-chips">
                    <label
                      *ngFor="let act of f.actions"
                      class="action-chip"
                      [class.checked]="hasPermission(f.formCode, act.action)"
                      [class.locked]="role.pcode === 'admin'"
                    >
                      <input
                        type="checkbox"
                        [checked]="hasPermission(f.formCode, act.action)"
                        [disabled]="role.pcode === 'admin'"
                        (change)="togglePermission(f.formCode, act.action, $event)"
                      />
                      <span class="action-label">{{ act.actionName }}</span>
                      <span class="action-code font-mono">{{ act.action }}</span>
                    </label>
                  </div>
                </div>
              </div>
            </div>

            <div *ngIf="filteredModuleGroups().length === 0" class="empty-matrix">
              <span class="material-symbols-outlined ico">filter_list_off</span>
              <p>Формы по запросу «{{ matrixSearchQuery }}» не найдены</p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Create Role Modal -->
    <ui-modal
      [isOpen]="isCreateModalOpen()"
      title="Новая роль"
      size="sm"
      (close)="isCreateModalOpen.set(false)"
    >
      <div body class="clean-form">
        <div class="form-group">
          <label class="clean-label">Название роли <span class="req">*</span></label>
          <input
            type="text"
            class="clean-input"
            [(ngModel)]="newRoleForm.name"
            placeholder="Например: Старший аналитик данных"
          />
        </div>
        <div class="form-group">
          <label class="clean-label">Порядок сортировки</label>
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

    <!-- Edit Role Modal -->
    <ui-modal
      [isOpen]="isEditModalOpen()"
      title="Редактировать роль"
      size="sm"
      (close)="isEditModalOpen.set(false)"
    >
      <div body class="clean-form" *ngIf="editingRole as r">
        <div class="form-group">
          <label class="clean-label">Название роли <span class="req">*</span></label>
          <input type="text" class="clean-input" [(ngModel)]="editRoleForm.name" />
        </div>
        <div class="form-group">
          <label class="clean-label">Статус</label>
          <select class="clean-input" [(ngModel)]="editRoleForm.state">
            <option value="A">Активна (A)</option>
            <option value="P">Отключена (P)</option>
          </select>
        </div>
        <div class="form-group">
          <label class="clean-label">Порядок сортировки</label>
          <input type="number" class="clean-input font-mono" [(ngModel)]="editRoleForm.orderNo" />
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isEditModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmittingRole()" (onClick)="submitEditRole()">Сохранить</ui-button>
      </div>
    </ui-modal>

    <!-- Delete Role Modal -->
    <ui-modal
      [isOpen]="isDeleteModalOpen()"
      title="Удаление роли"
      size="sm"
      (close)="isDeleteModalOpen.set(false)"
    >
      <div body class="delete-form" *ngIf="deletingRole as r">
        <p class="delete-msg">
          Вы действительно хотите удалить пользовательскую роль <strong>{{ r.name }}</strong>?
        </p>
        <span class="delete-hint">Все назначенные права этой роли будут отозваны.</span>
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
    .rbac-grid {
      display: grid;
      grid-template-columns: 280px 1fr;
      gap: 16px;
      align-items: start;
    }

    @media (max-width: 900px) {
      .rbac-grid { grid-template-columns: 1fr; }
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
    .sidebar-search {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 8px 10px;
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
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
      gap: 4px;
      transition: background 0.1s ease;
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
      font-family: monospace;
      background-color: var(--bg-hover);
      color: var(--primary);
      padding: 1px 5px;
      border-radius: 4px;
      border: 1px solid var(--border-color);
    }
    .custom-tag {
      font-size: 10px;
      color: var(--text-muted);
    }

    .role-card-bot {
      display: flex;
      align-items: center;
      gap: 5px;
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

    .role-actions { display: flex; align-items: center; gap: 2px; }
    .icon-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      padding: 2px;
      border-radius: 3px;
      display: flex;
    }
    .icon-btn .material-symbols-outlined { font-size: 14px; }
    .icon-btn:hover { color: var(--text-main); background-color: var(--bg-hover); }

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
    .role-info { display: flex; flex-direction: column; gap: 2px; }
    .title-row { display: flex; align-items: center; gap: 8px; }
    .selected-role-name { font-size: 15px; font-weight: 600; margin: 0; color: var(--text-main); }
    .system-pill {
      font-size: 11px;
      font-family: monospace;
      color: var(--primary);
      background: rgba(99,102,241,0.1);
      padding: 1px 6px;
      border-radius: 4px;
    }
    .role-desc { font-size: 12px; color: var(--text-muted); }

    .topbar-actions {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .form-search {
      display: flex;
      align-items: center;
      gap: 6px;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 3px 8px;
      width: 220px;
    }
    .form-search .icon { font-size: 16px; color: var(--text-muted); }
    .form-search .search-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 12px;
      color: var(--text-main);
      width: 100%;
    }

    .matrix-scroll-area {
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 20px;
      max-height: calc(100vh - 220px);
      overflow-y: auto;
    }

    .module-section {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .module-header {
      display: flex;
      align-items: center;
      gap: 8px;
      padding-bottom: 6px;
      border-bottom: 1px solid var(--border-color);
    }
    .mod-icon { font-size: 18px; color: var(--primary); }
    .module-title { font-size: 13px; font-weight: 600; color: var(--text-main); margin: 0; flex: 1; }
    .module-quick-actions { display: flex; align-items: center; gap: 4px; }
    .text-action-btn {
      background: transparent;
      border: none;
      color: var(--primary);
      font-size: 11px;
      cursor: pointer;
      padding: 0;
    }
    .text-action-btn:hover { text-decoration: underline; }
    .divider { color: var(--text-light); font-size: 11px; }

    .forms-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
      gap: 12px;
    }
    .form-card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 10px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .form-card-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 6px;
    }
    .form-title-box { display: flex; flex-direction: column; }
    .form-name { font-size: 12px; font-weight: 600; color: var(--text-main); }
    .form-code { font-size: 10px; color: var(--text-muted); }

    .form-quick-actions { display: flex; align-items: center; gap: 4px; }
    .form-mini-btn {
      border: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      font-size: 10px;
      color: var(--text-muted);
      border-radius: 3px;
      padding: 1px 4px;
      cursor: pointer;
    }
    .form-mini-btn:hover { color: var(--text-main); border-color: var(--text-muted); }

    .actions-chips {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .action-chip {
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
      transition: all 0.1s ease;
      user-select: none;
    }
    .action-chip:hover { border-color: var(--text-muted); }
    .action-chip.checked {
      background-color: rgba(99,102,241,0.08);
      border-color: var(--primary);
      color: var(--text-main);
      font-weight: 500;
    }
    .action-chip.locked { opacity: 0.85; cursor: not-allowed; }
    .action-label { font-size: 11px; }
    .action-code { font-size: 9px; color: var(--text-light); }

    .empty-matrix {
      text-align: center;
      padding: 32px;
      color: var(--text-muted);
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
    }
    .empty-matrix .ico { font-size: 32px; color: var(--text-light); }

    /* Forms in Modals */
    .clean-form {
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

    .delete-form { display: flex; flex-direction: column; gap: 6px; }
    .delete-msg { font-size: 13px; margin: 0; }
    .delete-hint { font-size: 11px; color: var(--text-muted); }

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
    return this.permService.canCreate('iam.roles') || this.permService.canCreate('md_roles');
  }

  canGrant(): boolean {
    return this.permService.hasPermission('iam.roles', 'grant') ||
           this.permService.hasPermission('md_roles', 'grant') ||
           this.permService.canUpdate('iam.roles');
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
      this.forms.set(res || []);
    });
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

  filteredModuleGroups(): ModuleGroup[] {
    const allForms = this.forms();
    const q = this.matrixSearchQuery.trim().toLowerCase();

    // Group forms by module and formCode
    const groupedMap = new Map<string, Map<string, GroupedForm>>();

    for (const item of allForms) {
      if (q) {
        const matches =
          item.formName.toLowerCase().includes(q) ||
          item.formCode.toLowerCase().includes(q) ||
          item.actionName.toLowerCase().includes(q) ||
          item.action.toLowerCase().includes(q) ||
          item.module.toLowerCase().includes(q);
        if (!matches) continue;
      }

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
    groupedMap.forEach((formMap, modName) => {
      groups.push({
        moduleName: modName,
        forms: Array.from(formMap.values())
      });
    });

    return groups;
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
