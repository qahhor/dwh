import { Component, DestroyRef, ElementRef, HostListener, OnDestroy, ViewChild, computed, effect, signal, inject } from '@angular/core';
import { BreakpointObserver } from '@angular/cdk/layout';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { PermissionService } from '../../core/services/permission.service';
import { ThemeService } from '../../core/services/theme.service';
import { I18nService, TranslatePipe, Language } from '../../core/services/i18n.service';
import { NotificationService } from '../../core/services/notification.service';
import { CommandPaletteService } from '../../core/services/command-palette.service';
import { CommandPaletteComponent } from '../command-palette/command-palette.component';
import { AppHeaderComponent } from './components/app-header.component';
import { AppSidebarComponent } from './components/app-sidebar.component';
import { ToastService } from '../../core/services/toast.service';
import { ModuleService } from '../../core/services/module.service';
import { NavigationService } from '../../core/services/navigation.service';
import { finalize } from 'rxjs';

export type { NavItem, NavSection } from './app-shell.models';
import type { NavItem, NavSection } from './app-shell.models';
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    TranslatePipe,
    CommandPaletteComponent,
    AppHeaderComponent,
    AppSidebarComponent
  ],
  template: `
    <a class="skip-link" href="#main-content" (click)="skipToContent($event)">{{ 'layout.app_shell.pereyti_k_osnovnomu_soderzhimomu' | t }}</a>
    <div class="app-layout">
      <!-- Sidebar (Delegated Component) -->
      <app-sidebar
        [sidebarId]="sidebarId"
        [isMobile]="isMobile()"
        [isMobileMenuOpen]="isMobileMenuOpen()"
        [isCollapsed]="isCollapsed()"
        [navSections]="navSections()"
        [isFlyoutVisible]="isFlyoutVisible()"
        [hoveredFlyoutSection]="hoveredFlyoutSection()"
        [flyoutAnchorTop]="flyoutAnchorTop()"
        [isProfileFlyoutVisible]="isProfileFlyoutVisible()"
        [currentUser]="authService.currentUser()"
        [isSectionActive]="isSectionActiveFn"
        [isRouteActive]="isRouteActiveFn"
        [getSectionIcon]="getSectionIconFn"
        [getSectionBadge]="getSectionBadgeFn"
        [hasVisibleItems]="hasVisibleItemsFn"
        [isSectionExpanded]="isSectionExpandedFn"
        [isSubmenuExpanded]="isSubmenuExpandedFn"
        (toggleSidebar)="toggleSidebar()"
        (closeMobileMenu)="closeMobileMenu($event)"
        (onNavClick)="onNavClick()"
        (toggleSection)="toggleSection($event.id, $event.event)"
        (toggleSubmenu)="toggleSubmenu($event.id, $event.event)"
        (categoryClick)="onCategoryClick($event.section, $event.event)"
        (categoryMouseEnter)="onCategoryMouseEnter($event.section, $event.event)"
        (categoryMouseLeave)="onCategoryMouseLeave()"
        (profileMouseEnter)="onProfileMouseEnter($event)"
        (profileMouseLeave)="onProfileMouseLeave()"
        (flyoutMouseEnter)="onFlyoutMouseEnter()"
        (flyoutMouseLeave)="onFlyoutMouseLeave()"
        (flyoutItemClick)="onFlyoutItemClick()"
        (profileFlyoutMouseEnter)="onProfileFlyoutMouseEnter()"
        (profileFlyoutMouseLeave)="onProfileFlyoutMouseLeave()"
        (profileFlyoutClick)="onProfileFlyoutClick()"
        (logout)="onLogout()"
      ></app-sidebar>

      <!-- Main Container -->
      <div class="main-wrapper" [attr.inert]="isMobile() && isMobileMenuOpen() ? true : null">
        <!-- Top Navigation (Delegated Component) -->
        <app-header
          [isMobile]="isMobile()"
          [isMobileMenuOpen]="isMobileMenuOpen()"
          [sidebarId]="sidebarId"
          [isChangingLanguage]="isChangingLanguage()"
          [isDismissingAnnouncement]="isDismissingAnnouncement()"
          [canReadNotifications]="canReadNotifications()"
          [canReadAnnouncements]="canReadAnnouncements()"
          (toggleMobileMenu)="toggleMobileMenu()"
          (changeLanguage)="changeLanguage($event)"
          (dismissAnnouncement)="dismissAnnouncement()"
          (logout)="onLogout()"
        ></app-header>

        <!-- Page View Outlet -->
        <main id="main-content" #mainContent class="page-content" tabindex="-1">
          <router-outlet></router-outlet>
        </main>
      </div>
    </div>

    <!-- Command Palette Modal -->
    <app-command-palette></app-command-palette>

    <!-- Global Toast Container -->
  `,
  styles: [`
    .skip-link {
      position: fixed;
      top: 8px;
      left: 8px;
      z-index: 3000;
      transform: translateY(-160%);
      padding: 8px 12px;
      border-radius: var(--radius-sm);
      background: var(--bg-surface);
      color: var(--text-main);
      box-shadow: var(--shadow-overlay);
    }

    .skip-link:focus {
      transform: translateY(0);
    }

    .app-layout {
      display: flex;
      height: 100vh;
      width: 100vw;
      overflow: hidden;
    }

    /* Main Wrapper */
    .main-wrapper {
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      background-color: var(--bg-app);
    }

    .page-content {
      flex: 1;
      min-width: 0;
      overflow-y: auto;
      padding: 20px;
    }

    @media (max-width: 767px) {
      .page-content {
        padding: 12px;
        overflow-x: hidden;
      }
    }
  `]
})
export class AppShellComponent implements OnDestroy {
  @ViewChild('mainContent') mainContent?: ElementRef<HTMLElement>;
  @ViewChild(AppHeaderComponent) appHeader?: AppHeaderComponent;
  get mobileMenuBtn(): ElementRef<HTMLButtonElement> | undefined {
    return this.appHeader?.mobileMenuBtn;
  }
  @ViewChild(AppSidebarComponent) appSidebar?: AppSidebarComponent;
  get sidebarElement(): ElementRef<HTMLElement> | undefined {
    return this.appSidebar?.sidebarElement;
  }
  get mobileDrawerClose(): ElementRef<HTMLButtonElement> | undefined {
    return this.appSidebar?.mobileDrawerClose;
  }

