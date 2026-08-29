import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiBadgeComponent } from '../../../shared/ui/ui-badge.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { Role, FormTreeItem, PermissionPair } from '../../../core/models/rbac.models';

@Component({
  selector: 'app-roles',
  standalone: true,
  imports: [CommonModule, FormsModule, UiButtonComponent, UiBadgeComponent, UiModalComponent],
  template: `
    <div class="roles-container">
      <div class="page-header">
        <div>
          <h2 class="page-title">Роли и матрица прав</h2>
          <p class="page-subtitle">Настройка ролевого доступа RBAC к формам и действиям системы</p>
        </div>
        <ui-button
          *ngIf="permService.canCreate('iam.roles') || permService.canCreate('md_roles')"
          variant="primary"
          icon="add"
          (onClick)="openCreateRoleModal()"
        >
          Создать роль
        </ui-button>
      </div>

      <div class="rbac-layout">
        <!-- Roles List Sidebar -->
        <div class="card roles-sidebar">
          <div class="sidebar-title">Список ролей</div>
          <div class="roles-list">
            <div
              *ngFor="let r of roles()"
              [class.active]="selectedRole()?.id === r.id"
              class="role-item"
              (click)="selectRole(r)"
            >
              <div class="role-item-header">
                <span class="role-name font-medium">{{ r.name }}</span>
                <ui-badge [variant]="r.state === 'A' ? 'active' : 'passive'" size="sm">{{ r.state === 'A' ? 'A' : 'P' }}</ui-badge>
              </div>
              <div class="role-pcode text-muted font-mono" *ngIf="r.pcode">{{ r.pcode }}</div>
            </div>
          </div>
        </div>

        <!-- Permission Matrix Grid -->
        <div class="card matrix-card" *ngIf="selectedRole() as role">
          <div class="matrix-header">
            <div>
              <h3 class="matrix-title">Матрица прав для роли: <strong>{{ role.name }}</strong></h3>
              <p class="matrix-subtitle">Отметьте разрешённые действия для каждой формы</p>
            </div>
            <ui-button
              *ngIf="permService.hasPermission('iam.roles', 'grant') || permService.hasPermission('md_roles', 'grant') || permService.canUpdate('iam.roles')"
              variant="primary"
              size="md"
              [loading]="isSaving()"
              (onClick)="savePermissions()"
            >
              Сохранить права
            </ui-button>

          </div>

          <div class="matrix-table-wrapper">
            <table class="matrix-table">
              <thead>
                <tr>
                  <th style="width: 250px;">Форма / Раздел</th>
                  <th>Модуль</th>
                  <th>Действие</th>
                  <th style="width: 100px; text-align: center;">Разрешено</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let item of forms()">
                  <td class="font-medium">{{ item.formName }} ({{ item.formCode }})</td>
                  <td class="font-mono text-muted">{{ item.module }}</td>
                  <td>{{ item.actionName }} ({{ item.action }})</td>
                  <td style="text-align: center;">
                    <input
                      type="checkbox"
                      class="perm-checkbox"
                      [checked]="hasPermission(item.formCode, item.action)"
                      (change)="togglePermission(item.formCode, item.action, $event)"
                    />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <!-- Create Role Modal -->
    <ui-modal
      [isOpen]="isCreateModalOpen()"
      title="Создание роли"
      size="sm"
      (close)="isCreateModalOpen.set(false)"
    >
      <div body class="form-group">
        <label class="form-label">Название роли</label>
        <input type="text" class="form-input" [(ngModel)]="newRoleName" placeholder="Например: Старший аналитик" />
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isCreateModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" (onClick)="submitCreateRole()">Создать</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .roles-container {
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

    .rbac-layout {
      display: grid;
      grid-template-columns: 280px 1fr;
      gap: 16px;
      align-items: start;
    }

    .card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      padding: 16px;
    }

    .roles-sidebar {
      padding: 12px;
    }

    .sidebar-title {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-muted);
      margin-bottom: 8px;
      padding: 4px 8px;
    }

    .roles-list {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .role-item {
      padding: 10px 12px;
      border-radius: var(--radius-md);
      cursor: pointer;
      border: 1px solid transparent;
      transition: all 0.1s ease;
    }

    .role-item:hover {
      background-color: var(--bg-hover);
    }

    .role-item.active {
      background-color: var(--primary-subtle);
      border-color: var(--primary);
    }

    .role-item-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .role-name {
      font-size: 13px;
      color: var(--text-main);
    }

    .role-pcode {
      font-size: 11px;
      margin-top: 2px;
    }

    .matrix-card {
      padding: 20px;
    }

    .matrix-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 16px;
    }

    .matrix-title {
      font-size: 15px;
      font-weight: 600;
      color: var(--text-main);
    }

    .matrix-subtitle {
      font-size: 12px;
      color: var(--text-muted);
      margin-top: 2px;
    }

    .matrix-table-wrapper {
      overflow-x: auto;
    }

    .matrix-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
    }

    .matrix-table th {
      text-align: left;
      padding: 8px 12px;
      font-weight: 600;
      color: var(--text-muted);
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      font-size: 12px;
    }

    .matrix-table td {
      padding: 8px 12px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
    }

    .perm-checkbox {
      width: 16px;
      height: 16px;
      cursor: pointer;
    }

    .font-mono { font-family: monospace; }
    .font-medium { font-weight: 500; }
    .text-muted { color: var(--text-muted); }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .form-label {
      font-size: 12px;
      font-weight: 500;
      color: var(--text-main);
    }

    .form-input {
      height: 34px;
      padding: 6px 10px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }
  `]
})
export class RolesComponent implements OnInit {
  readonly roles = signal<Role[]>([]);
  readonly forms = signal<FormTreeItem[]>([]);
  readonly selectedRole = signal<Role | null>(null);
  readonly selectedPermissions = signal<Set<string>>(new Set());
  readonly isSaving = signal<boolean>(false);

