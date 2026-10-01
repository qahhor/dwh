import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { QueryListMeta } from '../models/query-meta.models';
import { ApiService } from './api.service';
import { CustomFieldsApi } from './custom-fields.api';
import { PermissionService } from './permission.service';
import { QueryMetaService, parseSort, toQueryParams } from './query-meta.service';

const META: QueryListMeta = {
  code: 'upl.sources',
  fields: [],
  defaultSort: 'code',
  defaultLimit: 50,
  maxLimit: 200,
  maxConditions: 20,
  maxInValues: 100,
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
        { field: 'lastPublishedVersion', op: 'empty', value: undefined },
      ],
      sort: { field: 'name', descending: true },
    });

    expect(JSON.parse(params.filter!)).toEqual([
      { field: 'code', op: 'starts_with', value: 'sales.' },
      { field: 'periodicity', op: 'in', value: ['month', 'year'] },
      { field: 'lastPublishedVersion', op: 'empty' },
    ]);
    expect(params.filter).not.toContain('undefined');
    expect(params.sort).toBe('-name');
  });

  it('sends the free-text search as q, trimmed, and nothing for blank text', () => {
    expect(toQueryParams({ search: '  cement ' })).toEqual({ q: 'cement' });
    expect(toQueryParams({ search: '   ' })).toEqual({});
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
    const get = vi
      .fn()
      .mockReturnValueOnce(throwError(() => ({ status: 503 })))
      .mockReturnValueOnce(of(META));
    const service = setup(get);

    await expect(firstValueFrom(service.get('upl.sources'))).rejects.toEqual({ status: 503 });
    expect(await firstValueFrom(service.get('upl.sources'))).toEqual(META);
    expect(get).toHaveBeenCalledTimes(2);
  });

  // Plan 10/10, item 5.0: a new custom field becomes a column without a reload.
  it('asks again after a custom field was saved, so the new field is a column', async () => {
    const withField: QueryListMeta = {
      ...META,
      fields: [
        {
          key: 'cfRegion',
          labelKey: '',
          label: 'Region',
          type: 'text',
          ops: ['eq'],
          sortable: false,
          nullable: true,
          defaultVisible: true,
          enumValues: [],
          enumLabelPrefix: null,
        },
      ],
    };
    const get = vi.fn().mockReturnValueOnce(of(META)).mockReturnValueOnce(of(withField));
    const post = vi.fn(() => of({ id: 1 }));
    TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: { get, post } }] });
    const service = TestBed.inject(QueryMetaService);

    expect((await firstValueFrom(service.get('upl.sources'))).fields).toEqual([]);
    await firstValueFrom(
      TestBed.inject(CustomFieldsApi).create({
        entityType: 'USER',
        code: 'region',
        name: 'Region',
        fieldType: 'string',
        isRequired: false,
        orderNo: 1,
      }),
    );

    expect((await firstValueFrom(service.get('upl.sources'))).fields.map((field) => field.key)).toEqual(['cfRegion']);
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('asks again when the viewer’s rights change', async () => {
    const get = vi.fn(() => of(META));
    const service = setup(get);

    await firstValueFrom(service.get('upl.sources'));
    TestBed.inject(PermissionService).setPermissions(['upl.sources.view'], 2);
    await firstValueFrom(service.get('upl.sources'));

    expect(get).toHaveBeenCalledTimes(2);
  });
});
