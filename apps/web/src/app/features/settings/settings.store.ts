import { Injectable, inject, signal } from '@angular/core';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ThemeService } from '@core/services/theme.service';
import { ToastService } from '@core/services/toast.service';
import { SettingsApi } from './settings.api';

/**
 * System and personal settings of the settings screen: what the viewer may
 * see, loading, validation and saving. Provided by the screen, so a second
 * visit starts from the server again.
 */
@Injectable()
export class SettingsStore {
  private readonly settingsApi = inject(SettingsApi);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly permService = inject(PermissionService);
  private readonly themeService = inject(ThemeService);

  readonly isLoading = signal<boolean>(false);
  readonly loadError = signal<string | null>(null);
  readonly systemSettings = signal<Record<string, string>>({});
  readonly userSettings = signal<Record<string, string>>({});
  readonly isSaving = signal<boolean>(false);

  canManageSystemSettings(): boolean {
    return (
      this.permService.hasPermission('platform.settings', 'view') ||
      this.permService.hasPermission('platform.settings', 'update') ||
      this.permService.hasPermission('settings', 'view') ||
      this.permService.hasPermission('settings', 'update')
    );
  }

  canUpdateSystemSettings(): boolean {
    return (
      this.permService.hasPermission('platform.settings', 'update') ||
      this.permService.hasPermission('settings', 'update')
    );
  }

  canViewSearchSettings(): boolean {
    return this.permService.hasPermission('platform.search', 'view');
  }

  canViewNavigationSettings(): boolean {
    return this.permService.hasPermission('platform.navigation', 'view');
  }

  canViewWebhookSettings(): boolean {
    return (
      this.permService.hasPermission('platform.webhooks', 'view') ||
      this.permService.hasPermission('platform.webhooks', 'manage')
    );
  }

  userThemePreference(): string {
    return this.userSettings()['user.theme'] || this.themeService.themePreference();
  }

  /** Applies the theme at once, so the viewer sees it before saving. */
  onThemeChange(newTheme: string): void {
    this.userSettings.update((settings) => ({ ...settings, 'user.theme': newTheme }));
    if (newTheme === 'light' || newTheme === 'dark' || newTheme === 'system') {
      this.themeService.setTheme(newTheme);
    }
  }

  loadAllSettings(): void {
    this.isLoading.set(true);
    this.loadError.set(null);
    let sysLoaded = !this.canManageSystemSettings();
    let userLoaded = false;
    const checkDone = () => {
      if (sysLoaded && userLoaded) {
        this.isLoading.set(false);
      }
    };

    if (this.canManageSystemSettings()) {
      this.settingsApi.systemSettings().subscribe({
        next: (res) => {
          this.systemSettings.set({ ...res });
          sysLoaded = true;
          checkDone();
        },
        error: () => {
          this.loadError.set(this.i18n.translate('settings.oshibka_zagruzki_nastroek'));
          sysLoaded = true;
          checkDone();
        },
      });
    }

    this.settingsApi.userSettings().subscribe({
      next: (res) => {
        this.userSettings.set({ ...res });
        const theme = res['user.theme'];
        if (theme === 'light' || theme === 'dark' || theme === 'system') {
          this.themeService.setTheme(theme);
        }
        userLoaded = true;
        checkDone();
      },
      error: () => {
        this.loadError.set(this.i18n.translate('settings.oshibka_zagruzki_nastroek'));
        userLoaded = true;
        checkDone();
      },
    });
  }

  saveSystemSettings(): void {
    if (!this.canUpdateSystemSettings()) return;

    const sessionStr = this.systemSettings()['security.session_lifetime_hours'];
    if (sessionStr !== undefined) {
      const trimmed = String(sessionStr).trim();
      const sessionLifetime = trimmed === '' ? NaN : Number(trimmed);
      if (!Number.isFinite(sessionLifetime) || sessionLifetime < 1 || sessionLifetime > 8760) {
        this.toast.error(this.i18n.translate('settings.validation.session_lifetime'));
        return;
      }
    }

    const quotaStr = this.systemSettings()['storage.default_user_quota_mb'];
    if (quotaStr !== undefined) {
      const trimmed = String(quotaStr).trim();
      const quota = trimmed === '' ? NaN : Number(trimmed);
      if (!Number.isFinite(quota) || quota < 100 || quota > 102400) {
        this.toast.error(this.i18n.translate('settings.validation.quota'));
        return;
      }
    }

    this.isSaving.set(true);
    this.settingsApi.saveSystemSettings(this.systemSettings()).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.toast.success(this.i18n.translate('common.saved'));
      },
      error: () => this.isSaving.set(false),
    });
  }

  saveUserSettings(): void {
    this.isSaving.set(true);
    this.settingsApi.saveUserSettings(this.userSettings()).subscribe({
      next: () => {
        this.isSaving.set(false);
        const theme = this.userSettings()['user.theme'];
        if (theme === 'light' || theme === 'dark' || theme === 'system') {
          this.themeService.setTheme(theme);
        }
        this.toast.success(this.i18n.translate('common.saved'));
      },
      error: () => this.isSaving.set(false),
    });
  }

  changePersonalLang(lang: string): void {
    this.i18n.setLanguage(lang).subscribe({
      next: () => this.userSettings.update((settings) => ({ ...settings, 'user.language': lang })),
    });
  }

  /** A panel edited one setting; it is kept here and saved with the others. */
  setSystemSetting(key: string, value: string): void {
    this.systemSettings.update((settings) => ({ ...settings, [key]: value }));
  }

  toggleRequire2fa(enabled: boolean): void {
    this.systemSettings.update((settings) => ({
      ...settings,
      'security.require_2fa': enabled ? 'true' : 'false',
    }));
  }

  toggleSound(enabled: boolean): void {
    this.userSettings.update((settings) => ({
      ...settings,
      'user.notifications_sound': enabled ? 'true' : 'false',
    }));
  }
}
