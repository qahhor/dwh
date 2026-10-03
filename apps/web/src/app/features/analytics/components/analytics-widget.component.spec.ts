import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryMetaService } from '@core/services/query-meta.service';
import { EntityReportsApi, type ReportResult, type ReportWidget } from '@shared/entity/report/entity-reports';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import { translateTest } from '@testing/i18n-test.stub';
import { AnalyticsWidgetComponent } from './analytics-widget.component';

const WIDGET: ReportWidget = {
  id: 7,
  kind: 'widget',
  listCode: 'example.orders',
  entity: 'example.orders',
  name: 'Заказы по статусам',
  state: { groupBy: [{ field: 'status' }], measures: [{ op: 'count' }], filter: [], chart: 'bar' },
  lockVersion: 2,
};

const LIST = queryMetaFixture('example.orders', [
  metaField('status', '', 'enum', { label: 'Статус', enumValues: ['draft', 'posted'] }),
]);

const RESULT: ReportResult = {
  groups: [{ field: 'status', implicit: false }],
  measures: [{ op: 'count' }],
  rows: [
    { groups: ['draft'], values: [2] },
    { groups: ['posted'], values: [1] },
  ],
  truncated: false,
} as ReportResult;

/* One card of the dashboard (ADR-0032 10.2): it runs its saved report and fails on its own. */
describe('AnalyticsWidgetComponent', () => {
  let report: Subject<ReportResult>;
  const reports = { saved: vi.fn(() => report.asObservable()) };
  const queryMeta = { get: vi.fn(() => of(LIST)) };

  beforeEach(() => {
    report = new Subject<ReportResult>();
    reports.saved.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: EntityReportsApi, useValue: reports },
        { provide: QueryMetaService, useValue: queryMeta },
      ],
    });
  });

  async function render() {
    const fixture = TestBed.createComponent(AnalyticsWidgetComponent);
    fixture.componentRef.setInput('widget', WIDGET);
    await settle(fixture);
    return { fixture, widget: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  /* The report stays pending until a test answers it, so the fixture never turns stable: let the tasks run. */
  async function settle(fixture: { detectChanges(): void }) {
    for (let round = 0; round < 3; round += 1) {
      fixture.detectChanges();
      await new Promise((resolve) => setTimeout(resolve));
    }
    fixture.detectChanges();
  }

  it('runs its saved report with the list metadata and draws the chart', async () => {
    const { fixture, widget, host } = await render();
    expect(reports.saved).toHaveBeenCalledWith('example.orders', 7);
    expect(queryMeta.get).toHaveBeenCalledWith('example.orders');
    expect(widget.loaded.isLoading()).toBe(true);

    report.next(RESULT);
    report.complete();
    await settle(fixture);

    expect(widget.failure()).toBe('');
    expect(host.textContent).toContain('Заказы по статусам');
    expect(host.querySelectorAll('[data-testid="bar-chart-bar"]')).toHaveLength(2);
    expect(host.querySelector('a')?.getAttribute('href')).toBe('/e/example.orders');
  });

  it('shows the problem of a refused report in its own card, or its own text, and retries', async () => {
    const { fixture, widget, host } = await render();
    report.error({ detail: 'Нет такого поля в списке: total' });
    await settle(fixture);
    expect(widget.failure()).toBe('Нет такого поля в списке: total');
    expect(host.querySelector('[data-testid="dash-card-error"]')).not.toBeNull();

    reports.saved.mockReturnValueOnce(throwError(() => ({})));
    widget.loaded.reload();
    await settle(fixture);
    expect(widget.failure()).toBe(translateTest('analytics.widgets.failed'));
  });

  it('asks to be taken off the dashboard with its name in the button label', async () => {
    const { widget, host } = await render();
    const removed = vi.fn();
    widget.remove.subscribe(removed);

    const button = host.querySelector('button[aria-label]') as HTMLButtonElement;
    expect(button.getAttribute('aria-label')).toBe(
      translateTest('analytics.widgets.remove', { name: 'Заказы по статусам' }),
    );
    button.click();
    expect(removed).toHaveBeenCalledWith(WIDGET);
  });
});
