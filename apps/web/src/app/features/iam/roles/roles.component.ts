import { Component, DestroyRef, OnInit, ViewChild, signal, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable, Subscription } from 'rxjs';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { Role, FormTreeItem, PermissionPair } from '../../../core/models/rbac.models';
import { TranslatePipe, I18nService } from '../../../core/services/i18n.service';
import { safeNumericRecordId } from '../../../core/services/search-target';
import { RoleScopePanelComponent } from '../org-units/public-api';
import { RoleModalsComponent } from './components/role-modals.component';
import { RoleCardsBarComponent } from './components/role-cards-bar.component';
import { RolePermissionsMatrixComponent } from './components/role-permissions-matrix.component';
import { GroupedForm, ModuleGroup } from './roles.models';


@Component({
  selector: 'app-roles',
  standalone: true,
  imports: [
    TranslatePipe, CommonModule, FormsModule, UiButtonComponent, RoleScopePanelComponent, RoleModalsComponent, RoleCardsBarComponent, RolePermissionsMatrixComponent],
  template: `
    <div class="roles-page">
      <!-- Top Page Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'iam.roli_i_matrica_prav' | t }}</h1>
          <span class="count-badge">{{ roles().length }}</span>
        </div>
        <div class="header-right">
          <ui-button
            *ngIf="canCreateRole()"
            variant="primary"
            size="md"
            icon="add"
            (onClick)="openCreateModal()"
          >
            {{ 'iam.novaya_rol' | t }}
          </ui-button>
        </div>
      </div>

      <!-- Roles Horizontal Bar -->
      <app-role-cards-bar
        [roles]="filteredRoles()"
        [selectedRole]="selectedRole()"
        [roleUserCounts]="roleUserCounts()"
        [searchQuery]="roleSearchQuery"
        (searchQueryChange)="roleSearchQuery = $event"
        [isSaving]="isSaving()"
        [scopePanelBusy]="scopePanelBusy()"
        [isSubmittingRole]="isSubmittingRole()"
        [canCreateRole]="canCreateRole()"
        [canUpdateRole]="canUpdateRole()"
        [canDeleteRole]="canDeleteRole()"
        (selectRole)="selectRole($event)"
        (navigateToUsers)="navigateToUsersWithRole($event.role, $event.event)"
        (openEdit)="openEditRoleModal($event)"
        (openDelete)="openDeleteRoleModal($event)"
        (openCreate)="openCreateModal()"
      ></app-role-cards-bar>

      <!-- Main Permission Matrix Section -->
      <div class="matrix-card" *ngIf="selectedRole() as role" [attr.aria-busy]="isLoading() || isSaving()">
        <app-role-permissions-matrix
          [role]="role"
          [isLoading]="isLoading()"
          [isSaving]="isSaving()"
          [permissionsError]="permissionsError()"
          [activePermissionsCount]="activePermissionsCount()"
          [totalActionsCount]="totalActionsCount()"
          [permissionPercentage]="permissionPercentage()"
          [isPermissionsDirty]="isPermissionsDirty()"
          [dirtyPermissionsCount]="dirtyPermissionsCount()"
          [canGrant]="canGrant()"
          [canEditPermissions]="canEditPermissions()"
          [matrixSearchQuery]="matrixSearchQuery"
          [matchingFormsCount]="matchingFormsCount()"
          [formsCount]="forms().length"
          [selectedModuleTab]="selectedModuleTab"
          [moduleGroups]="moduleGroups"
          [visibleModuleGroups]="visibleModuleGroups()"
          [hasPermission]="hasPermissionFn"
          [isPermissionDirty]="isPermissionDirtyFn"
          [getModuleIcon]="getModuleIconFn"
          [getModuleActionsCount]="getModuleActionsCountFn"
          (resetChanges)="resetMatrixChanges()"
          (savePermissions)="savePermissions()"
          (refreshRole)="selectRole($event)"
          (matrixSearchQueryChange)="matrixSearchQuery = $event"
          (selectedModuleTabChange)="selectedModuleTab = $event"
          (setAllModulesExpanded)="setAllModulesExpanded($event)"
          (toggleAllPermissions)="toggleAllPermissions($event)"
          (toggleReadOnlyAllPermissions)="toggleReadOnlyAllPermissions()"
          (toggleModuleExpand)="toggleModuleExpand($event)"
          (toggleReadOnlyModule)="toggleReadOnlyModule($event)"
          (toggleAllModule)="toggleAllModule($event.mod, $event.select)"
          (toggleAllForm)="toggleAllForm($event.form, $event.select)"
          (togglePermission)="togglePermission($event.formCode, $event.action, $event.event)"
        ></app-role-permissions-matrix>
        <app-role-scope-panel
          *ngIf="canViewOrgUnits() && safeRoleId(role.id)"
          [roleId]="role.id"
          (busyChange)="scopePanelBusy.set($event)"
        ></app-role-scope-panel>
      </div>
    </div>

    <!-- Modals (Create, Edit, Delete, Discard) -->
    <app-role-modals
      [isCreateModalOpen]="isCreateModalOpen()"
      [isCreateSubmitted]="isCreateSubmitted"
      [newRoleForm]="newRoleForm"
      [isEditModalOpen]="isEditModalOpen()"
      [isEditSubmitted]="isEditSubmitted"
      [editingRole]="editingRole"
      [editRoleForm]="editRoleForm"
      [isDeleteModalOpen]="isDeleteModalOpen()"
      [deletingRole]="deletingRole"
      [isDiscardPermissionsModalOpen]="isDiscardPermissionsModalOpen()"
      [selectedRole]="selectedRole()"
      [dirtyPermissionsCount]="dirtyPermissionsCount()"
      [isSubmittingRole]="isSubmittingRole()"
      [isSaving]="isSaving()"
      (closeCreate)="isCreateModalOpen.set(false)"
      (submitCreate)="submitCreateRole()"
      (closeEdit)="isEditModalOpen.set(false)"
      (submitEdit)="submitEditRole()"
      (closeDelete)="closeDeleteRoleModal()"
      (confirmDelete)="confirmDeleteRole()"
      (closeDiscard)="closeDiscardModal()"
      (confirmDiscardAndSwitch)="confirmDiscardAndSwitch()"
      (saveAndSwitch)="saveAndSwitch()"
    ></app-role-modals>
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

    /* Main Matrix Card */
    .matrix-card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      display: flex;
      flex-direction: column;
    }


    .req { color: var(--danger); }
    .font-mono { font-family: monospace; }
  `]
})
export class RolesComponent implements OnInit {
  readonly hasPermissionFn = (formCode: string, action: string) => this.hasPermission(formCode, action);
  readonly isPermissionDirtyFn = (formCode: string, action: string) => this.isPermissionDirty(formCode, action);
  readonly getModuleIconFn = (moduleCode: string) => this.getModuleIcon(moduleCode);
  readonly getModuleActionsCountFn = (mod: ModuleGroup) => this.getModuleActionsCount(mod);

