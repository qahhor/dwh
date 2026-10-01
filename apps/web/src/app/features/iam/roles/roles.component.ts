import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  signal,
  inject,
  linkedSignal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';

import { Observable, Subscription, catchError, map, of, tap } from 'rxjs';
import { lastLoaded } from '@features/iam/last-loaded';
import { RolesApi } from '@core/services/roles.api';
import { PermissionService } from '@core/services/permission.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { Role, FormTreeItem } from '@core/models/rbac.models';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { safeNumericRecordId } from '@core/services/search-target';
import { RoleScopePanelComponent } from '../org-units/public-api';
import { RoleModalsComponent } from './components/role-modals.component';
import { RoleCardsBarComponent } from './components/role-cards-bar.component';
import { RolePermissionsMatrixComponent } from './components/role-permissions-matrix.component';
import {
  ModuleGroup,
  MODULE_ICON_MAP,
  MODULE_NAME_KEY_MAP,
  buildModuleGroups,
  filterRoles,
  filterModuleGroups,
} from './roles.models';
import { RoleFormsService } from './services/role-forms.service';
import { RolePermissionsEditor } from './services/role-permissions-editor.service';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

@Component({
  selector: 'app-roles',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    UiPageHeaderComponent,
    TranslatePipe,
    SMTButtonComponent,
    RoleScopePanelComponent,
    RoleModalsComponent,
    RoleCardsBarComponent,
    RolePermissionsMatrixComponent,
  ],
  providers: [RolePermissionsEditor],
  templateUrl: './roles.component.html',
  styleUrl: './roles.component.css',
})
export class RolesComponent implements OnInit {
  permService = inject(PermissionService);

  readonly roleForms = inject(RoleFormsService);
  readonly matrix = inject(RolePermissionsEditor);
  private rolesApi = inject(RolesApi);
  private readonly router = inject(Router, { optional: true });
  private readonly uiI18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly scopePanel = viewChild(RoleScopePanelComponent);

  readonly roles = signal<Role[]>([]);
  /** The scope panel's unsaved or saving state; a panel that goes away (another role, no right) is not busy. */
  readonly scopePanelBusy = linkedSignal({ source: this.scopePanel, computation: () => false });
  readonly isDiscardPermissionsModalOpen = signal<boolean>(false);
  private readonly pendingRoleToSelect = signal<Role | null>(null);

  // Arrow fields: the matrix takes them as inputs, so they keep one identity.
  readonly getModuleIcon = (mod: string): string => MODULE_ICON_MAP[mod] || 'folder';
  readonly getModuleActionsCount = (mod: ModuleGroup): number =>
    mod.forms.reduce((sum, f) => sum + f.actions.length, 0);
  private panelLeaveSubscription?: Subscription;
  readonly safeRoleId = safeNumericRecordId;

  roleSearchQuery = '';
  matrixSearchQuery = '';
  selectedModuleTab = 'all';

  moduleGroups: ModuleGroup[] = [];

  /** Every form action, asked once; the matrix groups them by module (the groups keep their open state). */
  readonly forms = toSignal(
    this.rolesApi.forms().pipe(
      map((res) => res || []),
      catchError(() => of<FormTreeItem[]>([])),
      tap((items) => this.buildModuleGroups(items)),
    ),
    { initialValue: [] },
  );
  /** How many users hold each role; asked again with the role list. */
  private readonly userCountsRead = rxResource({
    stream: () =>
      this.rolesApi.userCounts().pipe(
        map((counts) => counts || {}),
        catchError(() => of(null)),
      ),
  });
  readonly roleUserCounts = lastLoaded<Record<number, number>>(() => this.userCountsRead.value(), {});

  constructor() {
    this.destroyRef.onDestroy(() => this.panelLeaveSubscription?.unsubscribe());
  }

  ngOnInit() {
    this.loadRoles();
  }

  canCreateRole(): boolean {
    return (
      this.permService.canCreate('rbac.roles') ||
      this.permService.canCreate('iam.roles') ||
      this.permService.canCreate('md_roles')
    );
  }

