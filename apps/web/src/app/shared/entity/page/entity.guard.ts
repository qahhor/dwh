import { EnvironmentInjector, inject, runInInjectionContext } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { catchError, from, isObservable, map, of, switchMap } from 'rxjs';
import { moduleActiveGuard } from '@core/guards/module-active.guard';
import { NavigationService } from '@core/services/navigation.service';

/**
 * Lets `/e/:code` open only while the entity's installed module is on (ADR-0032 7.1): the module is the one its menu
 * item names (`GET /entities/menu`). An entity without a menu item, or one the viewer may not open, goes on to the
 * page, which shows "not found" from the server's 404 — rights are the server's, never the screen's.
 */
export const entityGuard: CanActivateFn = (route, state) => {
  const navigation = inject(NavigationService);
  const injector = inject(EnvironmentInjector);
  const code = route.paramMap.get('code') ?? '';
  const known = navigation.entityItems();
  const items = known.length > 0 ? of(known) : navigation.loadEntityItems().pipe(catchError(() => of([])));
  return items.pipe(
    map((list) => list.find((item) => item.code === code)?.module ?? null),
    switchMap((module) => {
      if (!module) return of(true);
      const check = runInInjectionContext(injector, () => moduleActiveGuard(module)(route, state));
      if (isObservable(check)) return check;
      return check instanceof Promise ? from(check) : of(check);
    }),
  );
};
