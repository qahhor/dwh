import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { QueryRefMeta } from '@core/models/query-meta.models';
import { ApiService } from '@core/services/api.service';
import { LookupSources, USERS_PATH } from './lookup-sources';
import { RefLookups } from './ref-lookup';

const USERS: QueryRefMeta = { path: USERS_PATH, labelField: 'name', keyField: 'id', paged: true };
const PROJECTS: QueryRefMeta = {
  path: '/entities/ms.projects',
  labelField: 'name',
  keyField: 'id',
  paged: true,
};
const UNITS: QueryRefMeta = { path: '/org/units', labelField: 'title', keyField: 'code', paged: false };

/** Plan 10/10, item 5.0: a reference is looked up and named by its own target, not as a person. */
describe('RefLookups', () => {
  function setup(get: ReturnType<typeof vi.fn>) {
    TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: { get } }] });
    return { refs: TestBed.inject(RefLookups), lookups: TestBed.inject(LookupSources) };
  }

  it('gives a known target its own lookup and any other list one read as the server names it', () => {
    const { refs, lookups } = setup(vi.fn(() => of([])));

    expect(refs.source(USERS)).toBe(lookups.activeUsers);
    expect(refs.source(PROJECTS)).toBe(lookups.projects);
    expect(refs.source({ ...USERS, keyField: 'login' })).not.toBe(lookups.activeUsers);
    const units = refs.source(UNITS);
    expect(refs.source(UNITS)).toBe(units);
    expect(units.option({ code: 'hq', title: 'Головной офис' }).label).toBe('Головной офис');
  });

  it('names a referenced row once, by its target, and keeps the name', async () => {
    const get = vi.fn((path: string) =>
      path === '/org/units' ? of([{ code: 'hq', title: 'Головной офис' }]) : of({ id: 7, name: 'Проект А' }),
    );
    const { refs } = setup(get);

    expect(refs.name(UNITS, 'hq')).toBeNull();
    expect(refs.name(PROJECTS, 7)).toBeNull();
    await Promise.resolve();

    expect(refs.name(UNITS, 'hq')).toBe('Головной офис');
    expect(refs.name(PROJECTS, 7)).toBe('Проект А');
    expect(get).toHaveBeenCalledWith('/entities/ms.projects/7', undefined, { notifyError: false });
    expect(get).toHaveBeenCalledTimes(2);
    expect(refs.name(UNITS, null)).toBeNull();
  });

  it('names an archived row by its own read, with its mark (ADR-0032 5.4)', async () => {
    const notes: QueryRefMeta = { path: '/notes', labelField: 'title', keyField: 'id', paged: true };
    const get = vi.fn(() => of({ id: 4, title: 'Старая заметка', archived: true }));
    const { refs } = setup(get);

    expect(refs.name(notes, 4)).toBeNull();
    await Promise.resolve();

    const name = refs.name(notes, 4);
    expect(name).toContain('Старая заметка');
    expect(name).not.toBe('Старая заметка');
    expect(get).toHaveBeenCalledWith('/notes/4', undefined, { notifyError: false });
  });

  it('a whole list is searched on the screen', async () => {
    const { refs } = setup(
      vi.fn(() =>
        of([
          { code: 'hq', title: 'Головной офис' },
          { code: 'br', title: 'Филиал' },
        ]),
      ),
    );

    const page = await firstValueFrom(refs.source(UNITS).page('фил', null, 20));
    expect(page?.items?.map((row) => row['code'])).toEqual(['br']);
  });

  it('seeds referenced row names from runtime labels without triggering HTTP reads', async () => {
    const get = vi.fn();
    const { refs } = setup(get);

    refs.seedRecord(
      {
        id: 1,
        title: 'Item 1',
        projectId: 42,
        userIds: [101, 102],
        lines: [{ unitCode: 'hq' }],
        labels: {
          projectId: 'Alpha Project',
          userIds: ['User 101', 'User 102'],
          lines: [{ unitCode: 'Headquarters' }],
        },
      },
      [
        { key: 'projectId', ref: PROJECTS },
        { key: 'userIds', ref: USERS },
      ],
      [{ key: 'lines', fields: [{ key: 'unitCode', ref: UNITS }] }],
    );

    expect(refs.name(PROJECTS, 42)).toBe('Alpha Project');
    expect(refs.name(USERS, 101)).toBe('User 101');
    expect(refs.name(USERS, 102)).toBe('User 102');
    expect(refs.name(UNITS, 'hq')).toBe('Headquarters');
    await Promise.resolve();
    expect(get).not.toHaveBeenCalled();
  });
});