  readonly isSectionActiveFn = (section: NavSection) => this.isSectionActive(section);
  readonly isRouteActiveFn = (route: string, exact: boolean = false) => this.isRouteActive(route, exact);
  readonly getSectionIconFn = (id: string) => this.getSectionIcon(id);
  readonly getSectionBadgeFn = (section: NavSection) => this.getSectionBadge(section);
  readonly hasVisibleItemsFn = (section: NavSection) => this.hasVisibleItems(section);
  readonly isSectionExpandedFn = (id: string) => this.isSectionExpanded(id);
  readonly isSubmenuExpandedFn = (id: string) => this.isSubmenuExpanded(id);

  readonly sidebarId = 'app-sidebar';
  private readonly uiI18n = inject(I18nService);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly breakpointObserver = inject(BreakpointObserver, { optional: true });

  private readonly COLLAPSED_STATE_KEY = 'smartup_nav_sidebar_collapsed';
  readonly isCollapsed = signal<boolean>(this.loadCollapsedState());

  // Collapsed rail flyout popover
  readonly hoveredFlyoutSection = signal<NavSection | null>(null);
  readonly hoveredFlyoutItem = signal<NavItem | null>(null);
  readonly flyoutAnchorTop = signal<number>(0);
  readonly isFlyoutVisible = signal<boolean>(false);
  readonly isProfileFlyoutVisible = signal<boolean>(false);
  private flyoutOpenTimer: any = null;
  private flyoutCloseTimer: any = null;
  private profileFlyoutCloseTimer: any = null;
  private hoverTimeout: any = null;
  readonly isMobile = signal<boolean>(false);
  readonly isMobileMenuOpen = signal<boolean>(false);
  readonly isChangingLanguage = signal(false);
  readonly isDismissingAnnouncement = signal(false);
  private readonly announcementRevision = signal(0);
  readonly canReadNotifications = computed(() => this.canViewNotifications());
  readonly canReadAnnouncements = computed(() => this.permService.canView('platform.announcements'));

