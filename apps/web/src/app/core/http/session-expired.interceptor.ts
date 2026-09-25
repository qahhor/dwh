import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injector, inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { isSessionBound } from './session-bound';

/**
 * A 401 from the API while someone is signed in means the server session is
 * gone — expired, revoked by an administrator or ended on another device
 * (roadmap item 29). Instead of a page full of failing requests, the tab
 * forgets the session, says why and opens the sign-in page, which brings the
 * person back to where they were. Sign-in's own endpoints answer 401 as part
 * of their flow and are left to their callers. The error still reaches the
 * caller, so nothing waits forever.
 */
export const sessionExpiredInterceptor: HttpInterceptorFn = (request, next) => {
  const injector = inject(Injector);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && isSessionBound(request.url)) {
        // Looked up only now: AuthService itself sends requests through this interceptor.
        injector.get(AuthService).sessionExpired();
      }
      return throwError(() => error);
    })
  );
};
