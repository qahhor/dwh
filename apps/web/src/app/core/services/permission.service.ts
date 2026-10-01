import { inject, Injectable, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { MetaCacheState } from './meta-cache';

/**
 * Form codes of the previous release by their successors (ADR-0028). A permission set loaded before the server moved
 * to the new codes still opens the screens until the sunset of the old codes (2026-12-31); then this table goes.
 */
export const LEGACY_FORM_CODES: Readonly<Record<string, string>> = {
  'md.users': 'iam.users',
  'md.profile': 'iam.profile',
  'md.org_units': 'iam.org_units',
  'md.roles': 'rbac.roles',
  'md.assignments': 'rbac.assignments',
  'md.settings': 'platform.settings',
  'md.navigation': 'platform.navigation',
  'md.modules': 'platform.modules',
  'notify.announcements': 'platform.announcements',
  'mf.files': 'platform.files',
  search: 'platform.search',
  'webhook.subscriptions': 'platform.webhooks',
};

@Injectable({
  providedIn: 'root',
})
export class PermissionService {
  private readonly metaCache = inject(MetaCacheState);

  readonly permissions = signal<Set<string>>(new Set());
  readonly permissionVersion = signal<number>(1);

  setPermissions(perms: string[], version: number = 1) {
    const next = new Set(perms);
    this.changed(next, version);
    this.permissions.set(next);
    this.permissionVersion.set(version);
  }

  clear() {
    this.changed(new Set(), 1);
    this.permissions.set(new Set());
    this.permissionVersion.set(1);
  }

  hasPermission(form: string, action: string): boolean {
    const perms = this.permissions();
    if (perms.has('*.*')) {
      return true;
    }
    if (perms.has(`${form}.${action}`) || perms.has(`${form}.*`)) {
      return true;
    }
    const legacy = LEGACY_FORM_CODES[form];
    return legacy !== undefined && (perms.has(`${legacy}.${action}`) || perms.has(`${legacy}.*`));
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

  /** Other rights show other actions and fields: list and form descriptions are read again (plan 10/10, item 5.0). */
  private changed(next: ReadonlySet<string>, version: number): void {
    const now = this.permissions();
    if (version !== this.permissionVersion() || next.size !== now.size || [...next].some((key) => !now.has(key))) {
      this.metaCache.invalidate();
    }
  }
}

export function permissionGuard(form: string, action: string = 'view'): CanActivateFn {
  return () => {
    const permissions = inject(PermissionService);
    const router = inject(Router);
    return permissions.hasPermission(form, action) ? true : router.createUrlTree(['/settings']);
  };
}