  private readonly router = inject(Router, { optional: true });
  private readonly uiI18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);
  private permissionsRequest?: Subscription;
  private panelLeaveSubscription?: Subscription;
  private roleScopePanel?: RoleScopePanelComponent;
  private readonly loadedPermissionsRoleId = signal<number | null>(null);
  readonly safeRoleId = safeNumericRecordId;
  readonly roles = signal<Role[]>([]);
  readonly forms = signal<FormTreeItem[]>([]);
  readonly selectedRole = signal<Role | null>(null);
  readonly rolePermissions = signal<Set<string>>(new Set());
  readonly originalRolePermissions = signal<Set<string>>(new Set());

  readonly isPermissionsDirty = computed<boolean>(() => {
    const orig = this.originalRolePermissions();
    const curr = this.rolePermissions();
    if (orig.size !== curr.size) return true;
    for (const p of curr) {
      if (!orig.has(p)) return true;
    }
    return false;
  });

  readonly dirtyPermissionsCount = computed<number>(() => {
    const orig = this.originalRolePermissions();
    const curr = this.rolePermissions();
    let diff = 0;
    for (const p of curr) {
      if (!orig.has(p)) diff++;
    }
    for (const p of orig) {
      if (!curr.has(p)) diff++;
    }
    return diff;
  });

  readonly isLoading = signal<boolean>(false);
  readonly permissionsError = signal('');
  readonly isSaving = signal<boolean>(false);
  readonly isSubmittingRole = signal<boolean>(false);
  readonly scopePanelBusy = signal(false);
  readonly roleUserCounts = signal<Record<number, number>>({});

  roleSearchQuery = '';
  matrixSearchQuery = '';
  selectedModuleTab = 'all';

  moduleGroups: ModuleGroup[] = [];

  // Modals
  readonly isCreateModalOpen = signal<boolean>(false);
  readonly isEditModalOpen = signal<boolean>(false);
  readonly isDeleteModalOpen = signal<boolean>(false);
  readonly isDiscardPermissionsModalOpen = signal<boolean>(false);
  pendingRoleToSelect: Role | null = null;
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
  ) {
    this.destroyRef.onDestroy(() => this.panelLeaveSubscription?.unsubscribe());
  }

  @ViewChild(RoleScopePanelComponent)
  set scopePanel(panel: RoleScopePanelComponent | undefined) {
    this.roleScopePanel = panel;
    if (!panel) this.scopePanelBusy.set(false);
  }

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
           this.permService.hasPermission('iam.roles', 'grant');
  }

  canViewOrgUnits(): boolean {
    return this.permService.hasPermission('iam.org_units', 'view') ||
           this.permService.hasPermission('iam.org_units', 'assign') ||
           this.scopePanelBusy();
  }

  canLeaveRecordPage(): boolean | Observable<boolean> {
    if (this.isSaving() || this.isPermissionsDirty()) return false;
    return this.roleScopePanel?.canLeave() ?? true;
  }

  canEditPermissions(): boolean {
    const role = this.selectedRole();
    return !!role && role.pcode !== 'admin' && this.canGrant() &&
      this.loadedPermissionsRoleId() === role.id && !this.isLoading() && !this.isSaving();
  }

  loadRoles() {
    this.loadRoleUserCounts();
    this.api.get<Role[]>('/rbac/roles').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: res => {
        const list = res || [];
        this.roles.set(list);
        if (!this.selectedRole() && list.length > 0) {
          this.selectRole(list[0]);
        }
      },
      error: () => {
        this.api.get<Role[]>('/iam/roles').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
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

  loadRoleUserCounts() {
    this.api.get<Record<number, number>>('/iam/roles/user-counts').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: counts => this.roleUserCounts.set(counts || {}),
      error: () => {}
    });
  }

  loadForms() {
    this.api.get<FormTreeItem[]>('/rbac/forms').pipe(takeUntilDestroyed(this.destroyRef)).subscribe(res => {
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
    if (this.isSaving() || this.isSubmittingRole() || this.destroyRef.destroyed) return;
    const selected = this.selectedRole();
    if (this.scopePanelBusy() && selected?.id !== role.id) return;
    if (!selected || selected.id === role.id) {
      this.activateRole(role);
      return;
    }
    if (this.isPermissionsDirty()) {
      this.pendingRoleToSelect = role;
      this.isDiscardPermissionsModalOpen.set(true);
      return;
    }
    this.afterRoleScopeLeave(() => this.activateRole(role));
  }

  closeDiscardModal(): void {
    if (this.isSaving()) return;
    this.pendingRoleToSelect = null;
    this.isDiscardPermissionsModalOpen.set(false);
  }

  confirmDiscardAndSwitch(): void {
    if (this.isSaving()) return;
    const nextRole = this.pendingRoleToSelect;
    this.isDiscardPermissionsModalOpen.set(false);
    this.pendingRoleToSelect = null;
    if (nextRole) {
      this.rolePermissions.set(new Set(this.originalRolePermissions()));
      this.afterRoleScopeLeave(() => this.activateRole(nextRole));
    }
  }

  saveAndSwitch(): void {
    const nextRole = this.pendingRoleToSelect;
    const currentRole = this.selectedRole();
    if (!currentRole || !this.canEditPermissions()) return;

    this.isSaving.set(true);
    const pairs: PermissionPair[] = Array.from(this.rolePermissions()).map(p => {
      const parts = p.split('.');
      const action = parts.pop() || '';
      const formCode = parts.join('.');
      return { formCode, action };
    });

    this.api.put(`/rbac/roles/${currentRole.id}/permissions`, pairs).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.originalRolePermissions.set(new Set(this.rolePermissions()));
        this.toast.success(this.uiI18n.translate('iam.matrica_prav_uspeshno_sohranena'));
        this.isDiscardPermissionsModalOpen.set(false);
        this.pendingRoleToSelect = null;
        if (nextRole) {
          this.afterRoleScopeLeave(() => this.activateRole(nextRole));
        }
      },
      error: () => {
        this.isSaving.set(false);
      }
    });
  }

  private activateRole(role: Role): void {
    if (this.destroyRef.destroyed) return;
    this.permissionsRequest?.unsubscribe();
    this.selectedRole.set(role);
    this.rolePermissions.set(new Set());
    this.originalRolePermissions.set(new Set());
    this.loadedPermissionsRoleId.set(null);
    this.permissionsError.set('');
    this.isLoading.set(true);
    this.permissionsRequest = this.api.get<string[]>(`/rbac/roles/${role.id}/permissions`, undefined, { notifyError: false })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: res => {
        if (this.selectedRole()?.id !== role.id) return;
        const perms = new Set(res || []);
        this.rolePermissions.set(new Set(perms));
        this.originalRolePermissions.set(new Set(perms));
        this.loadedPermissionsRoleId.set(role.id);
        this.isLoading.set(false);
      },
      error: error => {
        if (this.selectedRole()?.id !== role.id) return;
        this.permissionsError.set(error.detail || error.title);
        this.isLoading.set(false);
      }
    });
  }

  private afterRoleScopeLeave(action: () => void): void {
    if (this.destroyRef.destroyed) return;
    this.panelLeaveSubscription?.unsubscribe();
    this.panelLeaveSubscription = undefined;
    const decision = this.roleScopePanel?.canLeave() ?? true;
    if (typeof decision === 'boolean') {
      if (decision) action();
      return;
    }
    this.panelLeaveSubscription = decision.subscribe(allow => {
      if (allow && !this.destroyRef.destroyed) action();
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
          isExpanded: true,
          forms: matchingForms
        };
      })
      .filter(mod => mod.forms.length > 0);
  }

  matchingFormsCount(): number {
    return this.visibleModuleGroups().reduce((acc, mod) => acc + mod.forms.length, 0);
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
      'md': this.uiI18n.translate('iam.polzovateli_i_bezopasnost_iam'),
      'iam': this.uiI18n.translate('iam.uchetnye_zapisi_i_profil_iam'),
      'ms.task': this.uiI18n.translate('iam.upravlenie_zadachami_task'),
      'ms.notify': this.uiI18n.translate('iam.opovescheniya_i_sobytiya_notif'),
      'platform': this.uiI18n.translate('iam.sistemnaya_platforma_platform'),
      'audit': this.uiI18n.translate('iam.zhurnal_audita_audit'),
      'mf': this.uiI18n.translate('iam.faylovoe_hranilische_file'),
      'kwh': this.uiI18n.translate('iam.hranilische_dannyh_dwh')
    };
    return map[mod] || this.uiI18n.translate('iam.module_named', { name: mod.toUpperCase() });
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
    if (!this.canEditPermissions()) return;

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
    if (!this.canEditPermissions()) return;

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
    if (!this.canEditPermissions()) return;

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

  toggleReadOnlyModule(moduleGroup: ModuleGroup) {
    if (!this.canEditPermissions()) return;

    const current = new Set(this.rolePermissions());
    for (const f of moduleGroup.forms) {
      for (const act of f.actions) {
        const key = `${f.formCode}.${act.action}`;
        if (act.action === 'view') {
          current.add(key);
        } else {
          current.delete(key);
        }
      }
    }
    this.rolePermissions.set(current);
  }

  isPermissionDirty(formCode: string, action: string): boolean {
    if (this.selectedRole()?.pcode === 'admin') return false;
    const key = `${formCode}.${action}`;
    return this.originalRolePermissions().has(key) !== this.rolePermissions().has(key);
  }

  resetMatrixChanges(): void {
    if (!this.canEditPermissions()) return;
    this.rolePermissions.set(new Set(this.originalRolePermissions()));
  }

  toggleAllPermissions(grant: boolean): void {
    if (!this.canEditPermissions()) return;
    const current = new Set(this.rolePermissions());
    for (const group of this.moduleGroups) {
      for (const f of group.forms) {
        for (const act of f.actions) {
          const key = `${f.formCode}.${act.action}`;
          if (grant) {
            current.add(key);
          } else {
            current.delete(key);
          }
        }
      }
    }
    this.rolePermissions.set(current);
  }

  toggleReadOnlyAllPermissions(): void {
    if (!this.canEditPermissions()) return;
    const current = new Set(this.rolePermissions());
    for (const group of this.moduleGroups) {
      for (const f of group.forms) {
        for (const act of f.actions) {
          const key = `${f.formCode}.${act.action}`;
          if (act.action === 'view') {
            current.add(key);
          } else {
            current.delete(key);
          }
        }
      }
    }
    this.rolePermissions.set(current);
  }

  navigateToUsersWithRole(role: Role, event: Event): void {
    event.stopPropagation();
    this.router?.navigate(['/iam/users'], { queryParams: { roleId: role.id } });
  }

  savePermissions() {
    const role = this.selectedRole();
    if (!role || !this.canEditPermissions()) return;

    this.isSaving.set(true);
    const pairs: PermissionPair[] = Array.from(this.rolePermissions()).map(p => {
      const parts = p.split('.');
      const action = parts.pop() || '';
      const formCode = parts.join('.');
      return { formCode, action };
    });

    this.api.put(`/rbac/roles/${role.id}/permissions`, pairs).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.originalRolePermissions.set(new Set(this.rolePermissions()));
        this.toast.success(this.uiI18n.translate('iam.matrica_prav_uspeshno_sohranena'));
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
      this.toast.warning(this.uiI18n.translate('iam.vvedite_nazvanie_roli'));
      return;
    }

    this.isSubmittingRole.set(true);
    this.api.post<Role>('/rbac/roles', {
      name: this.newRoleForm.name.trim(),
      orderNo: this.newRoleForm.orderNo || 0
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: newRole => {
        this.isSubmittingRole.set(false);
        this.isCreateModalOpen.set(false);
        this.toast.success(this.uiI18n.translate('iam.rol_uspeshno_sozdana'));
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
      this.toast.warning(this.uiI18n.translate('iam.nazvanie_roli_obyazatelno'));
      return;
    }

    this.isSubmittingRole.set(true);
    this.api.patch(`/rbac/roles/${this.editingRole.id}`, this.editRoleForm).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.isSubmittingRole.set(false);
        this.isEditModalOpen.set(false);
        this.toast.success(this.uiI18n.translate('iam.dannye_roli_obnovleny'));
        this.loadRoles();
      },
      error: () => {
        this.isSubmittingRole.set(false);
      }
    });
  }

  openDeleteRoleModal(role: Role) {
    if (this.isSaving() || this.scopePanelBusy() || this.isSubmittingRole() || this.isDeleteModalOpen() || !safeNumericRecordId(role.id)) return;
    this.deletingRole = { ...role };
    this.isDeleteModalOpen.set(true);
  }

  confirmDeleteRole() {
    const target = this.deletingRole;
    if (!target || this.isSaving() || this.scopePanelBusy() || this.isSubmittingRole() || !safeNumericRecordId(target.id)) return;
    if (this.selectedRole()?.id === target.id) {
      this.afterRoleScopeLeave(() => this.deleteRole(target));
      return;
    }
    this.deleteRole(target);
  }

  closeDeleteRoleModal(): void {
    if (this.isSubmittingRole()) return;
    this.isDeleteModalOpen.set(false);
    this.deletingRole = null;
  }

  private deleteRole(target: Role): void {
    if (this.destroyRef.destroyed || this.isSubmittingRole() || !this.isDeleteModalOpen()) return;
    this.isSubmittingRole.set(true);
    this.api.delete(`/rbac/roles/${target.id}`).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.isSubmittingRole.set(false);
        this.isDeleteModalOpen.set(false);
        this.deletingRole = null;
        this.toast.success(this.uiI18n.translate('iam.rol_udalena'));
        if (this.selectedRole()?.id === target.id) {
          this.permissionsRequest?.unsubscribe();
          this.selectedRole.set(null);
          this.rolePermissions.set(new Set());
          this.loadedPermissionsRoleId.set(null);
          this.permissionsError.set('');
          this.isLoading.set(false);
        }
        this.loadRoles();
      },
      error: () => {
        this.isSubmittingRole.set(false);
      }
    });
  }
}
