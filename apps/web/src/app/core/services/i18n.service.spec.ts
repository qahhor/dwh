import { HttpClient } from '@angular/common/http';
import { firstValueFrom, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nService } from './i18n.service';

describe('I18nService', () => {
  const languages = [
    { code: 'ru', name: 'Русский', builtin: true, active: true, revision: 1, translated: 2, total: 2, coverage: 100 },
    { code: 'de', name: 'Deutsch', builtin: true, active: true, revision: 1, translated: 2, total: 2, coverage: 100 }
  ];
  const ru = { 'common.save': 'Сохранить', 'welcome.user': 'Здравствуйте, {name}!' };
  const de = { 'common.save': 'Speichern', 'welcome.user': 'Hallo, {name}!' };

  beforeEach(() => localStorage.clear());

  function createService(options: { failPatch?: boolean; omitGerman?: boolean } = {}) {
    const http = {
      get: vi.fn((url: string) => {
        if (url.endsWith('/languages')) return of(languages);
        if (url.endsWith('/ru')) return of(ru);
        if (url.endsWith('/de')) return options.omitGerman ? throwError(() => new Error('offline')) : of(de);
        return throwError(() => new Error(`unexpected GET ${url}`));
      }),
      patch: vi.fn(() => options.failPatch
        ? throwError(() => new Error('save failed'))
        : of(undefined)),
      post: vi.fn(() => of({}))
    } as unknown as HttpClient;
    return { service: new I18nService(http), http };
  }

  it('initializes the registry, Russian fallback, and saved language', async () => {
    localStorage.setItem('dwh_lang', 'de');
    const { service } = createService();

    await service.initialize();

    expect(service.languages()).toHaveLength(2);
    expect(service.currentLang()).toBe('de');
    expect(service.translate('common.save')).toBe('Speichern');
    expect(service.translate('welcome.user', { name: 'Anna' })).toBe('Hallo, Anna!');
  });

  it('falls back to Russian when the saved language is unavailable', async () => {
    localStorage.setItem('dwh_lang', 'xx');
    const { service } = createService();

    await service.initialize();

    expect(service.currentLang()).toBe('ru');
    expect(localStorage.getItem('dwh_lang')).toBe('ru');
    expect(service.translate('common.save')).toBe('Сохранить');
  });

  it('switches from the in-memory cache without a second dictionary request', async () => {
    const { service, http } = createService();
    await service.initialize();

    await firstValueFrom(service.setLanguage('de', false));
    await firstValueFrom(service.setLanguage('ru', false));
    await firstValueFrom(service.setLanguage('de', false));

    expect(service.currentLang()).toBe('de');
    expect((http.get as ReturnType<typeof vi.fn>).mock.calls.filter(call => call[0].endsWith('/de'))).toHaveLength(1);
  });

  it('rolls the visible language back when server preference persistence fails', async () => {
    const { service } = createService({ failPatch: true });
    await service.initialize();

    await expect(firstValueFrom(service.setLanguage('de'))).rejects.toBeDefined();

    expect(service.currentLang()).toBe('ru');
    expect(service.translate('common.save')).toBe('Сохранить');
    expect(localStorage.getItem('dwh_lang')).toBe('ru');
  });

  it('refreshes an active dictionary after an administrator saves it', async () => {
    const { service, http } = createService();
    await service.initialize();
    await firstValueFrom(service.setLanguage('de', false));
    (http.get as ReturnType<typeof vi.fn>).mockImplementation((url: string) => {
      if (url.endsWith('/de')) return of({ ...de, 'common.save': 'Jetzt speichern' });
      if (url.endsWith('/languages')) return of([{ ...languages[0] }, { ...languages[1], revision: 2 }]);
      if (url.endsWith('/ru')) return of(ru);
      return throwError(() => new Error(`unexpected GET ${url}`));
    });

    await firstValueFrom(service.refreshLanguage('de'));

    expect(service.translate('common.save')).toBe('Jetzt speichern');
  });

  it('invalidates the active fallback dictionary when Russian changes', async () => {
    const { service, http } = createService();
    await service.initialize();
    await firstValueFrom(service.setLanguage('de', false));
    (http.get as ReturnType<typeof vi.fn>).mockImplementation((url: string) => {
      if (url.endsWith('/languages')) {
        return of([{ ...languages[0], revision: 2 }, languages[1]]);
      }
      if (url.endsWith('/ru')) {
        return of({ ...ru, 'fallback.only': 'Обновлённый русский текст' });
      }
      if (url.endsWith('/de')) {
        return of({ ...de, 'fallback.only': 'Обновлённый русский текст' });
      }
      return throwError(() => new Error(`unexpected GET ${url}`));
    });

    await firstValueFrom(service.refreshLanguage('ru'));

    expect(service.currentLang()).toBe('de');
    expect(service.translate('fallback.only')).toBe('Обновлённый русский текст');
    expect((http.get as ReturnType<typeof vi.fn>).mock.calls.filter(call => call[0].endsWith('/de'))).toHaveLength(2);
  });

  it('rejects an invalid custom language code before sending it to the server', async () => {
    const { service, http } = createService();

    await expect(firstValueFrom(service.registerLanguage('../ru', 'Invalid', {}))).rejects.toBeDefined();

    expect(http.post).not.toHaveBeenCalled();
  });
});
