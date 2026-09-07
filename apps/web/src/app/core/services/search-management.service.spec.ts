import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { I18nService } from './i18n.service';
import { SearchManagementService } from './search-management.service';
import { ToastService } from './toast.service';

describe('SearchManagementService', () => {
  function setup() {
    const toast = { error: () => { throw new Error('management errors must stay local'); } };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        SearchManagementService,
        { provide: ToastService, useValue: toast },
        { provide: I18nService, useValue: { translate: (key: string) => key } }
      ]
    });
    return {
      service: TestBed.inject(SearchManagementService),
      http: TestBed.inject(HttpTestingController)
    };
  }

  it('uses the server-only read routes and preserves the bounded history cursor', () => {
    const { service, http } = setup();
    service.status().subscribe();
    service.settings().subscribe();
    service.job('job-1').subscribe();
    service.jobs(20, 'opaque+/=').subscribe();

    http.expectOne('/api/v1/search/status').flush({});
    http.expectOne('/api/v1/search/settings').flush({});
    http.expectOne('/api/v1/search/jobs/job-1').flush({});
    const history = http.expectOne(req => req.url === '/api/v1/search/jobs'
      && req.params.get('limit') === '20' && req.params.get('cursor') === 'opaque+/=');
    expect(history.request.method).toBe('GET');
    history.flush({ items: [], hasMore: false });
    http.verify();
  });

  it('sends only the typed policy and maintenance payloads accepted by the backend', () => {
    const { service, http } = setup();
    const policy = {
      globalLimit: 10,
      requestsPerMinute: 120,
      burst: 20,
      schemaProfile: 'MIXED' as const,
      fields: {
        TASK: [{ field: 'title', weight: 10, numTypos: 2, prefix: true }],
        PROJECT: [{ field: 'name', weight: 10, numTypos: 2, prefix: true }],
        USER: [{ field: 'name', weight: 10, numTypos: 2, prefix: true }]
      }
    };

    service.save({ version: 7, policy }).subscribe();
    service.preview({ q: 'invoice', entity: 'TASK', policy }).subscribe();
    service.startJob({ requestId: 'request-1', action: 'REBUILD' }).subscribe();
    service.cancel('job-1').subscribe();
    service.retry('job-1', { requestId: 'request-2' }).subscribe();

    const save = http.expectOne('/api/v1/search/settings');
    expect(save.request.method).toBe('PUT');
    expect(save.request.body).toMatchObject({
      version: 7,
      policy: { globalLimit: 10, requestsPerMinute: 120, burst: 20, schemaProfile: 'MIXED' }
    });
    expect(save.request.body.policy.fields.TASK[0]).toEqual({
      field: 'title', weight: 10, numTypos: 2, prefix: true
    });
    expect(save.request.body.policy.fields.TASK[0]).not.toHaveProperty('typos');
    save.flush({ version: 8, policy });
    const preview = http.expectOne('/api/v1/search/preview');
    expect(preview.request.method).toBe('POST');
    expect(preview.request.body).toEqual({ q: 'invoice', entity: 'TASK', policy });
    preview.flush({ result: { query: 'invoice', totalHits: 0, hits: [], foundHits: 0, hasMore: false, source: 'TYPESENSE', degraded: false }, activeProfile: 'MIXED' });
    const start = http.expectOne('/api/v1/search/jobs');
    expect(start.request.method).toBe('POST');
    expect(start.request.body).toEqual({ requestId: 'request-1', action: 'REBUILD' });
    start.flush({ id: 'job-1', state: 'QUEUED' });
    const cancel = http.expectOne('/api/v1/search/jobs/job-1/cancel');
    expect(cancel.request.method).toBe('POST');
    expect(cancel.request.body).toEqual({});
    cancel.flush({ id: 'job-1', state: 'CANCELLED' });
    const retry = http.expectOne('/api/v1/search/jobs/job-1/retry');
    expect(retry.request.method).toBe('POST');
    expect(retry.request.body).toEqual({ requestId: 'request-2' });
    retry.flush({ id: 'job-2', state: 'QUEUED' });
    http.verify();
  });

  it('keeps status, detail and Retry-After metadata for a locally-owned preview failure', () => {
    const { service, http } = setup();
    let failure: unknown;
    service.preview({ q: 'invoice' }).subscribe({ error: error => failure = error });

    http.expectOne('/api/v1/search/preview').flush(
      { code: 'RATE_LIMITED', detail: 'Повторите позже' },
      { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '3' } }
    );

    expect(failure).toMatchObject({
      status: 429,
      code: 'RATE_LIMITED',
      detail: 'Повторите позже',
      retryAfterSeconds: 3
    });
    http.verify();
  });
});
