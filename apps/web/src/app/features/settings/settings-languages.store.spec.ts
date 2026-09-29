import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ThemeService } from '@core/services/theme.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { translateTest } from '@testing/i18n-test.stub';
import { SettingsLanguagesStore } from './settings-languages.store';
import { SettingsStore } from './settings.store';

const LEGACY_KEY = 'dwh_custom_languages';

function setup(get: (path: string) => unknown = () => ({})) {
  const api = { get: vi.fn((path: string) => of(get(path))), patch: vi.fn(() => of({})), put: vi.fn(() => of({})) };
  const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
  const i18n = {
    languages: signal([{ code: 'ru', name: 'Русский', builtin: true, active: true }]),
    translate: translateTest,
    setLanguage: vi.fn(() => of(undefined)),
    registerLanguage: vi.fn(() => of({})),
    refreshLanguages: vi.fn(() => of([])),
  };
  TestBed.configureTestingModule({
    providers: [
      SettingsStore,
      SettingsLanguagesStore,
      { provide: ApiService, useValue: api },
      { provide: PermissionService, useValue: { hasPermission: () => true } },
      { provide: ThemeService, useValue: { themePreference: signal('light'), setTheme: vi.fn() } },
      { provide: ToastService, useValue: toast },
      { provide: I18nService, useValue: i18n },
    ],
  });
  const confirm = vi.spyOn(TestBed.inject(SMTModalService), 'confirm');
  return { store: TestBed.inject(SettingsLanguagesStore), api, toast, i18n, confirm };
}

describe('SettingsLanguagesStore', () => {
  afterEach(() => localStorage.removeItem(LEGACY_KEY));

  it('migrates legacy browser translations atomically and removes them only after success', () => {
    localStorage.setItem(
      LEGACY_KEY,
      JSON.stringify({ de: { name: 'Deutsch', dict: { 'common.save': 'Alt speichern', unknown: 'ignore' } } }),
    );
    const { store, api, i18n, confirm } = setup((path) => {
      if (path === '/i18n/admin/languages/ru/translations')
        return { language: { revision: 4 }, entries: [{ key: 'common.save' }, { key: 'common.cancel' }] };
      if (path === '/i18n/admin/languages/de/translations')
        return {
          language: { revision: 7 },
          entries: [
            { key: 'common.save', overrideValue: null },
            { key: 'common.cancel', overrideValue: 'Abbrechen' },
          ],
        };
      return {};
    });
    i18n.languages.set([...i18n.languages(), { code: 'de', name: 'Deutsch', builtin: true, active: true }]);
    confirm.mockReturnValue(of(true));
    store.countLegacyLanguages();
    expect(store.legacyLanguageCount()).toBe(1);

    store.migrateLegacyLanguages();

    expect(confirm).toHaveBeenCalledOnce();
    expect(api.put).toHaveBeenCalledWith('/i18n/admin/languages/de/translations', {
      expectedRevision: 7,
      translations: { 'common.save': 'Alt speichern', 'common.cancel': 'Abbrechen' },
    });
    expect(localStorage.getItem(LEGACY_KEY)).toBeNull();
    expect(store.legacyLanguageCount()).toBe(0);
    expect(store.isMigratingLegacyLanguages()).toBe(false);
  });

  it('registers a legacy language the server does not know yet with its known keys only', () => {
    localStorage.setItem(
      LEGACY_KEY,
      JSON.stringify({ kk: { name: 'Қазақша', dict: { 'common.save': 'Сақтау', x: 'y' } } }),
    );
    const { store, i18n, confirm } = setup((path) =>
      path.endsWith('/ru/translations') ? { language: { revision: 1 }, entries: [{ key: 'common.save' }] } : {},
    );
    confirm.mockReturnValue(of(true));

    store.migrateLegacyLanguages();

    expect(i18n.registerLanguage).toHaveBeenCalledWith('kk', 'Қазақша', { 'common.save': 'Сақтау' });
    expect(i18n.refreshLanguages).toHaveBeenCalled();
  });

  it('keeps legacy browser translations untouched when the migration is declined', () => {
    const legacy = JSON.stringify({ de: { name: 'Deutsch', dict: { 'common.save': 'Alt speichern' } } });
    localStorage.setItem(LEGACY_KEY, legacy);
    const { store, api, confirm } = setup();
    confirm.mockReturnValue(of(false));

    store.migrateLegacyLanguages();

    expect(api.put).not.toHaveBeenCalled();
    expect(localStorage.getItem(LEGACY_KEY)).toBe(legacy);
  });

  it('refuses a malformed language code or dictionary and registers a valid language', () => {
    const { store, toast, i18n } = setup();

    store.saveNewLanguage('invalid_123_toolongformat', 'Test', '');
    store.saveNewLanguage('kk', 'Қазақша', '{bad json');
    expect(toast.error).toHaveBeenCalledTimes(2);
    expect(i18n.registerLanguage).not.toHaveBeenCalled();

    store.isAddLangModalOpen.set(true);
    store.saveNewLanguage(' KK ', ' Қазақша ', '{"common.save":"Сақтау"}');
    expect(i18n.registerLanguage).toHaveBeenCalledWith('kk', 'Қазақша', { 'common.save': 'Сақтау' });
    expect(store.isAddLangModalOpen()).toBe(false);
    expect(store.isAddingLang()).toBe(false);
    expect(toast.success).toHaveBeenCalled();
  });
});
