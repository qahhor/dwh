import { TestBed } from '@angular/core/testing';
import { Observable, of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { AuditComponent, AuditRecord, SecurityEventRecord } from './audit.component';
import { AUDIT_LOGS_META, registryProviders, SECURITY_EVENTS_META } from '@testing/registry-meta';

/*
 * The screen's own contract: tabs, the summary, and how the filters, the pagers and
 * the retry actions are wired. The tables, the filters and the pager have their own specs.
 */
type Get = (url: string, params?: Record<string, unknown>) => Observable<unknown>;

const stats = (totalAuditLogs = 0) => ({
  totalAuditLogs,
  totalSecurityEvents: 0,
  securityEventsLast24h: 0,
  failedLoginsLast24h: 0,
});
const auditRow = (id: number, changedColumns: string[] = []): AuditRecord => ({
  id,
  tableName: 'md_users',
  rowPk: String(id),
  event: 'U',
  isApi: false,
  changedAt: '2026-09-04T00:00:00Z',
  changedColumns,
});
const securityRow = (id: number): SecurityEventRecord => ({
  id,
  eventType: 'LOGIN_FAILED',
  ip: '127.0.0.1',
  details: {},
  createdAt: '2026-09-04T00:00:00Z',
});
const page = <T>(items: T[], nextCursor: string | null = null) => ({
  items,
  nextCursor,
  hasMore: nextCursor !== null,
  totalEstimated: items.length,
});

/** Empty lists; the first page of each hands out the cursor 'p2'. */
const pagedGet = () =>
  vi.fn<Get>((url, params) =>
    of(url === '/audit/stats' ? stats() : params?.['cursor'] === 'p2' ? page([]) : page([], 'p2')),
  );

/** The parameters of the last request to one list. */
const lastParams = (get: ReturnType<typeof vi.fn<Get>>, url: string) =>
  get.mock.calls.filter(([called]) => called === url).at(-1)?.[1];

async function createFixture(get = vi.fn<Get>((url) => of(url === '/audit/stats' ? stats() : page([])))) {
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
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, get, component: fixture.componentInstance };
}

