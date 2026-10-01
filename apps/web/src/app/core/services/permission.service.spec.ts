import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { PermissionService } from './permission.service';

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

  it('opens nothing for a form code of the previous release (ADR-0028)', () => {
    const permissions = service(['iam.org_units.assign', 'platform.settings.*']);

    expect(permissions.hasPermission('md.org_units', 'assign')).toBe(false);
    expect(permissions.hasPermission('md.settings', 'update')).toBe(false);
  });

  it('does not treat an old code as current', () => {
    const permissions = service(['md.users.view']);

    expect(permissions.canView('iam.users')).toBe(false);
  });

  it('lets the administrator wildcard open everything', () => {
    expect(service(['*.*']).hasPermission('upl.sources', 'publish')).toBe(true);
  });
});
