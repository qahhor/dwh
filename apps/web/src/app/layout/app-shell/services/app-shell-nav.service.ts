import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { PermissionService } from '@core/services/permission.service';
import { ModuleService } from '@core/services/module.service';
import { NavigationService } from '@core/services/navigation.service';
import { NotificationService } from '@core/services/notification.service';
import {
  NavSection,
  buildNavSections,
  loadCollapsedSections,
  loadCollapsedState,
  COLLAPSED_STATE_KEY,
  COLLAPSED_SECTIONS_KEY,
  SECTION_ICON_MAP,
} from '../app-shell.models';

/**
 * Navigation state of one application shell: which sections the person may
 * see, which are folded, and whether the rail is collapsed. Provided by the
 * shell component, so each shell instance reads the stored layout afresh.
 */
@Injectable()
export class AppShellNavService {
  private readonly permService = inject(PermissionService);
  private readonly moduleService = inject(ModuleService);
  private readonly navService = inject(NavigationService);
  private readonly notifService = inject(NotificationService);
  private readonly router = inject(Router);

  readonly isCollapsed = signal<boolean>(loadCollapsedState());
  readonly collapsedSections = signal<Set<string>>(loadCollapsedSections());
  readonly expandedSubmenus = signal<Set<string>>(new Set<string>());

  readonly navSections = computed<NavSection[]>(() =>
    buildNavSections({
      activeCustomModules: this.moduleService.getActiveCustomModules(),
      customNavItems: this.navService.activeItems(),
      entityItems: this.navService.entityItems(),
      isModuleActive: (code) => this.moduleService.isModuleActive(code),
      canViewTasks: () => this.canViewTasks(),
      canViewProjects: () => this.canViewProjects(),
      canViewSources: () => this.canViewSources(),
      canViewPackages: () => this.canViewPackages(),
      canViewFiles: () => this.canViewFiles(),
      canViewAnalytics: () => this.canViewAnalytics(),
      canViewNotifications: () => this.canViewNotifications(),
      canViewUsers: () => this.canViewUsers(),
      canViewRoles: () => this.canViewRoles(),
      canViewOrgUnits: () => this.canViewOrgUnits(),
      canViewCustomFields: () => this.canViewCustomFields(),
      canViewAnnouncements: () => this.canViewAnnouncements(),
      canViewModules: () => this.canViewModules(),
      canViewNavigationSettings: () => this.canViewNavigationSettings(),
      canViewAudit: () => this.canViewAudit(),
      canViewSystem: () => this.canViewSystem(),
      canViewSettings: () => this.canViewSettings(),
      hasPermission: (permission) => this.permService.hasPermissionKey(permission),
      unreadCount: () => this.notifService.unreadCount(),
    }),
  );

  canViewTasks = () => this.permService.canView('tasks.items');
  canViewProjects = () => this.permService.canView('tasks.projects');
  canViewAnalytics = () => this.permService.canView('analytics.dashboard');
  canViewUsers = () => this.permService.canView('md.users');
  canViewRoles = () => this.permService.canView('md.roles');
  canViewOrgUnits = () => this.permService.canView('md.org_units');
  canViewCustomFields = () => this.permService.canView('md.custom_fields');
  canViewFiles = () => this.permService.canView('mf.files');
  canViewNotifications = () => this.permService.canView('notify.inbox');
  canViewAnnouncements = () => this.permService.canUpdate('notify.announcements');
  canViewAudit = () => this.permService.canView('audit.log');
  canViewSettings = () => true;
  canViewSystem = () => this.permService.canView('md.settings');
  canViewSources = () => this.permService.canView('upl.sources') && this.moduleService.isModuleActive('upl');
  canViewPackages = () => this.permService.canView('upl.packages') && this.moduleService.isModuleActive('upl');
  canViewModules = () => this.permService.canView('md.modules');
  canViewNavigationSettings = () => this.permService.canView('md.navigation');

  // Arrow fields, because the sidebar receives them as function inputs.
  readonly hasVisibleItems = (section: NavSection): boolean => section.items.some((item) => item.permission());
  readonly isSectionExpanded = (sectionId: string): boolean => !this.collapsedSections().has(sectionId);
  readonly isSubmenuExpanded = (itemId: string): boolean => this.expandedSubmenus().has(itemId);
  readonly getSectionBadge = (section: NavSection): number =>
    section.items.reduce((sum, item) => sum + (item.badge?.() || 0), 0);
  readonly getSectionIcon = (sectionId?: string): string => (sectionId && SECTION_ICON_MAP[sectionId]) || 'folder';
  readonly isRouteActive = (route: string, exact: boolean = false): boolean =>
    exact ? this.router.url === route : this.router.url.startsWith(route);
  readonly isSectionActive = (section: NavSection): boolean =>
    section.items.some((item) => {
      if (item.route && this.isRouteActive(item.route, !!item.exact)) return true;
      if (item.children) {
        return item.children.some((child) => child.route && this.isRouteActive(child.route, !!child.exact));
      }
      return false;
    });

  toggleSection(sectionId: string, event?: Event): void {
    if (event) {
      event.preventDefault();
      event.stopPropagation();
    }
    this.collapsedSections.update((prev) => {
      const next = new Set(prev);
      if (next.has(sectionId)) {
        next.delete(sectionId);
      } else {
        next.add(sectionId);
      }
      try {
        if (typeof window !== 'undefined' && window.localStorage) {
          localStorage.setItem(COLLAPSED_SECTIONS_KEY, JSON.stringify([...next]));
        }
      } catch {
        // Ignore storage errors
      }
      return next;
    });
  }

  toggleSubmenu(itemId: string, event?: Event): void {
    if (event) {
      event.preventDefault();
      event.stopPropagation();
    }
    this.expandedSubmenus.update((prev) => {
      const next = new Set(prev);
      if (next.has(itemId)) {
        next.delete(itemId);
      } else {
        next.add(itemId);
      }
      return next;
    });
  }

  toggleCollapsed(): void {
    this.isCollapsed.update((v) => {
      const next = !v;
      try {
        if (typeof window !== 'undefined' && window.localStorage) {
          localStorage.setItem(COLLAPSED_STATE_KEY, String(next));
        }
      } catch {
        // Ignore
      }
      return next;
    });
  }
}
