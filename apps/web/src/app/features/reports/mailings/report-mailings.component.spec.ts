import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PermissionService } from '@core/services/permission.service';
import { QueryMetaService } from '@core/services/query-meta.service';
import { ToastService } from '@core/services/toast.service';
import type { ReportViewItem } from '@shared/entity/report/report-catalog.api';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { translateTest } from '@testing/i18n-test.stub';
import { MAILING } from '@testing/report-mailings';
import { ReportMailingsApi } from './report-mailings.api';
import { ReportMailingsComponent } from './report-mailings.component';

const REPORT: ReportViewItem = {
  id: 7,
  entity: 'studio.orders',
  listCode: 'studio.orders',
  kind: 'report',
  name: 'Заказы по регионам',
  state: { groupBy: [{ field: 'region' }], measures: [{ op: 'sum', field: 'total' }], filter: [], chart: 'table' },
  lockVersion: 1,
  modifiedAt: '2026-10-18T08:00:00Z',
  canEdit: true,
  canShare: true,
  shared: true,
  owned: true,
};

/* The mailings of a saved report (ADR-0045): listed with their schedule, paused, sent now, tested, deleted. */
describe('ReportMailingsComponent', () => {
  const api = {
    report: vi.fn(() => of(REPORT)),
    options: vi.fn(() =>
      of({ channels: ['email', 'in_app'], maxRecipients: 50, maxAttachmentBytes: 5242880, canManageAll: false }),
    ),
    list: vi.fn(() => of({ items: [MAILING], hasMore: false, nextCursor: null })),
    update: vi.fn(() => of(MAILING)),
    send: vi.fn(() => of({ id: 10 })),
    remove: vi.fn(() => of(undefined)),
  };
  const modal = { confirm: vi.fn(() => of(true)) };
  const toast = { success: vi.fn(), error: vi.fn() };
  let rights = new Set(['own']);

  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockClear());
    toast.error.mockClear();
    rights = new Set(['own']);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '7' }) } } },
        { provide: ReportMailingsApi, useValue: api },
        { provide: SMTModalService, useValue: modal },
        { provide: ToastService, useValue: toast },
        {
          provide: QueryMetaService,
          useValue: { get: vi.fn(() => of({ code: 'studio.orders', fields: [] })), report: vi.fn() },
        },
        {
          provide: PermissionService,
          useValue: { hasPermission: (_form: string, action: string) => rights.has(action) },
        },
      ],
    });
  });

  function render() {
    const fixture = TestBed.createComponent(ReportMailingsComponent);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  it('lists the mailings of the report with their schedule, channels and latest run', () => {
    const { host, component } = render();
    expect(api.report).toHaveBeenCalledWith(7);
    expect(api.list).toHaveBeenCalledWith(7);
    const card = host.querySelector('[data-testid="report-mailing"]')!;
    expect(card.textContent).toContain('Итоги месяца');
    expect(component.schedule(MAILING)).toContain('Asia/Tashkent');
    expect(component.channels(MAILING)).toContain(translateTest('reports.mailings.channel.in_app'));
    expect(card.textContent).toContain(translateTest('reports.mailings.runs.status.done'));
    expect(host.querySelector('[data-testid="report-mailings-new"]')).not.toBeNull();
  });

  it('pauses at the revision read, sends now and tests, deletes once confirmed', () => {
    const { component } = render();
    component.toggle(MAILING);
    expect(api.update).toHaveBeenCalledWith(3, 4, expect.objectContaining({ active: false, recipientIds: [5] }));
    component.send(MAILING, 'all');
    component.send(MAILING, 'me');
    expect(api.send).toHaveBeenNthCalledWith(1, 3, 'all');
    expect(api.send).toHaveBeenNthCalledWith(2, 3, 'me');
    expect(toast.success).toHaveBeenCalled();
    component.remove(MAILING);
    expect(api.remove).toHaveBeenCalledWith(3, 4);
    component.editing.set(MAILING);
    component.saved(MAILING);
    expect(component.editing()).toBeUndefined();
    component.history.set(MAILING);
    expect(component.history()).toBe(MAILING);
  });

  it('tells a refused send and shows nothing to keep without the right', () => {
    api.send.mockReturnValueOnce(throwError(() => ({ detail: 'Рассылка приостановлена' })));
    const { component } = render();
    component.send(MAILING, 'all');
    expect(toast.error).toHaveBeenCalledWith('Рассылка приостановлена');

    rights = new Set();
    const other = render();
    expect(other.host.querySelector('[data-testid="report-mailings-denied"]')).not.toBeNull();
    expect(other.host.querySelector('[data-testid="report-mailings-new"]')).toBeNull();
  });

  it('says when the report cannot be read', () => {
    api.report.mockReturnValueOnce(throwError(() => ({ status: 404 })));
    const { host } = render();
    expect(host.querySelector('[data-testid="report-mailings-error"]')?.textContent).toContain(
      translateTest('reports.mailings.report_failed'),
    );
  });
});
