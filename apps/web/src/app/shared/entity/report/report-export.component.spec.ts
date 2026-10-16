import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ReportExportComponent } from './report-export.component';
import { ReportExportApi } from './report-export.api';
import { emptyReport } from './entity-reports';

describe('aggregate report export', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
  });
  it('exports a saved definition by ID instead of copying its query', async () => {
    const fixture = TestBed.createComponent(ReportExportComponent);
    fixture.componentRef.setInput('source', { kind: 'saved', code: 'test.list', id: 4 });
    fixture.detectChanges();
    fixture.componentInstance.download();
    const req = http.expectOne('/api/v1/entities/test.list/reports/4/export?lang=ru');
    const body = new Blob();
    Object.defineProperty(body, 'text', { value: async () => JSON.stringify({ detail: 'Access revoked' }) });
    req.flush(body, { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();
    expect(fixture.componentInstance.failure()).toContain('Access revoked');
    expect(fixture.componentInstance.busy()).toBe(false);
  });
  it('does not start a download while the result is pending or stale', () => {
    const fixture = TestBed.createComponent(ReportExportComponent);
    fixture.componentRef.setInput('source', { kind: 'saved', code: 'test.list', id: 4 });
    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();
    fixture.componentInstance.download();
    expect(fixture.nativeElement.querySelector('button')?.disabled).toBe(true);
    http.expectNone((req) => req.url.includes('/export'));
  });
  it('exports the exact aggregate filter and groupings instead of list rows', () => {
    const state = {
      ...emptyReport(),
      dataset: 'test.prepared',
      groupBy: [{ field: 'status' }],
      filter: [{ field: 'date', op: 'gte', value: '2026-01-01' }],
    };
    TestBed.inject(ReportExportApi).download({ kind: 'query', code: 'test.list', state }, 'en').subscribe();
    const req = http.expectOne((request) => request.url === '/api/v1/entities/test.list/report/export');
    expect(JSON.parse(req.request.params.get('groupBy')!)).toEqual(state.groupBy);
    expect(JSON.parse(req.request.params.get('filter')!)).toEqual(state.filter);
    expect(req.request.params.get('lang')).toBe('en');
    expect(req.request.params.get('dataset')).toBe('test.prepared');
    req.flush(new Blob());
  });
});