  private readonly COLLAPSED_SECTIONS_KEY = 'smartup_nav_collapsed_sections';
  readonly collapsedSections = signal<Set<string>>(this.loadCollapsedSections());
  readonly expandedSubmenus = signal<Set<string>>(new Set<string>());

  readonly navSections = computed<NavSection[]>(() => {
    const customItems: NavItem[] = this.moduleService.getActiveCustomModules().map(mod => ({
      id: `module-${mod.code}`,
      route: mod.route!,
      label: mod.name,
      icon: mod.icon || 'extension',
      permission: () => true
    }));

    const workspaceItems: NavItem[] = [
      { id: 'tasks', route: '/tasks', labelKey: 'nav.tasks', icon: 'task_alt', permission: () => this.canViewTasks(), exact: true },
      { id: 'projects', route: '/tasks/projects', labelKey: 'nav.projects', icon: 'folder', permission: () => this.canViewProjects() }
    ];

    if (this.moduleService.isModuleActive('notes')) {
      workspaceItems.push({
        id: 'notes',
        route: '/notes',
        labelKey: 'nav.notes',
        icon: 'description',
        permission: () => this.canViewNotes()
      });
    }

    workspaceItems.push(
      { id: 'files', route: '/files', labelKey: 'layout.app_shell.fayly', titleKey: 'files.faylovoe_hranilische', icon: 'folder_open', permission: () => this.canViewFiles() },
      { id: 'analytics', route: '/analytics', labelKey: 'layout.app_shell.analitika', titleKey: 'analytics.analitika_i_dashbordy', icon: 'insights', permission: () => this.canViewAnalytics() },
      { id: 'notifications', route: '/notifications', labelKey: 'nav.notifications', icon: 'notifications', permission: () => this.canViewNotifications(), badge: () => this.notifService.unreadCount() }
    );

    const sections: NavSection[] = [
      {
        id: 'workspace',
        titleKey: 'nav.section.workspace',
        items: workspaceItems
      }
    ];

    if (customItems.length > 0) {
      sections.push({
        id: 'custom-modules',
        titleKey: 'modules.title',
        items: customItems
      });
    }

    sections.push(
      {
        id: 'iam',
        titleKey: 'nav.section.iam',
        items: [
          { id: 'users', route: '/iam/users', labelKey: 'nav.users', icon: 'people', permission: () => this.canViewUsers() },
          { id: 'roles', route: '/iam/roles', labelKey: 'nav.roles', icon: 'security', permission: () => this.canViewRoles() },
          { id: 'org-units', route: '/iam/org-units', labelKey: 'iam.org_units.title', titleKey: 'iam.org_units.title', icon: 'account_tree', permission: () => this.canViewOrgUnits() },
          { id: 'custom-fields', route: '/iam/custom-fields', labelKey: 'nav.custom_fields', icon: 'tune', permission: () => this.canViewCustomFields() }
        ]
      },
      {
        id: 'administration',
        titleKey: 'nav.section.administration',
        items: [
          { id: 'announcements', route: '/announcements', labelKey: 'announcements.obyavleniya', titleKey: 'layout.app_shell.upravlenie_obyavleniyami', icon: 'campaign', permission: () => this.canViewAnnouncements() },
          { id: 'modules', route: '/settings/modules', labelKey: 'nav.modules', icon: 'extension', permission: () => this.canViewModules() },
          { id: 'navigation-settings', route: '/settings/navigation', labelKey: 'nav.navigation_settings', icon: 'menu_open', permission: () => this.canViewNavigationSettings() },
          { id: 'audit', route: '/audit', labelKey: 'nav.audit', titleKey: 'layout.app_shell.audit', icon: 'history', permission: () => this.canViewAudit() },
          { id: 'system', route: '/system', labelKey: 'layout.app_shell.sostoyanie', titleKey: 'system.sostoyanie_sistemy', icon: 'monitor_heart', permission: () => this.canViewSystem() },
          { id: 'settings', route: '/settings', labelKey: 'nav.settings', titleKey: 'layout.app_shell.nastroyki', icon: 'settings', permission: () => this.canViewSettings() }
        ]
      }
    );

    const customNavItems = this.navService.activeItems();
    if (customNavItems.length > 0) {
      const customReportItems: NavItem[] = [];

      for (const ci of customNavItems) {
        const navItem: NavItem = {
          id: `custom-nav-${ci.code}`,
          route: ci.targetType === 'EMBEDDED_IFRAME' ? `/embed/${ci.code}` : (ci.targetType === 'INTERNAL_ROUTE' ? ci.url : undefined),
          targetUrl: ci.targetType === 'EXTERNAL_LINK' ? ci.url : undefined,
          external: ci.targetType === 'EXTERNAL_LINK',
          openInIframe: ci.targetType === 'EMBEDDED_IFRAME',
          label: ci.title,
          icon: ci.icon || 'analytics',
          permission: () => true
        };

        const targetSection = sections.find(s => s.id === ci.sectionId);
        if (targetSection) {
          targetSection.items.push(navItem);
        } else {
          customReportItems.push(navItem);
        }
      }

      if (customReportItems.length > 0) {
        sections.splice(1, 0, {
          id: 'custom-reports',
          titleKey: 'nav.section.reports_and_services',
          items: customReportItems
        });
      }
    }

    return sections;
  });

