import { Component, OnInit, OnDestroy, signal } from '@angular/core';

import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { PermissionService } from '../../core/services/permission.service';
import { ThemeService } from '../../core/services/theme.service';
import { I18nService, TranslatePipe, Language } from '../../core/services/i18n.service';
import { NotificationService } from '../../core/services/notification.service';
import { CommandPaletteService } from '../../core/services/command-palette.service';
import { CommandPaletteComponent } from '../command-palette/command-palette.component';
import { UiToastContainerComponent } from '../../shared/ui/ui-toast.component';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    TranslatePipe,
    CommandPaletteComponent,
    UiToastContainerComponent
  ],
  template: `
    <a class="skip-link" href="#main-content">Перейти к основному содержимому</a>
    <div class="app-layout">
      <!-- Mobile Drawer Backdrop -->
      <div *ngIf="isMobileMenuOpen()" class="mobile-drawer-backdrop" (click)="closeMobileMenu()" aria-hidden="true"></div>

      <!-- Sidebar -->
      <aside class="sidebar" [class.collapsed]="isCollapsed()" [class.mobile-open]="isMobileMenuOpen()">
        <div class="sidebar-header">
          <div class="brand-logo" *ngIf="!isCollapsed() || isMobileMenuOpen()">
            <span class="brand-icon">S</span>
            <span class="brand-name">SmartupCMS</span>
          </div>
          <button
            type="button"
            class="toggle-btn"
            (click)="toggleSidebar()"
            [attr.aria-label]="isCollapsed() ? 'Развернуть навигацию' : 'Свернуть навигацию'"
            [attr.aria-expanded]="!isCollapsed()"
            [title]="isCollapsed() ? 'Развернуть' : 'Свернуть'"
          >
            <span class="material-symbols-outlined" aria-hidden="true">{{ isCollapsed() ? 'chevron_right' : 'chevron_left' }}</span>
          </button>
        </div>

        <nav class="sidebar-nav" aria-label="Основная навигация" (click)="closeMobileMenu()">
          <!-- Tasks & Workflows -->
          <div class="nav-section-title" *ngIf="(!isCollapsed() || isMobileMenuOpen()) && (canViewTasks() || canViewProjects())">{{ 'nav.tasks' | t }}</div>
          <a *ngIf="canViewTasks()" routerLink="/tasks" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }" [attr.aria-current]="isRouteActive('/tasks', true) ? 'page' : null" class="nav-item" title="Задачи">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">task_alt</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">{{ 'nav.tasks' | t }}</span>
          </a>
          <a *ngIf="canViewProjects()" routerLink="/tasks/projects" routerLinkActive="active" [attr.aria-current]="isRouteActive('/tasks/projects') ? 'page' : null" class="nav-item" title="Проекты">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">folder</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">{{ 'nav.projects' | t }}</span>
          </a>
          <a *ngIf="canViewAnalytics()" routerLink="/analytics" routerLinkActive="active" [attr.aria-current]="isRouteActive('/analytics') ? 'page' : null" class="nav-item" title="Аналитика и дашборды">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">insights</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">Аналитика</span>
          </a>

          <!-- Master Data & IAM -->
          <div class="nav-section-title" *ngIf="(!isCollapsed() || isMobileMenuOpen()) && (canViewUsers() || canViewRoles() || canViewCustomFields())">IAM & Настройки</div>
          <a *ngIf="canViewUsers()" routerLink="/iam/users" routerLinkActive="active" [attr.aria-current]="isRouteActive('/iam/users') ? 'page' : null" class="nav-item" title="Пользователи">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">people</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">{{ 'nav.users' | t }}</span>
          </a>
          <a *ngIf="canViewRoles()" routerLink="/iam/roles" routerLinkActive="active" [attr.aria-current]="isRouteActive('/iam/roles') ? 'page' : null" class="nav-item" title="Роли и права">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">security</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">{{ 'nav.roles' | t }}</span>
          </a>
          <a *ngIf="canViewCustomFields()" routerLink="/iam/custom-fields" routerLinkActive="active" [attr.aria-current]="isRouteActive('/iam/custom-fields') ? 'page' : null" class="nav-item" title="Динамические поля">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">tune</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">{{ 'nav.custom_fields' | t }}</span>
          </a>

          <!-- System -->
          <div class="nav-section-title" *ngIf="(!isCollapsed() || isMobileMenuOpen()) && (canViewFiles() || canViewNotifications() || canViewAnnouncements() || canViewAudit() || canViewSystem() || canViewSettings())">Система</div>
          <a *ngIf="canViewFiles()" routerLink="/files" routerLinkActive="active" [attr.aria-current]="isRouteActive('/files') ? 'page' : null" class="nav-item" title="Файловое хранилище">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">folder_open</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">Файлы</span>
          </a>
          <a *ngIf="canViewNotifications()" routerLink="/notifications" routerLinkActive="active" [attr.aria-current]="isRouteActive('/notifications') ? 'page' : null" class="nav-item" title="Уведомления">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">notifications</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">{{ 'nav.notifications' | t }}</span>
            <span class="unread-chip" *ngIf="notifService.unreadCount() > 0">
              {{ notifService.unreadCount() }}
            </span>
          </a>
          <a *ngIf="canViewAnnouncements()" routerLink="/announcements" routerLinkActive="active" [attr.aria-current]="isRouteActive('/announcements') ? 'page' : null" class="nav-item" title="Управление объявлениями">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">campaign</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">Объявления</span>
          </a>
          <a *ngIf="canViewAudit()" routerLink="/audit" routerLinkActive="active" [attr.aria-current]="isRouteActive('/audit') ? 'page' : null" class="nav-item" title="Аудит">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">history</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">{{ 'nav.audit' | t }}</span>
          </a>
          <a *ngIf="canViewSystem()" routerLink="/system" routerLinkActive="active" [attr.aria-current]="isRouteActive('/system') ? 'page' : null" class="nav-item" title="Состояние системы">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">monitor_heart</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">Состояние</span>
          </a>
          <a *ngIf="canViewSettings()" routerLink="/settings" routerLinkActive="active" [attr.aria-current]="isRouteActive('/settings') ? 'page' : null" class="nav-item" title="Настройки">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">settings</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">{{ 'nav.settings' | t }}</span>
          </a>
        </nav>

        <div class="sidebar-footer">
          <a routerLink="/iam/profile" routerLinkActive="active" [attr.aria-current]="isRouteActive('/iam/profile') ? 'page' : null" class="user-profile-btn" title="Мой профиль" (click)="closeMobileMenu()">
            <div class="avatar-circle">
              {{ getUserInitial() }}
            </div>
            <div class="user-meta" *ngIf="!isCollapsed() || isMobileMenuOpen()">
              <div class="user-name">{{ authService.currentUser()?.name }}</div>
              <div class="user-role">&#64;{{ authService.currentUser()?.login }}</div>
            </div>
          </a>
        </div>
      </aside>

      <!-- Main Container -->
      <div class="main-wrapper">
        <!-- Top Navigation -->
        <header class="topbar">
          <div class="topbar-left">
            <button type="button" class="icon-btn mobile-menu-btn" aria-label="Открыть меню навигации" (click)="toggleMobileMenu()">
              <span class="material-symbols-outlined" aria-hidden="true">{{ isMobileMenuOpen() ? 'close' : 'menu' }}</span>
            </button>

            <button type="button" class="palette-trigger" aria-label="Открыть глобальный поиск" (click)="paletteService.open()">
              <span class="material-symbols-outlined" aria-hidden="true">search</span>
              <span class="trigger-text">Поиск...</span>
              <kbd class="shortcut-kbd">Ctrl K</kbd>
            </button>
          </div>

          <div class="topbar-right">
            <!-- Language Switcher -->
            <div class="lang-selector">
              <button
                type="button"
                *ngFor="let lang of i18n.languages().slice(0, 3)"
                [class.active]="i18n.currentLang() === lang.code"
                class="lang-btn"
                [attr.aria-label]="'Выбрать язык ' + lang.name"
                [attr.aria-pressed]="i18n.currentLang() === lang.code"
                (click)="i18n.setLanguage(lang.code)"
              >
                {{ lang.code.toUpperCase() }}
              </button>
            </div>

            <!-- Theme Toggle -->
            <button type="button" class="icon-btn" aria-label="Переключить тему" [attr.aria-pressed]="themeService.currentTheme() === 'dark'" (click)="themeService.toggleTheme()" [title]="themeService.currentTheme() === 'light' ? 'Тёмная тема' : 'Светлая тема'">
              <span class="material-symbols-outlined" aria-hidden="true">
                {{ themeService.currentTheme() === 'light' ? 'dark_mode' : 'light_mode' }}
              </span>
            </button>

            <!-- Notification Bell -->
            <button type="button" class="icon-btn notif-btn" routerLink="/notifications" aria-label="Открыть уведомления" title="Уведомления">
              <span class="material-symbols-outlined" aria-hidden="true">notifications</span>
              <span class="bell-dot" *ngIf="notifService.unreadCount() > 0" aria-hidden="true"></span>
              <span class="sr-only" *ngIf="notifService.unreadCount() > 0">Непрочитанных уведомлений: {{ notifService.unreadCount() }}</span>
            </button>

            <!-- Logout -->
            <button type="button" class="icon-btn logout-btn" aria-label="Выйти из системы" (click)="onLogout()" title="Выйти из системы">
              <span class="material-symbols-outlined" aria-hidden="true">logout</span>
            </button>
          </div>
        </header>


        <!-- Active Announcement Banner -->
        <div *ngIf="notifService.activeAnnouncement()" class="announcement-banner" role="status">
          <div class="announcement-content">
            <span class="material-symbols-outlined banner-icon" aria-hidden="true">campaign</span>
            <span class="banner-text">
              {{ getAnnouncementTitle() }}
            </span>
          </div>
          <button type="button" class="banner-close" aria-label="Закрыть объявление" (click)="dismissAnnouncement()">
            <span class="material-symbols-outlined" aria-hidden="true">close</span>
          </button>
        </div>

        <!-- Page View Outlet -->
        <main id="main-content" class="page-content" tabindex="-1">
          <router-outlet></router-outlet>
        </main>
      </div>
    </div>

    <!-- Command Palette Modal -->
    <app-command-palette></app-command-palette>

    <!-- Global Toast Container -->
    <ui-toast-container></ui-toast-container>
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

    /* Sidebar */
    .sidebar {
      width: 220px;
      height: 100%;
      background-color: var(--bg-sidebar);
      color: #94a3b8;
      display: flex;
      flex-direction: column;
      border-right: 1px solid rgba(255, 255, 255, 0.05);
      transition: width 0.15s ease-in-out;
      flex-shrink: 0;
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
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);
    }

    .brand-logo {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .brand-icon {
      width: 26px;
      height: 26px;
      background-color: var(--primary);
      color: #ffffff;
      border-radius: var(--radius-sm);
      display: flex;
      align-items: center;
      justify-content: center;
      font-weight: 700;
      font-size: 14px;
    }

    .brand-name {
      font-size: 14px;
      font-weight: 600;
      color: #f8fafc;
    }

    .toggle-btn {
      background: transparent;
      border: none;
      color: #64748b;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 4px;
      border-radius: var(--radius-sm);
    }
    .toggle-btn:hover {
      background-color: var(--bg-sidebar-hover);
      color: #ffffff;
    }

    .sidebar-nav {
      flex: 1;
      padding: 10px 8px;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .nav-section-title {
      font-size: 10px;
      text-transform: uppercase;
      font-weight: 600;
      letter-spacing: 0.5px;
      color: #94a3b8;
      padding: 10px 8px 4px 8px;
    }

    .nav-item {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 10px;
      border-radius: var(--radius-sm);
      color: #94a3b8;
      text-decoration: none;
      font-size: 13px;
      font-weight: 500;
      transition: all 0.1s ease;
      position: relative;
    }

    .nav-item:hover {
      background-color: var(--bg-sidebar-hover);
      color: #f1f5f9;
    }

    .nav-item.active {
      background-color: var(--bg-sidebar-active);
      color: #ffffff;
      font-weight: 600;
    }

    .nav-icon {
      font-size: 18px;
    }

    .unread-chip {
      margin-left: auto;
      background-color: var(--primary);
      color: #ffffff;
      font-size: 10px;
      font-weight: 600;
      padding: 1px 6px;
      border-radius: 9999px;
    }

    .sidebar-footer {
      padding: 10px 8px;
      border-top: 1px solid rgba(255, 255, 255, 0.08);
    }

    .user-profile-btn {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 6px 8px;
      border-radius: var(--radius-sm);
      text-decoration: none;
      color: #f1f5f9;
      transition: background-color 0.1s ease;
    }
    .user-profile-btn:hover, .user-profile-btn.active {
      background-color: var(--bg-sidebar-hover);
    }

    .avatar-circle {
      width: 28px;
      height: 28px;
      border-radius: 50%;
      background-color: var(--primary);
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: center;
      font-weight: 600;
      font-size: 12px;
      flex-shrink: 0;
    }

    .user-meta {
      overflow: hidden;
    }

    .user-name {
      font-size: 12px;
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .user-role {
      font-size: 11px;
      color: #94a3b8;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
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


    /* Topbar */
    .topbar {
      height: 52px;
      background-color: var(--bg-surface);
      border-bottom: 1px solid var(--border-color);
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0 18px;
      flex-shrink: 0;
    }

    .topbar-left {
      display: flex;
      align-items: center;
      min-width: 0;
    }

    .palette-trigger {
      display: flex;
      align-items: center;
      gap: 8px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      padding: 6px 12px;
      color: var(--text-muted);
      cursor: pointer;
      font-size: 13px;
      font-family: inherit;
      width: 240px;
    }
    .palette-trigger:hover {
      border-color: var(--primary);
      color: var(--text-main);
    }

    .trigger-text {
      flex: 1;
      text-align: left;
    }

    .shortcut-kbd {
      font-size: 10px;
      padding: 2px 4px;
      border-radius: 3px;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
    }

    .topbar-right {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .lang-selector {
      display: flex;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      overflow: hidden;
    }

    .lang-btn {
      padding: 4px 8px;
      border: none;
      background: var(--bg-surface);
      font-size: 11px;
      font-weight: 600;
      color: var(--text-muted);
      cursor: pointer;
    }
    .lang-btn.active {
      background-color: var(--primary);
      color: #ffffff;
    }

    .icon-btn {
      width: 34px;
      height: 34px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-muted);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      position: relative;
    }
    .icon-btn:hover {
      background-color: var(--bg-hover);
      color: var(--text-main);
    }

    .palette-trigger:focus-visible,
    .icon-btn:focus-visible,
    .lang-btn:focus-visible {
      outline: 2px solid var(--focus-ring, var(--primary));
      outline-offset: 2px;
    }

    .bell-dot {
      position: absolute;
      top: 6px;
      right: 6px;
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background-color: var(--danger);
    }

    /* Announcement Banner */
    .announcement-banner {
      background-color: var(--info-bg);
      border-bottom: 1px solid var(--border-color);
      color: var(--info);
      padding: 8px 18px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      font-size: 12px;
      font-weight: 500;
    }

    .announcement-content {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .banner-close {
      background: transparent;
      border: none;
      cursor: pointer;
      color: var(--info);
      display: flex;
      align-items: center;
    }

    .page-content {
      flex: 1;
      min-width: 0;
      overflow-y: auto;
      padding: 20px;
    }

    /* Mobile Drawer */
    .mobile-menu-btn {
      display: none;
    }

    .mobile-drawer-backdrop {
      display: none;
    }

    @media (max-width: 1023px) {
      .sidebar,
      .sidebar.collapsed {
        width: 60px;
      }

      .brand-logo,
      .nav-label,
      .nav-section-title,
      .user-meta,
      .unread-chip {
        display: none;
      }

      .palette-trigger {
        width: min(240px, 40vw);
      }
    }

    @media (max-width: 767px) {
      .mobile-menu-btn {
        display: inline-flex;
        margin-right: 6px;
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

      .topbar {
        padding-inline: 10px;
      }

      .trigger-text,
      .shortcut-kbd,
      .lang-selector {
        display: none;
      }

      .palette-trigger {
        width: 36px;
        padding: 6px;
        justify-content: center;
      }

      .page-content {
        padding: 12px;
        overflow-x: auto;
      }
    }

  `]
})
export class AppShellComponent implements OnInit, OnDestroy {
  readonly isCollapsed = signal<boolean>(false);
  readonly isMobileMenuOpen = signal<boolean>(false);

