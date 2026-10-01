import { inject, Injectable, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

@Injectable({
  providedIn: 'root',
})
export class PermissionService {
  readonly permissions = signal<Set<string>>(new Set());
  readonly permissionVersion = signal<number>(1);

  setPermissions(perms: string[], version: number = 1) {
    this.permissions.set(new Set(perms));
    this.permissionVersion.set(version);
  }

  clear() {
    this.permissions.set(new Set());
    this.permissionVersion.set(1);
  }

  hasPermission(form: string, action: string): boolean {
    const perms = this.permissions();
    if (perms.has('*.*')) {
      return true;
    }
    return perms.has(`${form}.${action}`) || perms.has(`${form}.*`);
  }

  /** Checks a `form.action` pair written as one key, e.g. `md.navigation.manage`. */
  hasPermissionKey(key: string): boolean {
    const dot = key.lastIndexOf('.');
    return dot > 0 && dot < key.length - 1 && this.hasPermission(key.slice(0, dot), key.slice(dot + 1));
  }

  canView(form: string): boolean {
    return this.hasPermission(form, 'view');
  }

  canCreate(form: string): boolean {
    return this.hasPermission(form, 'create');
  }

  canUpdate(form: string): boolean {
    return this.hasPermission(form, 'update');
  }

  canDelete(form: string): boolean {
    return this.hasPermission(form, 'delete');
  }

  canManage(form: string): boolean {
    return this.hasPermission(form, 'manage');
  }
}

export function permissionGuard(form: string, action: string = 'view'): CanActivateFn {
  return () => {
    const permissions = inject(PermissionService);
    const router = inject(Router);
    return permissions.hasPermission(form, action) ? true : router.createUrlTree(['/settings']);
  };
}