  constructor(
    public authService: AuthService,
    public permService: PermissionService,
    public themeService: ThemeService,
    public i18n: I18nService,
    public notifService: NotificationService,
    public paletteService: CommandPaletteService,
    public moduleService: ModuleService,
    public navService: NavigationService,
    private router: Router
  ) {
    effect(() => {
      if (this.authService.currentUser()) {
        this.moduleService.loadActiveModules().subscribe({ error: () => {} });
        this.navService.loadActiveItems().subscribe({ error: () => {} });
      }
    });
    if (this.breakpointObserver) {
      this.breakpointObserver.observe('(max-width: 768px)')
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(result => {
          const wasMobile = this.isMobile();
          this.isMobile.set(result.matches);
          if (wasMobile && !result.matches) {
            if (this.isMobileMenuOpen()) {
              this.isMobileMenuOpen.set(false);
            }
            this.mainContent?.nativeElement?.focus();
            setTimeout(() => {
              this.mainContent?.nativeElement?.focus();
            }, 0);
          }
        });
    }

    // Permissions arrive asynchronously after login. Start only the reads
    // allowed by the server contract, and cancel them when access changes.
    effect(onCleanup => {
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
    effect(onCleanup => {
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

  @HostListener('keydown', ['$event'])
  handleKeyDown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      if (this.isFlyoutVisible() || this.isProfileFlyoutVisible()) {
        this.closeFlyout();
        this.closeProfileFlyout();
        return;
      }
    }
    if (this.isMobile() && this.isMobileMenuOpen()) {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        this.closeMobileMenu(true);
      } else if ((event.ctrlKey || event.metaKey) && !event.altKey && (event.code === 'KeyK' || event.key.toLowerCase() === 'k')) {
        this.isMobileMenuOpen.set(false);
        const opener = this.mobileMenuBtn?.nativeElement || document.querySelector('.mobile-menu-btn') as HTMLElement;
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
    this.mainContent?.nativeElement?.focus();
  }

  hasVisibleItems(section: NavSection): boolean {
    return section.items.some(item => item.permission());
  }

  private loadCollapsedSections(): Set<string> {
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        const stored = localStorage.getItem(this.COLLAPSED_SECTIONS_KEY);
        if (stored) {
          const parsed = JSON.parse(stored);
          if (Array.isArray(parsed)) {
            return new Set<string>(parsed);
          }
        }
      }
    } catch {
      // Ignore storage errors in test or restricted environments
    }
    return new Set<string>();
  }

  isSectionExpanded(sectionId: string): boolean {
    return !this.collapsedSections().has(sectionId);
  }

  toggleSection(sectionId: string, event?: Event): void {
    if (event) {
      event.preventDefault();
      event.stopPropagation();
    }
    this.collapsedSections.update(prev => {
      const next = new Set(prev);
      if (next.has(sectionId)) {
        next.delete(sectionId);
      } else {
        next.add(sectionId);
      }
      try {
        if (typeof window !== 'undefined' && window.localStorage) {
          localStorage.setItem(this.COLLAPSED_SECTIONS_KEY, JSON.stringify([...next]));
        }
      } catch {
        // Ignore storage errors
      }
      return next;
    });
  }

  isSectionActive(section: NavSection): boolean {
    return section.items.some(item => {
      if (item.route && this.isRouteActive(item.route, !!item.exact)) return true;
      if (item.children) {
        return item.children.some(child => child.route && this.isRouteActive(child.route, !!child.exact));
      }
      return false;
    });
  }

  isSubmenuExpanded(itemId: string): boolean {
    return this.expandedSubmenus().has(itemId);
  }

  toggleSubmenu(itemId: string, event?: Event): void {
    if (event) {
      event.preventDefault();
      event.stopPropagation();
    }
    this.expandedSubmenus.update(prev => {
      const next = new Set(prev);
      if (next.has(itemId)) {
        next.delete(itemId);
      } else {
        next.add(itemId);
      }
      return next;
    });
  }

  private loadCollapsedState(): boolean {
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        return localStorage.getItem(this.COLLAPSED_STATE_KEY) === 'true';
      }
    } catch {
      // Ignore in tests or restricted environments
    }
    return false;
  }

