import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { PermissionPair, Role } from '@core/models/rbac.models';
import { RolesApi } from '@core/services/roles.api';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { I18nService } from '@core/services/i18n.service';
import {
  GroupedForm,
  ModuleGroup,
  arePermissionsDirty,
  countDirtyPermissions,
  toggleFormPermissionSet,
  toggleModulePermissionSet,
  toggleReadOnlyModulePermissionSet,
  toggleAllPermissionsSet,
  toggleReadOnlyAllPermissionsSet,
} from '../roles.models';

/** `form.code.action` keys back into the pairs the server stores; the action is the last segment. */
function toPermissionPairs(keys: Set<string>): PermissionPair[] {
  return Array.from(keys).map((key) => {
    const parts = key.split('.');
    const action = parts.pop() || '';
    return { formCode: parts.join('.'), action };
  });
}

/**
 * The selected role's permission matrix: loading it, the unsaved draft and saving it.
 * Provided by the roles screen, so a late answer after leaving the screen changes nothing.
 */
@Injectable()
export class RolePermissionsEditor {
  private readonly rolesApi = inject(RolesApi);
  private readonly permService = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  readonly selectedRole = signal<Role | null>(null);
  readonly rolePermissions = signal<Set<string>>(new Set());
  readonly originalRolePermissions = signal<Set<string>>(new Set());
  readonly isLoading = signal<boolean>(false);
  readonly permissionsError = signal('');
  readonly isSaving = signal<boolean>(false);
  private readonly loadedPermissionsRoleId = signal<number | null>(null);

  readonly isPermissionsDirty = computed<boolean>(() =>
    arePermissionsDirty(this.originalRolePermissions(), this.rolePermissions()),
  );

  readonly dirtyPermissionsCount = computed<number>(() =>
    countDirtyPermissions(this.originalRolePermissions(), this.rolePermissions()),
  );

  // Arrow fields: the matrix takes them as inputs, so they keep one identity.
  readonly hasPermission = (formCode: string, action: string): boolean =>
    this.selectedRole()?.pcode === 'admin' || this.rolePermissions().has(`${formCode}.${action}`);

  readonly isPermissionDirty = (formCode: string, action: string): boolean => {
    if (this.selectedRole()?.pcode === 'admin') return false;
    const key = `${formCode}.${action}`;
    return this.originalRolePermissions().has(key) !== this.rolePermissions().has(key);
  };

  private permissionsRequest?: Subscription;

  canGrant(): boolean {
    return (
      this.permService.hasPermission('rbac.roles', 'grant') || this.permService.hasPermission('iam.roles', 'grant')
    );
  }

  /** Only the matrix loaded for the selected role is editable, never while it loads or saves. */
  canEditPermissions(): boolean {
    const role = this.selectedRole();
    return (
      !!role &&
      role.pcode !== 'admin' &&
      this.canGrant() &&
      this.loadedPermissionsRoleId() === role.id &&
      !this.isLoading() &&
      !this.isSaving()
    );
  }

  /** Selects the role and loads its matrix; an answer for a role no longer selected is dropped. */
  load(role: Role): void {
    if (this.destroyRef.destroyed) return;
    this.permissionsRequest?.unsubscribe();
    this.selectedRole.set(role);
    this.rolePermissions.set(new Set());
    this.originalRolePermissions.set(new Set());
    this.loadedPermissionsRoleId.set(null);
    this.permissionsError.set('');
    this.isLoading.set(true);
    this.permissionsRequest = this.rolesApi
      .permissions(role.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          if (this.selectedRole()?.id !== role.id) return;
          const perms = new Set(res || []);
          this.rolePermissions.set(new Set(perms));
          this.originalRolePermissions.set(new Set(perms));
          this.loadedPermissionsRoleId.set(role.id);
          this.isLoading.set(false);
        },
        error: (error) => {
          if (this.selectedRole()?.id !== role.id) return;
          this.permissionsError.set(error.detail || error.title);
          this.isLoading.set(false);
        },
      });
  }

  /** Forgets the selection when that role is gone. */
  clearIfSelected(roleId: number): void {
    if (this.selectedRole()?.id !== roleId) return;
    this.permissionsRequest?.unsubscribe();
    this.selectedRole.set(null);
    this.rolePermissions.set(new Set());
    this.loadedPermissionsRoleId.set(null);
    this.permissionsError.set('');
    this.isLoading.set(false);
  }

  togglePermission(formCode: string, action: string, checked: boolean): void {
    if (!this.canEditPermissions()) return;
    const current = new Set(this.rolePermissions());
    const key = `${formCode}.${action}`;
    if (checked) {
      current.add(key);
    } else {
      current.delete(key);
    }
    this.rolePermissions.set(current);
  }

  toggleAllForm(form: GroupedForm, grant: boolean): void {
    if (!this.canEditPermissions()) return;
    this.rolePermissions.set(toggleFormPermissionSet(this.rolePermissions(), form, grant));
  }

  toggleAllModule(moduleGroup: ModuleGroup, grant: boolean): void {
    if (!this.canEditPermissions()) return;
    this.rolePermissions.set(toggleModulePermissionSet(this.rolePermissions(), moduleGroup, grant));
  }

  toggleReadOnlyModule(moduleGroup: ModuleGroup): void {
    if (!this.canEditPermissions()) return;
    this.rolePermissions.set(toggleReadOnlyModulePermissionSet(this.rolePermissions(), moduleGroup));
  }

  toggleAllPermissions(moduleGroups: ModuleGroup[], grant: boolean): void {
    if (!this.canEditPermissions()) return;
    this.rolePermissions.set(toggleAllPermissionsSet(this.rolePermissions(), moduleGroups, grant));
  }

  toggleReadOnlyAllPermissions(moduleGroups: ModuleGroup[]): void {
    if (!this.canEditPermissions()) return;
    this.rolePermissions.set(toggleReadOnlyAllPermissionsSet(this.rolePermissions(), moduleGroups));
  }

  resetMatrixChanges(): void {
    if (!this.canEditPermissions()) return;
    this.discardChanges();
  }

  /** Drops the draft without the edit check: leaving a role discards it either way. */
  discardChanges(): void {
    this.rolePermissions.set(new Set(this.originalRolePermissions()));
  }

  /** Saves the draft; `onSaved` runs after the success toast, a failure keeps the draft for a retry. */
  savePermissions(onSaved?: () => void): void {
    const role = this.selectedRole();
    if (!role || !this.canEditPermissions()) return;

    this.isSaving.set(true);
    this.rolesApi
      .savePermissions(role.id, toPermissionPairs(this.rolePermissions()))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.isSaving.set(false);
          this.originalRolePermissions.set(new Set(this.rolePermissions()));
          this.toast.success(this.uiI18n.translate('iam.matrica_prav_uspeshno_sohranena'));
          onSaved?.();
        },
        error: () => {
          this.isSaving.set(false);
        },
      });
  }
}
