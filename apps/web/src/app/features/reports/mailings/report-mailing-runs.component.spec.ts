import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { translateTest } from '@testing/i18n-test.stub';
import { ReportMailingRunsComponent } from './report-mailing-runs.component';
import { ReportMailingsApi, type ReportMailingRun } from './report-mailings.api';
import { MAILING } from '@testing/report-mailings';

const RUN: ReportMailingRun = {
  id: 9,
  kind: 'manual',
  slot: '2026-10-19T03:00:00Z',
  status: 'partial',
  recipients: 2,
  sent: 2,
  skipped: 1,
  failed: 1,
  createdAt: '2026-10-19T03:00:00Z',
};

/* The run history of a mailing (ADR-0045): runs with their outcome, and what each recipient got. */
describe('ReportMailingRunsComponent', () => {
  const api = {
    runs: vi.fn(() => of({ items: [RUN], hasMore: true, nextCursor: 'next' })),
    run: vi.fn(() =>
      of({
        run: RUN,
        deliveries: [
          { userId: 5, userName: 'Продавец', channel: 'email', status: 'queued', outbox: 'SENT', rows: 2 },
          { userId: 6, userName: 'Другой', channel: 'in_app', status: 'skipped', reason: 'condition' },
        ],
      }),
    ),
  };

  beforeEach(() => {
    api.runs.mockClear();
    api.run.mockClear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: ReportMailingsApi, useValue: api }],
    });
  });

  function render() {
    const fixture = TestBed.createComponent(ReportMailingRunsComponent);
    fixture.componentRef.setInput('mailing', MAILING);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance };
  }

  it('lists the runs, opens one with its deliveries in words and pages on', () => {
    const { fixture, component } = render();
    expect(api.runs).toHaveBeenCalledWith(3, null);
    expect(component.cursor()).toBe('next');
    component.open(RUN);
    fixture.detectChanges();
    const [sent, skipped] = component.detail()!.deliveries;
    expect(component.result(sent)).toContain(translateTest('reports.mailings.runs.outbox.sent'));
    expect(component.result(skipped)).toContain(translateTest('reports.mailings.runs.reason.condition'));
    expect(component.tone('partial')).toBe('warning');
    component.open(RUN);
    expect(component.detail()).toBeNull();
    component.load('next');
    expect(api.runs).toHaveBeenLastCalledWith(3, 'next');
    expect(component.runs()).toHaveLength(2);
  });

  it('says when the history cannot be read', () => {
    api.runs.mockReturnValueOnce(throwError(() => ({ detail: 'Нет связи' })));
    const { component } = render();
    expect(component.error()).toBe('Нет связи');
  });
});
