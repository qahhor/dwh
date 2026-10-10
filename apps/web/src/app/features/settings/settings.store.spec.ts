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
import { SystemSettings } from './settings.api';
import { SettingsStore } from './settings.store';

function setup(
  get: (path: string) => Observable<unknown> = () => of({}),
  hasPermission: (form: string, action: string) => boolean = () => true,
) {
  const api = { get: vi.fn(get), patch: vi.fn(() => of({})) };
  const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn(), show: vi.fn() };
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
    const get = (path: string) =>
      of(path === '/settings/user' ? { 'user.theme': 'dark' } : { values: { 'system.x': '1' }, revision: 3 });
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
    expect(admin.store.systemRevision()).toBe(3);
  });

  it('reports a failed load once it ends, keeps the edits on screen and asks both again on refresh', async () => {
    const retried = new Subject<SystemSettings>();
    const systemAnswers: Observable<SystemSettings>[] = [
      of({ values: { 'system.company_name': 'Old' }, revision: 1 }),
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
    retried.next({ values: { 'system.company_name': 'Fresh' }, revision: 2 });
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

  it('refuses out-of-range, empty and non-numeric session lifetimes and quotas under their fields, sending nothing', () => {
    const { store, toast, api } = setup();
    const refused: [Record<string, string>, Record<string, string>][] = [
      [
        { 'security.session_lifetime_hours': '10000' },
        { 'security.session_lifetime_hours': 'settings.validation.session_lifetime' },
      ],
      [{ 'storage.default_user_quota_mb': '50' }, { 'storage.default_user_quota_mb': 'settings.validation.quota' }],
      [
        { 'security.session_lifetime_hours': '   ' },
        { 'security.session_lifetime_hours': 'settings.validation.session_lifetime' },
      ],
      [
        { 'security.session_lifetime_hours': 'letters' },
        { 'security.session_lifetime_hours': 'settings.validation.session_lifetime' },
      ],
      [
        { 'security.session_lifetime_hours': '720', 'storage.default_user_quota_mb': 'not-a-number' },
        { 'storage.default_user_quota_mb': 'settings.validation.quota' },
      ],
    ];

    for (const [settings, errors] of refused) {
      store.systemSettings.set(settings);
      store.saveSystemSettings();
      expect(store.systemErrors()).toEqual(errors);
    }
    expect(toast.error).not.toHaveBeenCalled();
    expect(api.patch).not.toHaveBeenCalled();

    // Fixing the field clears its error at once.
    store.setSystemSetting('storage.default_user_quota_mb', '2048');
    expect(store.systemErrors()).toEqual({});

    store.systemSettings.set({ 'security.session_lifetime_hours': '720', 'storage.default_user_quota_mb': '2048' });
    store.saveSystemSettings();
    expect(api.patch).toHaveBeenCalledOnce();
  });

  it('saves the system settings from their revision, keeps it in step, and offers a reload on a conflict', async () => {
    const answers: SystemSettings[] = [
      { values: { 'system.company_name': 'Old' }, revision: 4 },
      { values: { 'system.company_name': 'Theirs' }, revision: 6 },
    ];
    const { store, api, toast } = setup((path) => of(path === '/settings/system' ? answers.shift() : {}));
    await settle();

    store.setSystemSetting('system.company_name', 'Mine');
    store.saveSystemSettings();
    expect(api.patch).toHaveBeenLastCalledWith(
      '/settings/system',
      { 'system.company_name': 'Mine' },
      { notifyError: false, ifMatch: 4 },
    );
    expect(store.systemRevision()).toBe(5);

    api.patch.mockReturnValueOnce(throwError(() => ({ status: 409, code: 'revision_conflict', detail: 'moved' })));
    store.saveSystemSettings();
    expect(api.patch).toHaveBeenLastCalledWith('/settings/system', expect.anything(), {
      notifyError: false,
      ifMatch: 5,
    });
    expect(store.isSaving()).toBe(false);
    expect(toast.show).toHaveBeenCalledOnce();
    const reload = toast.show.mock.calls[0][4] as { run: () => void };
    reload.run();
    await settle();
    expect(store.systemSettings()).toEqual({ 'system.company_name': 'Theirs' });
    expect(store.systemRevision()).toBe(6);
  });
});
