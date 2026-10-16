import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, afterEach, describe, expect, it } from 'vitest';
import { DashboardApi } from './dashboard.api';
import { ReportCatalogApi } from '@shared/entity/report/report-catalog.api';

describe('dashboard and shared report transport', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('keeps empty catalog windows pageable', () => {
    let more = false;
    TestBed.inject(ReportCatalogApi)
      .list('test.list', 'after', 20)
      .subscribe((page) => (more = page.hasMore));
    const req = http.expectOne('/api/v1/report-views?listCode=test.list&limit=20&cursor=after');
    req.flush({ items: [], hasMore: true, nextCursor: 'next', totalReturned: 0 });
    expect(more).toBe(true);
  });
  it('sends dashboard CAS and preserves only declared layout properties', () => {
    TestBed.inject(DashboardApi)
      .update(9, { name: 'Sales', widgets: [{ viewId: 4, periodField: 'createdAt', width: 2 }] }, 3)
      .subscribe();
    const req = http.expectOne('/api/v1/dashboards/9');
    expect(req.request.method).toBe('PUT');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body.widgets[0]).toEqual({ viewId: 4, periodField: 'createdAt', width: 2 });
    req.flush({});
  });
  it('binds the widget result to the dashboard revision and explicit dates', () => {
    TestBed.inject(DashboardApi).result(9, 4, 3, { from: '2026-01-01', to: '2026-01-31' }).subscribe();
    const req = http.expectOne('/api/v1/dashboards/9/widgets/4/result?from=2026-01-01&to=2026-01-31');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    req.flush({ groups: [], measures: [], rows: [], truncated: false });
  });
  it('writes sharing roles with CAS and reads roles through the dedicated capability', () => {
    const api = TestBed.inject(ReportCatalogApi);
    api.share('/list-views/test.list/4', [2, 7], 5).subscribe();
    const req = http.expectOne('/api/v1/list-views/test.list/4/sharing');
    expect(req.request.headers.get('If-Match')).toBe('"5"');
    expect(req.request.body).toEqual({ roleIds: [2, 7] });
    req.flush({ revision: 6, roleIds: [2, 7] });
    api.roles(null, 20).subscribe();
    http.expectOne('/api/v1/report-sharing/roles?limit=20').flush({ items: [], hasMore: false });
  });
});
