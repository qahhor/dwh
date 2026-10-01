import { DestroyRef, Injectable, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, map, of } from 'rxjs';
import { ProblemDetail } from '@core/models/common.models';
import { PermissionPair, Role } from '@core/models/rbac.models';
import { RolesApi } from '@core/services/roles.api';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { I18nService } from '@core/services/i18n.service';
import { SaveErrorNotifier } from '@shared/ui/save-errors';
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
  private readonly saveErrors = inject(SaveErrorNotifier);

  readonly selectedRole = signal<Role | null>(null);
  readonly isSaving = signal<boolean>(false);

  /* The loaded matrix is the saved state and the start of the draft; a new answer (or none,
     while another role loads) replaces both, so a draft never outlives its role. */
  readonly originalRolePermissions = linkedSignal(() => new Set(this.loadedMatrix()?.perms));
  readonly rolePermissions = linkedSignal(() => new Set(this.loadedMatrix()?.perms));
  /** The role whose matrix is asked; a new object asks again for the same role. */
  private readonly matrixOf = signal<{ roleId: number } | undefined>(undefined);
  private readonly loadedPermissionsRoleId = linkedSignal(() => this.loadedMatrix()?.roleId ?? null);

  readonly isLoading = computed(() => this.matrixRead.isLoading());
  readonly permissionsError = computed(() => {
    const answer = this.matrixRead.value();
    return answer && 'error' in answer ? answer.error : '';
  });

  readonly isPermissionsDirty = computed<boolean>(() =>
    arePermissionsDirty(this.originalRolePermissions(), this.rolePermissions()),
  );

  readonly dirtyPermissionsCount = computed<number>(() =>
    countDirtyPermissions(this.originalRolePermissions(), this.rolePermissions()),
  );
  private readonly loadedMatrix = computed(() => {
    const answer = this.matrixRead.value();
    return answer && 'perms' in answer ? answer : undefined;
  });

  // Arrow fields: the matrix takes them as inputs, so they keep one identity.
  readonly hasPermission = (formCode: string, action: string): boolean =>
    this.selectedRole()?.pcode === 'admin' || this.rolePermissions().has(`${formCode}.${action}`);

  readonly isPermissionDirty = (formCode: string, action: string): boolean => {
    if (this.selectedRole()?.pcode === 'admin') return false;
    const key = `${formCode}.${action}`;
    return this.originalRolePermissions().has(key) !== this.rolePermissions().has(key);
  };

  /** Asking for another role cancels the answer still due for the previous one. */
  private readonly matrixRead = rxResource({
    params: () => this.matrixOf(),
    stream: ({ params }) =>
      this.rolesApi.permissions(params.roleId).pipe(
        map((res) => ({ roleId: params.roleId, perms: res || [] })),
        catchError((error: Partial<ProblemDetail>) => of({ error: error.detail || error.title || '' })),
      ),
  });

  canGrant(): boolean {
    return this.permService.hasPermission('md.roles', 'grant');
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
    this.selectedRole.set(role);
    this.matrixOf.set({ roleId: role.id });
  }

  /**
   * Takes the selected role as the role list read it again (a new name or revision), keeping its matrix and draft:
   * the next save names the revision the list has (plan item 3.6).
   */
  refreshSelected(role: Role): void {
    if (this.selectedRole()?.id === role.id) this.selectedRole.set(role);
  }

  /** Forgets the selection, and its matrix and draft, when that role is gone. */
  clearIfSelected(roleId: number): void {
    if (this.selectedRole()?.id !== roleId) return;
    this.selectedRole.set(null);
    this.matrixOf.set(undefined);
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

  /**
   * Saves the draft; `onSaved` gets the role with its new revision after the success toast, so the screen keeps its
   * list in step. A failure keeps the draft for a retry; one over a newer revision offers `onConflict` (read again).
   */
  savePermissions(onSaved?: (role: Role) => void, onConflict?: () => void): void {
    const role = this.selectedRole();
    if (!role || !this.canEditPermissions()) return;

    this.isSaving.set(true);
    this.rolesApi
      .savePermissions(role.id, toPermissionPairs(this.rolePermissions()), role.revision)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.isSaving.set(false);
          // The save raised the role's revision by one: the next save of it names the new one (plan item 3.6).
          const saved = role.revision !== undefined ? { ...role, revision: role.revision + 1 } : role;
          this.selectedRole.set(saved);
          this.originalRolePermissions.set(new Set(this.rolePermissions()));
          this.toast.success(this.uiI18n.translate('iam.roles.matrix.saved'));
          onSaved?.(saved);
        },
        error: (err: unknown) => {
          this.isSaving.set(false);
          this.saveErrors.show(err, { fallbackKey: 'iam.common.save_permissions_failed', reload: onConflict });
        },
      });
  }
}
