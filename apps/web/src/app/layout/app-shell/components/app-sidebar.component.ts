import { Component, ElementRef, Input, input, output, viewChild } from '@angular/core';

import { RouterModule } from '@angular/router';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { NavItem, NavSection } from '../app-shell.models';
import { AppSidebarNavSectionsComponent } from './app-sidebar-nav-sections.component';
import { AppSidebarFlyoutComponent } from './app-sidebar-flyout.component';
import { SMTAvatarComponent } from '../../../shared/ui-kit/components/avatar';

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [SMTAvatarComponent, RouterModule, TranslatePipe, AppSidebarNavSectionsComponent, AppSidebarFlyoutComponent],
  template: `
    <!-- Mobile Drawer Backdrop -->
    @if (isMobile() && isMobileMenuOpen()) {
      <div class="mobile-drawer-backdrop" (click)="closeMobileMenu.emit(true)" aria-hidden="true"></div>
    }

    <!-- Sidebar Slot (preserves flex-layout width during floating hover) -->
    <div class="sidebar-slot" [class.collapsed]="isCollapsed()">
      <!-- Sidebar -->
      <aside
        #sidebarElement
        [id]="sidebarId()"
        class="sidebar"
        [class.collapsed]="isCollapsed()"
        [class.mobile-open]="isMobile() && isMobileMenuOpen()"
        [attr.role]="isMobile() && isMobileMenuOpen() ? 'dialog' : null"
        [attr.aria-modal]="isMobile() && isMobileMenuOpen() ? 'true' : null"
        [attr.aria-hidden]="isMobile() && !isMobileMenuOpen() ? 'true' : null"
        [attr.inert]="isMobile() && !isMobileMenuOpen() ? true : null"
      >
        <div class="sidebar-header">
          @if (!isCollapsed() || isMobileMenuOpen()) {
            <div class="brand-logo">
              <span class="brand-icon">S</span>
              <span class="brand-name">SmartupCMS</span>
            </div>
          }
          @if (isCollapsed() && !isMobileMenuOpen()) {
            <div class="brand-mark-collapsed" [title]="'SmartupCMS'">
              <span class="brand-icon">S</span>
            </div>
          }
          @if (!isMobile()) {
            <button
              type="button"
              class="toggle-btn"
              [class.pinned]="!isCollapsed()"
              (click)="toggleSidebar.emit()"
              [attr.aria-label]="
                (isCollapsed() ? 'layout.app_shell.expand_navigation' : 'layout.app_shell.collapse_navigation') | t
              "
              [attr.aria-expanded]="!isCollapsed()"
              [title]="
                (isCollapsed() ? 'layout.app_shell.expand_navigation' : 'layout.app_shell.collapse_navigation') | t
              "
            >
              <span class="material-symbols-outlined" aria-hidden="true">{{
                isCollapsed() ? 'chevron_right' : 'chevron_left'
              }}</span>
            </button>
          }
          @if (isMobile() && isMobileMenuOpen()) {
            <button
              #mobileDrawerClose
              type="button"
              class="mobile-drawer-close"
              [attr.aria-label]="'common.close' | t"
              (click)="closeMobileMenu.emit(true)"
            >
              <span class="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          }
        </div>

        <nav
          class="sidebar-nav"
          [attr.aria-label]="'layout.app_shell.osnovnaya_navigaciya' | t"
          (click)="onNavClick.emit()"
        >
          <!-- Collapsed Mode: Categories Rail (Main Categories of the Menu) -->
          @if (isCollapsed() && !isMobileMenuOpen()) {
            <div class="rail-category-list">
              @for (section of navSections(); track section) {
                @if (hasVisibleItems()(section)) {
                  <button
                    type="button"
                    class="rail-category-btn"
                    [class.active]="isSectionActive()(section)"
                    [class.open]="isFlyoutVisible() && hoveredFlyoutSection()?.id === section.id"
                    [attr.aria-label]="section.titleKey | t"
                    [attr.title]="section.titleKey | t"
                    (click)="categoryClick.emit({ section: section, event: $event })"
                    (mouseenter)="categoryMouseEnter.emit({ section: section, event: $event })"
                    (mouseleave)="categoryMouseLeave.emit()"
                  >
                    <span class="material-symbols-outlined rail-category-icon" aria-hidden="true">{{
                      getSectionIcon()(section.id)
                    }}</span>
                    @if (isSectionActive()(section)) {
                      <span class="rail-category-active-bar" aria-hidden="true"></span>
                    }
                    @if (getSectionBadge(section) > 0) {
                      <span class="rail-category-badge">{{ getSectionBadge(section) }}</span>
                    }
                    <div class="nav-tooltip" role="tooltip">
                      <span>{{ section.titleKey | t }}</span>
                    </div>
                  </button>
                }
              }
            </div>
          }

          <!-- Expanded Mode / Mobile Drawer: Full Sections Hierarchy -->
          @if (!isCollapsed() || isMobileMenuOpen()) {
            <app-sidebar-nav-sections
              [navSections]="navSections()"
              [hasVisibleItems]="hasVisibleItems()"
              [isSectionExpanded]="isSectionExpanded()"
              [isSectionActive]="isSectionActive()"
              [isSubmenuExpanded]="isSubmenuExpanded()"
              [isRouteActive]="isRouteActive()"
              (toggleSection)="toggleSection.emit($event)"
              (toggleSubmenu)="toggleSubmenu.emit($event)"
            ></app-sidebar-nav-sections>
          }
        </nav>

        <div class="sidebar-footer">
          <a
            routerLink="/iam/profile"
            routerLinkActive="active"
            [attr.aria-current]="isRouteActive()('/iam/profile') ? 'page' : null"
            class="user-profile-btn"
            [title]="'nav.profile' | t"
            (mouseenter)="profileMouseEnter.emit($event)"
            (mouseleave)="profileMouseLeave.emit()"
            (click)="onNavClick.emit()"
          >
            <smt-avatar class="avatar-circle" [name]="currentUser()?.name" smtSize="md" />
            @if (!isCollapsed() || isMobileMenuOpen()) {
              <div class="user-meta">
                <div class="user-name">{{ currentUser()?.name }}</div>
                <div class="user-role font-mono">&#64;{{ currentUser()?.login }}</div>
              </div>
            }
            @if (isCollapsed() && !isMobile()) {
              <div class="nav-tooltip" role="tooltip">
                <span>{{ currentUser()?.name || ('nav.profile' | t) }}</span>
              </div>
            }
          </a>
        </div>
      </aside>

      <!-- Collapsed Rail Flyouts (Section Popover + Profile Popover) -->
      <app-sidebar-flyout
        [isCollapsed]="isCollapsed()"
        [isMobile]="isMobile()"
        [isFlyoutVisible]="isFlyoutVisible()"
        [hoveredFlyoutSection]="hoveredFlyoutSection()"
        [flyoutAnchorTop]="flyoutAnchorTop()"
        [isProfileFlyoutVisible]="isProfileFlyoutVisible()"
        [currentUser]="currentUser()"
        (flyoutMouseEnter)="flyoutMouseEnter.emit()"
        (flyoutMouseLeave)="flyoutMouseLeave.emit()"
        (flyoutItemClick)="flyoutItemClick.emit()"
        (profileFlyoutMouseEnter)="profileFlyoutMouseEnter.emit()"
        (profileFlyoutMouseLeave)="profileFlyoutMouseLeave.emit()"
        (profileFlyoutClick)="profileFlyoutClick.emit()"
        (logout)="logout.emit()"
      ></app-sidebar-flyout>
    </div>
  `,
  styleUrl: './app-sidebar.component.css',
})
export class AppSidebarComponent {
  readonly isSectionActive = input.required<(section: NavSection) => boolean>();
  readonly isRouteActive = input.required<(route: string, exact?: boolean) => boolean>();
  readonly getSectionIcon = input.required<(id: string) => string>();
  readonly hasVisibleItems = input.required<(section: NavSection) => boolean>();
  readonly isSectionExpanded = input.required<(sectionId: string) => boolean>();
  readonly isSubmenuExpanded = input.required<(itemId: string) => boolean>();

