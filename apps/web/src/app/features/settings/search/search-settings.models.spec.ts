import { describe, expect, it } from 'vitest';
import { ProblemDetail } from '@core/models/common.models';
import { SearchQueryPolicy } from '@core/models/search-management.models';
import {
  formatBytes,
  formatJobError,
  rateLimitedRetryDelayMs,
  searchPollRetryDelayMs,
  toProblemDetail,
  validateSearchPolicy,
} from './search-settings.models';

/** A failed request as the API reports it, with the Retry-After it carried. */
function failure(status: number, retryAfterSeconds?: number): ProblemDetail {
  return {
    title: 'Problem',
    status,
    code: 'X',
    detail: 'Failed',
    ...(retryAfterSeconds === undefined ? {} : { retryAfterSeconds }),
  };
}

const ENTITIES = ['ms.tasks', 'ms.projects', 'md.users'];

const valid: SearchQueryPolicy = {
  globalLimit: 10,
  requestsPerMinute: 120,
  burst: 20,
  schemaProfile: 'MIXED',
  fields: {
    'ms.tasks': [{ field: 'title', weight: 10, numTypos: 2, prefix: true }],
    'ms.projects': [{ field: 'name', weight: 10, numTypos: 2, prefix: true }],
    'md.users': [{ field: 'name', weight: 10, numTypos: 2, prefix: true }],
  },
};

function errorsOf(change: (policy: SearchQueryPolicy) => void): string[] {
  const policy = structuredClone(valid);
  change(policy);
  return validateSearchPolicy(policy, ENTITIES);
}

describe('search settings rules', () => {
  it('accepts a valid policy and refuses a missing one', () => {
    expect(validateSearchPolicy(valid, ENTITIES)).toEqual([]);
    expect(validateSearchPolicy(null, ENTITIES)).toEqual(['settings.search.validation.unavailable']);
  });

  it('names each broken bound once', () => {
    expect(errorsOf((p) => (p.globalLimit = 51))).toEqual(['settings.search.validation.global_limit']);
    expect(errorsOf((p) => (p.requestsPerMinute = 29))).toEqual(['settings.search.validation.rate']);
    expect(errorsOf((p) => (p.burst = 61))).toEqual(['settings.search.validation.burst']);
    expect(
      errorsOf((p) => {
        p.requestsPerMinute = 30;
        p.burst = 31;
      }),
    ).toEqual(['settings.search.validation.burst']);
    expect(errorsOf((p) => (p.fields['ms.tasks'] = []))).toEqual(['settings.search.validation.searchable_field']);
    expect(errorsOf((p) => (p.fields['ms.tasks'][0].weight = 0))).toEqual([
      'settings.search.validation.searchable_field',
    ]);
    expect(
      errorsOf((p) => p.fields['ms.tasks'].push({ field: 'body', weight: 128, numTypos: 3, prefix: true })),
    ).toEqual(['settings.search.validation.weight', 'settings.search.validation.typos']);
    expect(
      errorsOf((p) => {
        p.fields['ms.tasks'][0].weight = 0;
        p.fields['ms.projects'][0].weight = 0;
      }),
    ).toEqual(['settings.search.validation.searchable_field']);
  });

  it('keeps a server problem and replaces anything else with the fallback', () => {
    const problem = { title: 'Conflict', status: 409, code: 'CONFLICT', detail: 'Changed' };
    expect(toProblemDetail(problem, 'Error', 'Failed')).toBe(problem);
    expect(toProblemDetail(new Error('offline'), 'Error', 'Failed')).toEqual({
      title: 'Error',
      status: 0,
      code: 'NETWORK_ERROR',
      detail: 'Failed',
    });
  });

  it('shows sizes in binary units and unknown sizes by name', () => {
    expect(formatBytes(null, '?')).toBe('?');
    expect(formatBytes(undefined, '?')).toBe('?');
    expect(formatBytes(512, '?')).toBe('512 B');
    expect(formatBytes(1536, '?')).toBe('1.5 KiB');
    expect(formatBytes(5 * 1024 * 1024, '?')).toBe('5.0 MiB');
    expect(formatBytes(3 * 1024 ** 3, '?')).toBe('3.0 GiB');
  });

  it('maps known job errors to their text and hides any other code behind the generic one', () => {
    const translate = (key: string) => `t:${key}`;
    expect(formatJobError(null, translate)).toBe('');
    expect(formatJobError('COLLECTION_MISSING', translate)).toBe('t:settings.search.error.collection_missing');
    expect(formatJobError('RAW_DOWNSTREAM_SECRET', translate)).toBe('t:settings.search.error.generic_job');
  });

  it('retries a job poll no sooner than the regular pace, Retry-After or the doubled pause, and stops on a final failure', () => {
    expect(searchPollRetryDelayMs(failure(429, 5), 1)).toBe(10_000);
    expect(searchPollRetryDelayMs(failure(429, 45), 1)).toBe(45_000);
    expect(searchPollRetryDelayMs(failure(429), 2)).toBe(20_000);
    expect([1, 2, 3, 4, 9].map((attempt) => searchPollRetryDelayMs(failure(503), attempt))).toEqual([
      10_000, 20_000, 40_000, 60_000, 60_000,
    ]);
    expect(searchPollRetryDelayMs(failure(0), 1)).toBe(10_000);
    expect(searchPollRetryDelayMs(failure(502), 1)).toBe(10_000);
    expect(searchPollRetryDelayMs(failure(504), 1)).toBe(10_000);
    expect(searchPollRetryDelayMs(failure(403), 1)).toBeNull();
    expect(searchPollRetryDelayMs(failure(404), 1)).toBeNull();
    expect(searchPollRetryDelayMs(failure(500), 1)).toBeNull();
  });

  it('repeats only a read refused as too frequent, after its Retry-After or a second', () => {
    expect(rateLimitedRetryDelayMs(failure(429, 7))).toBe(7_000);
    expect(rateLimitedRetryDelayMs(failure(429))).toBe(1_000);
    expect(rateLimitedRetryDelayMs(failure(429, 0))).toBe(1_000);
    expect(rateLimitedRetryDelayMs(failure(503, 7))).toBeNull();
  });
});