  onItemMouseEnter(section: NavSection, item: NavItem, event: MouseEvent) {
    if (!this.isCollapsed() || this.isMobile()) {
      return;
    }
    if (this.flyoutCloseTimer) {
      clearTimeout(this.flyoutCloseTimer);
      this.flyoutCloseTimer = null;
    }
    const target = (event.currentTarget || event.target) as HTMLElement;
    const rect = target.getBoundingClientRect();
    const top = Math.max(8, Math.min(rect.top - 4, (typeof window !== 'undefined' ? window.innerHeight : 800) - 340));

    this.flyoutOpenTimer = setTimeout(() => {
      this.closeProfileFlyout();
      this.hoveredFlyoutSection.set(section);
      this.hoveredFlyoutItem.set(item);
      this.flyoutAnchorTop.set(top);
      this.isFlyoutVisible.set(true);
    }, 80);
  }

  onItemMouseLeave() {
    if (this.flyoutOpenTimer) {
      clearTimeout(this.flyoutOpenTimer);
      this.flyoutOpenTimer = null;
    }
    this.flyoutCloseTimer = setTimeout(() => {
      this.closeFlyout();
    }, 180);
  }

  onFlyoutMouseEnter() {
    if (this.flyoutCloseTimer) {
      clearTimeout(this.flyoutCloseTimer);
      this.flyoutCloseTimer = null;
    }
  }

  onFlyoutMouseLeave() {
    this.flyoutCloseTimer = setTimeout(() => {
      this.closeFlyout();
    }, 150);
  }

  closeFlyout() {
    if (this.flyoutOpenTimer) {
      clearTimeout(this.flyoutOpenTimer);
      this.flyoutOpenTimer = null;
    }
    if (this.flyoutCloseTimer) {
      clearTimeout(this.flyoutCloseTimer);
      this.flyoutCloseTimer = null;
    }
    this.isFlyoutVisible.set(false);
    this.hoveredFlyoutSection.set(null);
    this.hoveredFlyoutItem.set(null);
  }

  onFlyoutItemClick() {
    this.closeFlyout();
    this.onNavClick();
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    const target = event.target as HTMLElement;
    if (!target.closest('.rail-flyout-popover') && !target.closest('.rail-category-btn') && !target.closest('.user-profile-btn')) {
      this.closeFlyout();
      this.closeProfileFlyout();
    }
  }

  @HostListener('window:keydown.escape')
  onEscapeKey() {
    this.closeFlyout();
    this.closeProfileFlyout();
    this.closeMobileMenu(true);
  }

  onProfileMouseEnter(event: MouseEvent) {
    if (!this.isCollapsed() || this.isMobile()) return;
    this.closeFlyout();
    this.isProfileFlyoutVisible.set(true);
  }

