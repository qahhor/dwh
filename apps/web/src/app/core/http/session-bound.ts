import { HttpErrorResponse } from '@angular/common/http';

/** API calls that need a session, i.e. not sign-in, OTP, password reset and the like (roadmap item 29). */
export function isSessionBound(url: string): boolean {
  const path = url.split('?')[0].replace(/^https?:\/\/[^/]+/, '');
  return path.startsWith('/api/v1/') && !path.startsWith('/api/v1/auth/') && path !== '/api/v1/auth';
}

/**
 * A 401 that means the session is gone. A 401 whose code is `invalid_credentials` answers a wrong current
 * password (the profile's password change) inside a live session: it is the request's error, and signing the
 * person out for a typo would lose their session and their form.
 */
export function isSessionLoss(error: HttpErrorResponse, url = error.url ?? ''): boolean {
  if (error.status !== 401 || !isSessionBound(url)) return false;
  const body: unknown = error.error;
  const code = body && typeof body === 'object' ? (body as { code?: unknown }).code : undefined;
  return code !== 'invalid_credentials';
}
