import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, CanActivateFn, Router, RouterStateSnapshot } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { PermissionService } from './core/services/permission.service';
import { recordNavigationGuard } from './core/guards/record-navigation.guard';
import { routes } from './app.routes';

describe('application route permissions', () => {
  let permissions: PermissionService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PermissionService, { provide: Router, useValue: { createUrlTree: () => 'settings-redirect' } }],
    });
    permissions = TestBed.inject(PermissionService);
  });

  it('prevents navigation to audit without audit.log view permission', () => {
    permissions.setPermissions([]);

    expect(runAuditGuard()).toBe('settings-redirect');
  });

  it('allows navigation to audit with audit.log view permission', () => {
    permissions.setPermissions(['audit.log.view']);

    expect(runAuditGuard()).toBe(true);
  });

  it('exposes the organization page only for view permission and protects every IAM record host', () => {
    permissions.setPermissions([]);
    expect(runOrgUnitsGuard()).toBe('settings-redirect');

    permissions.setPermissions(['md.org_units.assign']);
    expect(runOrgUnitsGuard()).toBe('settings-redirect');

    permissions.setPermissions(['md.org_units.view']);
    expect(runOrgUnitsGuard()).toBe(true);

    const shell = routes.find((route) => route.path === '');
    const rolesRoute = shell?.children?.find((route) => route.path === 'iam/roles');
    const orgUnits = shell?.children?.find((route) => route.path === 'iam/org-units');
    expect(rolesRoute?.canDeactivate).toContain(recordNavigationGuard);
    expect(orgUnits?.canDeactivate).toContain(recordNavigationGuard);
  });

  it.each(['settings/form-studio', 'settings/form-studio/new'])(
    'requires authoring permission for %s and protects the unsaved declaration',
    (path) => {
      const studio = routes.find((route) => route.path === '')?.children?.find((route) => route.path === path);
      const guard = studio?.canActivate?.[0] as CanActivateFn;
      expect(guard).toBeTypeOf('function');
      const run = () =>
        TestBed.runInInjectionContext(() => guard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot));
      permissions.setPermissions(['md.settings.view']);
      expect(run()).toBe('settings-redirect');
      permissions.setPermissions(['md.settings.update']);
      expect(run()).toBe(true);
      expect(studio?.canDeactivate).toContain(recordNavigationGuard);
    },
  );

  it('protects unsaved named dashboard changes when leaving analytics', () => {
    const analytics = routes.find((route) => route.path === '')?.children?.find((route) => route.path === 'analytics');
    expect(analytics?.canDeactivate).toEqual([recordNavigationGuard]);
  });

  function runAuditGuard(): unknown {
    const shell = routes.find((route) => route.path === '');
    const audit = shell?.children?.find((route) => route.path === 'audit');
    const guard = audit?.canActivate?.[0] as CanActivateFn | undefined;
    expect(guard).toBeTypeOf('function');
    return TestBed.runInInjectionContext(() => guard!({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot));
  }

  function runOrgUnitsGuard(): unknown {
    const shell = routes.find((route) => route.path === '');
    const orgUnits = shell?.children?.find((route) => route.path === 'iam/org-units');
    const guard = orgUnits?.canActivate?.[0] as CanActivateFn | undefined;
    expect(guard).toBeTypeOf('function');
    return TestBed.runInInjectionContext(() => guard!({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot));
  }
});
