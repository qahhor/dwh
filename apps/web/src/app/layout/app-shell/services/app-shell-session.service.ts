import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { AuthService } from '@core/services/auth.service';
import { I18nService } from '@core/services/i18n.service';
import { ModuleService } from '@core/services/module.service';
import { NavigationService } from '@core/services/navigation.service';
import { NotificationService } from '@core/services/notification.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { LanguageChangeRequest } from '../components/app-header.component';
import { AppShellNavService } from './app-shell-nav.service';

/**
 * What the shell loads and changes for the signed-in person: menus, the unread
 * counter, the announcement banner and the interface language. Provided by the
 * shell component, so its reads start and stop with the shell.
 */
@Injectable()
export class AppShellSessionService {
  private readonly authService = inject(AuthService);
  private readonly i18n = inject(I18nService);
  private readonly moduleService = inject(ModuleService);
  private readonly navService = inject(NavigationService);
  private readonly notifService = inject(NotificationService);
  private readonly permService = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly nav = inject(AppShellNavService);

  readonly isChangingLanguage = signal(false);
  readonly isDismissingAnnouncement = signal(false);
  private readonly announcementRevision = signal(0);

  readonly canReadNotifications = computed(() => this.nav.canViewNotifications());
  readonly canReadAnnouncements = computed(() => this.permService.canView('platform.announcements'));

  constructor() {
    effect(() => {
      if (this.authService.currentUser()) {
        this.moduleService.loadActiveModules().subscribe({ error: () => {} });
        this.navService.loadActiveItems().subscribe({ error: () => {} });
        this.navService.loadEntityItems().subscribe({ error: () => {} });
      }
    });

    // Permissions arrive asynchronously after login. Start only the reads
    // allowed by the server contract, and cancel them when access changes.
    effect((onCleanup) => {
      if (!this.canReadNotifications()) {
        this.notifService.unreadCount.set(0);
        return;
      }
      const request = this.notifService.fetchUnreadCount().subscribe({ error: () => {} });
      this.notifService.connectSse();
      onCleanup(() => {
        request.unsubscribe();
        this.notifService.disconnectSse();
        this.notifService.unreadCount.set(0);
      });
    });
    effect((onCleanup) => {
      if (!this.canReadAnnouncements()) {
        this.notifService.activeAnnouncement.set(null);
        return;
      }
      this.announcementRevision();
      const request = this.notifService.fetchActiveAnnouncement(this.i18n.currentLang()).subscribe({ error: () => {} });
      onCleanup(() => {
        request.unsubscribe();
        this.notifService.activeAnnouncement.set(null);
      });
    });
  }

  dismissAnnouncement() {
    if (this.isDismissingAnnouncement() || this.authService.isLoggingOut()) return;
    const a = this.notifService.activeAnnouncement();
    if (a && a.id) {
      this.isDismissingAnnouncement.set(true);
      this.notifService
        .dismissAnnouncement(a.id)
        .pipe(finalize(() => this.isDismissingAnnouncement.set(false)))
        .subscribe({
          next: () => this.announcementRevision.update((revision) => revision + 1),
          error: () => {}, // ApiService owns the single error message; keep the banner for retry.
        });
    }
  }

  changeLanguage(request: LanguageChangeRequest) {
    if (this.isChangingLanguage() || this.i18n.isLoading() || this.authService.isLoggingOut()) {
      request.revert();
      return;
    }
    if (request.code === this.i18n.currentLang()) return;
    this.isChangingLanguage.set(true);
    this.i18n
      .setLanguage(request.code)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.isChangingLanguage.set(false)),
      )
      .subscribe({
        error: () => {
          request.revert();
          if (!this.destroyRef.destroyed)
            this.toast.error(this.i18n.translate('layout.app_shell.language_change_failed'));
        },
      });
  }
}
