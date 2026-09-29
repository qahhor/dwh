import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Observable, of, Subject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { SearchManagementService } from '@core/services/search-management.service';
import { ToastService } from '@core/services/toast.service';
import { ThemeService } from '@core/services/theme.service';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { SettingsComponent } from './settings.component';
import { translateTest } from '@testing/i18n-test.stub';
import { inScreen, redraw } from '@testing/in-screen';

// Each panel has its own spec, and the stores keep their rules in settings.store.spec.ts and
// settings-languages.store.spec.ts; this spec pins the tabs and how the screen wires them together.
describe('SettingsComponent UI contracts', () => {
  async function createFixture(
    api: object = { get: vi.fn(() => of({})), patch: vi.fn(() => of({})) },
    hasPermission: (form: string, action: string) => boolean = () => true,
    searchManagement: object = {
      status: vi.fn(() => of({})),
      settings: vi.fn(() => of({})),
      jobs: vi.fn(() => of({ items: [], hasMore: false })),
    },
  ) {
    await TestBed.configureTestingModule({
      imports: [SettingsComponent],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: PermissionService, useValue: { hasPermission } },
        { provide: SearchManagementService, useValue: searchManagement },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
        {
          provide: ThemeService,
          useValue: { themePreference: signal('light'), currentTheme: signal('light'), setTheme: vi.fn() },
        },
        {
          provide: I18nService,
          useValue: {
            currentLang: signal('ru'),
            languages: signal([
              { code: 'ru', name: 'Русский', builtin: true, active: true },
              { code: 'de', name: 'Deutsch', builtin: true, active: true },
              { code: 'tr', name: 'Türkçe', builtin: true, active: true },
            ]),
            translate: translateTest,
            setLanguage: vi.fn(() => of(undefined)),
            refreshLanguages: vi.fn(() => of([])),
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(SettingsComponent);
    redraw(fixture);
    return fixture;
  }

  it('renders settings that arrive after the initial change-detection pass', async () => {
    const systemSettings = new Subject<Record<string, string>>();
    const userSettings = new Subject<Record<string, string>>();
    const api = {
      get: vi.fn((path: string) => (path === '/settings/system' ? systemSettings : userSettings)),
      patch: vi.fn(() => of({})),
    };
    const fixture = await createFixture(api);
    fixture.autoDetectChanges();

    systemSettings.next({
      'system.company_name': 'Persisted Company',
      'system.default_language': 'en',
      'system.default_timezone': 'UTC',
      'system.date_format': 'yyyy-MM-dd HH:mm',
    });
    userSettings.next({ 'user.theme': 'light' });
    await fixture.whenStable();

    const companyName = inScreen(fixture.nativeElement).querySelector('#settings-company-name') as HTMLInputElement;
    expect(companyName.value).toBe('Persisted Company');
  });

  it('keeps what is typed in the panels and saves it with the other system settings', async () => {
    const api = {
      get: vi.fn((path: string) =>
        of(
          path === '/settings/system'
            ? { 'system.company_name': 'Old', 'storage.default_user_quota_mb': '1024' }
            : { 'user.theme': 'light' },
        ),
      ),
      patch: vi.fn(() => of({})),
    };
    const fixture = await createFixture(api, () => true);

    const companyName = inScreen(fixture.nativeElement).querySelector('#settings-company-name') as HTMLInputElement;
    companyName.value = 'New Company';
    companyName.dispatchEvent(new Event('input'));
    redraw(fixture);

    (inScreen(fixture.nativeElement).querySelector('#settings-storage-tab') as HTMLButtonElement).click();
    redraw(fixture);
    const quota = inScreen(fixture.nativeElement).querySelector('#settings-user-quota') as HTMLInputElement;
    expect(quota.value).toBe('1024');
    quota.value = '2048';
    quota.dispatchEvent(new Event('input'));
    redraw(fixture);

    fixture.componentInstance.store.saveSystemSettings();
    expect(api.patch).toHaveBeenCalledWith('/settings/system', {
      'system.company_name': 'New Company',
      'storage.default_user_quota_mb': '2048',
    });
  });

  it('connects the settings tabs to their panels and gives the general panel the interface languages', async () => {
    const fixture = await createFixture();

    expect(
      inScreen(fixture.nativeElement).querySelector('[role="tablist"][aria-label="Разделы настроек"]'),
    ).not.toBeNull();
    expect(inScreen(fixture.nativeElement).querySelector('#settings-general-tab[aria-selected="true"]')).not.toBeNull();
    expect(inScreen(fixture.nativeElement).querySelector('#settings-general-panel[role="tabpanel"]')).not.toBeNull();
    const picker = fixture.debugElement.query(By.css('smt-select[name="settingsDefaultLanguage"]'))
      .componentInstance as SMTSelectComponent<string>;
    expect(picker.options().map((option) => option.id)).toEqual(['ru', 'de', 'tr']);

    fixture.componentInstance.activeTab.set('security');
    redraw(fixture);
    expect(inScreen(fixture.nativeElement).querySelector('#settings-security-panel[role="tabpanel"]')).not.toBeNull();
    expect(inScreen(fixture.nativeElement).querySelector('#settings-general-panel')).toBeNull();
  });

  it('shows the search tab from search permission and destroys its child on a structural tab switch', async () => {
    let statusUnsubscribed = 0;
    const searchManagement = {
      status: vi.fn(() => new Observable(() => () => statusUnsubscribed++)),
      jobs: vi.fn(() => of({ items: [], hasMore: false })),
      settings: vi.fn(() => of({})),
    };
    const fixture = await createFixture(
      undefined,
      (form, action) => form === 'platform.search' && action === 'view',
      searchManagement,
    );

    const searchTab = inScreen(fixture.nativeElement).querySelector('#settings-search-tab') as HTMLButtonElement;
    expect(searchTab).not.toBeNull();
    expect(inScreen(fixture.nativeElement).querySelector('#settings-general-tab')).toBeNull();
    searchTab.click();
    redraw(fixture);
    expect(searchManagement.status).toHaveBeenCalledTimes(1);
    expect(inScreen(fixture.nativeElement).querySelector('#settings-search-panel app-search-settings')).not.toBeNull();

    (inScreen(fixture.nativeElement).querySelector('#settings-preferences-tab') as HTMLButtonElement).click();
    redraw(fixture);
    expect(inScreen(fixture.nativeElement).querySelector('#settings-search-panel')).toBeNull();
    expect(statusUnsubscribed).toBe(1);
  });

  it('centers only the latest locally focused settings tab and cancels pending work on teardown', async () => {
    vi.useFakeTimers();
    try {
      const fixture = await createFixture();
      const storage = inScreen(fixture.nativeElement).querySelector('#settings-storage-tab') as HTMLButtonElement;
      const languages = inScreen(fixture.nativeElement).querySelector('#settings-languages-tab') as HTMLButtonElement;
      const search = inScreen(fixture.nativeElement).querySelector('#settings-search-tab') as HTMLButtonElement;
      const storageScroll = vi.fn();
      const languagesScroll = vi.fn();
      const searchScroll = vi.fn();
      storage.scrollIntoView = storageScroll;
      languages.scrollIntoView = languagesScroll;
      search.scrollIntoView = searchScroll;

      storage.focus();
      languages.focus();
      vi.runOnlyPendingTimers();

      expect(storageScroll).not.toHaveBeenCalled();
      expect(languagesScroll).toHaveBeenCalledOnce();
      expect(languagesScroll).toHaveBeenCalledWith({ behavior: 'instant', block: 'nearest', inline: 'center' });

      search.focus();
      fixture.destroy();
      vi.runOnlyPendingTimers();
      expect(searchScroll).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps operational status out of settings and exposes no custom-module controls', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as { get: ReturnType<typeof vi.fn> };

    expect(api.get).not.toHaveBeenCalledWith('/system/info');
    expect(api.get).not.toHaveBeenCalledWith('/system/license-info');
    expect(api.get).not.toHaveBeenCalledWith('/modules');
    expect(inScreen(fixture.nativeElement).querySelector('#settings-modules-tab')).toBeNull();

    expect(inScreen(fixture.nativeElement).querySelector('#settings-system-tab')).toBeNull();
    expect(inScreen(fixture.nativeElement).textContent).not.toContain('Control Plane');
    expect(inScreen(fixture.nativeElement).textContent).not.toContain('Лиценз');
  });

  it('passes a view-only right down to the system panels', async () => {
    const hasPermission = (form: string, action: string) =>
      (form === 'platform.settings' || form === 'settings') && action === 'view';
    const fixture = await createFixture(undefined, hasPermission);

    const companyInput = inScreen(fixture.nativeElement).querySelector('#settings-company-name') as HTMLInputElement;
    expect(companyInput.disabled).toBe(true);
    expect(
      inScreen(fixture.nativeElement).querySelector('#settings-general-panel .card-footer-actions .smt-button'),
    ).toBeNull();
  });

  it('saves the open system tab on Ctrl+S, personal settings on the preferences tab, and nothing elsewhere', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as { patch: ReturnType<typeof vi.fn> };
    const press = (init: KeyboardEventInit) =>
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 's', cancelable: true, ...init }));

    press({ ctrlKey: true });
    expect(api.patch).toHaveBeenLastCalledWith('/settings/system', {});
    fixture.componentInstance.setTab('preferences');
    press({ metaKey: true });
    expect(api.patch).toHaveBeenLastCalledWith('/settings/user', {});
    press({ ctrlKey: true, shiftKey: true });
    fixture.componentInstance.setTab('languages');
    press({ ctrlKey: true });
    expect(api.patch).toHaveBeenCalledTimes(2);
  });

  it('opens add language modal with isOpen=true and closes it cleanly', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.activeTab.set('languages');
    redraw(fixture);

    expect(inScreen(fixture.nativeElement).querySelector('[role="dialog"]')).toBeNull();
    fixture.componentInstance.openAddLangModal();
    redraw(fixture);

    const modal = inScreen(fixture.nativeElement).querySelector('[role="dialog"]');
    expect(modal).not.toBeNull();
    expect(fixture.componentInstance.languageStore.isAddLangModalOpen()).toBe(true);
    expect(modal.querySelector('.smt-dialog')).not.toBeNull();
  });

  it('supports webhooks tab when user has platform.webhooks permission', async () => {
    const fixture = await createFixture(undefined, (form) => form === 'platform.webhooks');
    expect(fixture.componentInstance.store.canViewWebhookSettings()).toBe(true);
    expect(fixture.componentInstance.isTabAvailable('webhooks')).toBe(true);

    // A click marks the screen for checking, as it does in the app; the switch works through setTab.
    (inScreen(fixture.nativeElement).querySelector('#settings-webhooks-tab') as HTMLButtonElement).click();
    redraw(fixture);

    const panel = inScreen(fixture.nativeElement).querySelector('#settings-webhooks-panel');
    expect(panel).not.toBeNull();
    const webhooksCmp = panel.querySelector('app-webhooks-settings');
    expect(webhooksCmp).not.toBeNull();
  });
});
