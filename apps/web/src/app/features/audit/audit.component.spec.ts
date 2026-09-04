import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../core/services/toast.service';
import { AuditComponent, AuditRecord, SecurityEventRecord } from './audit.component';

describe('AuditComponent UI contracts', () => {
  async function createFixture(get: any = vi.fn((url: string) => url === '/audit/stats'
    ? of({ totalAuditLogs: 0, totalSecurityEvents: 0, securityEventsLast24h: 0, failedLoginsLast24h: 0 })
    : of({ items: [], nextCursor: null, hasMore: false, totalEstimated: 0 }))) {
    await TestBed.configureTestingModule({
      imports: [AuditComponent],
      providers: [
        { provide: ApiService, useValue: { get } },
        { provide: ToastService, useValue: { error: vi.fn() } }
      ]
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
      changedColumns: ['title']
    };
    fixture.componentInstance.auditLogs.set([record]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="tablist"][aria-label="Разделы аудита"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#audit-log-tab[aria-selected="true"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="audit-table-filter"]')).not.toBeNull();
    const region = fixture.nativeElement.querySelector('#audit-log-panel .table-container[role="region"]') as HTMLElement;
    expect(region.tabIndex).toBe(0);
    expect(region.querySelector('table')?.getAttribute('aria-label')).toBe('Журнал изменений данных');
    expect(fixture.nativeElement.querySelector('button[aria-label="Просмотреть изменение #11"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('tr.clickable-row')).toBeNull();
  });

  it('labels security filters and details actions', async () => {
    const { fixture } = await createFixture();
    const event: SecurityEventRecord = {
      id: 9,
      eventType: 'LOGIN_FAILED',
      ip: '127.0.0.1',
      details: {},
      createdAt: '2026-08-30T00:00:00Z'
    };
    fixture.componentInstance.securityEvents.set([event]);
    fixture.componentInstance.setTab('security');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('label[for="security-event-filter"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="security-ip-search"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#security-events-panel table')?.getAttribute('aria-label')).toBe('События безопасности');
    expect(fixture.nativeElement.querySelector('button[aria-label="Просмотреть событие безопасности #9"]')).not.toBeNull();
  });

  it('loads audit pages from the server and uses the returned cursor for the next page', async () => {
    const get = vi.fn((url: string, params?: Record<string, unknown>) => {
      if (url === '/audit/stats') {
        return of({ totalAuditLogs: 41, totalSecurityEvents: 0, securityEventsLast24h: 0, failedLoginsLast24h: 0 });
      }
      if (url === '/audit/logs' && params?.['cursor'] === 'audit-next') {
        return of({
          items: [{ id: 20, tableName: 'md_users', rowPk: '20', event: 'U', isApi: false, changedAt: '2026-09-03T00:00:00Z', changedColumns: [] }],
          nextCursor: null,
          hasMore: false,
          totalEstimated: 41
        });
      }
      return of({
        items: [{ id: 41, tableName: 'md_users', rowPk: '41', event: 'U', isApi: false, changedAt: '2026-09-04T00:00:00Z', changedColumns: [] }],
        nextCursor: 'audit-next',
        hasMore: true,
        totalEstimated: 41
      });
    });
    const { fixture } = await createFixture(get);

    expect(get).toHaveBeenCalledWith('/audit/logs', expect.objectContaining({ limit: 20, cursor: undefined }));
    expect(fixture.componentInstance.auditTotal()).toBe(41);

    fixture.componentInstance.onAuditPageChange(2);

    expect(get).toHaveBeenCalledWith('/audit/logs', expect.objectContaining({ limit: 20, cursor: 'audit-next' }));
    expect(fixture.componentInstance.auditCurrentPage).toBe(2);
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
      newRow: { password_hash: '[REDACTED]' }
    };

    expect(fixture.componentInstance.getDiffKeys(record)).toContain('password_hash');
  });
});
