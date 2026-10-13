import { describe, expect, it } from 'vitest';
import type { FormMeta } from '../models/form-meta.models';
import { formField } from '@testing/form-meta';
import { recordPayload, recordValues, rowsOf } from './form-meta.service';

const META: FormMeta = {
  code: 'test.moments',
  fields: [
    formField('title', 'text'),
    formField('startsAt', 'datetime'),
    formField('cfDueAt', 'datetime', { attribute: 'due_at' }),
  ],
  layout: [],
  actions: ['create', 'update'],
  capabilities: [],
};

const RECORD = {
  id: 4,
  title: 'Before',
  startsAt: '2026-10-13T04:15:37.123456Z',
  attributes: { due_at: '2026-10-13T09:15:48.987654+05:00', untouched: 'keep' },
};

describe('datetime values in a record save', () => {
  it('keeps seconds, fractions and offsets of untouched declared and custom moments', () => {
    const values = { ...recordValues(META, RECORD), title: 'After' };

    expect(recordPayload(META, values, RECORD)).toEqual({
      title: 'After',
      startsAt: '2026-10-13T04:15:37.123456Z',
      attributes: { due_at: '2026-10-13T09:15:48.987654+05:00', untouched: 'keep' },
    });
  });

  it('saves a deliberately changed minute in UTC without carrying the previous seconds', () => {
    const values = {
      ...recordValues(META, RECORD),
      startsAt: '2026-10-14T10:20',
      cfDueAt: '2026-10-14T11:30',
    };

    expect(recordPayload(META, values, RECORD)).toMatchObject({
      startsAt: new Date(2026, 9, 14, 10, 20).toISOString(),
      attributes: { due_at: new Date(2026, 9, 14, 11, 30).toISOString() },
    });
  });

  it('lets an edit clear an existing declared or custom moment', () => {
    const values = { ...recordValues(META, RECORD), startsAt: '', cfDueAt: '' };

    expect(recordPayload(META, values, RECORD)).toMatchObject({ startsAt: '', attributes: { due_at: null } });
  });

  it('keeps creating from a fixed default and clearing a hidden moment as before', () => {
    const meta: FormMeta = {
      ...META,
      fields: [
        formField('startsAt', 'datetime', {
          defaultValue: { kind: 'fixed', value: '2026-10-14T10:20' },
          visibleWhen: [{ field: 'show', op: 'eq', values: ['true'] }],
        }),
      ],
    };
    const initial = { ...recordValues(meta, null), show: true };

    expect(recordPayload(meta, initial)['startsAt']).toBe(new Date(2026, 9, 14, 10, 20).toISOString());
    expect(recordPayload(meta, recordValues(meta, RECORD), RECORD)['startsAt']).toBeNull();
  });

  it('leaves fields locked on the original record out of a patch', () => {
    const meta: FormMeta = {
      ...META,
      fields: [
        formField('title', 'text'),
        formField('status', 'text'),
        formField('resolvedAt', 'datetime', { readonly: true }),
        formField('createdAt', 'datetime', { readonlyOnUpdate: true }),
        formField('approvedAt', 'datetime', {
          readonlyWhen: [{ field: 'status', op: 'eq', values: ['posted'] }],
        }),
        formField('lockedAt', 'datetime'),
      ],
      workflow: {
        field: 'status',
        states: [{ code: 'posted', labelKey: 'test.posted', initial: false, terminal: false, locks: ['lockedAt'] }],
        transitions: [],
      },
    };
    const record = {
      ...RECORD,
      status: 'posted',
      resolvedAt: '2026-10-13T04:15:37.123456Z',
      createdAt: '2026-10-13T04:15:37.123456Z',
      approvedAt: '2026-10-13T04:15:37.123456Z',
      lockedAt: '2026-10-13T04:15:37.123456Z',
    };

    expect(recordPayload(meta, { ...recordValues(meta, record), title: 'After', status: 'draft' }, record)).toEqual({
      title: 'After',
      status: 'draft',
      attributes: RECORD.attributes,
    });
  });

  it('allows update-only and conditional fields on create and an unlocked field on update', () => {
    const meta: FormMeta = {
      ...META,
      fields: [
        formField('startsAt', 'datetime', { readonlyOnUpdate: true }),
        formField('approvedAt', 'datetime', {
          readonlyWhen: [{ field: 'status', op: 'eq', values: ['posted'] }],
        }),
      ],
    };
    const values = { startsAt: '2026-10-14T10:20', approvedAt: '2026-10-14T11:30', status: 'posted' };

    expect(recordPayload(meta, values)).toEqual({
      startsAt: new Date(2026, 9, 14, 10, 20).toISOString(),
      approvedAt: new Date(2026, 9, 14, 11, 30).toISOString(),
      attributes: {},
    });
    expect(recordPayload(meta, values, { status: 'draft' })['approvedAt']).toBe(
      new Date(2026, 9, 14, 11, 30).toISOString(),
    );
  });

  it('matches untouched collection moments by row id after reordering, leaving new rows independent', () => {
    const meta: FormMeta = {
      ...META,
      fields: [],
      collections: [{ key: 'lines', labelKey: 'test.lines', maxRows: 10, fields: [formField('startsAt', 'datetime')] }],
    };
    const record = {
      lines: [
        { id: 11, startsAt: '2026-10-13T04:15:37.123456Z' },
        { id: 12, startsAt: '2026-10-13T04:15:48.987654Z' },
      ],
    };
    const [first, second] = rowsOf(recordValues(meta, record), 'lines');
    const values = { lines: [second, { id: null, startsAt: '2026-10-14T10:20' }, first] };

    expect(recordPayload(meta, values, record)['lines']).toEqual([
      { id: 12, startsAt: '2026-10-13T04:15:48.987654Z' },
      { startsAt: new Date(2026, 9, 14, 10, 20).toISOString() },
      { id: 11, startsAt: '2026-10-13T04:15:37.123456Z' },
    ]);
    expect(recordPayload(meta, { lines: [{ ...first, startsAt: '2026-10-14T11:30' }] }, record)['lines']).toEqual([
      { id: 11, startsAt: new Date(2026, 9, 14, 11, 30).toISOString() },
    ]);
  });
});
