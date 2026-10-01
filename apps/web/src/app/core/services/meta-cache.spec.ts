import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FormMeta } from '../models/form-meta.models';
import { ApiService } from './api.service';
import { FormMetaService } from './form-meta.service';
import { META_TTL_MS, MetaCacheState } from './meta-cache';
import { PermissionService } from './permission.service';

const FORM: FormMeta = { code: 'ms.notes', fields: [], layout: [], actions: ['create'], capabilities: [] };

/** Plan 10/10, item 5.0: the entity form is cached like the list description, and goes stale the same ways. */
describe('FormMetaService cache', () => {
  afterEach(() => vi.useRealTimers());

  function setup() {
    const get = vi.fn(() => of(FORM));
    TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: { get } }] });
    return { service: TestBed.inject(FormMetaService), get };
  }

  it('reads a form once and shares it while it is fresh', async () => {
    const { service, get } = setup();

    await firstValueFrom(service.get('ms.notes'));
    await firstValueFrom(service.get('ms.notes'));

    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith('/form-meta/ms.notes', undefined, { notifyError: false });
  });

  it('reads it again after custom fields changed, after the rights changed and when it is old', async () => {
    vi.useFakeTimers();
    const { service, get } = setup();

    await firstValueFrom(service.get('ms.notes'));
    TestBed.inject(MetaCacheState).invalidate();
    await firstValueFrom(service.get('ms.notes'));
    expect(get).toHaveBeenCalledTimes(2);

    TestBed.inject(PermissionService).setPermissions(['notes.view'], 3);
    await firstValueFrom(service.get('ms.notes'));
    expect(get).toHaveBeenCalledTimes(3);

    vi.advanceTimersByTime(META_TTL_MS - 1);
    await firstValueFrom(service.get('ms.notes'));
    expect(get).toHaveBeenCalledTimes(3);
    vi.advanceTimersByTime(2);
    await firstValueFrom(service.get('ms.notes'));
    expect(get).toHaveBeenCalledTimes(4);
  });
});
