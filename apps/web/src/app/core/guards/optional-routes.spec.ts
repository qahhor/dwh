import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, CanActivateFn, provideRouter, RouterStateSnapshot, UrlTree } from '@angular/router';
import { firstValueFrom, isObservable } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { routes } from '@app/app.routes';
import { ModuleService } from '../services/module.service';
import { ToastService } from '../services/toast.service';
import { projectRecordMatcher, taskRecordMatcher } from '../services/search-target';

describe('optional application routes', () => {
  for (const matcher of [taskRecordMatcher, projectRecordMatcher]) {
    it(`gates ${matcher.name} before loading a disabled task screen`, async () => {
      TestBed.configureTestingModule({
        providers: [
          provideRouter([]),
          { provide: ModuleService, useValue: { isLoaded: () => true, isModuleActive: () => false } },
          { provide: ToastService, useValue: { warning: () => {} } },
        ],
      });
      const route = routes.find((entry) => entry.path === '')?.children?.find((entry) => entry.matcher === matcher);
      const guard = route?.canActivate?.[0] as CanActivateFn | undefined;
      expect(guard).toBeTypeOf('function');
      const result = TestBed.runInInjectionContext(() =>
        guard!({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
      );
      const value = isObservable(result) ? await firstValueFrom(result) : await result;
      expect(value).toBeInstanceOf(UrlTree);
      expect((value as UrlTree).toString()).toBe('/iam/profile');
    });
  }
});
