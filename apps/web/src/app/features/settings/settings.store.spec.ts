import { ApplicationRef, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ThemeService } from '@core/services/theme.service';
import { ToastService } from '@core/services/toast.service';
import { translateTest } from '@testing/i18n-test.stub';
import { SettingsStore } from './settings.store';

function setup(
  get: (path: string) => Observable<unknown> = () => of({}),
  hasPermission: (form: string, action: string) => boolean = () => true,
) {
  const api = { get: vi.fn(get), patch: vi.fn(() => of({})) };
  const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
  const theme = { themePreference: signal('light'), setTheme: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      SettingsStore,
      { provide: ApiService, useValue: api },
      { provide: PermissionService, useValue: { hasPermission } },
      { provide: ThemeService, useValue: theme },
      { provide: ToastService, useValue: toast },
      { provide: I18nService, useValue: { translate: translateTest, setLanguage: vi.fn(() => of(undefined)) } },
    ],
  });
  return { store: TestBed.inject(SettingsStore), api, toast, theme };
}

/** Runs the resource effects and waits for their answers. */
async function settle(): Promise<void> {
  TestBed.tick();
  await TestBed.inject(ApplicationRef).whenStable();
}

describe('SettingsStore', () => {
  it('loads personal settings for everyone, applies their theme, and system settings only with the right', async () => {
    const get = (path: string) => of(path === '/settings/user' ? { 'user.theme': 'dark' } : { 'system.x': '1' });
    const viewer = setup(get, () => false);
    expect(viewer.store.isLoading()).toBe(true);
    await settle();

    expect(viewer.api.get).toHaveBeenCalledTimes(1);
    expect(viewer.api.get).toHaveBeenCalledWith('/settings/user');
    expect(viewer.store.userSettings()).toEqual({ 'user.theme': 'dark' });
    expect(viewer.store.systemSettings()).toEqual({});
    expect(viewer.theme.setTheme).toHaveBeenCalledWith('dark');
    expect(viewer.store.isLoading()).toBe(false);
    expect(viewer.store.loadError()).toBeNull();

    TestBed.resetTestingModule();
    const admin = setup(get);
    await settle();
    expect(admin.api.get).toHaveBeenCalledWith('/settings/system');
    expect(admin.store.systemSettings()).toEqual({ 'system.x': '1' });
  });

  it('reports a failed load once it ends, keeps the edits on screen and asks both again on refresh', async () => {
    const retried = new Subject<Record<string, string>>();
    const systemAnswers: Observable<Record<string, string>>[] = [
      of({ 'system.company_name': 'Old' }),
      throwError(() => ({ status: 503, detail: 'down' })),
      retried,
    ];
    const { store, api } = setup((path) => (path === '/settings/system' ? systemAnswers.shift()! : of({})));
    await settle();
    store.setSystemSetting('system.company_name', 'Typed');
    expect(store.systemSettings()).toEqual({ 'system.company_name': 'Typed' });

    store.loadAllSettings();
    await settle();
    expect(api.get).toHaveBeenCalledTimes(4);
    expect(store.loadError()).toBe('Не удалось загрузить настройки с сервера');
    expect(store.systemSettings()).toEqual({ 'system.company_name': 'Typed' });

    store.loadAllSettings();
    TestBed.tick();
    expect(store.isLoading()).toBe(true);
    expect(store.loadError()).toBeNull();
    retried.next({ 'system.company_name': 'Fresh' });
    await settle();
    expect(store.loadError()).toBeNull();
    expect(store.systemSettings()).toEqual({ 'system.company_name': 'Fresh' });
  });

  it('applies a chosen theme at once and the saved one again after a save', () => {
    const { store, theme, api } = setup();

    store.onThemeChange('dark');
    expect(theme.setTheme).toHaveBeenCalledWith('dark');
    expect(store.userSettings()['user.theme']).toBe('dark');

    store.userSettings.set({ 'user.theme': 'system' });
    store.saveUserSettings();
    expect(api.patch).toHaveBeenCalledWith('/settings/user', { 'user.theme': 'system' });
    expect(theme.setTheme).toHaveBeenCalledWith('system');
  });

  it('refuses out-of-range, empty and non-numeric session lifetimes and quotas before sending anything', () => {
    const { store, toast, api } = setup();
    const refused: Record<string, string>[] = [
      { 'security.session_lifetime_hours': '10000' },
      { 'storage.default_user_quota_mb': '50' },
      { 'security.session_lifetime_hours': '   ' },
      { 'security.session_lifetime_hours': 'letters' },
      { 'security.session_lifetime_hours': '720', 'storage.default_user_quota_mb': 'not-a-number' },
    ];

    for (const settings of refused) {
      toast.error.mockClear();
      store.systemSettings.set(settings);
      store.saveSystemSettings();
      expect(toast.error).toHaveBeenCalled();
    }
    expect(api.patch).not.toHaveBeenCalled();

    store.systemSettings.set({ 'security.session_lifetime_hours': '720', 'storage.default_user_quota_mb': '2048' });
    store.saveSystemSettings();
    expect(api.patch).toHaveBeenCalledOnce();
  });
});
