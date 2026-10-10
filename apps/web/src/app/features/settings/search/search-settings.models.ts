import { ProblemDetail } from '@core/models/common.models';
import {
  SearchEntityType,
  SearchJobAction,
  SearchQueryPolicy,
  SearchRetryJobRequest,
  SearchSettingsSnapshot,
  SearchStartJobRequest,
} from '@core/models/search-management.models';

export type PendingMutation =
  | { kind: 'start'; request: SearchStartJobRequest }
  | { kind: 'retry'; jobId: string; request: SearchRetryJobRequest }
  | { kind: 'cancel'; jobId: string };

/** A read's answer or its failure, kept as one value. */
export type Loaded<T> = { value: T; failure?: undefined } | { value?: undefined; failure: ProblemDetail };

export interface MaintenanceConfirmation {
  action: Extract<SearchJobAction, 'REBUILD' | 'ROLLBACK'>;
  generationId?: string;
}

export function validateSearchPolicy(
  policy: SearchQueryPolicy | null,
  entities: readonly SearchEntityType[],
): string[] {
  if (!policy) return ['settings.search.validation.unavailable'];
  const errors: string[] = [];
  if (!Number.isInteger(policy.globalLimit) || policy.globalLimit < 1 || policy.globalLimit > 50)
    errors.push('settings.search.validation.global_limit');
  if (!Number.isInteger(policy.requestsPerMinute) || policy.requestsPerMinute < 30 || policy.requestsPerMinute > 600)
    errors.push('settings.search.validation.rate');
  if (
    !Number.isInteger(policy.burst) ||
    policy.burst < 10 ||
    policy.burst > 60 ||
    policy.burst > policy.requestsPerMinute
  )
    errors.push('settings.search.validation.burst');
  for (const entity of entities) {
    const fields = policy.fields[entity] ?? [];
    if (fields.length === 0) {
      errors.push('settings.search.validation.searchable_field');
      continue;
    }
    if (!fields.some((field) => Number.isInteger(field.weight) && field.weight > 0))
      errors.push('settings.search.validation.searchable_field');
    if (fields.some((field) => !Number.isInteger(field.weight) || field.weight < 0 || field.weight > 127))
      errors.push('settings.search.validation.weight');
    if (fields.some((field) => !Number.isInteger(field.numTypos) || field.numTypos < 0 || field.numTypos > 2))
      errors.push('settings.search.validation.typos');
  }
  return [...new Set(errors)];
}

export function cloneSearchSnapshot(snapshot: SearchSettingsSnapshot): SearchSettingsSnapshot {
  return { version: snapshot.version, policy: cloneSearchPolicy(snapshot.policy) };
}

export function cloneSearchPolicy(policy: SearchQueryPolicy): SearchQueryPolicy {
  return structuredClone(policy);
}

export function toProblemDetail(error: unknown, fallbackTitle: string, fallbackDetail: string): ProblemDetail {
  if (error && typeof error === 'object' && 'status' in error && 'detail' in error) return error as ProblemDetail;
  return {
    title: fallbackTitle,
    status: 0,
    code: 'NETWORK_ERROR',
    detail: fallbackDetail,
  };
}

/** The pace of job polls while nothing goes wrong. */
export const SEARCH_POLL_INTERVAL_MS = 10_000;
/** Polls retried in a row before the screen reports the failure and offers to resume by hand. */
export const SEARCH_POLL_MAX_RETRIES = 6;
const SEARCH_POLL_MAX_BACKOFF_MS = 60_000;
const TRANSIENT_STATUSES = new Set([0, 502, 503, 504]);

/**
 * When to ask again after a failed job poll, or null to stop: a busy server (429) is asked no sooner than its
 * Retry-After, a lost answer or an unavailable server after a pause that doubles from the poll interval up to a
 * minute; any other failure is final. Never sooner than the regular pace, so a retry cannot hammer the server.
 */
export function searchPollRetryDelayMs(failure: ProblemDetail, attempt: number): number | null {
  const backoff = Math.min(SEARCH_POLL_INTERVAL_MS * 2 ** Math.max(0, attempt - 1), SEARCH_POLL_MAX_BACKOFF_MS);
  if (failure.status === 429) return Math.max(backoff, (failure.retryAfterSeconds ?? 0) * 1000);
  return TRANSIENT_STATUSES.has(failure.status) ? backoff : null;
}

/** When to repeat a read the server refused as too frequent (429), or null for any other failure. */
export function rateLimitedRetryDelayMs(failure: ProblemDetail): number | null {
  if (failure.status !== 429) return null;
  return Math.max(1, failure.retryAfterSeconds ?? 1) * 1000;
}

export function formatBytes(value: number | null | undefined, unknownLabel: string): string {
  if (value === null || value === undefined) return unknownLabel;
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MiB`;
  return `${(value / 1024 / 1024 / 1024).toFixed(1)} GiB`;
}

export function formatJobError(code: string | null | undefined, translate: (key: string) => string): string {
  if (!code) return '';
  const known: Record<string, string> = {
    DEPENDENCY_UNAVAILABLE: 'settings.search.error.dependency_unavailable',
    COLLECTION_MISSING: 'settings.search.error.collection_missing',
    STORAGE_CAPACITY_EXCEEDED: 'settings.search.error.storage_capacity',
    GENERATION_CAPACITY_EXCEEDED: 'settings.search.error.generation_capacity',
    VERIFICATION_FAILED: 'settings.search.error.verification_failed',
  };
  return translate(known[code] ?? 'settings.search.error.generic_job');
}
