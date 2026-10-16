import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, afterEach, describe, expect, it } from 'vitest';
import { firstValueFrom } from 'rxjs';
import { ApiService } from './api.service';

describe('authorized binary API reads', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('sends the same period and revision on XLSX download', () => {
    TestBed.inject(ApiService)
      .getBlob('/dashboards/9/widgets/4/export', { from: '2026-01-01', to: '2026-01-31', lang: 'ru' }, { ifMatch: 3 })
      .subscribe();
    const req = http.expectOne('/api/v1/dashboards/9/widgets/4/export?from=2026-01-01&to=2026-01-31&lang=ru');
    expect(req.request.responseType).toBe('blob');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    req.flush(new Blob());
  });
  it('keeps Problem Details status and message when a download is denied', async () => {
    const promise = firstValueFrom(
      TestBed.inject(ApiService).getBlob('/entities/test.list/reports/4/export', undefined, { notifyError: false }),
    );
    const body = new Blob([JSON.stringify({ detail: 'Access revoked' })], { type: 'application/problem+json' });
    Object.defineProperty(body, 'text', { value: async () => JSON.stringify({ detail: 'Access revoked' }) });
    http.expectOne('/api/v1/entities/test.list/reports/4/export').flush(body, { status: 403, statusText: 'Forbidden' });
    await expect(promise).rejects.toMatchObject({ status: 403, detail: 'Access revoked' });
  });
});
