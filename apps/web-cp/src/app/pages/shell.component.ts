import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { CpApiService } from '../core/cp-api.service';

@Component({
  selector: 'cp-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <a class="skip-link" href="#cp-main-content">Перейти к основному содержимому</a>
    <div class="shell">
      <aside aria-label="Панель Control Panel">
        <div class="brand">
          <span class="brand-mark" aria-hidden="true">CP</span>
          <span class="brand-text">Control Panel</span>
        </div>

        <nav aria-label="Основная навигация">
          <a routerLink="/fleet" routerLinkActive="active" ariaCurrentWhenActive="page">Флот</a>
          <a routerLink="/clients" routerLinkActive="active" ariaCurrentWhenActive="page">Клиенты</a>
          <a routerLink="/backups" routerLinkActive="active" ariaCurrentWhenActive="page">Бэкапы</a>
          <a routerLink="/announcements" routerLinkActive="active" ariaCurrentWhenActive="page">Объявления</a>
        </nav>

        <div class="who">
          <div class="who-name">{{ api.user()?.name }}</div>
          <div class="who-roles">{{ roles() }}</div>
          <button type="button" (click)="logout()" aria-label="Выйти из Control Panel">Выйти</button>
        </div>
      </aside>

      <main id="cp-main-content" tabindex="-1">
        <router-outlet />
      </main>
    </div>
  `,
  styles: [`
    .shell { display: grid; grid-template-columns: 232px 1fr; min-height: 100vh; }
    .skip-link {
      position: fixed; top: 8px; left: 8px; z-index: 1000;
      padding: 8px 12px; border-radius: var(--radius-md);
      background: var(--bg-surface); color: var(--text-main);
      transform: translateY(-160%);
    }
    .skip-link:focus { transform: translateY(0); }
    aside {
      background: var(--bg-sidebar); display: flex; flex-direction: column;
      padding: 20px 12px; gap: 24px;
    }
    .brand { display: flex; align-items: center; gap: 10px; padding: 0 8px; }
    .brand-mark {
      width: 30px; height: 30px; border-radius: var(--radius-sm);
      background: var(--primary); color: var(--text-inverse);
      display: grid; place-items: center; font-size: 12px; font-weight: 700;
    }
    .brand-text { color: var(--text-inverse); font-size: 14px; font-weight: 600; }
    nav { display: flex; flex-direction: column; gap: 2px; flex: 1; }
    nav a {
      padding: 9px 12px; border-radius: var(--radius-sm); text-decoration: none;
      font-size: 14px; color: var(--text-light);
    }
    nav a:hover { background: var(--bg-sidebar-hover); color: var(--text-inverse); }
    nav a.active { background: var(--bg-sidebar-active); color: var(--text-inverse); font-weight: 500; }
    .who { padding: 12px 8px 0; border-top: 1px solid rgba(255,255,255,.1); }
    .who-name { color: var(--text-inverse); font-size: 13px; font-weight: 500; }
    .who-roles { color: var(--text-light); font-size: 12px; margin: 2px 0 10px; }
    .who button {
      width: 100%; padding: 7px; border: 1px solid rgba(255,255,255,.2);
      background: transparent; color: var(--text-light);
      border-radius: var(--radius-sm); font-size: 13px; cursor: pointer;
    }
    .who button:hover { background: var(--bg-sidebar-hover); color: var(--text-inverse); }
    main { background: var(--bg-app); padding: 28px 32px; overflow: auto; }

    @media (max-width: 768px) {
      .shell { display: block; }
      aside {
        position: sticky; top: 0; z-index: 20;
        flex-flow: row wrap; align-items: center;
        gap: 12px; padding: 12px;
      }
      nav {
        order: 3; flex: 1 0 100%;
        flex-direction: row; overflow-x: auto;
        padding-bottom: 2px;
      }
      nav a { white-space: nowrap; }
      .who {
        margin-left: auto; padding: 0; border-top: 0;
        display: flex; align-items: center; gap: 8px;
      }
      .who-roles { display: none; }
      .who button { width: auto; }
      main { min-width: 0; padding: 18px 16px; overflow: visible; }
    }

    @media (max-width: 420px) {
      .brand-text, .who-name { display: none; }
    }
  `]
})
export class ShellComponent {
  api = inject(CpApiService);
  private router = inject(Router);

  roles(): string {
    return (this.api.user()?.roles ?? []).join(', ');
  }

  async logout(): Promise<void> {
    await this.api.logout();
    await this.router.navigate(['/login']);
  }
}