  onProfileMouseLeave() {
    this.profileFlyoutCloseTimer = setTimeout(() => {
      this.closeProfileFlyout();
    }, 180);
  }

  onProfileFlyoutMouseEnter() {
    if (this.profileFlyoutCloseTimer) {
      clearTimeout(this.profileFlyoutCloseTimer);
      this.profileFlyoutCloseTimer = null;
    }
  }

  onProfileFlyoutMouseLeave() {
    this.profileFlyoutCloseTimer = setTimeout(() => {
      this.closeProfileFlyout();
    }, 150);
  }

  closeProfileFlyout() {
    if (this.profileFlyoutCloseTimer) {
      clearTimeout(this.profileFlyoutCloseTimer);
      this.profileFlyoutCloseTimer = null;
    }
    this.isProfileFlyoutVisible.set(false);
  }

  onProfileFlyoutClick() {
    this.closeProfileFlyout();
    this.onNavClick();
  }

  onCategoryMouseEnter(section: NavSection, event: MouseEvent) {
    if (!this.isCollapsed() || this.isMobile()) return;
    if (this.flyoutOpenTimer) {
      clearTimeout(this.flyoutOpenTimer);
      this.flyoutOpenTimer = null;
    }
    if (this.flyoutCloseTimer) {
      clearTimeout(this.flyoutCloseTimer);
      this.flyoutCloseTimer = null;
    }
    const target = (event.currentTarget || event.target) as HTMLElement;
    const rect = target.getBoundingClientRect();
    const top = Math.max(8, Math.min(rect.top, (typeof window !== 'undefined' ? window.innerHeight : 800) - 340));

    this.flyoutOpenTimer = setTimeout(() => {
      this.closeProfileFlyout();
      this.hoveredFlyoutSection.set(section);
      this.flyoutAnchorTop.set(top);
      this.isFlyoutVisible.set(true);
    }, 60);
  }

  onCategoryMouseLeave() {
    if (this.flyoutOpenTimer) {
      clearTimeout(this.flyoutOpenTimer);
      this.flyoutOpenTimer = null;
    }
    this.flyoutCloseTimer = setTimeout(() => {
      this.closeFlyout();
    }, 180);
  }

  onCategoryClick(section: NavSection, event: MouseEvent) {
    event.stopPropagation();
    if (this.isFlyoutVisible() && this.hoveredFlyoutSection()?.id === section.id) {
      this.closeFlyout();
    } else {
      if (this.flyoutOpenTimer) {
        clearTimeout(this.flyoutOpenTimer);
        this.flyoutOpenTimer = null;
      }
      if (this.flyoutCloseTimer) {
        clearTimeout(this.flyoutCloseTimer);
        this.flyoutCloseTimer = null;
      }
      const target = (event.currentTarget || event.target) as HTMLElement;
      const rect = target.getBoundingClientRect();
      const top = Math.max(8, Math.min(rect.top, (typeof window !== 'undefined' ? window.innerHeight : 800) - 340));
      this.closeProfileFlyout();
      this.hoveredFlyoutSection.set(section);
      this.flyoutAnchorTop.set(top);
      this.isFlyoutVisible.set(true);
    }
  }

  getSectionBadge(section: NavSection): number {
    return section.items.reduce((sum, item) => {
      const b = item.badge ? item.badge() : 0;
      return sum + (typeof b === 'number' ? b : 0);
    }, 0);
  }

  getSectionIcon(sectionId?: string): string {
    switch (sectionId) {
      case 'workspace': return 'space_dashboard';
      case 'iam': return 'manage_accounts';
      case 'administration': return 'tune';
      case 'custom-modules': return 'extension';
      case 'custom-reports': return 'analytics';
      default: return 'folder';
    }
  }

