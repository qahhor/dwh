import { Injectable, signal } from '@angular/core';

@Injectable({
  providedIn: 'root'
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
    return perms.has(`${form}.${action}`);
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
}
