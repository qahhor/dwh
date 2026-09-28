import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { AuditComponent, AuditRecord, SecurityEventRecord } from './audit.component';
import { AUDIT_LOGS_META, registryProviders, SECURITY_EVENTS_META } from '@testing/registry-meta';

/** The button that opens a tab's period picker. */
function periodTrigger(fixture: { nativeElement: HTMLElement }, tab: 'audit' | 'security'): HTMLButtonElement | null {
  return fixture.nativeElement.querySelector(`[data-testid="${tab}-period-filter"] .smt-date-range-picker__trigger`);
}

describe('AuditComponent UI contracts', () => {
  /** Stats plus two-page list endpoints: the first page hands out cursor 'p2'. */
  const pagedGet = () =>
    vi.fn((url: string, params?: Record<string, unknown>) =>
      url === '/audit/stats'
        ? of({ totalAuditLogs: 0, totalSecurityEvents: 0, securityEventsLast24h: 0, failedLoginsLast24h: 0 })
        : params?.['cursor'] === 'p2'
          ? of({ items: [], nextCursor: null, hasMore: false, totalEstimated: 0 })
          : of({ items: [], nextCursor: 'p2', hasMore: true, totalEstimated: 0 }),
    );

  async function createFixture(
    get: any = vi.fn((url: string) =>
      url === '/audit/stats'
        ? of({ totalAuditLogs: 0, totalSecurityEvents: 0, securityEventsLast24h: 0, failedLoginsLast24h: 0 })
        : of({ items: [], nextCursor: null, hasMore: false, totalEstimated: 0 }),
    ),
  ) {
    await TestBed.configureTestingModule({
      imports: [AuditComponent],
      providers: [
        { provide: ApiService, useValue: { get } },
        ...registryProviders(AUDIT_LOGS_META, SECURITY_EVENTS_META),
        { provide: ToastService, useValue: { error: vi.fn() } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AuditComponent);
    fixture.detectChanges();
    return { fixture, get };
  }

  it('exposes audit tabs, named filters and an explicit details action', async () => {
    const { fixture } = await createFixture();
    const record: AuditRecord = {
      id: 11,
      tableName: 'ms_tasks',
      rowPk: '42',
      event: 'U',
      isApi: false,
      changedAt: '2026-08-30T00:00:00Z',
      changedColumns: ['title'],
    };
    fixture.componentInstance.auditPager.items.set([record]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="tablist"][aria-label="Разделы аудита"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#audit-log-tab[aria-selected="true"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="audit-table-filter"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#audit-table-filter')?.getAttribute('role')).toBe('combobox');
    expect(fixture.nativeElement.querySelector('#audit-event-filter')?.getAttribute('role')).toBe('combobox');
    const region = fixture.nativeElement.querySelector(
      '#audit-log-panel .table-container[role="region"]',
    ) as HTMLElement;
    expect(region.querySelector('[role="table"]')?.getAttribute('aria-label')).toBe('Журнал изменений данных');
    expect(region.querySelectorAll('[role="rowgroup"] > [role="row"]')).toHaveLength(1);
    // Details open from an explicit button, not from a click anywhere on the row.
    expect(fixture.nativeElement.querySelector('button[aria-label="Просмотреть изменение #11"]')).not.toBeNull();
  });

  it('labels security filters and details actions', async () => {
    const { fixture } = await createFixture();
    const event: SecurityEventRecord = {
      id: 9,
      eventType: 'LOGIN_FAILED',
      ip: '127.0.0.1',
      details: {},
      createdAt: '2026-08-30T00:00:00Z',
    };
    // The tab loads its metadata and first page on the way in; the row is set after it.
    fixture.componentInstance.setTab('security');
    fixture.componentInstance.securityPager.items.set([event]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('label[for="security-event-filter"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#security-event-filter')?.getAttribute('role')).toBe('combobox');
    expect(fixture.nativeElement.querySelector('label[for="security-ip-search"]')).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('#security-events-panel [role="table"]')?.getAttribute('aria-label'),
    ).toBe('События безопасности');
    expect(
      fixture.nativeElement.querySelector('button[aria-label="Просмотреть событие безопасности #9"]'),
    ).not.toBeNull();
  });

  it('loads audit pages from the server and uses the returned cursor for the next page', async () => {
    const get = vi.fn((url: string, params?: Record<string, unknown>) => {
      if (url === '/audit/stats') {
        return of({ totalAuditLogs: 41, totalSecurityEvents: 0, securityEventsLast24h: 0, failedLoginsLast24h: 0 });
      }
      if (url === '/audit/logs' && params?.['cursor'] === 'audit-next') {
        return of({
          items: [
            {
              id: 20,
              tableName: 'md_users',
              rowPk: '20',
              event: 'U',
              isApi: false,
              changedAt: '2026-09-03T00:00:00Z',
              changedColumns: [],
            },
          ],
          nextCursor: null,
          hasMore: false,
          totalEstimated: 41,
        });
      }
      return of({
        items: [
          {
            id: 41,
            tableName: 'md_users',
            rowPk: '41',
            event: 'U',
            isApi: false,
            changedAt: '2026-09-04T00:00:00Z',
            changedColumns: [],
          },
        ],
        nextCursor: 'audit-next',
        hasMore: true,
        totalEstimated: 41,
      });
    });
    const { fixture } = await createFixture(get);

    expect(get).toHaveBeenCalledWith('/audit/logs', expect.objectContaining({ limit: 20, cursor: undefined }));
    expect(fixture.componentInstance.auditTotal()).toBe(41);

    fixture.componentInstance.auditPager.goTo(2);

    expect(get).toHaveBeenCalledWith('/audit/logs', expect.objectContaining({ limit: 20, cursor: 'audit-next' }));
    expect(fixture.componentInstance.auditPager.page()).toBe(2);
    expect(fixture.componentInstance.auditLogs()[0].id).toBe(20);
  });

  it('keeps redacted credential keys visible so auditors can see that a field changed', async () => {
    const { fixture } = await createFixture();
    const record: AuditRecord = {
      id: 12,
      tableName: 'md_users',
      rowPk: '5',
      event: 'U',
      isApi: false,
      changedAt: '2026-09-04T00:00:00Z',
      changedColumns: ['password_hash'],
      oldRow: { password_hash: '[REDACTED]' },
      newRow: { password_hash: '[REDACTED]' },
    };

    expect(fixture.componentInstance.getDiffKeys(record)).toContain('password_hash');
  });

  it('shows an accessible audit error and retries the failed request without hiding existing rows', async () => {
    let attempts = 0;
    const row: AuditRecord = {
      id: 31,
      tableName: 'ms_tasks',
      rowPk: '8',
      event: 'U',
      isApi: false,
      changedAt: '2026-09-04T00:00:00Z',
      changedColumns: ['title'],
    };
    const get = vi.fn((url: string) => {
      if (url === '/audit/stats') {
        return of({ totalAuditLogs: 1, totalSecurityEvents: 0, securityEventsLast24h: 0, failedLoginsLast24h: 0 });
      }
      attempts++;
      return attempts === 1
        ? of({ items: [row], nextCursor: null, hasMore: false, totalEstimated: 1 })
        : attempts === 2
          ? throwError(() => new Error('network unavailable'))
          : of({ items: [row], nextCursor: null, hasMore: false, totalEstimated: 1 });
    });
    const { fixture } = await createFixture(get);

    fixture.componentInstance.loadAuditLogs();
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('#audit-load-error[role="alert"]') as HTMLElement | null;
    expect(alert?.textContent).toContain('Не удалось загрузить журнал изменений');
    expect(fixture.nativeElement.textContent).toContain('#31');

    const retry = alert?.querySelector('button') as HTMLButtonElement | null;
    expect(retry?.textContent).toContain('Повторить');
    retry?.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#audit-load-error')).toBeNull();
    expect(attempts).toBe(3);
  });

  it('renders complete server-backed filter controls with explicit reset actions', async () => {
    const { fixture } = await createFixture();

    expect(fixture.nativeElement.querySelector('label[for="audit-row-pk-filter"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="audit-user-filter"]')).not.toBeNull();
    expect(periodTrigger(fixture, 'audit')?.getAttribute('aria-label')).toMatch(/^Период \(UTC\): /);
    expect(fixture.nativeElement.querySelector('#audit-reset-filters')).not.toBeNull();

    fixture.componentInstance.setTab('security');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('label[for="security-user-filter"]')).not.toBeNull();
    expect(periodTrigger(fixture, 'security')?.getAttribute('aria-label')).toMatch(/^Период \(UTC\): /);
    expect(fixture.nativeElement.querySelector('#security-reset-filters')).not.toBeNull();
  });

  it('applies a period preset at once as whole UTC days', async () => {
    const { fixture, get } = await createFixture(pagedGet());
    const component = fixture.componentInstance;

    periodTrigger(fixture, 'audit')!.click();
    fixture.detectChanges();
    const today = [...document.querySelectorAll<HTMLButtonElement>('.smt-date-popup__preset')][0];
    today.click();
    fixture.detectChanges();

    expect(component.auditFilters.from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(component.auditFilters.to).toBe(component.auditFilters.from);
    const params = get.mock.calls.filter(([url]: [string]) => url === '/audit/logs').at(-1)?.[1];
    expect(params).toEqual(
      expect.objectContaining({
        from: `${component.auditFilters.from}T00:00:00.000Z`,
        to: `${component.auditFilters.from}T23:59:59.999Z`,
      }),
    );
  });

  it('sends every audit filter to the server and resets the cursor history', async () => {
    const { fixture, get } = await createFixture(pagedGet());
    const component = fixture.componentInstance;
    component.auditFilters.table = 'ms_tasks';
    component.auditFilters.event = 'U';
    component.auditFilters.rowPk = '42';
    component.auditFilters.user = '7';
    component.auditFilters.from = '2026-09-01';
    component.auditFilters.to = '2026-09-04';
    component.auditPager.goTo(2);
    expect(component.auditPager.page()).toBe(2);

    component.loadAuditLogs(true);

    const params = get.mock.calls.filter(([url]: [string]) => url === '/audit/logs').at(-1)?.[1];
    expect(params).toEqual(
      expect.objectContaining({
        table_name: 'ms_tasks',
        row_pk: '42',
        event: 'U',
        user_id: '7',
        from: '2026-09-01T00:00:00.000Z',
        to: '2026-09-04T23:59:59.999Z',
        cursor: undefined,
      }),
    );
    expect(component.auditPager.page()).toBe(1);
  });

  it('clears every security filter before requesting the first page', async () => {
    const { fixture, get } = await createFixture(pagedGet());
    const component = fixture.componentInstance;
    component.securityFilters.eventType = 'LOGIN_FAILED';
    component.securityFilters.ip = '10.0.0.1';
    component.securityFilters.user = '9';
    component.securityFilters.from = '2026-08-01';
    component.securityFilters.to = '2026-08-31';
    component.setTab('security');
    component.securityPager.goTo(2);
    expect(component.securityPager.page()).toBe(2);

    component.resetSecurityFilters();

    expect(component.securityFilters.eventType).toBe('');
    expect(component.securityFilters.ip).toBe('');
    expect(component.securityFilters.user).toBe('');
    expect(component.securityFilters.from).toBe('');
    expect(component.securityFilters.to).toBe('');
    expect(component.securityPager.page()).toBe(1);
    const params = get.mock.calls.filter(([url]: [string]) => url === '/audit/security-events').at(-1)?.[1];
    expect(params).toEqual(
      expect.objectContaining({
        event_type: undefined,
        ip: undefined,
        user_id: undefined,
        from: undefined,
        to: undefined,
        cursor: undefined,
      }),
    );
  });

  it('sends every security-event filter to the server', async () => {
    const { fixture, get } = await createFixture();
    const component = fixture.componentInstance;
    component.securityFilters.eventType = 'LOGIN_FAILED';
    component.securityFilters.ip = '10.0.0.1';
    component.securityFilters.user = '9';
    component.securityFilters.from = '2026-08-01';
    component.securityFilters.to = '2026-08-31';

    component.loadSecurityEvents(true);

    const params = get.mock.calls.filter(([url]: [string]) => url === '/audit/security-events').at(-1)?.[1];
    expect(params).toEqual(
      expect.objectContaining({
        event_type: 'LOGIN_FAILED',
        ip: '10.0.0.1',
        user_id: '9',
        from: '2026-08-01T00:00:00.000Z',
        to: '2026-08-31T23:59:59.999Z',
      }),
    );
  });

  it('clears every audit filter before requesting the first page', async () => {
    const { fixture } = await createFixture(pagedGet());
    const component = fixture.componentInstance;
    component.auditFilters.table = 'ms_tasks';
    component.auditFilters.event = 'D';
    component.auditFilters.rowPk = '44';
    component.auditFilters.user = '5';
    component.auditFilters.from = '2026-07-01';
    component.auditFilters.to = '2026-07-31';
    component.auditPager.goTo(2);
    expect(component.auditPager.page()).toBe(2);

    component.resetAuditFilters();

    expect(component.auditFilters.table).toBe('');
    expect(component.auditFilters.event).toBe('');
    expect(component.auditFilters.rowPk).toBe('');
    expect(component.auditFilters.user).toBe('');
    expect(component.auditFilters.from).toBe('');
    expect(component.auditFilters.to).toBe('');
    expect(component.auditPager.page()).toBe(1);
  });

  it('shows an accessible statistics error and clears it after retry', async () => {
    let statsAttempts = 0;
    const get = vi.fn((url: string) => {
      if (url === '/audit/stats') {
        statsAttempts++;
        return statsAttempts === 1
          ? throwError(() => new Error('stats unavailable'))
          : of({ totalAuditLogs: 12, totalSecurityEvents: 4, securityEventsLast24h: 2, failedLoginsLast24h: 1 });
      }
      return of({ items: [], nextCursor: null, hasMore: false, totalEstimated: 0 });
    });
    const { fixture } = await createFixture(get);

    const alert = fixture.nativeElement.querySelector('#audit-stats-error[role="alert"]') as HTMLElement | null;
    expect(alert?.textContent).toContain('Не удалось загрузить сводку аудита');

    (alert?.querySelector('button') as HTMLButtonElement | null)?.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#audit-stats-error')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('12');
    expect(statsAttempts).toBe(2);
  });

  it('shows an accessible security-events error and retries the failed request', async () => {
    let securityAttempts = 0;
    const get = vi.fn((url: string) => {
      if (url === '/audit/stats') {
        return of({ totalAuditLogs: 0, totalSecurityEvents: 1, securityEventsLast24h: 1, failedLoginsLast24h: 1 });
      }
      if (url === '/audit/security-events') {
        securityAttempts++;
        return securityAttempts === 1
          ? throwError(() => new Error('security events unavailable'))
          : of({
              items: [
                { id: 51, eventType: 'LOGIN_FAILED', ip: '127.0.0.1', details: {}, createdAt: '2026-09-04T00:00:00Z' },
              ],
              nextCursor: null,
              hasMore: false,
              totalEstimated: 1,
            });
      }
      return of({ items: [], nextCursor: null, hasMore: false, totalEstimated: 0 });
    });
    const { fixture } = await createFixture(get);

    fixture.componentInstance.setTab('security');
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('#security-load-error[role="alert"]') as HTMLElement | null;
    expect(alert?.textContent).toContain('Не удалось загрузить события безопасности');

    (alert?.querySelector('button') as HTMLButtonElement | null)?.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#security-load-error')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('#51');
    expect(securityAttempts).toBe(2);
  });

  it('does not let a slow answer to an earlier filter overwrite the newer result', async () => {
    const answers: Subject<unknown>[] = [];
    const get = vi.fn((url: string) => {
      if (url === '/audit/stats')
        return of({ totalAuditLogs: 0, totalSecurityEvents: 0, securityEventsLast24h: 0, failedLoginsLast24h: 0 });
      const answer = new Subject<unknown>();
      answers.push(answer);
      return answer.asObservable();
    });
    const { fixture } = await createFixture(get);
    const component = fixture.componentInstance;
    const record = (id: number) => ({
      items: [
        {
          id,
          tableName: 'md_users',
          rowPk: String(id),
          event: 'U',
          isApi: false,
          changedAt: '2026-09-04T00:00:00Z',
          changedColumns: [],
        },
      ],
      nextCursor: null,
      hasMore: false,
      totalEstimated: 1,
    });

    // The user narrows the filter twice; the first narrowing answers last.
    component.auditFilters.rowPk = '5';
    component.loadAuditLogs(true);
    const stale = answers.at(-1)!;
    component.auditFilters.rowPk = '7';
    component.loadAuditLogs(true);
    const fresh = answers.at(-1)!;
    fresh.next(record(7));
    fresh.complete();
    stale.next(record(5));
    stale.complete();

    expect(component.auditLogs().map((row) => row.id)).toEqual([7]);
  });

  it('stays on the current page when the next page fails, and retries that page', async () => {
    let failNext = false;
    const get = vi.fn((url: string, params?: Record<string, unknown>) => {
      if (url === '/audit/stats')
        return of({ totalAuditLogs: 0, totalSecurityEvents: 0, securityEventsLast24h: 0, failedLoginsLast24h: 0 });
      if (params?.['cursor'] === 'p2') {
        if (failNext) return throwError(() => new Error('offline'));
        return of({
          items: [
            {
              id: 2,
              tableName: 't',
              rowPk: '2',
              event: 'U',
              isApi: false,
              changedAt: '2026-09-04T00:00:00Z',
              changedColumns: [],
            },
          ],
          nextCursor: null,
          hasMore: false,
          totalEstimated: 2,
        });
      }
      return of({
        items: [
          {
            id: 1,
            tableName: 't',
            rowPk: '1',
            event: 'U',
            isApi: false,
            changedAt: '2026-09-04T00:00:00Z',
            changedColumns: [],
          },
        ],
        nextCursor: 'p2',
        hasMore: true,
        totalEstimated: 2,
      });
    });
    const { fixture } = await createFixture(get);
    const component = fixture.componentInstance;

    failNext = true;
    component.auditPager.goTo(2);
    expect(component.auditPager.page()).toBe(1);
    expect(component.auditLogs().map((row) => row.id)).toEqual([1]);

    failNext = false;
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('#audit-load-error button') as HTMLButtonElement).click();
    expect(component.auditPager.page()).toBe(2);
    expect(component.auditLogs().map((row) => row.id)).toEqual([2]);
  });

  it('announces an in-progress audit request and clears the busy state when it completes', async () => {
    const pending = new Subject<{ items: AuditRecord[]; nextCursor: null; hasMore: false; totalEstimated: number }>();
    const get = vi.fn((url: string) =>
      url === '/audit/stats'
        ? of({ totalAuditLogs: 0, totalSecurityEvents: 0, securityEventsLast24h: 0, failedLoginsLast24h: 0 })
        : pending.asObservable(),
    );
    const { fixture } = await createFixture(get);

    const region = fixture.nativeElement.querySelector('#audit-log-panel .table-container') as HTMLElement;
    expect(region.getAttribute('aria-busy')).toBe('true');
    expect(region.querySelector('[role="status"]')?.textContent).toContain('Загрузка журнала изменений');

    pending.next({ items: [], nextCursor: null, hasMore: false, totalEstimated: 0 });
    pending.complete();
    fixture.detectChanges();

    expect(region.getAttribute('aria-busy')).toBe('false');
    expect(region.querySelector('[role="status"]')).toBeNull();
  });
});
