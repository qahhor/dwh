import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { CpApiService } from './cp-api.service';

/**
 * Пускает в панель только при живой сессии. Cookie httpOnly из JS не видна,
 * поэтому наличие сессии проверяем запросом /auth/me — он же восстанавливает
 * пользователя после перезагрузки страницы.
 */
export const authGuard: CanActivateFn = async () => {
  const api = inject(CpApiService);
  const router = inject(Router);

  if (api.user()) {
    return true;
  }
  const user = await api.restoreSession();
  return user ? true : router.createUrlTree(['/login']);
};
