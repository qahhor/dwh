import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { CpApiService } from '../core/cp-api.service';

@Component({
  selector: 'cp-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="shell">
      <aside>
        <div class="brand">
          <span class="brand-mark">CP</span>
          <span class="brand-text">Control Panel</span>
        </div>

        <nav>
          <a routerLink="/fleet" routerLinkActive="active">Флот</a>
          <a routerLink="/clients" routerLinkActive="active">Клиенты</a>
          <a routerLink="/backups" routerLinkActive="active">Бэкапы</a>
          <a routerLink="/announcements" routerLinkActive="active">Объявления</a>
        </nav>

        <div class="who">
          <div class="who-name">{{ api.user()?.name }}</div>
          <div class="who-roles">{{ roles() }}</div>
          <button type="button" (click)="logout()">Выйти</button>
        </div>
      </aside>

      <main>
        <router-outlet />
      </main>
    </div>
  `,
  styles: [`
    .shell { display: grid; grid-template-columns: 232px 1fr; min-height: 100vh; }
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
