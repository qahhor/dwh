import { inject } from '@angular/core';
import { Router, CanActivateFn } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { map } from 'rxjs';

export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (authService.currentUser()) {
    return true;
  }

  return authService.checkSession().pipe(
    map(res => {
      if (res && res.user) {
        return true;
      }
      router.navigate(['/login']);
      return false;
    })
  );
};
