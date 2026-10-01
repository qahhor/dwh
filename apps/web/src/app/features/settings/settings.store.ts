import { Injectable, Resource, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { tap } from 'rxjs';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ThemeService } from '@core/services/theme.service';
import { ToastService } from '@core/services/toast.service';
import { SaveErrorNotifier } from '@shared/ui/save-errors';
import { SettingsApi, SettingsValues } from './settings.api';

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
  private readonly saveErrors = inject(SaveErrorNotifier);

  /** Edited by the panels and saved as a whole; a reload replaces the edits, a failed one keeps them. */
  readonly systemSettings = linkedSignal<SettingsValues | undefined, SettingsValues>({
    source: () => loadedValue(this.systemResource)?.values,
    computation: (loaded, previous) => (loaded ? { ...loaded } : (previous?.value ?? {})),
  });
  /** The revision of the system settings the next save names (plan item 3.6); a reload takes the server's. */
  readonly systemRevision = linkedSignal<number | undefined, number | undefined>({
    source: () => loadedValue(this.systemResource)?.revision,
    computation: (loaded, previous) => loaded ?? previous?.value,
  });
  readonly userSettings = linkedSignal<SettingsValues | undefined, SettingsValues>({
    source: () => loadedValue(this.userResource),
    computation: (loaded, previous) => (loaded ? { ...loaded } : (previous?.value ?? {})),
  });
  readonly isSaving = signal<boolean>(false);

  readonly isLoading = computed(() => this.systemResource.isLoading() || this.userResource.isLoading());
  /** Hidden while a retry runs, so the banner only reports a finished load. */
  readonly loadError = computed(() =>
    loadFailed(this.systemResource) || loadFailed(this.userResource)
      ? this.i18n.translate('settings.oshibka_zagruzki_nastroek')
      : null,
  );

  /** Only a viewer who may see system settings asks for them. */
  private readonly systemResource = rxResource({
    params: () => (this.canManageSystemSettings() ? true : undefined),
    stream: () => this.settingsApi.systemSettings(),
  });
  /** The stored theme applies as soon as it arrives. */
  private readonly userResource = rxResource({
    stream: () => this.settingsApi.userSettings().pipe(tap((values) => this.applyTheme(values['user.theme']))),
  });

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
    this.applyTheme(newTheme);
  }

  /** Both loads start with the screen; this asks the server again. */
  loadAllSettings(): void {
    this.systemResource.reload();
    this.userResource.reload();
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
    const revision = this.systemRevision();
    this.settingsApi.saveSystemSettings(this.systemSettings(), revision).subscribe({
      next: () => {
        this.isSaving.set(false);
        // The save raised the revision of the set by one: the next save names the new one (plan item 3.6).
        if (revision !== undefined) this.systemRevision.set(revision + 1);
        this.toast.success(this.i18n.translate('common.saved'));
      },
      error: (err: unknown) => {
        this.isSaving.set(false);
        this.saveErrors.show(err, {
          fallbackKey: 'common.operation_failed',
          reload: () => this.systemResource.reload(),
        });
      },
    });
  }

  saveUserSettings(): void {
    this.isSaving.set(true);
    this.settingsApi.saveUserSettings(this.userSettings()).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.applyTheme(this.userSettings()['user.theme']);
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

  private applyTheme(theme: string | undefined): void {
    if (theme === 'light' || theme === 'dark' || theme === 'system') {
      this.themeService.setTheme(theme);
    }
  }
}

/** The loaded answer, or undefined while loading, idle or failed. */
function loadedValue<T>(resource: Resource<T | undefined>): T | undefined {
  return resource.hasValue() ? resource.value() : undefined;
}

function loadFailed(resource: Resource<unknown>): boolean {
  return resource.error() !== undefined && !resource.isLoading();
}
