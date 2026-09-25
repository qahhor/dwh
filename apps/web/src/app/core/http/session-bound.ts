/** API calls that need a session, i.e. not sign-in, OTP, password reset and the like (roadmap item 29). */
export function isSessionBound(url: string): boolean {
  const path = url.split('?')[0].replace(/^https?:\/\/[^/]+/, '');
  return path.startsWith('/api/v1/') && !path.startsWith('/api/v1/auth/') && path !== '/api/v1/auth';
}
