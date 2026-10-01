import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { LEGACY_FORM_CODES, PermissionService } from './permission.service';

describe('PermissionService', () => {
  function service(permissions: string[]): PermissionService {
    TestBed.configureTestingModule({ providers: [PermissionService] });
    const permissionService = TestBed.inject(PermissionService);
    permissionService.setPermissions(permissions);
    return permissionService;
  }

  it('checks form codes of the module rule (ADR-0028)', () => {
    const permissions = service(['md.users.view', 'webhook.subscriptions.*', 'search.view']);

    expect(permissions.canView('md.users')).toBe(true);
    expect(permissions.canCreate('md.users')).toBe(false);
    expect(permissions.canManage('webhook.subscriptions')).toBe(true);
    expect(permissions.hasPermissionKey('search.view')).toBe(true);
    expect(permissions.hasPermissionKey('search')).toBe(false);
  });

  it('still honours a permission set of the previous release until the sunset', () => {
    const permissions = service(['iam.org_units.assign', 'platform.settings.*']);

    expect(permissions.hasPermission('md.org_units', 'assign')).toBe(true);
    expect(permissions.hasPermission('md.org_units', 'view')).toBe(false);
    expect(permissions.hasPermission('md.settings', 'update')).toBe(true);
  });

  it('does not treat an old code as current', () => {
    const permissions = service(['md.users.view']);

    expect(permissions.canView('iam.users')).toBe(false);
    expect(Object.values(LEGACY_FORM_CODES)).not.toContain('md.users');
  });

  it('lets the administrator wildcard open everything', () => {
    expect(service(['*.*']).hasPermission('upl.sources', 'publish')).toBe(true);
  });
});
