import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { retry, timer } from 'rxjs';

export const IDEMPOTENCY_HEADER = 'Idempotency-Key';
/** The server keeps bodies up to 64 KB with a key (IdempotencyFilter); larger ones go without. */
const MAX_BODY_BYTES = 60 * 1024;
const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
/** Where the server refuses a key: sign-in and secrets must never be replayed. */
const UNSUPPORTED = ['/api/v1/auth/', '/api/v1/iam/profile/api-tokens', '/api/v1/iam/profile/channels'];
/** Statuses after which the change may or may not have happened — safe to repeat only under the same key. */
const RETRYABLE = new Set([0, 502, 503, 504]);
const RETRY_DELAYS_MS = [1_000, 3_000];
const MAX_RETRY_AFTER_SECONDS = 10;

/**
 * Gives every change sent to the API its own Idempotency-Key (roadmap item 29)
 * and, when the answer is lost on the way — a dropped connection or a gateway
 * error — repeats it twice under the same key. The server then either does the
 * change once or replays the answer it already gave, so a flaky network never
 * creates a second task or a second upload. A request that already has a key,
 * sends a file or a large body, or goes to sign-in is left as it is.
 */
export const idempotencyKeyInterceptor: HttpInterceptorFn = (request, next) => {
  if (!wantsKey(request)) return next(request);
  const keyed = request.clone({ setHeaders: { [IDEMPOTENCY_HEADER]: newKey() } });
  return next(keyed).pipe(
    retry({
      count: RETRY_DELAYS_MS.length,
      delay: (error: unknown, attempt: number) => {
        if (!(error instanceof HttpErrorResponse) || !RETRYABLE.has(error.status)) throw error;
        return timer(retryDelay(error, attempt));
      }
    })
  );
};

function wantsKey(request: HttpRequest<unknown>): boolean {
  if (!MUTATING.has(request.method) || request.headers.has(IDEMPOTENCY_HEADER)) return false;
  const path = pathOf(request.url).split('?')[0];
  if (!path.startsWith('/api/v1/') || path === '/api/v1/auth' || UNSUPPORTED.some(prefix => path.startsWith(prefix))) return false;
  // A stored answer is replayed as JSON: downloads keep their own handling.
  if (request.responseType !== 'json' && request.responseType !== 'text') return false;
  return fitsBody(request.body);
}

function pathOf(url: string): string {
  if (url.startsWith('/')) return url;
  try {
    return new URL(url).pathname;
  } catch {
    return url;
  }
}

function fitsBody(body: unknown): boolean {
  if (body === null || body === undefined) return true;
  if (typeof FormData !== 'undefined' && body instanceof FormData) return false;
  if (typeof Blob !== 'undefined' && body instanceof Blob) return false;
  if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) return false;
  if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) return false;
  try {
    const text = typeof body === 'string' ? body : JSON.stringify(body);
    return new TextEncoder().encode(text ?? '').length <= MAX_BODY_BYTES;
  } catch {
    return false;
  }
}

function retryDelay(error: HttpErrorResponse, attempt: number): number {
  const retryAfter = error.headers?.get('Retry-After');
  if (retryAfter && /^\d+$/.test(retryAfter) && Number(retryAfter) <= MAX_RETRY_AFTER_SECONDS) {
    return Number(retryAfter) * 1000;
  }
  return RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length) - 1];
}

/** A random UUID v4; `crypto.randomUUID` needs a secure context, a stand on plain HTTP may not have one. */
export function newKey(): string {
  if (typeof crypto.randomUUID === 'function') {
    try {
      return crypto.randomUUID();
    } catch {
      // falls through to getRandomValues
    }
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