  readonly sidebarId = input('app-sidebar');
  readonly isMobile = input(false);
  readonly isMobileMenuOpen = input(false);
  readonly isCollapsed = input(false);
  readonly navSections = input<NavSection[]>([]);
  readonly isFlyoutVisible = input(false);
  readonly hoveredFlyoutSection = input<NavSection | null>(null);
  readonly flyoutAnchorTop = input(0);
  readonly isProfileFlyoutVisible = input(false);
  readonly currentUser = input<any>(null);

  readonly toggleSidebar = output<void>();
  readonly closeMobileMenu = output<boolean>();
  readonly onNavClick = output<void>();
  readonly toggleSection = output<{
    id: string;
    event: MouseEvent;
  }>();
  readonly toggleSubmenu = output<{
    id: string;
    event: MouseEvent;
  }>();
  readonly categoryClick = output<{
    section: NavSection;
    event: MouseEvent;
  }>();
  readonly categoryMouseEnter = output<{
    section: NavSection;
    event: MouseEvent;
  }>();
  readonly categoryMouseLeave = output<void>();
  readonly profileMouseEnter = output<MouseEvent>();
  readonly profileMouseLeave = output<void>();
  readonly flyoutMouseEnter = output<void>();
  readonly flyoutMouseLeave = output<void>();
  readonly flyoutItemClick = output<void>();
  readonly profileFlyoutMouseEnter = output<void>();
  readonly profileFlyoutMouseLeave = output<void>();
  readonly profileFlyoutClick = output<void>();
  readonly logout = output<void>();

  readonly sidebarElement = viewChild<ElementRef<HTMLElement>>('sidebarElement');
  readonly mobileDrawerClose = viewChild<ElementRef<HTMLButtonElement>>('mobileDrawerClose');

  @Input() getSectionBadge!: (section: NavSection) => number;
}
