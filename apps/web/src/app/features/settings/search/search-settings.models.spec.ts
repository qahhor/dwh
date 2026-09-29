import { describe, expect, it } from 'vitest';
import { SEARCH_ENTITIES, SearchQueryPolicy } from '@core/models/search-management.models';
import { formatBytes, formatJobError, toProblemDetail, validateSearchPolicy } from './search-settings.models';

const valid: SearchQueryPolicy = {
  globalLimit: 10,
  requestsPerMinute: 120,
  burst: 20,
  schemaProfile: 'MIXED',
  fields: {
    TASK: [{ field: 'title', weight: 10, numTypos: 2, prefix: true }],
    PROJECT: [{ field: 'name', weight: 10, numTypos: 2, prefix: true }],
    USER: [{ field: 'name', weight: 10, numTypos: 2, prefix: true }],
  },
};

function errorsOf(change: (policy: SearchQueryPolicy) => void): string[] {
  const policy = structuredClone(valid);
  change(policy);
  return validateSearchPolicy(policy, SEARCH_ENTITIES);
}

describe('search settings rules', () => {
  it('accepts a valid policy without note fields and refuses a missing one', () => {
    expect(validateSearchPolicy(valid, SEARCH_ENTITIES)).toEqual([]);
    expect(validateSearchPolicy(null, SEARCH_ENTITIES)).toEqual(['settings.search.validation.unavailable']);
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
    expect(errorsOf((p) => (p.fields.TASK = []))).toEqual(['settings.search.validation.searchable_field']);
    expect(errorsOf((p) => (p.fields.TASK[0].weight = 0))).toEqual(['settings.search.validation.searchable_field']);
    expect(errorsOf((p) => p.fields.TASK.push({ field: 'body', weight: 128, numTypos: 3, prefix: true }))).toEqual([
      'settings.search.validation.weight',
      'settings.search.validation.typos',
    ]);
    expect(
      errorsOf((p) => {
        p.fields.TASK[0].weight = 0;
        p.fields.PROJECT[0].weight = 0;
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
});
