import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { CpApiService } from '../core/cp-api.service';

@Component({
  selector: 'cp-shell',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <a class="skip-link" href="#cp-main-content">Перейти к основному содержимому</a>
    <div class="app-layout">
      <!-- Mobile Drawer Backdrop -->
      <div *ngIf="isMobileMenuOpen()" class="mobile-drawer-backdrop" (click)="closeMobileMenu()" aria-hidden="true"></div>

      <!-- Sidebar -->
      <aside class="sidebar" [class.collapsed]="isCollapsed()" [class.mobile-open]="isMobileMenuOpen()">
        <div class="sidebar-header">
          <div class="brand-logo" *ngIf="!isCollapsed() || isMobileMenuOpen()">
            <span class="brand-icon">CP</span>
            <div class="brand-text-group">
              <span class="brand-name">Control Panel</span>
              <span class="brand-sub">Smartup Fleet</span>
            </div>
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
          <div class="nav-section-title" *ngIf="!isCollapsed() || isMobileMenuOpen()">ОПЕРАЦИИ & ФЛОТ</div>

          <a routerLink="/fleet" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }" class="nav-item" title="Флот экземпляров">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">dns</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">Флот экземпляров</span>
          </a>

          <a routerLink="/clients" routerLinkActive="active" class="nav-item" title="Клиенты">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">corporate_fare</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">Клиенты</span>
          </a>

          <a routerLink="/modules" routerLinkActive="active" class="nav-item" title="Модерация модулей">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">extension</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">Модерация модулей</span>
          </a>

          <a routerLink="/backups" routerLinkActive="active" class="nav-item" title="Резервные копии">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">history</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">Резервные копии</span>
          </a>

          <a routerLink="/announcements" routerLinkActive="active" class="nav-item" title="Объявления">
            <span class="material-symbols-outlined nav-icon" aria-hidden="true">campaign</span>
            <span class="nav-label" *ngIf="!isCollapsed() || isMobileMenuOpen()">Объявления</span>
          </a>
        </nav>

        <div class="sidebar-footer">
          <div class="user-profile-btn" title="Профиль администратора">
            <div class="avatar-circle">
              {{ userInitial() }}
            </div>
            <div class="user-meta" *ngIf="!isCollapsed()">
              <div class="user-name">{{ api.user()?.name || 'Administrator' }}</div>
              <div class="user-role">&#64;{{ roles() }}</div>
            </div>
          </div>
        </div>
      </aside>

      <!-- Main Wrapper -->
      <div class="main-wrapper">
        <!-- Topbar -->
        <header class="topbar">
          <div class="topbar-left">
            <button type="button" class="icon-btn mobile-menu-btn" aria-label="Открыть меню навигации" (click)="toggleMobileMenu()">
              <span class="material-symbols-outlined" aria-hidden="true">{{ isMobileMenuOpen() ? 'close' : 'menu' }}</span>
            </button>

            <div class="palette-trigger">
              <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
              <span class="trigger-text">Поиск...</span>
              <kbd class="shortcut-kbd">Ctrl K</kbd>
            </div>
          </div>

          <div class="topbar-right">
            <!-- Language Switcher -->
            <div class="lang-selector">
              <button
                type="button"
                *ngFor="let lang of ['RU', 'UZ', 'EN']"
                [class.active]="currentLang() === lang"
                class="lang-btn"
                (click)="setLang(lang)"
              >
                {{ lang }}
              </button>
            </div>

            <!-- Theme Toggle -->
            <button
              type="button"
              class="icon-btn"
              [title]="isDark() ? 'Светлая тема' : 'Тёмная тема'"
              (click)="toggleTheme()"
            >
              <span class="material-symbols-outlined" aria-hidden="true">
                {{ isDark() ? 'light_mode' : 'dark_mode' }}
              </span>
            </button>

            <!-- Notifications Bell -->
            <button
              type="button"
              class="icon-btn notif-btn"
              title="Системный пульс и уведомления"
            >
              <span class="material-symbols-outlined" aria-hidden="true">notifications</span>
              <span class="pulse-badge"></span>
            </button>

            <!-- Logout -->
            <button
              type="button"
              class="icon-btn logout-btn"
              (click)="logout()"
              aria-label="Выйти из Control Panel"
              title="Выйти из системы"
            >
              <span class="material-symbols-outlined" aria-hidden="true">logout</span>
            </button>
          </div>
        </header>

        <!-- Page Content -->
        <main id="cp-main-content" class="page-content" tabindex="-1">
          <router-outlet></router-outlet>
        </main>
      </div>
    </div>
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
    .skip-link:focus { transform: translateY(0); }

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
      gap: 10px;
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
      font-size: 13px;
    }

    .brand-text-group {
      display: flex;
      flex-direction: column;
    }

    .brand-name {
      font-size: 13px;
      font-weight: 600;
      color: #f8fafc;
    }

    .brand-sub {
      font-size: 10px;
      color: #64748b;
      font-weight: 500;
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
      color: #f1f5f9;
      background-color: transparent;
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
      min-width: 0;
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
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      padding: 2px;
      gap: 2px;
    }

    .lang-btn {
      background: transparent;
      border: none;
      padding: 4px 8px;
      font-size: 11px;
      font-weight: 600;
      color: var(--text-muted);
      border-radius: var(--radius-sm);
      cursor: pointer;
      transition: all 0.1s ease;
    }
    .lang-btn.active {
      background-color: var(--bg-surface);
      color: var(--primary);
      box-shadow: var(--shadow-sm);
    }

    .icon-btn {
      width: 34px;
      height: 34px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: var(--radius-md);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-muted);
      cursor: pointer;
      transition: all 0.15s ease;
      position: relative;
    }
    .icon-btn:hover {
      background-color: var(--bg-hover);
      color: var(--text-main);
    }

    .pulse-badge {
      position: absolute;
      top: 6px;
      right: 6px;
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background-color: var(--success);
      box-shadow: 0 0 0 2px var(--bg-surface);
    }

    .logout-btn:hover {
      background-color: var(--danger-bg);
      border-color: rgba(220, 38, 38, 0.3);
      color: var(--danger);
    }

    /* Mobile Drawer */
    .mobile-menu-btn {
      display: none;
    }

    .mobile-drawer-backdrop {
      display: none;
    }

    @media (max-width: 1023px) {
      .sidebar, .sidebar.collapsed { width: 60px; }
      .brand-logo, .nav-label, .nav-section-title, .user-meta { display: none; }
      .palette-trigger { width: min(200px, 30vw); }
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
      .sidebar.mobile-open .user-meta {
        display: flex !important;
      }

      .topbar { padding-inline: 10px; }
      .trigger-text, .shortcut-kbd, .lang-selector { display: none; }
      .palette-trigger { width: 36px; padding: 6px; justify-content: center; }
      .page-content { padding: 12px; }
    }

    /* Page Content */
    .page-content {
      flex: 1;
      overflow-y: auto;
      padding: 20px 24px;
    }
  `]
})
export class ShellComponent {
  api = inject(CpApiService);
  private router = inject(Router);

  readonly isCollapsed = signal<boolean>(false);
  readonly isMobileMenuOpen = signal<boolean>(false);
  readonly isDark = signal<boolean>(false);
  readonly currentLang = signal<string>('RU');

  constructor() {
    const savedTheme = localStorage.getItem('cp_theme');
    if (savedTheme === 'dark') {
      this.isDark.set(true);
      document.documentElement.setAttribute('data-theme', 'dark');
    }
  }

  toggleSidebar(): void {
    this.isCollapsed.set(!this.isCollapsed());
  }

  toggleMobileMenu(): void {
    this.isMobileMenuOpen.update(v => !v);
  }

  closeMobileMenu(): void {
    this.isMobileMenuOpen.set(false);
  }

  toggleTheme(): void {
    const next = !this.isDark();
    this.isDark.set(next);
    const theme = next ? 'dark' : 'light';
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('cp_theme', theme);
  }

  setLang(lang: string): void {
    this.currentLang.set(lang);
  }

  userInitial(): string {
    const name = this.api.user()?.name || 'A';
    return name.charAt(0).toUpperCase();
  }

  roles(): string {
    return (this.api.user()?.roles ?? ['cp-admin']).join(', ');
  }

  async logout(): Promise<void> {
    await this.api.logout();
    await this.router.navigate(['/login']);
  }
}
