import { Injector, runInInjectionContext } from '@angular/core';
import { HttpClient, HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CommandPaletteService } from './command-palette.service';
import { firstValueFrom, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { I18nService } from './i18n.service';
import { ApiService } from './api.service';
import { ToastService } from './toast.service';

describe('ApiService localized Problem Details', () => {
  it.each(['PATCH', 'DELETE'] as const)('%s keeps default toast and allows inline ownership', (method) => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const api = TestBed.inject(ApiService);
    const http = TestBed.inject(HttpTestingController);
    const toast = vi.spyOn(TestBed.inject(ToastService), 'error');
    for (const inline of [false, true]) {
      let failure: unknown;
      const request =
        method === 'PATCH'
          ? api.patch('/iam/org-units/7', { name: 'New' }, inline ? { notifyError: false } : undefined)
          : api.delete('/iam/org-units/7', inline ? { notifyError: false } : undefined);
      request.subscribe({ error: (error: unknown) => (failure = error) });
      http
        .expectOne((req) => req.method === method && req.url === '/api/v1/iam/org-units/7')
        .flush({ code: 'CONFLICT', detail: 'Conflict detail' }, { status: 409, statusText: 'Conflict' });
      expect(failure).toMatchObject({ status: 409, code: 'CONFLICT', detail: 'Conflict detail' });
      expect(toast).toHaveBeenCalledTimes(1);
    }
    http.verify();
  });

  it('sends the revision a change is made from as If-Match, and nothing when there is none (plan item 3.6)', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const api = TestBed.inject(ApiService);
    const http = TestBed.inject(HttpTestingController);

    api.put('/notes/5', { title: 'x' }, { ifMatch: 3 }).subscribe();
    api.patch('/tasks/projects/5', { name: 'y' }).subscribe();

    expect(http.expectOne('/api/v1/notes/5').request.headers.get('If-Match')).toBe('"3"');
    expect(http.expectOne('/api/v1/tasks/projects/5').request.headers.has('If-Match')).toBe(false);
  });

  it('lets PUT callers own a conflict locally without losing its status or detail', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    let failure: unknown;

    TestBed.inject(ApiService)
      .put('/search/settings', { version: 7 }, { notifyError: false })
      .subscribe({ error: (error) => (failure = error) });

    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne((req) => req.method === 'PUT' && req.url === '/api/v1/search/settings')
      .flush({ code: 'CONFLICT', detail: 'Настройки уже изменены' }, { status: 409, statusText: 'Conflict' });

    expect(failure).toMatchObject({ status: 409, code: 'CONFLICT', detail: 'Настройки уже изменены' });
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);
    http.verify();
  });

  it.each([
    ['12', 12],
    ['0', 0],
    ['-1', undefined],
    ['1.2', undefined],
    ['NaN', undefined],
    ['99999999999999999999', undefined],
    [null, undefined],
  ])('preserves only valid optional retry seconds from HTTP header %s', (header, expected) => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    let failure: any;
    TestBed.inject(CommandPaletteService)
      .search('test')
      .subscribe({ error: (error) => (failure = error) });
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne((req) => req.url === '/api/v1/search')
      .flush(
        { code: 'RATE_LIMITED', detail: 'retry', retryAfterSeconds: 666 },
        { status: 429, statusText: 'Too Many Requests', headers: header === null ? {} : { 'Retry-After': header } },
      );
    expect(failure.retryAfterSeconds).toBe(expected);
    expect(failure.status).toBe(429);
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);
    http.verify();
  });
  function serviceFor(body: object, translations: Record<string, string> = {}) {
    const http = {
      get: vi.fn(() =>
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              url: '/api/v1/i18n/admin/languages/de/translations',
              error: body,
            }),
        ),
      ),
    } as unknown as HttpClient;
    const toast = { error: vi.fn() } as unknown as ToastService;
    const i18n = {
      translate: (key: string, params?: Record<string, string | number>) =>
        Object.entries(params ?? {}).reduce(
          (text, [name, value]) => text.replace(`{${name}}`, String(value)),
          translations[key] ?? key,
        ),
    } as I18nService;
    return { service: withDeps(http, toast, i18n), toast };
  }

  it('renders the text the server names by key, with its parameters, from the client catalog', async () => {
    const { service, toast } = serviceFor(
      {
        code: 'i18n_language_not_found',
        detail: 'server rendering',
        messageKey: 'error.md.language_not_found',
        params: { code: 'de' },
      },
      { 'error.md.language_not_found': 'Язык {code} не найден' },
    );

    await expect(firstValueFrom(service.get('/test'))).rejects.toMatchObject({
      code: 'i18n_language_not_found',
      detail: 'Язык de не найден',
    });
    expect(toast.error).toHaveBeenCalledWith('Язык de не найден');
  });

  it('keeps the server text when the client catalog lacks the key', async () => {
    const { service } = serviceFor({ code: 'conflict', detail: 'Текст сервера', messageKey: 'error.new_key' });

    await expect(firstValueFrom(service.get('/test'))).rejects.toMatchObject({ detail: 'Текст сервера' });
  });

  it('keeps a specific server detail without a key; the code text only fills an empty one', async () => {
    const translations = { 'error.i18n_revision_conflict': 'Пакет уже изменён другим администратором' };
    const specific = serviceFor({ code: 'i18n_revision_conflict', detail: 'Точный текст сервера' }, translations);
    await expect(firstValueFrom(specific.service.get('/test'))).rejects.toMatchObject({
      detail: 'Точный текст сервера',
    });

    const empty = serviceFor({ code: 'i18n_revision_conflict' }, translations);
    await expect(firstValueFrom(empty.service.get('/test'))).rejects.toMatchObject({
      detail: 'Пакет уже изменён другим администратором',
    });
  });

  it('preserves an unknown server detail as the fallback', async () => {
    const { service, toast } = serviceFor({ code: 'future_error', detail: 'Подробность сервера' });

    await expect(firstValueFrom(service.get('/test'))).rejects.toMatchObject({
      code: 'future_error',
      detail: 'Подробность сервера',
    });
    expect(toast.error).toHaveBeenCalledWith('Подробность сервера');
  });

  it('does not show a toast when the caller handles the error locally', async () => {
    const { service, toast } = serviceFor({ code: 'future_error', detail: 'Подробность сервера' });

    await expect(firstValueFrom(service.get('/test', undefined, { notifyError: false }))).rejects.toMatchObject({
      code: 'future_error',
      detail: 'Подробность сервера',
    });
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('passes field errors of problem+json to the caller', async () => {
    const http = {
      get: vi.fn(() =>
        throwError(
          () =>
            new HttpErrorResponse({
              status: 422,
              url: '/api/v1/test',
              error: {
                code: 'validation_failed',
                detail: 'UPL_FORMAT_INVALID',
                errors: [{ field: 'sheets[0].columns[1].keyMask', code: 'UPL_KEY_MASK_REQUIRED', message: 'x' }],
              },
            }),
        ),
      ),
    } as unknown as HttpClient;
    const service = withDeps(
      http,
      { error: vi.fn() } as unknown as ToastService,
      { translate: (key: string) => key } as I18nService,
    );

    await expect(firstValueFrom(service.get('/test', undefined, { notifyError: false }))).rejects.toMatchObject({
      status: 422,
      detail: 'UPL_FORMAT_INVALID',
      errors: [{ field: 'sheets[0].columns[1].keyMask', code: 'UPL_KEY_MASK_REQUIRED', message: 'x' }],
    });
  });
});

/** ApiService takes its collaborators by inject(); build it in an injector holding the fakes. */
function withDeps(http: HttpClient, toast: ToastService, i18n: I18nService): ApiService {
  const injector = Injector.create({
    providers: [
      { provide: HttpClient, useValue: http },
      { provide: ToastService, useValue: toast },
      { provide: I18nService, useValue: i18n },
    ],
  });
  return runInInjectionContext(injector, () => new ApiService());
}
