import { describe, expect, it } from 'vitest';
import { AuditLogFilters, SecurityEventFilters } from './audit-filters';

describe('Audit filters', () => {
  it('sends every change-log filter, trimmed, with the period as whole UTC days', () => {
    const filters = new AuditLogFilters();
    filters.table = 'ms_tasks';
    filters.event = 'U';
    filters.rowPk = ' 42 ';
    filters.user = ' 7 ';
    filters.from = '2026-09-01';
    filters.to = '2026-09-04';

    expect(filters.flat()).toEqual({
      tableName: 'ms_tasks',
      rowPk: '42',
      event: 'U',
      userId: '7',
      from: '2026-09-01T00:00:00.000Z',
      to: '2026-09-04T23:59:59.999Z',
    });

    filters.reset();
    expect(Object.values(filters.flat()).every((value) => value === undefined)).toBe(true);
    expect([filters.table, filters.event, filters.rowPk, filters.user, filters.from, filters.to]).toEqual(
      Array(6).fill(''),
    );
  });

  it('sends every security-event filter and leaves the empty ones out', () => {
    const filters = new SecurityEventFilters();
    filters.eventType = 'LOGIN_FAILED';
    filters.ip = '10.0.0.1';
    filters.user = '9';
    filters.from = '2026-08-01';
    filters.to = '2026-08-31';

    expect(filters.flat()).toEqual({
      eventType: 'LOGIN_FAILED',
      userId: '9',
      ip: '10.0.0.1',
      from: '2026-08-01T00:00:00.000Z',
      to: '2026-08-31T23:59:59.999Z',
    });

    filters.reset();
    expect(filters.flat()).toEqual({
      eventType: undefined,
      userId: undefined,
      ip: undefined,
      from: undefined,
      to: undefined,
    });
    expect([filters.eventType, filters.ip, filters.user, filters.from, filters.to]).toEqual(Array(5).fill(''));
  });

  it('hands the export the same options object until a filter changes', () => {
    const filters = new AuditLogFilters();
    filters.table = 'ms_tasks';
    const first = filters.exportOptions();

    expect(first).toEqual({ tableName: 'ms_tasks' });
    expect(filters.exportOptions()).toBe(first);
    filters.event = 'D';
    expect(filters.exportOptions()).toEqual({ tableName: 'ms_tasks', event: 'D' });

    const security = new SecurityEventFilters();
    const empty = security.exportOptions();
    expect(security.exportOptions()).toBe(empty);
    security.ip = '10.0.0.1';
    expect(security.exportOptions()).toEqual({ ip: '10.0.0.1' });
  });
});
