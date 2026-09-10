import { Component, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { NavItem, NavSection } from '../app-shell.models';
import { AppSidebarNavSectionsComponent } from './app-sidebar-nav-sections.component';
import { AppSidebarFlyoutComponent } from './app-sidebar-flyout.component';

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    TranslatePipe,
    AppSidebarNavSectionsComponent,
    AppSidebarFlyoutComponent
  ],
  template: `
    <!-- Mobile Drawer Backdrop -->
    <div *ngIf="isMobile && isMobileMenuOpen" class="mobile-drawer-backdrop" (click)="closeMobileMenu.emit(true)" aria-hidden="true"></div>

    <!-- Sidebar Slot (preserves flex-layout width during floating hover) -->
    <div class="sidebar-slot" [class.collapsed]="isCollapsed">
      <!-- Sidebar -->
      <aside
        #sidebarElement
        [id]="sidebarId"
        class="sidebar"
        [class.collapsed]="isCollapsed"
        [class.mobile-open]="isMobile && isMobileMenuOpen"
        [attr.role]="isMobile && isMobileMenuOpen ? 'dialog' : null"
        [attr.aria-modal]="isMobile && isMobileMenuOpen ? 'true' : null"
        [attr.aria-hidden]="isMobile && !isMobileMenuOpen ? 'true' : null"
        [attr.inert]="isMobile && !isMobileMenuOpen ? true : null"
      >
        <div class="sidebar-header">
          <div class="brand-logo" *ngIf="!isCollapsed || isMobileMenuOpen">
            <span class="brand-icon">S</span>
            <span class="brand-name">SmartupCMS</span>
          </div>
          <div class="brand-mark-collapsed" *ngIf="isCollapsed && !isMobileMenuOpen" [title]="'SmartupCMS'">
            <span class="brand-icon">S</span>
          </div>
          <button
            *ngIf="!isMobile"
            type="button"
            class="toggle-btn"
            [class.pinned]="!isCollapsed"
            (click)="toggleSidebar.emit()"
            [attr.aria-label]="(isCollapsed ? 'layout.app_shell.expand_navigation' : 'layout.app_shell.collapse_navigation') | t"
            [attr.aria-expanded]="!isCollapsed"
            [title]="(isCollapsed ? 'layout.app_shell.expand_navigation' : 'layout.app_shell.collapse_navigation') | t"
          >
            <span class="material-symbols-outlined" aria-hidden="true">{{ isCollapsed ? 'chevron_right' : 'chevron_left' }}</span>
          </button>
          <button
            *ngIf="isMobile && isMobileMenuOpen"
            #mobileDrawerClose
            type="button"
            class="mobile-drawer-close"
            [attr.aria-label]="'common.close' | t"
            (click)="closeMobileMenu.emit(true)"
          >
            <span class="material-symbols-outlined" aria-hidden="true">close</span>
          </button>
        </div>

        <nav class="sidebar-nav" [attr.aria-label]="'layout.app_shell.osnovnaya_navigaciya' | t" (click)="onNavClick.emit()">
          <!-- Collapsed Mode: Categories Rail (Main Categories of the Menu) -->
          <div *ngIf="isCollapsed && !isMobileMenuOpen" class="rail-category-list">
            <ng-container *ngFor="let section of navSections">
              <button
                *ngIf="hasVisibleItems(section)"
                type="button"
                class="rail-category-btn"
                [class.active]="isSectionActive(section)"
                [class.open]="isFlyoutVisible && hoveredFlyoutSection?.id === section.id"
                [attr.aria-label]="section.titleKey | t"
                [attr.title]="section.titleKey | t"
                (click)="categoryClick.emit({ section: section, event: $event })"
                (mouseenter)="categoryMouseEnter.emit({ section: section, event: $event })"
                (mouseleave)="categoryMouseLeave.emit()"
              >
                <span class="material-symbols-outlined rail-category-icon" aria-hidden="true">{{ getSectionIcon(section.id) }}</span>
                <span class="rail-category-active-bar" *ngIf="isSectionActive(section)" aria-hidden="true"></span>
                <span class="rail-category-badge" *ngIf="getSectionBadge(section) > 0">{{ getSectionBadge(section) }}</span>
                <div class="nav-tooltip" role="tooltip">
                  <span>{{ section.titleKey | t }}</span>
                </div>
              </button>
            </ng-container>
          </div>

          <!-- Expanded Mode / Mobile Drawer: Full Sections Hierarchy -->
          <app-sidebar-nav-sections
            *ngIf="!isCollapsed || isMobileMenuOpen"
            [navSections]="navSections"
            [hasVisibleItems]="hasVisibleItems"
            [isSectionExpanded]="isSectionExpanded"
            [isSectionActive]="isSectionActive"
            [isSubmenuExpanded]="isSubmenuExpanded"
            [isRouteActive]="isRouteActive"
            (toggleSection)="toggleSection.emit($event)"
            (toggleSubmenu)="toggleSubmenu.emit($event)"
          ></app-sidebar-nav-sections>
        </nav>

        <div class="sidebar-footer">
          <a
            routerLink="/iam/profile"
            routerLinkActive="active"
            [attr.aria-current]="isRouteActive('/iam/profile') ? 'page' : null"
            class="user-profile-btn"
            [title]="'nav.profile' | t"
            (mouseenter)="profileMouseEnter.emit($event)"
            (mouseleave)="profileMouseLeave.emit()"
            (click)="onNavClick.emit()"
          >
            <div class="avatar-circle">
              {{ getUserInitial() }}
            </div>
            <div class="user-meta" *ngIf="!isCollapsed || isMobileMenuOpen">
              <div class="user-name">{{ currentUser?.name }}</div>
              <div class="user-role font-mono">&#64;{{ currentUser?.login }}</div>
            </div>
            <div class="nav-tooltip" *ngIf="isCollapsed && !isMobile" role="tooltip">
              <span>{{ currentUser?.name || ('nav.profile' | t) }}</span>
            </div>
          </a>
        </div>
      </aside>

      <!-- Collapsed Rail Flyouts (Section Popover + Profile Popover) -->
      <app-sidebar-flyout
        [isCollapsed]="isCollapsed"
        [isMobile]="isMobile"
        [isFlyoutVisible]="isFlyoutVisible"
        [hoveredFlyoutSection]="hoveredFlyoutSection"
        [flyoutAnchorTop]="flyoutAnchorTop"
        [isProfileFlyoutVisible]="isProfileFlyoutVisible"
        [currentUser]="currentUser"
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
  styles: [`
    :host {
      display: contents;
    }

    /* Collapsed Rail Categories */
    .brand-mark-collapsed {
      width: 44px;
      height: 44px;
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .rail-category-list {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
      padding: 6px 0;
      width: 100%;
    }

    .rail-category-btn {
      position: relative;
      width: 44px;
      height: 44px;
      border-radius: 10px;
      background: transparent;
      border: none;
      display: flex;
      align-items: center;
      justify-content: center;
      color: #94a3b8;
      cursor: pointer;
      transition: background-color 0.15s ease, color 0.15s ease, transform 0.15s ease;
    }

    .rail-category-btn:hover,
    .rail-category-btn.open {
      background-color: var(--bg-sidebar-hover, rgba(255, 255, 255, 0.08));
      color: #ffffff;
    }

    .rail-category-btn.active {
      background-color: rgba(2, 132, 199, 0.18);
      color: #38bdf8;
    }

    .rail-category-btn.active .rail-category-icon {
      color: #38bdf8;
    }

    .rail-category-icon {
      font-size: 22px;
      line-height: 1;
    }

    .rail-category-active-bar {
      position: absolute;
      left: 0;
      top: 10px;
      bottom: 10px;
      width: 3px;
      border-radius: 0 3px 3px 0;
      background-color: #38bdf8;
    }

    .rail-category-badge {
      position: absolute;
      top: 4px;
      right: 4px;
      min-width: 16px;
      height: 16px;
      padding: 0 4px;
      border-radius: 8px;
      background-color: #ef4444;
      color: #ffffff;
      font-size: 10px;
      font-weight: 700;
      display: flex;
      align-items: center;
      justify-content: center;
      line-height: 1;
    }

    .sidebar-slot:has(.rail-flyout-popover) .nav-tooltip {
      opacity: 0 !important;
      visibility: hidden !important;
      pointer-events: none !important;
    }

    .sidebar-slot {
      width: 250px;
      flex-shrink: 0;
      position: relative;
      z-index: 100;
      transition: width 0.25s cubic-bezier(0.4, 0, 0.2, 1);
    }

    .sidebar-slot.collapsed {
      width: 60px;
    }

    .sidebar {
      position: fixed;
      top: 0;
      bottom: 0;
      left: 0;
      width: 250px;
      background-color: var(--bg-sidebar);
      border-right: 1px solid var(--border-sidebar);
      display: flex;
      flex-direction: column;
      z-index: 100;
      transition: width 0.25s cubic-bezier(0.4, 0, 0.2, 1);
      overflow: hidden;
    }

    .sidebar.collapsed {
      width: 60px;
    }

    .sidebar-header {
      height: 52px;
      padding: 0 14px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid var(--border-sidebar);
      flex-shrink: 0;
    }

    .brand-logo {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .brand-icon {
      width: 28px;
      height: 28px;
      background: linear-gradient(135deg, var(--primary) 0%, var(--primary-hover) 100%);
      color: #ffffff;
      border-radius: var(--radius-sm);
      display: flex;
      align-items: center;
      justify-content: center;
      font-weight: 700;
      font-size: 14px;
      flex-shrink: 0;
    }

    .brand-name {
      font-weight: 600;
      font-size: 15px;
      letter-spacing: -0.2px;
      color: #ffffff;
    }

    .toggle-btn {
      width: 26px;
      height: 26px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-sidebar);
      background-color: transparent;
      color: var(--text-sidebar);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      opacity: 0.7;
      transition: opacity 0.15s, background-color 0.15s;
    }
    .toggle-btn:hover {
      opacity: 1;
      background-color: var(--bg-sidebar-hover);
    }
    .toggle-btn.pinned {
      opacity: 0.9;
    }

    .mobile-drawer-close {
      width: 32px;
      height: 32px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-sidebar);
      background-color: transparent;
      color: var(--text-sidebar);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
    }

    .sidebar.collapsed .sidebar-header {
      justify-content: center;
      padding: 0;
    }

    .sidebar-nav {
      flex: 1;
      overflow-y: auto;
      overflow-x: hidden;
      padding: 8px 6px;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .sidebar.collapsed .sidebar-nav {
      align-items: center;
      padding: 6px 0;
    }

    .nav-tooltip {
      position: absolute;
      left: calc(100% + 10px);
      top: 50%;
      transform: translateY(-50%);
      background-color: #0f172a;
      color: #ffffff;
      font-size: 12px;
      font-weight: 500;
      padding: 4px 8px;
      border-radius: var(--radius-sm);
      white-space: nowrap;
      pointer-events: none;
      opacity: 0;
      transition: opacity 0.15s ease, transform 0.15s ease;
      box-shadow: var(--shadow-overlay);
      z-index: 1000;
    }

    .sidebar.collapsed .nav-item:hover .nav-tooltip,
    .rail-category-btn:hover .nav-tooltip {
      opacity: 1;
    }

    .sidebar-footer {
      padding: 10px;
      border-top: 1px solid var(--border-sidebar);
      flex-shrink: 0;
    }

    .user-profile-btn {
      position: relative;
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 6px 8px;
      border-radius: var(--radius-sm);
      color: var(--text-sidebar);
      text-decoration: none;
      transition: background-color 0.12s;
      cursor: pointer;
    }
    .user-profile-btn:hover {
      background-color: var(--bg-sidebar-hover);
      color: #ffffff;
    }
    .user-profile-btn.active {
      background-color: var(--bg-sidebar-hover);
      color: #ffffff;
    }

    .avatar-circle {
      width: 30px;
      height: 30px;
      border-radius: 50%;
      background-color: var(--primary);
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: center;
      font-weight: 600;
      font-size: 13px;
      flex-shrink: 0;
    }

    .user-meta {
      flex: 1;
      min-width: 0;
    }

    .user-name {
      font-size: 12px;
      font-weight: 600;
      color: #ffffff;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .user-role {
      font-size: 11px;
      color: var(--text-sidebar);
    }

    .sidebar.collapsed .user-profile-btn {
      justify-content: center;
      padding: 6px 0;
    }

    .mobile-drawer-backdrop {
      display: none;
    }

    @media (max-width: 1023px) {
      .sidebar-slot {
        width: 60px;
      }

      .sidebar.collapsed:not(.hover-expanded) .brand-logo,
      .sidebar.collapsed:not(.hover-expanded) .nav-label,
      .sidebar.collapsed:not(.hover-expanded) .nav-section-title,
      .sidebar.collapsed:not(.hover-expanded) .user-meta,
      .sidebar.collapsed:not(.hover-expanded) .unread-chip {
        display: none;
      }
    }

    @media (max-width: 767px) {
      .sidebar-slot {
        width: 0 !important;
      }

      .mobile-drawer-backdrop {
        display: block;
        position: fixed;
        inset: 0;
        background: rgba(15, 23, 42, 0.6);
        backdrop-filter: blur(3px);
        z-index: 1100;
        animation: fadeIn 0.15s ease-out;
      }

      .sidebar {
        position: fixed;
        left: -260px;
        top: 0;
        bottom: 0;
        width: 250px !important;
        z-index: 1200;
        box-shadow: var(--shadow-overlay);
        transition: left 0.25s cubic-bezier(0.4, 0, 0.2, 1);
      }

      .sidebar.mobile-open {
        left: 0;
      }

      .sidebar.mobile-open .brand-logo,
      .sidebar.mobile-open .nav-label,
      .sidebar.mobile-open .nav-section-title,
      .sidebar.mobile-open .user-meta,
      .sidebar.mobile-open .unread-chip {
        display: flex !important;
      }
    }

    @keyframes fadeIn {
      from { opacity: 0; }
      to { opacity: 1; }
    }

    .font-mono {
      font-family: monospace;
    }
  `]
})
export class AppSidebarComponent {
  @ViewChild('sidebarElement') sidebarElement?: ElementRef<HTMLElement>;
  @ViewChild('mobileDrawerClose') mobileDrawerClose?: ElementRef<HTMLButtonElement>;

  @Input() sidebarId = 'app-sidebar';
  @Input() isMobile = false;
  @Input() isMobileMenuOpen = false;
  @Input() isCollapsed = false;
  @Input() navSections: NavSection[] = [];
  @Input() isFlyoutVisible = false;
  @Input() hoveredFlyoutSection: NavSection | null = null;
  @Input() flyoutAnchorTop = 0;
  @Input() isProfileFlyoutVisible = false;
  @Input() currentUser: any = null;

  @Input() isSectionActive!: (section: NavSection) => boolean;
  @Input() isRouteActive!: (route: string, exact?: boolean) => boolean;
  @Input() getSectionIcon!: (id: string) => string;
  @Input() getSectionBadge!: (section: NavSection) => number;
  @Input() hasVisibleItems!: (section: NavSection) => boolean;
  @Input() isSectionExpanded!: (sectionId: string) => boolean;
  @Input() isSubmenuExpanded!: (itemId: string) => boolean;

  @Output() toggleSidebar = new EventEmitter<void>();
  @Output() closeMobileMenu = new EventEmitter<boolean>();
  @Output() onNavClick = new EventEmitter<void>();
  @Output() toggleSection = new EventEmitter<{ id: string, event: MouseEvent }>();
  @Output() toggleSubmenu = new EventEmitter<{ id: string, event: MouseEvent }>();
  @Output() categoryClick = new EventEmitter<{ section: NavSection, event: MouseEvent }>();
  @Output() categoryMouseEnter = new EventEmitter<{ section: NavSection, event: MouseEvent }>();
  @Output() categoryMouseLeave = new EventEmitter<void>();
  @Output() profileMouseEnter = new EventEmitter<MouseEvent>();
  @Output() profileMouseLeave = new EventEmitter<void>();
  @Output() flyoutMouseEnter = new EventEmitter<void>();
  @Output() flyoutMouseLeave = new EventEmitter<void>();
  @Output() flyoutItemClick = new EventEmitter<void>();
  @Output() profileFlyoutMouseEnter = new EventEmitter<void>();
  @Output() profileFlyoutMouseLeave = new EventEmitter<void>();
  @Output() profileFlyoutClick = new EventEmitter<void>();
  @Output() logout = new EventEmitter<void>();

  getUserInitial(): string {
    const user = this.currentUser;
    return user && user.name ? user.name.charAt(0).toUpperCase() : 'U';
  }
}
