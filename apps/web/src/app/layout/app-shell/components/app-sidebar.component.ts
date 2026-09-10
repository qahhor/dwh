import { Component, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { NavItem, NavSection } from '../app-shell.models';

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    TranslatePipe
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
          <ng-container *ngIf="!isCollapsed || isMobileMenuOpen">
            <ng-container *ngFor="let section of navSections; let first = first">
              <button
                type="button"
                class="nav-section-header"
                *ngIf="hasVisibleItems(section)"
                (click)="toggleSection.emit({ id: section.id, event: $event })"
                [attr.aria-expanded]="isSectionExpanded(section.id)"
                [attr.aria-controls]="'section-content-' + section.id"
                [attr.aria-label]="(isSectionExpanded(section.id) ? 'layout.app_shell.collapse_section' : 'layout.app_shell.expand_section') | t"
              >
                <span class="nav-section-title">{{ section.titleKey | t }}</span>
                <span class="section-active-dot" *ngIf="!isSectionExpanded(section.id) && isSectionActive(section)" [attr.title]="'common.active' | t"></span>
                <span class="material-symbols-outlined section-chevron" [class.rotated]="!isSectionExpanded(section.id)" aria-hidden="true">expand_more</span>
              </button>
              <div
                [id]="'section-content-' + section.id"
                class="nav-section-content"
                [class.collapsed]="!isSectionExpanded(section.id)"
              >
                <ng-container *ngFor="let item of section.items">
                  <!-- Internal link item -->
                  <a
                    *ngIf="item.permission() && !item.children?.length && !item.external"
                    [routerLink]="item.route"
                    routerLinkActive="active"
                    [routerLinkActiveOptions]="{ exact: !!item.exact }"
                    [attr.aria-current]="item.route && isRouteActive(item.route, !!item.exact) ? 'page' : null"
                    class="nav-item"
                    [title]="item.label ? item.label : ((item.titleKey || item.labelKey!) | t)"
                  >
                    <span class="material-symbols-outlined nav-icon" aria-hidden="true">{{ item.icon }}</span>
                    <span class="nav-label">{{ item.label ? item.label : (item.labelKey! | t) }}</span>
                    <span class="unread-chip" *ngIf="item.badge && item.badge() > 0">
                      {{ item.badge() }}
                    </span>
                  </a>

                  <!-- External link item -->
                  <a
                    *ngIf="item.permission() && !item.children?.length && item.external"
                    [href]="item.targetUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                    class="nav-item"
                    [title]="item.label ? item.label : ((item.titleKey || item.labelKey!) | t)"
                  >
                    <span class="material-symbols-outlined nav-icon" aria-hidden="true">{{ item.icon }}</span>
                    <span class="nav-label">{{ item.label ? item.label : (item.labelKey! | t) }}</span>
                    <span class="material-symbols-outlined sub-icon" style="margin-left: auto; font-size: 14px; opacity: 0.7;" aria-hidden="true">open_in_new</span>
                  </a>

                  <!-- Submenu parent -->
                  <div *ngIf="item.permission() && item.children?.length" class="nav-parent-group">
                    <button
                      type="button"
                      class="nav-item nav-parent-btn"
                      (click)="toggleSubmenu.emit({ id: item.id, event: $event })"
                      [attr.aria-expanded]="isSubmenuExpanded(item.id)"
                      [title]="item.label ? item.label : ((item.titleKey || item.labelKey!) | t)"
                    >
                      <span class="material-symbols-outlined nav-icon" aria-hidden="true">{{ item.icon }}</span>
                      <span class="nav-label">{{ item.label ? item.label : (item.labelKey! | t) }}</span>
                      <span class="material-symbols-outlined submenu-chevron" [class.rotated]="!isSubmenuExpanded(item.id)" aria-hidden="true">expand_more</span>
                    </button>

                    <div class="nav-submenu" *ngIf="isSubmenuExpanded(item.id)">
                      <ng-container *ngFor="let child of item.children">
                        <a
                          *ngIf="child.permission()"
                          [routerLink]="child.route"
                          routerLinkActive="active"
                          [routerLinkActiveOptions]="{ exact: !!child.exact }"
                          [attr.aria-current]="child.route && isRouteActive(child.route, !!child.exact) ? 'page' : null"
                          class="nav-item nav-subitem"
                          [title]="child.label ? child.label : ((child.titleKey || child.labelKey!) | t)"
                        >
                          <span class="material-symbols-outlined nav-icon sub-icon" aria-hidden="true">{{ child.icon }}</span>
                          <span class="nav-label">{{ child.label ? child.label : (child.labelKey! | t) }}</span>
                        </a>
                      </ng-container>
                    </div>
                  </div>
                </ng-container>
              </div>
            </ng-container>
          </ng-container>
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
              <div class="user-role">&#64;{{ currentUser?.login }}</div>
            </div>
            <div class="nav-tooltip" *ngIf="isCollapsed && !isMobile" role="tooltip">
              <span>{{ currentUser?.name || ('nav.profile' | t) }}</span>
            </div>
          </a>
        </div>
      </aside>

      <!-- Collapsed Rail Flyout Popover (Opens next to hovered/clicked category) -->
      <div
        *ngIf="isCollapsed && !isMobile && isFlyoutVisible && hoveredFlyoutSection as section"
        class="rail-flyout-popover"
        [style.top.px]="flyoutAnchorTop"
        (mouseenter)="flyoutMouseEnter.emit()"
        (mouseleave)="flyoutMouseLeave.emit()"
        role="menu"
        [attr.aria-label]="section.titleKey | t"
      >
        <div class="flyout-header">
          <span class="flyout-section-title">{{ section.titleKey | t }}</span>
        </div>
        <div class="flyout-body">
          <ng-container *ngFor="let item of section.items">
            <!-- Internal link item -->
            <a
              *ngIf="item.permission() && !item.children?.length && !item.external"
              [routerLink]="item.route"
              routerLinkActive="active"
              [routerLinkActiveOptions]="{ exact: !!item.exact }"
              class="flyout-item"
              (click)="flyoutItemClick.emit()"
              role="menuitem"
            >
              <span class="material-symbols-outlined flyout-icon" aria-hidden="true">{{ item.icon }}</span>
              <span class="flyout-label">{{ item.label ? item.label : ((item.titleKey || item.labelKey!) | t) }}</span>
              <span class="unread-chip flyout-chip" *ngIf="item.badge && item.badge() > 0">
                {{ item.badge() }}
              </span>
            </a>

            <!-- External link item -->
            <a
              *ngIf="item.permission() && !item.children?.length && item.external"
              [href]="item.targetUrl"
              target="_blank"
              rel="noopener noreferrer"
              class="flyout-item"
              (click)="flyoutItemClick.emit()"
              role="menuitem"
            >
              <span class="material-symbols-outlined flyout-icon" aria-hidden="true">{{ item.icon }}</span>
              <span class="flyout-label">{{ item.label ? item.label : ((item.titleKey || item.labelKey!) | t) }}</span>
              <span class="material-symbols-outlined flyout-ext-icon" aria-hidden="true">open_in_new</span>
            </a>

            <!-- Submenu group item -->
            <div *ngIf="item.permission() && item.children?.length" class="flyout-parent-group">
              <div class="flyout-item flyout-parent-header">
                <span class="material-symbols-outlined flyout-icon" aria-hidden="true">{{ item.icon }}</span>
                <span class="flyout-label">{{ item.label ? item.label : ((item.titleKey || item.labelKey!) | t) }}</span>
              </div>
              <div class="flyout-subitems">
                <ng-container *ngFor="let child of item.children">
                  <a
                    *ngIf="child.permission()"
                    [routerLink]="child.route"
                    routerLinkActive="active"
                    [routerLinkActiveOptions]="{ exact: !!child.exact }"
                    class="flyout-item flyout-subitem"
                    (click)="flyoutItemClick.emit()"
                    role="menuitem"
                  >
                    <span class="material-symbols-outlined flyout-icon sub-icon" aria-hidden="true">{{ child.icon }}</span>
                    <span class="flyout-label">{{ child.label ? child.label : (child.labelKey! | t) }}</span>
                  </a>
                </ng-container>
              </div>
            </div>
          </ng-container>
        </div>
      </div>

      <!-- Collapsed Rail Profile Popover -->
      <div
        *ngIf="isCollapsed && !isMobile && isProfileFlyoutVisible"
        class="rail-flyout-popover profile-flyout"
        [style.bottom.px]="12"
        (mouseenter)="profileFlyoutMouseEnter.emit()"
        (mouseleave)="profileFlyoutMouseLeave.emit()"
        role="menu"
      >
        <div class="flyout-header profile-flyout-header">
          <div class="flyout-user-name">{{ currentUser?.name }}</div>
          <div class="flyout-user-role font-mono">&#64;{{ currentUser?.login }}</div>
        </div>
        <div class="flyout-body">
          <a routerLink="/iam/profile" class="flyout-item" (click)="profileFlyoutClick.emit()" role="menuitem">
            <span class="material-symbols-outlined flyout-icon">account_circle</span>
            <span class="flyout-label">{{ 'nav.profile' | t }}</span>
          </a>
          <a routerLink="/settings" class="flyout-item" (click)="profileFlyoutClick.emit()" role="menuitem">
            <span class="material-symbols-outlined flyout-icon">settings</span>
            <span class="flyout-label">{{ 'nav.settings' | t }}</span>
          </a>
          <button type="button" class="flyout-item flyout-btn text-danger" (click)="logout.emit()" role="menuitem">
            <span class="material-symbols-outlined flyout-icon">logout</span>
            <span class="flyout-label">{{ 'layout.app_shell.vyyti_iz_sistemy' | t }}</span>
          </button>
        </div>
      </div>
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

    /* Rail Flyout Popover (Floating card matching user screenshot) */
    .rail-flyout-popover {
      position: fixed;
      left: 64px;
      width: 240px;
      max-height: calc(100vh - 32px);
      background-color: #0f172a;
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 10px;
      box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5), 0 8px 10px -6px rgba(0, 0, 0, 0.5);
      z-index: 1250;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      padding: 8px;
      animation: flyout-appear 0.14s cubic-bezier(0.16, 1, 0.3, 1);
    }

    .rail-flyout-popover.profile-flyout {
      width: 220px;
    }

    @keyframes flyout-appear {
      from {
        opacity: 0;
        transform: translateX(-6px);
      }
      to {
        opacity: 1;
        transform: translateX(0);
      }
    }

    .flyout-header {
      padding: 6px 10px 8px 10px;
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);
      margin-bottom: 4px;
    }

    .flyout-section-title {
      font-size: 11px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.6px;
      color: #94a3b8;
    }

    .profile-flyout-header {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .flyout-user-name {
      font-size: 13px;
      font-weight: 600;
      color: #f8fafc;
    }

    .flyout-user-role {
      font-size: 11px;
      color: #94a3b8;
    }

    .flyout-body {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .flyout-item {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 12px;
      border-radius: 6px;
      color: #cbd5e1;
      text-decoration: none;
      font-size: 13px;
      font-weight: 500;
      transition: all 0.12s ease;
      cursor: pointer;
    }

    .flyout-btn {
      width: 100%;
      background: transparent;
      border: none;
      font-family: inherit;
      text-align: left;
    }

    .flyout-item:hover,
    .flyout-item.highlighted {
      background-color: rgba(255, 255, 255, 0.08);
      color: #ffffff;
    }

    .flyout-item.active {
      background-color: #0284c7 !important;
      color: #ffffff !important;
      font-weight: 600;
    }

    .flyout-item.active .flyout-icon {
      color: #ffffff !important;
      opacity: 1;
    }

    .flyout-icon {
      font-size: 18px;
      color: #94a3b8;
      opacity: 0.9;
      flex-shrink: 0;
    }

    .flyout-label {
      flex: 1;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .flyout-chip {
      margin-left: auto;
      font-size: 11px;
    }

    .flyout-ext-icon {
      font-size: 14px;
      margin-left: auto;
      opacity: 0.7;
    }

    .flyout-parent-group {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .flyout-parent-header {
      font-weight: 600;
      color: #94a3b8;
      pointer-events: none;
    }

    .flyout-subitems {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding-left: 12px;
    }

    .flyout-subitem {
      font-size: 12px;
      padding: 6px 10px;
    }

    .text-danger {
      color: #f87171 !important;
    }
    .text-danger:hover {
      background-color: rgba(239, 68, 68, 0.15) !important;
      color: #fca5a5 !important;
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

    .nav-section-header {
      width: 100%;
      background: transparent;
      border: none;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 10px 4px 10px;
      margin-top: 6px;
      cursor: pointer;
      text-align: left;
      font-family: inherit;
      border-radius: var(--radius-sm);
    }
    .nav-section-header:hover {
      background-color: rgba(255, 255, 255, 0.03);
    }

    .nav-section-title {
      font-size: 10px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.8px;
      color: #64748b;
      flex: 1;
    }

    .section-active-dot {
      width: 5px;
      height: 5px;
      border-radius: 50%;
      background-color: var(--primary);
      margin-right: 6px;
      flex-shrink: 0;
    }

    .section-chevron {
      font-size: 16px;
      color: #64748b;
      transition: transform 0.2s ease;
      line-height: 1;
    }
    .section-chevron.rotated {
      transform: rotate(-90deg);
    }

    .nav-section-content {
      display: flex;
      flex-direction: column;
      gap: 2px;
      overflow: hidden;
      transition: max-height 0.25s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.2s ease;
      max-height: 1000px;
      opacity: 1;
    }
    .nav-section-content.collapsed {
      max-height: 0;
      opacity: 0;
      pointer-events: none;
    }

    .nav-item {
      position: relative;
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 7px 10px;
      border-radius: var(--radius-sm);
      color: var(--text-sidebar);
      text-decoration: none;
      font-size: 13px;
      font-weight: 500;
      transition: background-color 0.12s, color 0.12s;
      white-space: nowrap;
      cursor: pointer;
    }

    .nav-item:hover {
      background-color: var(--bg-sidebar-hover);
      color: #ffffff;
    }

    .nav-item.active {
      background-color: var(--primary);
      color: #ffffff;
      font-weight: 600;
    }

    .nav-icon {
      font-size: 18px;
      line-height: 1;
      opacity: 0.85;
      flex-shrink: 0;
    }
    .nav-item.active .nav-icon {
      opacity: 1;
    }

    .nav-label {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .unread-chip {
      background-color: var(--primary);
      color: #ffffff;
      font-size: 10px;
      font-weight: 700;
      padding: 1px 6px;
      border-radius: 8px;
      margin-left: auto;
    }
    .nav-item.active .unread-chip {
      background-color: rgba(255, 255, 255, 0.25);
    }

    .sub-icon {
      font-size: 15px;
    }

    .nav-parent-group {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .nav-parent-btn {
      width: 100%;
      background: transparent;
      border: none;
      font-family: inherit;
      text-align: left;
    }

    .submenu-chevron {
      font-size: 16px;
      margin-left: auto;
      transition: transform 0.2s ease;
      line-height: 1;
    }
    .submenu-chevron.rotated {
      transform: rotate(-90deg);
    }

    .nav-submenu {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding-left: 28px;
    }

    .nav-subitem {
      font-size: 12px;
      padding: 6px 10px;
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
      font-family: monospace;
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