  constructor(
    public authService: AuthService,
    public permService: PermissionService,
    public themeService: ThemeService,
    public i18n: I18nService,
    public notifService: NotificationService,
    public paletteService: CommandPaletteService,
    private router: Router
  ) {}

  ngOnInit() {
    this.notifService.fetchUnreadCount().subscribe();
    this.notifService.fetchActiveAnnouncement().subscribe();
    this.notifService.connectSse();
  }

  ngOnDestroy() {
    this.notifService.disconnectSse();
  }

  toggleSidebar() {
    this.isCollapsed.update(v => !v);
  }

  toggleMobileMenu() {
    this.isMobileMenuOpen.update(v => !v);
  }

  closeMobileMenu() {
    this.isMobileMenuOpen.set(false);
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



  getUserInitial(): string {
    const user = this.authService.currentUser();
    return user && user.name ? user.name.charAt(0).toUpperCase() : 'U';
  }

  asLang(l: string): Language {
    return l as Language;
  }

  getAnnouncementTitle(): string {
    const a = this.notifService.activeAnnouncement();
    if (!a) return '';
    const lang = this.i18n.currentLang();
    return a.titleJson?.[lang] || a.titleJson?.['ru'] || 'Внимание';
  }

  dismissAnnouncement() {
    const a = this.notifService.activeAnnouncement();
    if (a && a.id) {
      this.notifService.dismissAnnouncement(a.id).subscribe();
    } else {
      this.notifService.activeAnnouncement.set(null);
    }
  }

  onLogout() {
    this.notifService.disconnectSse();
    this.authService.logout();
  }
}
