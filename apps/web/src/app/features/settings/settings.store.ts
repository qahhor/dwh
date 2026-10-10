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
  /** Why the last save of the system settings was refused, by setting key (i18n keys); an edit of a field clears it. */
  readonly systemErrors = signal<Readonly<Record<string, string>>>({});

  /** What the server holds, to tell an edit that leaving the screen would lose (forms standard, section 8). */
  private readonly savedSystem = linkedSignal<SettingsValues | undefined, SettingsValues>({
    source: () => loadedValue(this.systemResource)?.values,
    computation: (loaded, previous) => loaded ?? previous?.value ?? {},
  });
  private readonly savedUser = linkedSignal<SettingsValues | undefined, SettingsValues>({
    source: () => loadedValue(this.userResource),
    computation: (loaded, previous) => loaded ?? previous?.value ?? {},
  });
  readonly dirty = computed(
    () => !sameValues(this.systemSettings(), this.savedSystem()) || !sameValues(this.userSettings(), this.savedUser()),
  );

  readonly isLoading = computed(() => this.systemResource.isLoading() || this.userResource.isLoading());
  /** Hidden while a retry runs, so the banner only reports a finished load. */
  readonly loadError = computed(() =>
    loadFailed(this.systemResource) || loadFailed(this.userResource)
      ? this.i18n.translate('settings.page.load_failed')
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
      this.permService.hasPermission('md.settings', 'view') || this.permService.hasPermission('md.settings', 'update')
    );
  }

  canUpdateSystemSettings(): boolean {
    return this.permService.hasPermission('md.settings', 'update');
  }

  canViewSearchSettings(): boolean {
    return this.permService.hasPermission('search', 'view');
  }

  canViewNavigationSettings(): boolean {
    return this.permService.hasPermission('md.navigation', 'view');
  }

  canViewWebhookSettings(): boolean {
    return (
      this.permService.hasPermission('webhook.subscriptions', 'view') ||
      this.permService.hasPermission('webhook.subscriptions', 'manage')
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

  /**
   * Saves the system settings as a whole. A value out of range is not sent: its error goes under its field
   * ({@link systemErrors}) instead of a toast, and the screen shows the panel that holds it.
   */
  saveSystemSettings(): void {
    if (!this.canUpdateSystemSettings() || this.isSaving()) return;

    const errors = systemSettingErrors(this.systemSettings());
    this.systemErrors.set(errors);
    if (Object.keys(errors).length > 0) return;

    this.isSaving.set(true);
    const revision = this.systemRevision();
    const sent = { ...this.systemSettings() };
    this.settingsApi.saveSystemSettings(sent, revision).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.savedSystem.set(sent);
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
    if (this.isSaving()) return;
    this.isSaving.set(true);
    const sent = { ...this.userSettings() };
    this.settingsApi.saveUserSettings(sent).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.savedUser.set(sent);
        this.applyTheme(this.userSettings()['user.theme']);
        this.toast.success(this.i18n.translate('common.saved'));
      },
      error: () => this.isSaving.set(false),
    });
  }

  changePersonalLang(lang: string): void {
    this.i18n.setLanguage(lang).subscribe({
      // The language is stored by the switch itself, so it is no unsaved edit.
      next: () => {
        this.userSettings.update((settings) => ({ ...settings, 'user.language': lang }));
        this.savedUser.update((settings) => ({ ...settings, 'user.language': lang }));
      },
    });
  }

  /** A panel edited one setting; it is kept here and saved with the others. */
  setSystemSetting(key: string, value: string): void {
    this.systemSettings.update((settings) => ({ ...settings, [key]: value }));
    if (key in this.systemErrors()) {
      this.systemErrors.update(({ [key]: _fixed, ...rest }) => rest);
    }
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

/** The limits of the numeric system settings; the server checks them again. */
const RANGES: Readonly<Record<string, { min: number; max: number; key: string }>> = {
  'security.session_lifetime_hours': { min: 1, max: 8760, key: 'settings.validation.session_lifetime' },
  'storage.default_user_quota_mb': { min: 100, max: 102400, key: 'settings.validation.quota' },
};

/** The settings whose value is out of range, with the i18n key of the error. */
export function systemSettingErrors(values: SettingsValues): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const [setting, range] of Object.entries(RANGES)) {
    const raw = values[setting];
    if (raw === undefined) continue;
    const trimmed = String(raw).trim();
    const value = trimmed === '' ? NaN : Number(trimmed);
    if (!Number.isFinite(value) || value < range.min || value > range.max) errors[setting] = range.key;
  }
  return errors;
}

function sameValues(left: SettingsValues, right: SettingsValues): boolean {
  const keys = new Set([...Object.keys(left), ...Object.keys(right)]);
  return [...keys].every((key) => (left[key] ?? '') === (right[key] ?? ''));
}

/** The loaded answer, or undefined while loading, idle or failed. */
function loadedValue<T>(resource: Resource<T | undefined>): T | undefined {
  return resource.hasValue() ? resource.value() : undefined;
}

function loadFailed(resource: Resource<unknown>): boolean {
  return resource.error() !== undefined && !resource.isLoading();
}
