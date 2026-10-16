import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from './auth.service';
import { I18nService } from './i18n.service';

const LIVE_KEY = 'studio.live.locations.title';
const languages = [
  { code: 'ru', name: 'Russian', builtin: true, active: true, revision: 1, translated: 1, total: 1, coverage: 100 },
  { code: 'en', name: 'English', builtin: true, active: true, revision: 1, translated: 1, total: 1, coverage: 100 },
];
const user = { id: 17, name: 'Test', language: 'ru', forcePasswordChange: false };

describe('Dictionaries follow the authenticated access context', () => {
  let http: HttpTestingController;
  let i18n: I18nService;
  let auth: AuthService;

  beforeEach(async () => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: { url: '/tasks', navigate: vi.fn(), navigateByUrl: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    i18n = TestBed.inject(I18nService);
    auth = TestBed.inject(AuthService);
    const ready = i18n.initialize();
    http.expectOne('/api/v1/i18n/languages').flush(languages);
    await Promise.resolve();
    http.expectOne('/api/v1/i18n/ru').flush({ 'common.save': 'Save' });
    await ready;
  });
  afterEach(() => http.verify());

  function acceptSession(version = 1, permissions = ['studio.locations.view'], language = 'ru') {
    http.expectOne('/api/v1/auth/me').flush({ user: { ...user, language }, permissions, permissionsVersion: version });
  }
  function refreshDictionary(values: Record<string, string>) {
    http.expectOne('/api/v1/i18n/languages').flush(languages);
    http.expectOne('/api/v1/i18n/ru').flush(values);
  }
  function signIn() {
    auth.login('test', 'test').subscribe();
    http.expectOne('/api/v1/auth/login').flush({ step: 'success', user });
    acceptSession();
    refreshDictionary({ 'common.save': 'Save', [LIVE_KEY]: 'Locations' });
  }

  it('replaces the anonymous dictionary after login even when the language and catalog revision are unchanged', () => {
    expect(i18n.hasKey(LIVE_KEY)).toBe(false);
    signIn();
    expect(i18n.translate(LIVE_KEY)).toBe('Locations');
    auth.refreshMe().subscribe();
    acceptSession();
    http.expectNone('/api/v1/i18n/languages');
  });

  it('forgets authorized labels and cached alternate languages synchronously after logout', () => {
    signIn();
    i18n.setLanguage('en', false).subscribe();
    http.expectOne('/api/v1/i18n/en').flush({ 'common.save': 'Save', [LIVE_KEY]: 'Private label' });
    auth.logout();
    http.expectOne('/api/v1/auth/logout').flush(null);
    expect(i18n.hasKey(LIVE_KEY)).toBe(false);
    expect(i18n.getDictionary('en')[LIVE_KEY]).toBeUndefined();
    expect(i18n.translate('common.save')).toBe('Save');
  });

  it('removes revoked labels immediately and keeps them absent if reloading fails', () => {
    signIn();
    auth.refreshMe().subscribe();
    acceptSession(2, []);
    expect(i18n.hasKey(LIVE_KEY)).toBe(false);
    http.expectOne('/api/v1/i18n/languages').flush(null, { status: 503, statusText: 'Unavailable' });
    expect(i18n.hasKey(LIVE_KEY)).toBe(false);
    expect(i18n.loadError()).toBeTruthy();
  });

  it('does not restore a private fallback when a language preference save fails after logout', () => {
    signIn();
    i18n.setLanguage('en').subscribe({ error: () => undefined });
    http.expectOne('/api/v1/i18n/en').flush({ 'common.save': 'Save', [LIVE_KEY]: 'Private label' });
    const save = http.expectOne('/api/v1/settings/user');
    auth.logout();
    http.expectOne('/api/v1/auth/logout').flush(null);
    save.flush(null, { status: 503, statusText: 'Unavailable' });
    expect(i18n.hasKey(LIVE_KEY)).toBe(false);
  });

  it('refreshes both the active language and Russian fallback for the next user without accepting older responses', () => {
    signIn();
    i18n.setLanguage('en', false).subscribe();
    http.expectOne('/api/v1/i18n/en').flush({ 'common.save': 'Save', [LIVE_KEY]: 'First user' });
    auth.refreshMe().subscribe();
    acceptSession(2, ['studio.locations.view'], 'en');
    http.expectOne('/api/v1/i18n/languages').flush(languages);
    const old = http.expectOne('/api/v1/i18n/ru');
    auth.checkSession().subscribe();
    http.expectOne('/api/v1/auth/me').flush({
      user: { ...user, id: 18, language: 'en' },
      permissions: ['studio.locations.view'],
      permissionsVersion: 2,
    });
    http.expectOne('/api/v1/i18n/languages').flush(languages);
    http.expectOne('/api/v1/i18n/ru').flush({ 'common.save': 'Save', [LIVE_KEY]: 'Second user fallback' });
    http.expectOne('/api/v1/i18n/en').flush({ 'common.save': 'Save', [LIVE_KEY]: 'Second user' });
    old.flush({ 'common.save': 'Save', [LIVE_KEY]: 'Stale first user' });
    expect(i18n.translate(LIVE_KEY)).toBe('Second user');
    expect(i18n.getDictionary('ru')[LIVE_KEY]).toBe('Second user fallback');
  });

  it('keeps the next user language while a context refresh finishes in the previous language', () => {
    signIn();
    i18n.setLanguage('en', false).subscribe();
    http.expectOne('/api/v1/i18n/en').flush({ 'common.save': 'English save', [LIVE_KEY]: 'First user' });
    auth.checkSession().subscribe();
    http.expectOne('/api/v1/auth/me').flush({
      user: { ...user, id: 18, language: 'ru' },
      permissions: ['studio.locations.view'],
      permissionsVersion: 2,
    });
    http.expectOne('/api/v1/i18n/ru').flush({ 'common.save': 'Russian save', [LIVE_KEY]: 'Second user' });
    http.expectOne('/api/v1/i18n/languages').flush(languages);
    http.expectOne('/api/v1/i18n/ru').flush({ 'common.save': 'Russian save', [LIVE_KEY]: 'Second user' });
    for (const staleLanguage of http.match('/api/v1/i18n/en')) {
      staleLanguage.flush({ 'common.save': 'English save', [LIVE_KEY]: 'Second user English' });
    }
    expect(i18n.currentLang()).toBe('ru');
    expect(i18n.translate('common.save')).toBe('Russian save');
  });

  it('refreshes after activation instead of sharing a dictionary read from before activation', () => {
    signIn();
    i18n.refreshLanguage('ru').subscribe();
    http.expectOne('/api/v1/i18n/languages').flush(languages);
    const old = http.expectOne('/api/v1/i18n/ru');
    i18n.refreshLanguage('ru').subscribe();
    http.expectOne('/api/v1/i18n/languages').flush(languages);
    http.expectOne('/api/v1/i18n/ru').flush({ 'common.save': 'Save', [LIVE_KEY]: 'Newly activated' });
    old.flush({ 'common.save': 'Save' });
    expect(i18n.translate(LIVE_KEY)).toBe('Newly activated');
    expect(i18n.getDictionary('ru')[LIVE_KEY]).toBe('Newly activated');
  });

  it('keeps a preferred-language request started after the access refresh began', () => {
    auth.checkSession().subscribe();
    acceptSession(1, ['studio.locations.view'], 'en');
    const preferred = http.expectOne('/api/v1/i18n/en');
    http.expectOne('/api/v1/i18n/languages').flush(languages);
    http.expectOne('/api/v1/i18n/ru').flush({ 'common.save': 'Russian save', [LIVE_KEY]: 'Fallback' });
    preferred.flush({ 'common.save': 'English save', [LIVE_KEY]: 'Preferred language' });
    expect(i18n.currentLang()).toBe('en');
    expect(i18n.translate(LIVE_KEY)).toBe('Preferred language');
  });

  it('does not restore labels from a dictionary request that finishes after logout', () => {
    signIn();
    auth.refreshMe().subscribe();
    acceptSession(2);
    http.expectOne('/api/v1/i18n/languages').flush(languages);
    const pending = http.expectOne('/api/v1/i18n/ru');
    auth.logout();
    http.expectOne('/api/v1/auth/logout').flush(null);
    pending.flush({ 'common.save': 'Save', [LIVE_KEY]: 'Stale private label' });
    expect(i18n.hasKey(LIVE_KEY)).toBe(false);
    expect(i18n.getDictionary('ru')[LIVE_KEY]).toBeUndefined();
  });
});
