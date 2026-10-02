import { SearchHit } from '../models/search.models';
import { UrlMatcher } from '@angular/router';

export function canonicalRecordId(id: unknown): id is string {
  return typeof id === 'string' && /^[1-9]\d{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n;
}

// Keep each list and its detail on one route config: Angular retains the page's
// filters and still runs CanDeactivate when the path/record parameter changes.
export const taskRecordMatcher: UrlMatcher = (segments) => {
  if (segments[0]?.path !== 'tasks') return null;
  if (segments.length === 1) return { consumed: segments };
  if (segments[1]?.path !== 'items' || segments.length > 3) return null;
  return { consumed: segments, ...(segments[2] ? { posParams: { id: segments[2] } } : {}) };
};

export const projectRecordMatcher: UrlMatcher = (segments) => {
  if (segments[0]?.path !== 'tasks' || segments[1]?.path !== 'projects' || segments.length > 3) return null;
  return { consumed: segments, ...(segments[2] ? { posParams: { id: segments[2] } } : {}) };
};

export function safeNumericRecordId(id: unknown): id is number {
  return typeof id === 'number' && Number.isSafeInteger(id) && id > 0;
}

// Legacy JSON numbers can round bigint IDs. The GET URL remains authoritative;
// this check only rejects inconsistent responses, never reconstructs an ID.
export function recordResponseMatches(id: unknown, requestedId: string): boolean {
  return (
    canonicalRecordId(requestedId) && (id === requestedId || (typeof id === 'number' && id === Number(requestedId)))
  );
}

/** An address inside the application: lower-case path segments, no host. */
const INTERNAL_PATH = /^\/[a-z0-9_.-]+(\/[a-z0-9_.-]+)*$/;

/**
 * Where a hit leads (ADR-0032, 10.3): the server names it — the entity's general screen `/e/<code>/<id>` or its own
 * screen — and the application follows it only when it is an internal path that ends with the hit's own record id,
 * so a hit can never send the person elsewhere.
 */
export function searchTarget(hit: SearchHit): string | null {
  if (!canonicalRecordId(hit.id) || typeof hit.targetUrl !== 'string') return null;
  const url = hit.targetUrl;
  if (!INTERNAL_PATH.test(url) || url.split('/').some((segment) => segment === '.' || segment === '..')) return null;
  return url.endsWith(`/${hit.id}`) ? url : null;
}
