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
    <div class="app-layout">
      <!-- Sidebar -->
      <aside class="sidebar" [class.collapsed]="isCollapsed()">
        <div class="sidebar-header">
          <div class="brand-logo" *ngIf="!isCollapsed()">
            <span class="brand-icon">D</span>
            <span class="brand-name">DWH Platform</span>
          </div>
          <button class="toggle-btn" (click)="toggleSidebar()" [title]="isCollapsed() ? 'Развернуть' : 'Свернуть'">
            <span class="material-symbols-outlined">{{ isCollapsed() ? 'chevron_right' : 'chevron_left' }}</span>
          </button>
        </div>

        <nav class="sidebar-nav">
          <!-- Tasks & Workflows -->
          <div class="nav-section-title" *ngIf="!isCollapsed()">{{ 'nav.tasks' | t }}</div>
          <a routerLink="/tasks" routerLinkActive="active" class="nav-item" title="Задачи">
            <span class="material-symbols-outlined nav-icon">task_alt</span>
            <span class="nav-label" *ngIf="!isCollapsed()">{{ 'nav.tasks' | t }}</span>
          </a>
          <a routerLink="/tasks/projects" routerLinkActive="active" class="nav-item" title="Проекты">
            <span class="material-symbols-outlined nav-icon">folder</span>
            <span class="nav-label" *ngIf="!isCollapsed()">{{ 'nav.projects' | t }}</span>
          </a>

          <!-- Master Data & IAM -->
          <div class="nav-section-title" *ngIf="!isCollapsed() && (canViewUsers() || canViewRoles() || canViewCustomFields())">IAM & Настройки</div>
          <a *ngIf="canViewUsers()" routerLink="/iam/users" routerLinkActive="active" class="nav-item" title="Пользователи">
            <span class="material-symbols-outlined nav-icon">people</span>
            <span class="nav-label" *ngIf="!isCollapsed()">{{ 'nav.users' | t }}</span>
          </a>
          <a *ngIf="canViewRoles()" routerLink="/iam/roles" routerLinkActive="active" class="nav-item" title="Роли и права">
            <span class="material-symbols-outlined nav-icon">security</span>
            <span class="nav-label" *ngIf="!isCollapsed()">{{ 'nav.roles' | t }}</span>
          </a>
          <a *ngIf="canViewCustomFields()" routerLink="/iam/custom-fields" routerLinkActive="active" class="nav-item" title="Динамические поля">
            <span class="material-symbols-outlined nav-icon">tune</span>
            <span class="nav-label" *ngIf="!isCollapsed()">{{ 'nav.custom_fields' | t }}</span>
          </a>


          <!-- System -->
          <div class="nav-section-title" *ngIf="!isCollapsed()">Система</div>
          <a routerLink="/files" routerLinkActive="active" class="nav-item" title="Файловое хранилище">
            <span class="material-symbols-outlined nav-icon">folder_open</span>
            <span class="nav-label" *ngIf="!isCollapsed()">Файлы</span>
          </a>
          <a routerLink="/notifications" routerLinkActive="active" class="nav-item" title="Уведомления">
            <span class="material-symbols-outlined nav-icon">notifications</span>
            <span class="nav-label" *ngIf="!isCollapsed()">{{ 'nav.notifications' | t }}</span>
            <span class="unread-chip" *ngIf="notifService.unreadCount() > 0 && !isCollapsed()">
              {{ notifService.unreadCount() }}
            </span>
          </a>
          <a *ngIf="canViewAudit()" routerLink="/audit" routerLinkActive="active" class="nav-item" title="Аудит">
            <span class="material-symbols-outlined nav-icon">history</span>
            <span class="nav-label" *ngIf="!isCollapsed()">{{ 'nav.audit' | t }}</span>
          </a>
          <a routerLink="/settings" routerLinkActive="active" class="nav-item" title="Настройки">
            <span class="material-symbols-outlined nav-icon">settings</span>
            <span class="nav-label" *ngIf="!isCollapsed()">{{ 'nav.settings' | t }}</span>
          </a>
        </nav>



        <div class="sidebar-footer">
          <a routerLink="/iam/profile" routerLinkActive="active" class="user-profile-btn" title="Мой профиль">
            <div class="avatar-circle">
              {{ getUserInitial() }}
            </div>
            <div class="user-meta" *ngIf="!isCollapsed()">
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
            <button class="palette-trigger" (click)="paletteService.open()">
              <span class="material-symbols-outlined">search</span>
              <span class="trigger-text">Поиск...</span>
              <kbd class="shortcut-kbd">Ctrl K</kbd>
            </button>
          </div>

          <div class="topbar-right">
            <!-- Language Switcher -->
            <div class="lang-selector">
              <button
                *ngFor="let lang of ['ru', 'uz', 'en']"
                [class.active]="i18n.currentLang() === lang"
                class="lang-btn"
                (click)="i18n.setLanguage(asLang(lang))"
              >
                {{ lang.toUpperCase() }}
              </button>
            </div>

            <!-- Theme Toggle -->
            <button class="icon-btn" (click)="themeService.toggleTheme()" [title]="themeService.currentTheme() === 'light' ? 'Тёмная тема' : 'Светлая тема'">
              <span class="material-symbols-outlined">
                {{ themeService.currentTheme() === 'light' ? 'dark_mode' : 'light_mode' }}
              </span>
            </button>

            <!-- Notification Bell -->
            <button class="icon-btn notif-btn" routerLink="/notifications" title="Уведомления">
              <span class="material-symbols-outlined">notifications</span>
              <span class="bell-dot" *ngIf="notifService.unreadCount() > 0"></span>
            </button>

            <!-- Logout -->
            <button class="icon-btn logout-btn" (click)="onLogout()" title="Выйти из системы">
              <span class="material-symbols-outlined">logout</span>
            </button>
          </div>
        </header>


        <!-- Active Announcement Banner -->
        <div *ngIf="notifService.activeAnnouncement()" class="announcement-banner">
          <div class="announcement-content">
            <span class="material-symbols-outlined banner-icon">campaign</span>
            <span class="banner-text">
              {{ getAnnouncementTitle() }}
            </span>
          </div>
          <button class="banner-close" (click)="dismissAnnouncement()">
            <span class="material-symbols-outlined">close</span>
          </button>
        </div>

        <!-- Page View Outlet -->
        <main class="page-content">
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
      color: #475569;
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
      color: #64748b;
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

  `]
})
export class AppShellComponent implements OnInit, OnDestroy {
  readonly isCollapsed = signal<boolean>(false);

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

  canViewUsers(): boolean {
    return this.permService.canView('iam.users') || this.permService.canView('md_users');
  }

  canViewRoles(): boolean {
    return this.permService.canView('rbac.roles') || this.permService.canView('iam.roles') || this.permService.canView('md_roles') || this.permService.canView('md.roles');
  }

  canViewCustomFields(): boolean {
    return this.permService.canView('system.custom_fields') || this.permService.canView('md_custom_fields') || this.permService.canView('md.custom_fields');
  }


  canViewAudit(): boolean {
    return this.permService.hasPermission('audit.log', 'view') ||
           this.permService.hasPermission('audit.logs', 'view') ||
           this.permService.hasPermission('audit', 'view');
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
    if (a) {
      this.notifService.dismissAnnouncement(a.id).subscribe();
    }
  }

  onLogout() {
    this.notifService.disconnectSse();
    this.authService.logout();
  }
}

