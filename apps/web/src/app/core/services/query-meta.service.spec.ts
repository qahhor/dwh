import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { QueryListMeta } from '../models/query-meta.models';
import { ApiService } from './api.service';
import { QueryMetaService, parseSort, toQueryParams } from './query-meta.service';

const META: QueryListMeta = {
  code: 'upl.sources',
  fields: [],
  defaultSort: 'code',
  defaultLimit: 50,
  maxLimit: 200,
  maxConditions: 20,
  maxInValues: 100
};

describe('toQueryParams', () => {
  it('sends nothing for an empty query', () => {
    expect(toQueryParams(null)).toEqual({});
    expect(toQueryParams({ conditions: [], sort: null })).toEqual({});
  });

  it('writes conditions as the JSON DSL and the sort with a minus for descending', () => {
    const params = toQueryParams({
      conditions: [
        { field: 'code', op: 'starts_with', value: 'sales.' },
        { field: 'periodicity', op: 'in', value: ['month', 'year'] },
        { field: 'lastPublishedVersion', op: 'empty', value: undefined }
      ],
      sort: { field: 'name', descending: true }
    });

    expect(JSON.parse(params.filter!)).toEqual([
      { field: 'code', op: 'starts_with', value: 'sales.' },
      { field: 'periodicity', op: 'in', value: ['month', 'year'] },
      { field: 'lastPublishedVersion', op: 'empty' }
    ]);
    expect(params.filter).not.toContain('undefined');
    expect(params.sort).toBe('-name');
  });

  it('reads a sort back', () => {
    expect(parseSort('-name')).toEqual({ field: 'name', descending: true });
    expect(parseSort('code')).toEqual({ field: 'code', descending: false });
  });
});

describe('QueryMetaService', () => {
  function setup(get: ReturnType<typeof vi.fn>) {
    TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: { get } }] });
    return TestBed.inject(QueryMetaService);
  }

  it('fetches a list once and shares it', async () => {
    const get = vi.fn(() => of(META));
    const service = setup(get);

    expect(await firstValueFrom(service.get('upl.sources'))).toEqual(META);
    expect(await firstValueFrom(service.get('upl.sources'))).toEqual(META);
    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith('/query-meta/upl.sources', undefined, { notifyError: false });
  });

  it('does not keep a failed request, so the next call asks again', async () => {
    const get = vi.fn()
      .mockReturnValueOnce(throwError(() => ({ status: 503 })))
      .mockReturnValueOnce(of(META));
    const service = setup(get);

    await expect(firstValueFrom(service.get('upl.sources'))).rejects.toEqual({ status: 503 });
    expect(await firstValueFrom(service.get('upl.sources'))).toEqual(META);
    expect(get).toHaveBeenCalledTimes(2);
  });
});