describe('AuditComponent', () => {
  it('switches between the change log and the security events as named tabs with explicit details actions', async () => {
    const { fixture, host, component } = await createFixture();
    component.auditPager.items.set([auditRow(11, ['title'])]);
    fixture.detectChanges();

    expect(host.querySelector('[role="tablist"][aria-label="Разделы аудита"]')).not.toBeNull();
    expect(host.querySelector('#audit-log-tab[aria-selected="true"]')).not.toBeNull();
    // Details open from an explicit button, not from a click anywhere on the row.
    expect(host.querySelector('button[aria-label="Просмотреть изменение #11"]')).not.toBeNull();

    // The tab loads its metadata and first page on the way in; the row is set after it.
    component.setTab('security');
    component.securityPager.items.set([securityRow(9)]);
    fixture.detectChanges();
    expect(host.querySelector('#security-events-tab[aria-selected="true"]')).not.toBeNull();
    expect(host.querySelector('#security-events-panel [role="table"]')?.getAttribute('aria-label')).toBe(
      'События безопасности',
    );
    expect(host.querySelector('button[aria-label="Просмотреть событие безопасности #9"]')).not.toBeNull();
  });

  it('loads audit pages from the server and uses the returned cursor for the next page', async () => {
    const get = vi.fn<Get>((url, params) =>
      of(
        url === '/audit/stats'
          ? stats(41)
          : params?.['cursor'] === 'audit-next'
            ? { ...page([auditRow(20)]), totalEstimated: 41 }
            : { ...page([auditRow(41)], 'audit-next'), totalEstimated: 41 },
      ),
    );
    const { component } = await createFixture(get);

    expect(get).toHaveBeenCalledWith('/audit/logs', expect.objectContaining({ limit: 20, cursor: undefined }));
    expect(component.auditTotal()).toBe(41);

    component.auditPager.goTo(2);
    expect(get).toHaveBeenCalledWith('/audit/logs', expect.objectContaining({ limit: 20, cursor: 'audit-next' }));
    expect(component.auditPager.page()).toBe(2);
    expect(component.auditLogs()[0].id).toBe(20);
  });

  it('keeps redacted credential keys visible so auditors can see that a field changed', async () => {
    const { component } = await createFixture();
    const record = {
      ...auditRow(12, ['password_hash']),
      oldRow: { password_hash: '[REDACTED]' },
      newRow: { password_hash: '[REDACTED]' },
    };

    expect(component.getDiffKeys(record)).toContain('password_hash');
  });

  it('shows an accessible audit error and retries the failed request without hiding existing rows', async () => {
    let attempts = 0;
    const get = vi.fn<Get>((url) => {
      if (url === '/audit/stats') return of(stats(1));
      attempts++;
      return attempts === 2 ? throwError(() => new Error('network unavailable')) : of(page([auditRow(31)]));
    });
    const { fixture, host, component } = await createFixture(get);

    component.loadAuditLogs();
    fixture.detectChanges();
    const alert = host.querySelector('#audit-load-error[role="alert"]');
    expect(alert?.textContent).toContain('Не удалось загрузить журнал изменений');
    expect(host.textContent).toContain('#31');

    const retry = alert?.querySelector('button') as HTMLButtonElement;
    expect(retry.textContent).toContain('Повторить');
    retry.click();
    fixture.detectChanges();
    expect(host.querySelector('#audit-load-error')).toBeNull();
    expect(attempts).toBe(3);
  });

  it('applies a period preset at once as whole UTC days', async () => {
    const { fixture, host, get, component } = await createFixture(pagedGet());

    host
      .querySelector<HTMLButtonElement>('[data-testid="audit-period-filter"] .smt-date-range-picker__trigger')!
      .click();
    fixture.detectChanges();
    document.querySelector<HTMLButtonElement>('.smt-date-popup__preset')!.click();
    fixture.detectChanges();

    expect(component.auditFilters.from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(component.auditFilters.to).toBe(component.auditFilters.from);
    expect(lastParams(get, '/audit/logs')).toEqual(
      expect.objectContaining({
        from: `${component.auditFilters.from}T00:00:00.000Z`,
        to: `${component.auditFilters.from}T23:59:59.999Z`,
      }),
    );
  });

  it('sends the screen filters with a new first page and forgets the cursor history', async () => {
    const { get, component } = await createFixture(pagedGet());
    component.auditFilters.table = 'ms_tasks';
    component.auditFilters.rowPk = '42';
    component.auditFilters.from = '2026-09-01';
    component.auditPager.goTo(2);
    expect(component.auditPager.page()).toBe(2);

    component.loadAuditLogs(true);

    expect(lastParams(get, '/audit/logs')).toEqual(
      expect.objectContaining({ ...component.auditFilters.flat(), table_name: 'ms_tasks', cursor: undefined }),
    );
    expect(component.auditPager.page()).toBe(1);
  });

  it.each(['audit', 'security'] as const)(
    'clears every %s filter and asks for the unfiltered first page',
    async (tab) => {
      const { get, component } = await createFixture(pagedGet());
      const url = tab === 'audit' ? '/audit/logs' : '/audit/security-events';
      const pager = tab === 'audit' ? component.auditPager : component.securityPager;
      component.auditFilters.table = 'ms_tasks';
      component.auditFilters.user = '5';
      component.securityFilters.eventType = 'LOGIN_FAILED';
      component.securityFilters.ip = '10.0.0.1';
      component.setTab(tab);
      pager.goTo(2);
      expect(pager.page()).toBe(2);

      if (tab === 'audit') component.resetAuditFilters();
      else component.resetSecurityFilters();

      const filters = tab === 'audit' ? component.auditFilters : component.securityFilters;
      const cleared = Object.fromEntries(Object.keys(filters.flat()).map((key) => [key, undefined]));
      expect(filters.flat()).toEqual(cleared);
      expect(pager.page()).toBe(1);
      expect(lastParams(get, url)).toEqual(expect.objectContaining({ ...cleared, cursor: undefined }));
    },
  );

  it('refreshes the summary and the first page of the open tab from the header button', async () => {
    const { fixture, host, get, component } = await createFixture(pagedGet());
    const calls = (url: string) => get.mock.calls.filter(([called]) => called === url).length;
    const refresh = () => {
      host
        .querySelector<HTMLButtonElement>(`button[aria-label="${PACKAGED_RUSSIAN['audit.obnovit_zhurnal_audita']}"]`)!
        .click();
      fixture.detectChanges();
    };
    const logs = calls('/audit/logs');

    refresh();
    expect(calls('/audit/stats')).toBe(2);
    expect(calls('/audit/logs')).toBe(logs + 1);

    component.setTab('security');
    fixture.detectChanges();
    const events = calls('/audit/security-events');
    refresh();
    expect(calls('/audit/stats')).toBe(3);
    expect(calls('/audit/security-events')).toBe(events + 1);
    expect(calls('/audit/logs')).toBe(logs + 1);
  });

  it('shows an accessible statistics error and clears it after retry', async () => {
    let statsAttempts = 0;
    const get = vi.fn<Get>((url) => {
      if (url !== '/audit/stats') return of(page([]));
      statsAttempts++;
      return statsAttempts === 1
        ? throwError(() => new Error('stats unavailable'))
        : of({ totalAuditLogs: 12, totalSecurityEvents: 4, securityEventsLast24h: 2, failedLoginsLast24h: 1 });
    });
    const { fixture, host } = await createFixture(get);

    const alert = host.querySelector('#audit-stats-error[role="alert"]');
    expect(alert?.textContent).toContain('Не удалось загрузить сводку аудита');
    (alert?.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(host.querySelector('#audit-stats-error')).toBeNull();
    expect(host.textContent).toContain('12');
    expect(statsAttempts).toBe(2);
  });

  it('shows an accessible security-events error and retries the failed request', async () => {
    let securityAttempts = 0;
    const get = vi.fn<Get>((url) => {
      if (url === '/audit/stats') return of(stats());
      if (url !== '/audit/security-events') return of(page([]));
      securityAttempts++;
      return securityAttempts === 1
        ? throwError(() => new Error('security events unavailable'))
        : of(page([securityRow(51)]));
    });
    const { fixture, host, component } = await createFixture(get);

    component.setTab('security');
    fixture.detectChanges();
    const alert = host.querySelector('#security-load-error[role="alert"]');
    expect(alert?.textContent).toContain('Не удалось загрузить события безопасности');

    (alert?.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(host.querySelector('#security-load-error')).toBeNull();
    expect(host.textContent).toContain('#51');
    expect(securityAttempts).toBe(2);
  });

  it('announces an in-progress audit request and clears the busy state when it completes', async () => {
    const pending = new Subject<ReturnType<typeof page<AuditRecord>>>();
    const { fixture, host } = await createFixture(
      vi.fn<Get>((url) => (url === '/audit/stats' ? of(stats()) : pending.asObservable())),
    );

    const region = host.querySelector('#audit-log-panel .table-container') as HTMLElement;
    expect(region.getAttribute('aria-busy')).toBe('true');
    expect(region.querySelector('[role="status"]')?.textContent).toContain('Загрузка журнала изменений');

    pending.next(page([]));
    pending.complete();
    fixture.detectChanges();
    expect(region.getAttribute('aria-busy')).toBe('false');
    expect(region.querySelector('[role="status"]')).toBeNull();
  });
});
