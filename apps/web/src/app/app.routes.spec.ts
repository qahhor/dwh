import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, CanActivateFn, Router, RouterStateSnapshot } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { PermissionService } from './core/services/permission.service';
import { routes } from './app.routes';

describe('application route permissions', () => {
  let permissions: PermissionService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        PermissionService,
        { provide: Router, useValue: { createUrlTree: () => 'settings-redirect' } }
      ]
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

  function runAuditGuard(): unknown {
    const shell = routes.find(route => route.path === '');
    const audit = shell?.children?.find(route => route.path === 'audit');
    const guard = audit?.canActivate?.[0] as CanActivateFn | undefined;
    expect(guard).toBeTypeOf('function');
    return TestBed.runInInjectionContext(() => guard!(
      {} as ActivatedRouteSnapshot,
      {} as RouterStateSnapshot
    ));
  }
});
