import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import { emptyReport } from '@shared/entity/report/entity-reports';
import { DashboardWidgetComponent } from './dashboard-widget.component';
const REPORT = {
  id: 7,
  entity: 'test.orders',
  listCode: 'test.orders',
  name: 'Sales',
  kind: 'report',
  state: emptyReport(),
  lockVersion: 0,
  modifiedAt: '',
  canEdit: true,
  canShare: false,
  shared: false,
};
const META = queryMetaFixture('test.orders', [metaField('date', '', 'date', { label: 'Date' })]);
describe('dashboard widget period and isolation', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  async function render(field: string | undefined, available = true) {
    const fixture = TestBed.createComponent(DashboardWidgetComponent);
    fixture.componentRef.setInput('dashboardId', 2);
    fixture.componentRef.setInput('revision', 3);
    fixture.componentRef.setInput('widget', {
      viewId: 7,
      width: 1,
      available,
      periodField: field,
      ...(available ? { report: REPORT } : {}),
    });
    fixture.componentRef.setInput('period', { from: '2026-01-01', to: '2026-01-31' });
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
    return fixture;
  }
  it('uses the saved dataset metadata on a named panel without substituting root metadata', async () => {
    const fixture = TestBed.createComponent(DashboardWidgetComponent);
    fixture.componentRef.setInput('dashboardId', 2);
    fixture.componentRef.setInput('revision', 3);
    fixture.componentRef.setInput('widget', {
      viewId: 7,
      width: 1,
      available: true,
      report: { ...REPORT, state: { ...REPORT.state, dataset: 'test.dataset' } },
    });
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve));
    http.expectNone('/api/v1/query-meta/test.orders');
    http.expectOne('/api/v1/entities/test.orders/report-meta?dataset=test.dataset').flush(META);
    http
      .expectOne('/api/v1/dashboards/2/widgets/7/result')
      .flush({ groups: [], measures: [{ op: 'count' }], rows: [{ groups: [], values: [8] }], truncated: false });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('8');
  });

  it('does not show or request an unfiltered value when the mapping is missing', async () => {
    const fixture = await render(undefined);
    expect(fixture.nativeElement.querySelector('[data-testid="dash-card-error"]')).not.toBeNull();
    http.expectNone((req) => req.url.includes('/result'));
    expect(fixture.nativeElement.querySelector('[data-testid="report-export"]')).toBeNull();
  });
  it('does not leak a previous report title or request metadata for an unavailable definition', async () => {
    const fixture = await render(undefined, false);
    expect(fixture.nativeElement.textContent).not.toContain('Sales');
    expect(fixture.nativeElement.querySelector('[data-testid="dash-card-error"]')).not.toBeNull();
    http.expectNone((req) => req.url.includes('/query-meta') || req.url.includes('/result'));
  });
  it('asks the parent to reload a stale dashboard definition', async () => {
    const fixture = await render('date');
    const notified: unknown[] = [];
    const output = (fixture.componentInstance as unknown as { stale?: { subscribe: (fn: () => void) => void } }).stale;
    output?.subscribe(() => notified.push(true));
    http.expectOne('/api/v1/query-meta/test.orders').flush(META);
    http
      .expectOne('/api/v1/dashboards/2/widgets/7/result?from=2026-01-01&to=2026-01-31')
      .flush({ status: 409, detail: 'Stale definition' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();
    expect(notified).toEqual([true]);
  });
  it('applies the common period using the saved dashboard revision and keeps an error local', async () => {
    const fixture = await render('date');
    http.expectOne('/api/v1/query-meta/test.orders').flush(META);
    const req = http.expectOne('/api/v1/dashboards/2/widgets/7/result?from=2026-01-01&to=2026-01-31');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    req.flush({ detail: 'Field permission revoked' }, { status: 422, statusText: 'Unprocessable Entity' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="dash-card-error"]')?.textContent).toContain(
      'Field permission revoked',
    );
  });
});
