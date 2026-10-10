import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NEVER, of, throwError } from 'rxjs';
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
  it('registers a language the dialog checked and closes the dialog', () => {
    const { store, toast, i18n } = setup();

    store.openAddLanguage();
    store.saveNewLanguage({ code: 'kk', name: 'Қазақша', dictionary: { 'common.save': 'Сақтау' } });
    expect(i18n.registerLanguage).toHaveBeenCalledWith('kk', 'Қазақша', { 'common.save': 'Сақтау' });
    expect(store.isAddLangModalOpen()).toBe(false);
    expect(store.isAddingLang()).toBe(false);
    expect(toast.success).toHaveBeenCalled();
  });

  it('sends one request while a save runs', () => {
    const { store, i18n } = setup();
    i18n.registerLanguage.mockReturnValue(NEVER);

    store.saveNewLanguage({ code: 'kk', name: 'Қазақша', dictionary: {} });
    store.saveNewLanguage({ code: 'kk', name: 'Қазақша', dictionary: {} });
    expect(i18n.registerLanguage).toHaveBeenCalledTimes(1);
  });

  it('puts a refusal about a field under it and keeps the dialog open; any other refusal is a toast', () => {
    const { store, toast, i18n } = setup();
    store.openAddLanguage();
    i18n.registerLanguage.mockReturnValueOnce(
      throwError(() => ({ status: 422, errors: [{ field: 'code', message: 'Язык kk уже есть' }] })),
    );
    store.saveNewLanguage({ code: 'kk', name: 'Қазақша', dictionary: {} });
    expect(store.addLanguageErrors()).toEqual({ code: 'Язык kk уже есть' });
    expect(toast.error).not.toHaveBeenCalled();
    expect(store.isAddLangModalOpen()).toBe(true);

    i18n.registerLanguage.mockReturnValueOnce(throwError(() => ({ status: 500, detail: 'Сбой' })));
    store.saveNewLanguage({ code: 'kk', name: 'Қазақша', dictionary: {} });
    expect(store.addLanguageErrors()).toEqual({});
    expect(toast.error).toHaveBeenCalledWith('Сбой');
  });

  it('refreshes the languages after the editor saved one', () => {
    const { store, i18n } = setup();

    store.openLanguageEditor('uz');
    expect(store.editingLanguageCode()).toBe('uz');
    store.onLanguageSaved();
    expect(i18n.refreshLanguages).toHaveBeenCalled();
  });
});
