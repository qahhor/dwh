import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { translateTest } from '@testing/i18n-test.stub';
import { SettingsLanguagesStore } from './settings-languages.store';

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
      SettingsLanguagesStore,
      { provide: ApiService, useValue: api },
      { provide: ToastService, useValue: toast },
      { provide: I18nService, useValue: i18n },
    ],
  });
  return { store: TestBed.inject(SettingsLanguagesStore), api, toast, i18n };
}

describe('SettingsLanguagesStore', () => {
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

  it('refreshes the languages after the editor saved one', () => {
    const { store, i18n } = setup();

    store.openLanguageEditor('uz');
    expect(store.editingLanguageCode()).toBe('uz');
    store.onLanguageSaved();
    expect(i18n.refreshLanguages).toHaveBeenCalled();
  });
});
