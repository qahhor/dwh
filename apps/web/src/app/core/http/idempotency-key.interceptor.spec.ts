import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { IDEMPOTENCY_HEADER, idempotencyKeyInterceptor, newKey } from './idempotency-key.interceptor';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('idempotencyKeyInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(withInterceptors([idempotencyKeyInterceptor])), provideHttpClientTesting()
    ] });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    backend.verify();
    vi.useRealTimers();
  });

  it.each(['POST', 'PUT', 'PATCH', 'DELETE'])('gives a %s to the API its own key', method => {
    http.request(method, '/api/v1/ms/tasks', { body: { title: 'A' } }).subscribe();
    const request = backend.expectOne('/api/v1/ms/tasks');
    expect(request.request.headers.get(IDEMPOTENCY_HEADER)).toMatch(UUID);
    request.flush({});
  });

  it('uses a new key for every change', () => {
    http.post('/api/v1/ms/tasks', {}).subscribe();
    http.post('/api/v1/ms/tasks', {}).subscribe();
    const [first, second] = backend.match('/api/v1/ms/tasks');
    expect(first.request.headers.get(IDEMPOTENCY_HEADER)).not.toBe(second.request.headers.get(IDEMPOTENCY_HEADER));
    first.flush({});
    second.flush({});
  });

  it.each([
    ['a read', () => TestBed.inject(HttpClient).get('/api/v1/ms/tasks'), '/api/v1/ms/tasks'],
    ['sign-in', () => TestBed.inject(HttpClient).post('/api/v1/auth/login', {}), '/api/v1/auth/login'],
    ['a new API token', () => TestBed.inject(HttpClient).post('/api/v1/iam/profile/api-tokens', {}), '/api/v1/iam/profile/api-tokens'],
    ['a file upload', () => TestBed.inject(HttpClient).post('/api/v1/upl/packages', new FormData()), '/api/v1/upl/packages'],
    ['a large body', () => TestBed.inject(HttpClient).post('/api/v1/md/lists', { text: 'x'.repeat(70_000) }), '/api/v1/md/lists'],
    ['a download', () => TestBed.inject(HttpClient).post('/api/v1/exports/file', {}, { responseType: 'blob' }), '/api/v1/exports/file'],
    ['another site', () => TestBed.inject(HttpClient).post('https://example.test/hook', {}), 'https://example.test/hook']
  ] as const)('leaves %s without a key', (_name, send, url) => {
    send().subscribe();
    const request = backend.expectOne(url);
    expect(request.request.headers.has(IDEMPOTENCY_HEADER)).toBe(false);
    request.flush(request.request.responseType === 'blob' ? new Blob() : {});
  });

  it('keeps a key the caller chose', () => {
    http.post('/api/v1/ms/tasks', {}, { headers: { [IDEMPOTENCY_HEADER]: 'mine' } }).subscribe();
    const request = backend.expectOne('/api/v1/ms/tasks');
    expect(request.request.headers.get(IDEMPOTENCY_HEADER)).toBe('mine');
    request.flush({});
  });

  it('repeats a change whose answer was lost, under the same key', () => {
    vi.useFakeTimers();
    const result = vi.fn();
    http.post('/api/v1/ms/tasks', { title: 'A' }).subscribe(result);

    const first = backend.expectOne('/api/v1/ms/tasks');
    const key = first.request.headers.get(IDEMPOTENCY_HEADER);
    first.error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    vi.advanceTimersByTime(1_000);
    const second = backend.expectOne('/api/v1/ms/tasks');
    expect(second.request.headers.get(IDEMPOTENCY_HEADER)).toBe(key);
    second.flush(null, { status: 504, statusText: 'Gateway Timeout' });
    vi.advanceTimersByTime(3_000);
    const third = backend.expectOne('/api/v1/ms/tasks');
    expect(third.request.headers.get(IDEMPOTENCY_HEADER)).toBe(key);
    third.flush({ id: 5 });
    expect(result).toHaveBeenCalledWith({ id: 5 });
  });

  it('gives up after two repeats and waits as long as Retry-After asks', () => {
    vi.useFakeTimers();
    const failed = vi.fn();
    http.post('/api/v1/ms/tasks', {}).subscribe({ error: failed });
    backend.expectOne('/api/v1/ms/tasks').flush(null, { status: 503, statusText: 'Unavailable', headers: { 'Retry-After': '5' } });
    vi.advanceTimersByTime(4_999);
    backend.expectNone('/api/v1/ms/tasks');
    vi.advanceTimersByTime(1);
    backend.expectOne('/api/v1/ms/tasks').flush(null, { status: 502, statusText: 'Bad Gateway' });
    vi.advanceTimersByTime(3_000);
    backend.expectOne('/api/v1/ms/tasks').flush(null, { status: 502, statusText: 'Bad Gateway' });
    expect(failed).toHaveBeenCalledTimes(1);
  });

  it.each([400, 409, 422, 500])('does not repeat a change the server answered with %s', status => {
    const failed = vi.fn();
    http.post('/api/v1/ms/tasks', {}).subscribe({ error: failed });
    backend.expectOne('/api/v1/ms/tasks').flush(null, { status, statusText: 'No' });
    backend.expectNone('/api/v1/ms/tasks');
    expect(failed).toHaveBeenCalledTimes(1);
  });

  it('makes version 4 UUIDs even without crypto.randomUUID', () => {
    const original = crypto.randomUUID;
    try {
      Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true });
      expect(newKey()).toMatch(UUID);
    } finally {
      Object.defineProperty(crypto, 'randomUUID', { value: original, configurable: true });
    }
    expect(newKey()).toMatch(UUID);
  });
});
