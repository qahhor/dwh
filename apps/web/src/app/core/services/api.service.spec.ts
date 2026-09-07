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
  it.each([['12', 12], ['0', 0], ['-1', undefined], ['1.2', undefined], ['NaN', undefined], ['99999999999999999999', undefined], [null, undefined]])
    ('preserves only valid optional retry seconds from HTTP header %s', (header, expected) => {
      TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
      let failure: any;
      TestBed.inject(CommandPaletteService).search('test').subscribe({ error: error => failure = error });
      const http = TestBed.inject(HttpTestingController);
      http.expectOne(req => req.url === '/api/v1/search').flush({ code: 'RATE_LIMITED', detail: 'retry', retryAfterSeconds: 666 },
        { status: 429, statusText: 'Too Many Requests', headers: header === null ? {} : { 'Retry-After': header } });
      expect(failure.retryAfterSeconds).toBe(expected);
      expect(failure.status).toBe(429);
      expect(TestBed.inject(ToastService).toasts()).toEqual([]);
      http.verify();
    });
  function serviceFor(body: object, translations: Record<string, string> = {}) {
    const http = {
      get: vi.fn(() => throwError(() => new HttpErrorResponse({
        status: 409,
        url: '/api/v1/i18n/admin/languages/de/translations',
        error: body
      })))
    } as unknown as HttpClient;
    const toast = { error: vi.fn() } as unknown as ToastService;
    const i18n = {
      translate: (key: string) => translations[key] ?? key
    } as I18nService;
    return { service: new ApiService(http, toast, i18n), toast };
  }

  it('uses a catalog message for a known stable error code', async () => {
    const { service, toast } = serviceFor(
      { code: 'i18n_revision_conflict', detail: 'server detail' },
      { 'error.i18n_revision_conflict': 'Пакет уже изменён другим администратором' }
    );

    await expect(firstValueFrom(service.get('/test'))).rejects.toMatchObject({
      code: 'i18n_revision_conflict',
      detail: 'Пакет уже изменён другим администратором'
    });
    expect(toast.error).toHaveBeenCalledWith('Пакет уже изменён другим администратором');
  });

  it('preserves an unknown server detail as the fallback', async () => {
    const { service, toast } = serviceFor({ code: 'future_error', detail: 'Подробность сервера' });

    await expect(firstValueFrom(service.get('/test'))).rejects.toMatchObject({
      code: 'future_error', detail: 'Подробность сервера'
    });
    expect(toast.error).toHaveBeenCalledWith('Подробность сервера');
  });

  it('does not show a toast when the caller handles the error locally', async () => {
    const { service, toast } = serviceFor({ code: 'future_error', detail: 'Подробность сервера' });

    await expect(firstValueFrom(service.get('/test', undefined, { notifyError: false }))).rejects.toMatchObject({
      code: 'future_error', detail: 'Подробность сервера'
    });
    expect(toast.error).not.toHaveBeenCalled();
  });
});
