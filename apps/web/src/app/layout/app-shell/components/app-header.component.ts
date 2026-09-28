import { ChangeDetectionStrategy, Component, ElementRef, inject, input, output, viewChild } from '@angular/core';

import { RouterModule } from '@angular/router';
import { AuthService } from '@core/services/auth.service';
import { ThemeService } from '@core/services/theme.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { NotificationService } from '@core/services/notification.service';
import { CommandPaletteService } from '@core/services/command-palette.service';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';

/** A language the person picked; `revert` shows the current one again. */
export interface LanguageChangeRequest {
  readonly code: string;
  revert(): void;
}

@Component({
  selector: 'app-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterModule, TranslatePipe, SMTSelectComponent],
  template: `
    <!-- Top Navigation -->
    <header class="topbar">
      <div class="topbar-left">
        <button
          #mobileMenuBtn
          type="button"
          class="icon-btn mobile-menu-btn"
          [attr.aria-label]="'layout.app_shell.otkryt_menyu_navigacii' | t"
          [attr.aria-expanded]="isMobileMenuOpen()"
          [attr.aria-controls]="sidebarId()"
          (click)="toggleMobileMenu.emit()"
        >
          <span class="material-symbols-outlined" aria-hidden="true">{{ isMobileMenuOpen() ? 'close' : 'menu' }}</span>
        </button>

        <button
          type="button"
          class="palette-trigger"
          [attr.aria-label]="'layout.app_shell.otkryt_globalnyy_poisk' | t"
          aria-haspopup="dialog"
          aria-keyshortcuts="Control+K Meta+K"
          [attr.aria-expanded]="paletteService.isOpen()"
          [disabled]="authService.isLoggingOut()"
          (click)="paletteService.open()"
        >
          <span class="material-symbols-outlined" aria-hidden="true">search</span>
          <span class="trigger-text">{{ 'layout.app_shell.poisk' | t }}</span>
          <kbd class="shortcut-kbd">Ctrl K</kbd>
        </button>
      </div>

      <div class="topbar-right">
        <!-- Language Switcher -->
        <div class="lang-selector">
          <span class="material-symbols-outlined lang-icon" aria-hidden="true">language</span>
          <smt-select
            smtTriggerId="app-language-selector"
            class="lang-select"
            [ariaLabel]="'settings.yazyk_interfeysa' | t"
            [options]="languageOptions()"
            [allowClear]="false"
            [value]="i18n.currentLang()"
            [disabled]="i18n.isLoading() || isChangingLanguage() || authService.isLoggingOut()"
            [attr.aria-busy]="isChangingLanguage()"
            (valueChange)="onLanguagePick($event)"
          />
        </div>

        <!-- Theme Toggle -->
        <button
          type="button"
          class="icon-btn"
          [attr.aria-label]="'layout.app_shell.pereklyuchit_temu' | t"
          [attr.aria-pressed]="themeService.currentTheme() === 'dark'"
          [disabled]="authService.isLoggingOut()"
          (click)="themeService.toggleTheme()"
          [title]="(themeService.currentTheme() === 'light' ? 'common.dark_theme' : 'common.light_theme') | t"
        >
          <span class="material-symbols-outlined" aria-hidden="true">
            {{ themeService.currentTheme() === 'light' ? 'dark_mode' : 'light_mode' }}
          </span>
        </button>

        <!-- Notification Bell -->
        @if (canReadNotifications()) {
          <button
            type="button"
            class="icon-btn notif-btn"
            routerLink="/notifications"
            [attr.aria-label]="'layout.app_shell.otkryt_uvedomleniya' | t"
            [attr.aria-describedby]="notifService.unreadCount() > 0 ? 'header-unread-count' : null"
            [disabled]="authService.isLoggingOut()"
            [title]="'nav.notifications' | t"
          >
            <span class="material-symbols-outlined" aria-hidden="true">notifications</span>
            @if (notifService.unreadCount() > 0) {
              <span class="bell-dot" aria-hidden="true"></span>
            }
            @if (notifService.unreadCount() > 0) {
              <span id="header-unread-count" class="sr-only">
                {{ 'layout.app_shell.unread_notifications' | t: { count: notifService.unreadCount() } }}
              </span>
            }
          </button>
        }

        <!-- Logout -->
        <button
          type="button"
          class="icon-btn logout-btn"
          [attr.aria-label]="'layout.app_shell.vyyti_iz_sistemy' | t"
          [disabled]="authService.isLoggingOut()"
          [attr.aria-busy]="authService.isLoggingOut()"
          (click)="onLogout()"
          [title]="'layout.app_shell.vyyti_iz_sistemy' | t"
        >
          <span class="material-symbols-outlined" aria-hidden="true">
            {{ authService.isLoggingOut() ? 'hourglass_top' : 'logout' }}
          </span>
        </button>
      </div>
    </header>

    <!-- Active Announcement Banner -->
    @if (canReadAnnouncements() && notifService.activeAnnouncement()) {
      <div class="announcement-banner" role="status">
        <div class="announcement-content">
          <span class="material-symbols-outlined banner-icon" aria-hidden="true">campaign</span>
          <div class="banner-text">
            <strong>{{ notifService.activeAnnouncement()?.title }}</strong>
            <p class="banner-body">{{ notifService.activeAnnouncement()?.body }}</p>
          </div>
        </div>
        <button
          type="button"
          class="banner-close"
          [disabled]="isDismissingAnnouncement() || authService.isLoggingOut()"
          [attr.aria-label]="'layout.app_shell.zakryt_obyavlenie' | t"
          (click)="dismissAnnouncement.emit()"
        >
          <span class="material-symbols-outlined" aria-hidden="true">close</span>
        </button>
      </div>
    }
  `,
  styleUrl: './app-header.component.css',
})
export class AppHeaderComponent {
  readonly authService = inject(AuthService);
  readonly themeService = inject(ThemeService);
  readonly i18n = inject(I18nService);
  readonly notifService = inject(NotificationService);
  readonly paletteService = inject(CommandPaletteService);

