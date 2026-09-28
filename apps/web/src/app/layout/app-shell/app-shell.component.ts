import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  OnDestroy,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { BreakpointObserver } from '@angular/cdk/layout';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterModule } from '@angular/router';
import { AuthService } from '@core/services/auth.service';
import { TranslatePipe } from '@core/services/i18n.service';
import { NotificationService } from '@core/services/notification.service';
import { CommandPaletteComponent } from '../command-palette/command-palette.component';
import { AppHeaderComponent } from './components/app-header.component';
import { AppSidebarComponent } from './components/app-sidebar.component';
import { IdleLockDialogComponent } from './components/idle-lock-dialog.component';
import { NavSection } from './app-shell.models';
import { AppShellFlyoutService } from './services/app-shell-flyout.service';
import { AppShellNavService } from './services/app-shell-nav.service';
import { AppShellSessionService } from './services/app-shell-session.service';

export type { NavItem, NavSection } from './app-shell.models';

/**
 * Frame around every signed-in page. Navigation state lives in
 * AppShellNavService, session reads in AppShellSessionService and the rail
 * flyouts in AppShellFlyoutService; the shell itself keeps the mobile drawer,
 * whose focus handling needs the rendered header and sidebar.
 */
@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    IdleLockDialogComponent,
    RouterModule,
    TranslatePipe,
    CommandPaletteComponent,
    AppHeaderComponent,
    AppSidebarComponent,
  ],
  providers: [AppShellNavService, AppShellSessionService],
  templateUrl: './app-shell.component.html',
  styleUrl: './app-shell.component.css',
})
export class AppShellComponent implements OnDestroy {
  readonly authService = inject(AuthService);
  readonly nav = inject(AppShellNavService);
  readonly session = inject(AppShellSessionService);
  readonly flyout = inject(AppShellFlyoutService);
  private readonly notifService = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly breakpointObserver = inject(BreakpointObserver, { optional: true });

  readonly mainContent = viewChild<ElementRef<HTMLElement>>('mainContent');
  readonly appHeader = viewChild(AppHeaderComponent);
  readonly appSidebar = viewChild(AppSidebarComponent);

  readonly isMobile = signal<boolean>(false);
  readonly isMobileMenuOpen = signal<boolean>(false);

  readonly sidebarId = 'app-sidebar';

  constructor() {
    if (this.breakpointObserver) {
      this.breakpointObserver
        .observe('(max-width: 768px)')
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((result) => {
          const wasMobile = this.isMobile();
          this.isMobile.set(result.matches);
          if (wasMobile && !result.matches) {
            if (this.isMobileMenuOpen()) {
              this.isMobileMenuOpen.set(false);
            }
            this.mainContent()?.nativeElement?.focus();
            setTimeout(() => {
              this.mainContent()?.nativeElement?.focus();
            }, 0);
          }
        });
    }
  }

  get mobileMenuBtn(): ElementRef<HTMLButtonElement> | undefined {
    return this.appHeader()?.mobileMenuBtn();
  }
  get sidebarElement(): ElementRef<HTMLElement> | undefined {
    return this.appSidebar()?.sidebarElement();
  }

  @HostListener('keydown', ['$event'])
  handleKeyDown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      if (this.flyout.isFlyoutVisible() || this.flyout.isProfileFlyoutVisible()) {
        this.flyout.closeFlyout();
        this.flyout.closeProfileFlyout();
        return;
      }
    }
    if (this.isMobile() && this.isMobileMenuOpen()) {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        this.closeMobileMenu(true);
      } else if (
        (event.ctrlKey || event.metaKey) &&
        !event.altKey &&
        (event.code === 'KeyK' || event.key.toLowerCase() === 'k')
      ) {
        this.isMobileMenuOpen.set(false);
        const opener = this.mobileMenuBtn?.nativeElement || (document.querySelector('.mobile-menu-btn') as HTMLElement);
        if (opener) {
          opener.closest('.main-wrapper')?.removeAttribute('inert');
          opener.focus();
        }
      }
    }
  }

  ngOnDestroy() {
    this.notifService.resetSession();
  }

  skipToContent(event: MouseEvent) {
    event.preventDefault();
    this.mainContent()?.nativeElement?.focus();
  }

  // The flyouts open only on the collapsed desktop rail, which the shell knows.
  onCategoryMouseEnter(section: NavSection, event: MouseEvent) {
    this.flyout.onCategoryMouseEnter(section, event, this.nav.isCollapsed(), this.isMobile());
  }

  onProfileMouseEnter(event: MouseEvent) {
    this.flyout.onProfileMouseEnter(event, this.nav.isCollapsed(), this.isMobile());
  }

  onFlyoutItemClick() {
    this.flyout.onFlyoutItemClick(() => this.onNavClick());
  }

  onProfileFlyoutClick() {
    this.flyout.onProfileFlyoutClick(() => this.onNavClick());
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    this.flyout.onDocumentClick(event);
  }

  @HostListener('window:keydown.escape')
  onEscapeKey() {
    this.flyout.closeFlyout();
    this.flyout.closeProfileFlyout();
    this.closeMobileMenu(true);
  }

  toggleSidebar() {
    this.nav.toggleCollapsed();
    this.flyout.closeFlyout();
    this.flyout.closeProfileFlyout();
  }

  toggleMobileMenu() {
    if (this.isMobileMenuOpen()) {
      this.closeMobileMenu(true);
    } else {
      this.openMobileMenu();
    }
  }

  openMobileMenu() {
    this.isMobileMenuOpen.set(true);
    setTimeout(() => {
      const closeBtn = this.sidebarElement?.nativeElement?.querySelector<HTMLElement>('.mobile-drawer-close');
      if (closeBtn) {
        closeBtn.focus();
      } else {
        this.sidebarElement?.nativeElement?.querySelector<HTMLElement>('button, a[href]')?.focus();
      }
    }, 0);
  }

  closeMobileMenu(restoreFocus: boolean = true) {
    if (!this.isMobileMenuOpen()) return;
    this.isMobileMenuOpen.set(false);
    const opener = this.mobileMenuBtn?.nativeElement;
    if (opener) {
      opener.closest('.main-wrapper')?.removeAttribute('inert');
      if (restoreFocus) {
        opener.focus();
        setTimeout(() => {
          opener.focus();
        }, 0);
      }
    }
  }

  onNavClick() {
    if (this.isMobileMenuOpen()) {
      this.closeMobileMenu(false);
    }
  }
}
