import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../core/services/toast.service';
import { AuditComponent, AuditRecord, SecurityEventRecord } from './audit.component';

describe('AuditComponent UI contracts', () => {
  async function createFixture() {
    await TestBed.configureTestingModule({
      imports: [AuditComponent],
      providers: [
        { provide: ApiService, useValue: { get: vi.fn(() => of([])) } },
        { provide: ToastService, useValue: { error: vi.fn() } }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(AuditComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('exposes audit tabs, named filters and an explicit details action', async () => {
    const fixture = await createFixture();
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
    const fixture = await createFixture();
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
});