  canUpdateRole(): boolean {
    return (
      this.permService.canUpdate('rbac.roles') ||
      this.permService.canUpdate('iam.roles') ||
      this.permService.canUpdate('md_roles')
    );
  }

  canDeleteRole(): boolean {
    return (
      this.permService.canDelete('rbac.roles') ||
      this.permService.canDelete('iam.roles') ||
      this.permService.canDelete('md_roles')
    );
  }

  canViewOrgUnits(): boolean {
    return (
      this.permService.hasPermission('iam.org_units', 'view') ||
      this.permService.hasPermission('iam.org_units', 'assign') ||
      this.scopePanelBusy()
    );
  }

  canLeaveRecordPage(): boolean | Observable<boolean> {
    if (this.matrix.isSaving() || this.matrix.isPermissionsDirty()) return false;
    return this.scopePanel()?.canLeave() ?? true;
  }

  /**
   * Reads the roles; the selected one is taken from the answer, so its revision is the list's (plan item 3.6).
   * `reloadMatrix` also reads its matrix again, dropping the draft, as after a save refused over a newer revision.
   */
  loadRoles(reloadMatrix = false) {
    this.loadRoleUserCounts();
    this.rolesApi
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          const list = res || [];
          this.roles.set(list);
          const selected = this.matrix.selectedRole();
          const fresh = selected ? list.find((role) => role.id === selected.id) : undefined;
          if (fresh && reloadMatrix) this.matrix.load(fresh);
          else if (fresh) this.matrix.refreshSelected(fresh);
          if (!selected && list.length > 0) {
            this.selectRole(list[0]);
          }
        },
      });
  }

  /** Saves the matrix and keeps the role list's copy of the role at the revision the save gave it. */
  savePermissions(onSaved?: () => void): void {
    this.matrix.savePermissions(
      (role) => {
        this.replaceRole(role);
        onSaved?.();
      },
      () => this.loadRoles(true),
    );
  }

  /** A save of the scope rule raised the role's revision: the list and the matrix name the new one (plan item 3.6). */
  onScopeRevision(role: Role, revision: number): void {
    const saved = { ...role, revision };
    this.replaceRole(saved);
    this.matrix.refreshSelected(saved);
  }

  loadRoleUserCounts() {
    this.userCountsRead.reload();
  }

  buildModuleGroups(items: FormTreeItem[]) {
    this.moduleGroups = buildModuleGroups(items, (mod) => this.getModuleDisplayName(mod));
  }

  /** Switching roles asks about an unsaved matrix and waits for the scope panel to let go. */
  selectRole(role: Role) {
    if (this.matrix.isSaving() || this.roleForms.isSubmittingRole() || this.destroyRef.destroyed) return;
    const selected = this.matrix.selectedRole();
    if (this.scopePanelBusy() && selected?.id !== role.id) return;
    if (!selected || selected.id === role.id) {
      this.matrix.load(role);
      return;
    }
    if (this.matrix.isPermissionsDirty()) {
      this.pendingRoleToSelect.set(role);
      this.isDiscardPermissionsModalOpen.set(true);
      return;
    }
    this.afterRoleScopeLeave(() => this.matrix.load(role));
  }

  closeDiscardModal(): void {
    if (this.matrix.isSaving()) return;
    this.pendingRoleToSelect.set(null);
    this.isDiscardPermissionsModalOpen.set(false);
  }

  confirmDiscardAndSwitch(): void {
    if (this.matrix.isSaving()) return;
    const nextRole = this.pendingRoleToSelect();
    this.isDiscardPermissionsModalOpen.set(false);
    this.pendingRoleToSelect.set(null);
    if (nextRole) {
      this.matrix.discardChanges();
      this.afterRoleScopeLeave(() => this.matrix.load(nextRole));
    }
  }

  saveAndSwitch(): void {
    const nextRole = this.pendingRoleToSelect();
    this.savePermissions(() => {
      this.isDiscardPermissionsModalOpen.set(false);
      this.pendingRoleToSelect.set(null);
      if (nextRole) {
        this.afterRoleScopeLeave(() => this.matrix.load(nextRole));
      }
    });
  }

  filteredRoles(): Role[] {
    return filterRoles(this.roles(), this.roleSearchQuery);
  }

  visibleModuleGroups(): ModuleGroup[] {
    return filterModuleGroups(this.moduleGroups, this.matrixSearchQuery, this.selectedModuleTab);
  }

  matchingFormsCount(): number {
    return this.visibleModuleGroups().reduce((acc, mod) => acc + mod.forms.length, 0);
  }

  setAllModulesExpanded(expanded: boolean) {
    this.moduleGroups.forEach((mod) => (mod.isExpanded = expanded));
  }

  toggleModuleExpand(mod: ModuleGroup) {
    mod.isExpanded = !mod.isExpanded;
  }

  getModuleDisplayName(mod: string): string {
    const key = MODULE_NAME_KEY_MAP[mod];
    return key ? this.uiI18n.translate(key) : this.uiI18n.translate('iam.module_named', { name: mod.toUpperCase() });
  }

  totalActionsCount(): number {
    return this.forms().length;
  }

  activePermissionsCount(): number {
    return this.matrix.selectedRole()?.pcode === 'admin'
      ? this.totalActionsCount()
      : this.matrix.rolePermissions().size;
  }

  permissionPercentage(): number {
    const total = this.totalActionsCount();
    return total === 0 ? 0 : Math.round((this.activePermissionsCount() / total) * 100);
  }

  toggleAllPermissions(grant: boolean): void {
    this.matrix.toggleAllPermissions(this.moduleGroups, grant);
  }

  toggleReadOnlyAllPermissions(): void {
    this.matrix.toggleReadOnlyAllPermissions(this.moduleGroups);
  }

  navigateToUsersWithRole(role: Role, event: Event): void {
    event.stopPropagation();
    this.router?.navigate(['/iam/users'], { queryParams: { roleId: role.id } });
  }

  submitCreateRole() {
    this.roleForms.submitCreateRole((newRole) => {
      this.loadRoles();
      this.selectRole(newRole);
    });
  }

  submitEditRole() {
    this.roleForms.submitEditRole(
      () => this.loadRoles(),
      () => this.loadRoles(true),
    );
  }

  openDeleteRoleModal(role: Role) {
    this.roleForms.openDeleteRoleModal(role, this.matrix.isSaving(), this.scopePanelBusy());
  }

  confirmDeleteRole() {
    const target = this.roleForms.deletingRole;
    if (
      !target ||
      this.matrix.isSaving() ||
      this.scopePanelBusy() ||
      this.roleForms.isSubmittingRole() ||
      !safeNumericRecordId(target.id)
    )
      return;
    if (this.matrix.selectedRole()?.id === target.id) {
      this.afterRoleScopeLeave(() => this.deleteRole(target));
      return;
    }
    this.deleteRole(target);
  }

  private replaceRole(role: Role): void {
    this.roles.update((list) => list.map((item) => (item.id === role.id ? role : item)));
  }

  private afterRoleScopeLeave(action: () => void): void {
    if (this.destroyRef.destroyed) return;
    this.panelLeaveSubscription?.unsubscribe();
    this.panelLeaveSubscription = undefined;
    const decision = this.scopePanel()?.canLeave() ?? true;
    if (typeof decision === 'boolean') {
      if (decision) action();
      return;
    }
    this.panelLeaveSubscription = decision.subscribe((allow) => {
      if (allow && !this.destroyRef.destroyed) action();
    });
  }

  private deleteRole(target: Role): void {
    if (this.destroyRef.destroyed || this.roleForms.isSubmittingRole() || !this.roleForms.isDeleteModalOpen()) return;
    this.roleForms.deleteRole(target, () => {
      this.matrix.clearIfSelected(target.id);
      this.loadRoles();
    });
  }
}