  readonly isMobile = input(false);
  readonly isMobileMenuOpen = input(false);
  readonly sidebarId = input('app-sidebar-nav');
  readonly isChangingLanguage = input(false);
  readonly isDismissingAnnouncement = input(false);
  readonly canReadNotifications = input(false);
  readonly canReadAnnouncements = input(false);

  readonly toggleMobileMenu = output<void>();
  readonly changeLanguage = output<LanguageChangeRequest>();
  readonly dismissAnnouncement = output<void>();
  readonly logout = output<void>();

  readonly mobileMenuBtn = viewChild<ElementRef<HTMLButtonElement>>('mobileMenuBtn');

  readonly languagePicker = viewChild(SMTSelectComponent);

  private readonly languageMemo = optionsMemo<SMTSelectOption<string>[]>();

  /** True while the shell writes the current language back into the picker. */
  private restoringLanguage = false;

  languageOptions(): SMTSelectOption<string>[] {
    const languages = this.i18n.languages();
    return this.languageMemo([languages], () =>
      languages.map((lang) => ({ id: lang.code, label: `${lang.code.toUpperCase()} — ${lang.name}` })),
    );
  }

  /** A pick hands the shell the code and a way to show the current language again when it refuses or the save fails. */
  onLanguagePick(code: string | null): void {
    const picker = this.languagePicker();
    if (!code || !picker || this.restoringLanguage) return;
    this.changeLanguage.emit({
      code,
      revert: () => {
        this.restoringLanguage = true;
        picker.value.set(this.i18n.currentLang());
        this.restoringLanguage = false;
      },
    });
  }

  onLogout() {
    this.logout.emit();
    this.authService.logout();
  }
}