  readonly isCreateModalOpen = signal<boolean>(false);
  newRoleName = '';

  constructor(
    public permService: PermissionService,
    private api: ApiService,
    private toast: ToastService
  ) {}

  ngOnInit() {
    this.loadRoles();
    this.loadFormCatalog();
  }

  loadRoles() {
    this.api.get<Role[]>('/rbac/roles').subscribe(res => {
      this.roles.set(res || []);
      if (!this.selectedRole() && res && res.length > 0) {
        this.selectRole(res[0]);
      }
    });
  }

  loadFormCatalog() {
    this.api.get<FormTreeItem[]>('/rbac/forms').subscribe(res => {
      this.forms.set(res || []);
    });
  }

  selectRole(role: Role) {
    this.selectedRole.set(role);
    this.api.get<string[]>(`/rbac/roles/${role.id}/permissions`).subscribe(res => {
      this.selectedPermissions.set(new Set(res || []));
    });
  }

  hasPermission(formCode: string, action: string): boolean {
    return this.selectedPermissions().has(`${formCode}.${action}`);
  }

  togglePermission(formCode: string, action: string, event: Event) {
    const checked = (event.target as HTMLInputElement).checked;
    const key = `${formCode}.${action}`;
    const perms = new Set(this.selectedPermissions());
    if (checked) {
      perms.add(key);
    } else {
      perms.delete(key);
    }
    this.selectedPermissions.set(perms);
  }

  savePermissions() {
    const role = this.selectedRole();
    if (!role) return;

    const list: PermissionPair[] = [];
    this.selectedPermissions().forEach(p => {
      const parts = p.split('.');
      if (parts.length === 2) {
        list.push({ formCode: parts[0], action: parts[1] });
      }
    });

    this.isSaving.set(true);
    this.api.put(`/rbac/roles/${role.id}/permissions`, list).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.toast.success('Матрица прав успешно сохранена');
      },
      error: () => {
        this.isSaving.set(false);
      }
    });
  }

  openCreateRoleModal() {
    this.newRoleName = '';
    this.isCreateModalOpen.set(true);
  }

  submitCreateRole() {
    if (!this.newRoleName.trim()) return;

    this.api.post('/rbac/roles', { name: this.newRoleName.trim(), orderNo: 0 }).subscribe(() => {
      this.isCreateModalOpen.set(false);
      this.toast.success('Роль создана');
      this.loadRoles();
    });
  }
}
