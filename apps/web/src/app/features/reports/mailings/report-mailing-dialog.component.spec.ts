import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from '@core/services/auth.service';
import type { ReportViewItem } from '@shared/entity/report/report-catalog.api';
import { translateTest } from '@testing/i18n-test.stub';
import { ReportMailingDialogComponent } from './report-mailing-dialog.component';
import { ReportMailingsApi, type ReportMailing } from './report-mailings.api';
import { MAILING } from '@testing/report-mailings';

const REPORT: ReportViewItem = {
  id: 7,
  entity: 'studio.orders',
  listCode: 'studio.orders',
  kind: 'report',
  name: 'Заказы по регионам',
  state: {
    groupBy: [{ field: 'region' }],
    measures: [{ op: 'sum', field: 'total' }],
    filter: [],
    chart: 'table',
    totals: true,
    parameters: [{ key: 'period', field: 'orderdate', required: true }],
  },
  lockVersion: 1,
  modifiedAt: '2026-10-18T08:00:00Z',
  canEdit: true,
  canShare: true,
  shared: true,
  owned: true,
};

/* The dialog of a report mailing (ADR-0045): recipients, channels, format, schedule, parameters and condition. */
describe('ReportMailingDialogComponent', () => {
  const api = {
    create: vi.fn((body: unknown) => of({ ...MAILING, ...(body as object) } as ReportMailing)),
    update: vi.fn(() => of(MAILING)),
  };

  beforeEach(() => {
    api.create.mockClear();
    api.update.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ReportMailingsApi, useValue: api },
        { provide: AuthService, useValue: { currentUser: signal({ id: 1, name: 'Владелец', login: 'owner' }) } },
      ],
    });
  });

  function render(mailing: ReportMailing | null = null) {
    const fixture = TestBed.createComponent(ReportMailingDialogComponent);
    fixture.componentRef.setInput('report', REPORT);
    fixture.componentRef.setInput('options', {
      channels: ['email', 'in_app'],
      maxRecipients: 50,
      maxAttachmentBytes: 5242880,
      canManageAll: false,
    });
    fixture.componentRef.setInput('meta', { code: 'studio.orders', fields: [] });
    fixture.componentRef.setInput('mailing', mailing);
    const saved: ReportMailing[] = [];
    fixture.componentInstance.saved.subscribe((value) => saved.push(value));
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, saved };
  }

  it('creates a monthly mailing to its author with a period, a condition and the configured channels', () => {
    const { component, saved } = render();
    expect(component.name()).toBe('Заказы по регионам');
    expect(component.recipients()).toEqual([1]);
    expect(component.channels()).toEqual(['email', 'in_app']);
    expect(component.channelOptions().map((option) => option.id)).toEqual(['email', 'in_app']);
    expect(component.maxMegabytes()).toBe(5);
    component.frequency.set('monthly');
    component.monthDay.set('31');
    component.timeZone.set('Asia/Tashkent');
    component.parameters.set({ period: { preset: 'last_month' } });
    component.conditionKind.set('has_rows');
    component.toggleChannel('email', false);
    component.toggleChannel('email', true);
    component.submit();
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({
        viewId: 7,
        recipientIds: [1],
        schedule: expect.objectContaining({ frequency: 'monthly', monthDay: 31, timeZone: 'Asia/Tashkent' }),
        parameters: { period: { preset: 'last_month' } },
        condition: { kind: 'has_rows' },
        active: true,
      }),
    );
    expect(saved).toHaveLength(1);
  });

  it('changes a mailing at its revision with a weekly schedule and a threshold', () => {
    const { component } = render(MAILING);
    expect(component.recipients()).toEqual([5]);
    expect(component.measureOptions()).toHaveLength(1);
    expect(component.weekdayOptions()[4].label).toBe(translateTest('reports.mailings.weekday.friday'));
    component.frequency.set('weekly');
    component.weekday.set(5);
    component.conditionKind.set('threshold');
    component.op.set('gte');
    component.value.set('100');
    component.submit();
    expect(api.update).toHaveBeenCalledWith(
      3,
      4,
      expect.objectContaining({
        schedule: expect.objectContaining({ frequency: 'weekly', weekday: 5 }),
        condition: { kind: 'threshold', measure: 0, op: 'gte', value: 100 },
      }),
    );
  });

  it('puts the server messages under their parts and says when the mailing changed meanwhile', () => {
    api.create.mockReturnValueOnce(
      throwError(() => ({
        status: 422,
        detail: 'Рассылка не сохранена',
        errors: [
          { field: 'channels', message: 'Канал telegram не настроен' },
          { field: 'parameters.period', message: 'Укажите период' },
        ],
      })),
    );
    const { component } = render();
    component.submit();
    expect(component.error('channels')).toContain('telegram');
    expect(component.parameterProblems()).toEqual(['Укажите период']);
    api.update.mockReturnValueOnce(throwError(() => ({ status: 409 })));
    const changed = render(MAILING);
    changed.component.submit();
    expect(changed.component.alert()).toBe(translateTest('reports.mailings.conflict'));
  });
});