  private saveCollapsedState(collapsed: boolean): void {
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        localStorage.setItem(this.COLLAPSED_STATE_KEY, String(collapsed));
      }
    } catch {
      // Ignore
    }
  }

  toggleSidebar() {
    this.isCollapsed.update(v => {
      const next = !v;
      try {
        if (typeof window !== 'undefined' && window.localStorage) {
          localStorage.setItem(this.COLLAPSED_STATE_KEY, String(next));
        }
      } catch {
        // Ignore
      }
      return next;
    });
    this.closeFlyout();
    this.closeProfileFlyout();
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

  isRouteActive(route: string, exact: boolean = false): boolean {
    return exact ? this.router.url === route : this.router.url.startsWith(route);
  }

  canViewTasks(): boolean {
    return this.permService.canView('tasks.items') || this.permService.canView('tasks');
  }

  canViewProjects(): boolean {
    return this.permService.canView('tasks.projects') || this.permService.canView('projects');
  }

  canViewAnalytics(): boolean {
    return this.permService.canView('analytics.dashboard') || this.permService.canView('analytics');
  }

  canViewUsers(): boolean {
    return this.permService.canView('iam.users') || this.permService.canView('md_users');
  }

  canViewRoles(): boolean {
    return this.permService.canView('rbac.roles') || this.permService.canView('iam.roles') || this.permService.canView('md_roles') || this.permService.canView('md.roles');
  }

  canViewOrgUnits(): boolean {
    return this.permService.canView('iam.org_units');
  }

  canViewCustomFields(): boolean {
    return this.permService.canView('md.custom_fields') || this.permService.canView('system.custom_fields') || this.permService.canView('md_custom_fields');
  }

  canViewFiles(): boolean {
    return this.permService.canView('platform.files') || this.permService.canView('files');
  }

  canViewNotifications(): boolean {
    return this.permService.canView('notify.inbox') || this.permService.canView('notifications');
  }

  canViewAnnouncements(): boolean {
    return this.permService.canUpdate('platform.announcements');
  }

  canViewAudit(): boolean {
    return this.permService.canView('audit.log') ||
           this.permService.canView('audit.logs') ||
           this.permService.canView('audit');
  }

  canViewSettings(): boolean {
    return this.permService.canView('platform.settings') ||
           this.permService.canView('settings') ||
           true; // Базовые личные настройки (язык, тема, пароль) доступны всем аутентифицированным пользователям
  }

  canViewSystem(): boolean {
    return this.permService.canView('platform.settings');
  }

  canViewNotes(): boolean {
    return this.permService.canView('notes') && this.moduleService.isModuleActive('notes');
  }

  canViewModules(): boolean {
    return this.permService.canView('platform.modules');
  }

  canViewNavigationSettings(): boolean {
    return this.permService.canView('platform.navigation');
  }



  getUserInitial(): string {
    const user = this.authService.currentUser();
    return user && user.name ? user.name.charAt(0).toUpperCase() : 'U';
  }

  asLang(l: string): Language {
    return l as Language;
  }

  dismissAnnouncement() {
    if (this.isDismissingAnnouncement() || this.authService.isLoggingOut()) return;
    const a = this.notifService.activeAnnouncement();
    if (a && a.id) {
      this.isDismissingAnnouncement.set(true);
      this.notifService.dismissAnnouncement(a.id).pipe(
        finalize(() => this.isDismissingAnnouncement.set(false))
      ).subscribe({
        next: () => this.announcementRevision.update(revision => revision + 1),
        error: () => {} // ApiService owns the single error message; keep the banner for retry.
      });
    }
  }

  changeLanguage(event: Event) {
    const select = event.target as HTMLSelectElement;
    if (this.isChangingLanguage() || this.i18n.isLoading() || this.authService.isLoggingOut()) {
      select.value = this.i18n.currentLang();
      return;
    }
    if (select.value === this.i18n.currentLang()) return;
    this.isChangingLanguage.set(true);
    this.i18n.setLanguage(select.value).pipe(
      takeUntilDestroyed(this.destroyRef),
      finalize(() => this.isChangingLanguage.set(false))
    ).subscribe({
      error: () => {
        select.value = this.i18n.currentLang();
        if (!this.destroyRef.destroyed) this.toast.error(this.uiI18n.translate('layout.app_shell.language_change_failed'));
      }
    });
  }

  onLogout() {
    this.authService.logout();
  }
}
