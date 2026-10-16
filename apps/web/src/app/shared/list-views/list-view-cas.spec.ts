import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { ListViewsApi } from './list-views';
import { EntityReportsApi, emptyReport } from '../entity/report/entity-reports';

describe('revision-bound reads and saved views', () => {
  function setup() {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    return TestBed.inject(HttpTestingController);
  }
  it('sends If-Match on dashboard widget reads', () => {
    const http = setup();
    TestBed.inject(ApiService)
      .get('/dashboards/9/widgets/4/result', { from: '2026-01-01' }, { ifMatch: 3 })
      .subscribe();
    const req = http.expectOne('/api/v1/dashboards/9/widgets/4/result?from=2026-01-01');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    req.flush({});
    http.verify();
  });
  it('moves list-view revision out of the update body into If-Match', () => {
    const http = setup();
    TestBed.inject(ListViewsApi)
      .update('test.list', 4, {
        name: 'View',
        isDefault: false,
        lockVersion: 2,
        state: { columns: { order: [], hidden: [], widths: {} }, sort: null, filter: [] },
      })
      .subscribe();
    const req = http.expectOne('/api/v1/list-views/test.list/4');
    expect(req.request.headers.get('If-Match')).toBe('"2"');
    expect(req.request.body).not.toHaveProperty('lockVersion');
    req.flush({});
    http.verify();
  });
  it('moves report revision out of the update body into If-Match', () => {
    const http = setup();
    TestBed.inject(EntityReportsApi)
      .update('test.list', {
        id: 4,
        name: 'Report',
        kind: 'report',
        state: emptyReport(),
        lockVersion: 7,
      })
      .subscribe();
    const req = http.expectOne('/api/v1/list-views/test.list/4');
    expect(req.request.headers.get('If-Match')).toBe('"7"');
    expect(req.request.body).not.toHaveProperty('lockVersion');
    req.flush({});
    http.verify();
  });
});
