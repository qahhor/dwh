import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CpApiService } from '../core/cp-api.service';

@Component({
  selector: 'cp-login',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="login-wrap">
      <form class="login-card" (ngSubmit)="submit()">
        <div class="brand">
          <span class="brand-mark">CP</span>
          <div>
            <h1>Control Panel</h1>
            <p class="muted">Управление флотом экземпляров</p>
          </div>
        </div>

        <label>
          Логин
          <input name="login" [(ngModel)]="login" autocomplete="username" required autofocus>
        </label>

        <label>
          Пароль
          <input name="password" type="password" [(ngModel)]="password"
                 autocomplete="current-password" required>
        </label>

        @if (error()) {
          <p class="error" role="alert">{{ error() }}</p>
        }

        <button type="submit" [disabled]="busy()">
          {{ busy() ? 'Проверяем…' : 'Войти' }}
        </button>
      </form>
    </div>
  `,
  styles: [`
    .login-wrap { min-height: 100vh; display: grid; place-items: center; background: var(--bg-app); }
    .login-card {
      width: 360px; padding: 32px; background: var(--bg-surface);
      border: 1px solid var(--border-color); border-radius: var(--radius-lg);
      box-shadow: var(--shadow-sm); display: flex; flex-direction: column; gap: 16px;
    }
    .brand { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
    .brand-mark {
      width: 40px; height: 40px; border-radius: var(--radius-md);
      background: var(--primary); color: var(--text-inverse);
      display: grid; place-items: center; font-weight: 700; letter-spacing: .5px;
    }
    h1 { margin: 0; font-size: 18px; color: var(--text-main); }
    .muted { margin: 2px 0 0; font-size: 13px; color: var(--text-muted); }
    label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--text-muted); }
    input {
      padding: 10px 12px; border: 1px solid var(--border-color);
      border-radius: var(--radius-md); font-size: 14px; color: var(--text-main);
      background: var(--bg-surface);
    }
    input:focus { outline: 2px solid var(--primary); outline-offset: 1px; border-color: var(--primary); }
    button {
      margin-top: 8px; padding: 11px; border: 0; border-radius: var(--radius-md);
      background: var(--primary); color: var(--text-inverse); font-size: 14px;
      font-weight: 600; cursor: pointer;
    }
    button:hover:not(:disabled) { background: var(--primary-hover); }
    button:disabled { opacity: .6; cursor: default; }
    .error {
      margin: 0; padding: 10px 12px; border-radius: var(--radius-md);
      background: var(--danger-bg); color: var(--danger); font-size: 13px;
    }
  `]
})
export class LoginComponent {
  private api = inject(CpApiService);
  private router = inject(Router);

  login = '';
  password = '';
  busy = signal(false);
  error = signal('');

  async submit(): Promise<void> {
    if (!this.login || !this.password) {
      this.error.set('Заполните логин и пароль');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    try {
      await this.api.login(this.login, this.password);
      await this.router.navigate(['/fleet']);
    } catch (e: unknown) {
      // Сервер намеренно не различает «нет пользователя» и «неверный пароль»
      const status = (e as { status?: number }).status;
      this.error.set(status === 401 ? 'Неверный логин или пароль'
        : status === 403 ? 'Учётная запись заблокирована'
        : 'Не удалось войти. Проверьте доступность control plane.');
    } finally {
      this.busy.set(false);
    }
  }
}
