import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, Subject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { I18nService } from '../../core/services/i18n.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { SettingsComponent } from './settings.component';
import { translateTest } from '../../../testing/i18n-test.stub';

describe('SettingsComponent UI contracts', () => {
  async function createFixture(api: object = {
    get: vi.fn(() => of({})),
    patch: vi.fn(() => of({}))
  }) {
    await TestBed.configureTestingModule({
      imports: [SettingsComponent],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: PermissionService, useValue: { hasPermission: () => true } },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
        {
          provide: I18nService,
          useValue: {
            currentLang: signal('ru'),
            languages: signal([
              { code: 'ru', name: 'Русский', builtin: true, active: true },
              { code: 'de', name: 'Deutsch', builtin: true, active: true },
              { code: 'tr', name: 'Türkçe', builtin: true, active: true }
            ]),
            translate: translateTest,
            setLanguage: vi.fn(() => of(undefined)),
            registerLanguage: vi.fn(() => of({})),
            refreshLanguages: vi.fn(() => of([])),
            exportDictionary: vi.fn(() => '{}')
          }
        }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(SettingsComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('renders settings that arrive after the initial change-detection pass', async () => {
    const systemSettings = new Subject<Record<string, string>>();
    const userSettings = new Subject<Record<string, string>>();
    const api = {
      get: vi.fn((path: string) => path === '/settings/system' ? systemSettings : userSettings),
      patch: vi.fn(() => of({}))
    };
    const fixture = await createFixture(api);
    fixture.autoDetectChanges();

    systemSettings.next({
      'system.company_name': 'Persisted Company',
      'system.default_language': 'en',
      'system.default_timezone': 'UTC',
      'system.date_format': 'yyyy-MM-dd HH:mm'
    });
    userSettings.next({ 'user.theme': 'light' });
    await fixture.whenStable();

    const companyName = fixture.nativeElement.querySelector('#settings-company-name') as HTMLInputElement;
    expect(companyName.value).toBe('Persisted Company');
  });

  it('connects settings tabs and general fields', async () => {
    const fixture = await createFixture();

    expect(fixture.nativeElement.querySelector('[role="tablist"][aria-label="Разделы настроек"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#settings-general-tab[aria-selected="true"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#settings-general-panel[role="tabpanel"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="settings-company-name"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="settings-default-language"]')).not.toBeNull();
    const language = fixture.nativeElement.querySelector('#settings-default-language') as HTMLSelectElement;
    expect(Array.from(language.options).map(option => option.value)).toEqual(['ru', 'de', 'tr']);
  });

  it('names security values and switches', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.activeTab = 'security';
    fixture.detectChanges();

    const passwordLength = fixture.nativeElement.querySelector('#settings-password-length') as HTMLInputElement;
    expect(fixture.nativeElement.querySelector(`label[for="${passwordLength.id}"]`)).not.toBeNull();
    expect(passwordLength.min).toBe('8');
    expect(passwordLength.getAttribute('aria-describedby')).toBe('settings-password-length-hint');
    expect(fixture.nativeElement.querySelector('#settings-require-2fa[aria-labelledby="settings-require-2fa-label"]')).not.toBeNull();
  });

  it('keeps operational status out of settings and exposes no custom-module controls', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as { get: ReturnType<typeof vi.fn> };

    expect(api.get).not.toHaveBeenCalledWith('/system/info');
    expect(api.get).not.toHaveBeenCalledWith('/system/license-info');
    expect(api.get).not.toHaveBeenCalledWith('/modules');
    expect(fixture.nativeElement.querySelector('#settings-modules-tab')).toBeNull();

    expect(fixture.nativeElement.querySelector('#settings-system-tab')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Control Plane');
    expect(fixture.nativeElement.textContent).not.toContain('Лиценз');
  });

  it('migrates legacy browser translations atomically and removes them only after success', async () => {
    localStorage.setItem('dwh_custom_languages', JSON.stringify({
      de: { name: 'Deutsch', dict: { 'common.save': 'Alt speichern', unknown: 'ignore' } }
    }));
    const api = {
      get: vi.fn((path: string) => {
        if (path === '/i18n/admin/languages/ru/translations') {
          return of({ language: { revision: 4 }, entries: [
            { key: 'common.save' }, { key: 'common.cancel' }
          ] });
        }
        if (path === '/i18n/admin/languages/de/translations') {
          return of({ language: { revision: 7 }, entries: [
            { key: 'common.save', overrideValue: null },
            { key: 'common.cancel', overrideValue: 'Abbrechen' }
          ] });
        }
        return of({});
      }),
      patch: vi.fn(() => of({})),
      put: vi.fn(() => of({}))
    };
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const fixture = await createFixture(api);

    fixture.componentInstance.migrateLegacyLanguages();

    expect(api.put).toHaveBeenCalledWith('/i18n/admin/languages/de/translations', {
      expectedRevision: 7,
      translations: {
        'common.save': 'Alt speichern',
        'common.cancel': 'Abbrechen'
      }
    });
    expect(localStorage.getItem('dwh_custom_languages')).toBeNull();
    confirm.mockRestore();
  });
});
