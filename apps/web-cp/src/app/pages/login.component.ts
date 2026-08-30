import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CpApiService } from '../core/cp-api.service';

@Component({
  selector: 'cp-login',
  standalone: true,
  imports: [FormsModule],
  template: `
    <main class="login-wrap">
      <form class="login-card" (ngSubmit)="submit()" aria-labelledby="cp-login-title">
        <div class="brand">
          <span class="brand-mark" aria-hidden="true">CP</span>
          <div>
            <h1 id="cp-login-title">Control Panel</h1>
            <p class="muted">Управление флотом экземпляров</p>
          </div>
        </div>

        <label for="cp-login">Логин</label>
        <input
          id="cp-login"
          name="login"
          [(ngModel)]="login"
          autocomplete="username"
          autocapitalize="none"
          spellcheck="false"
          [attr.aria-invalid]="error() ? 'true' : null"
          [attr.aria-describedby]="error() ? 'cp-login-error' : null"
          required
          autofocus
        >

        <label for="cp-password">Пароль</label>
        <input
          id="cp-password"
          name="password"
          type="password"
          [(ngModel)]="password"
          autocomplete="current-password"
          [attr.aria-invalid]="error() ? 'true' : null"
          [attr.aria-describedby]="error() ? 'cp-login-error' : null"
          required
        >

        @if (error()) {
          <p id="cp-login-error" class="error" role="alert">{{ error() }}</p>
        }

        <button type="submit" [disabled]="busy()" [attr.aria-busy]="busy()">
          {{ busy() ? 'Проверяем…' : 'Войти' }}
        </button>
      </form>
    </main>
  `,
  styles: [`
    .login-wrap { min-height: 100vh; display: grid; place-items: center; background: var(--bg-app); }
    .login-card {
      width: min(360px, calc(100% - 32px)); padding: 32px; background: var(--bg-surface);
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
    label { margin-bottom: -10px; font-size: 13px; color: var(--text-muted); }
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
    @media (max-width: 480px) {
      .login-card { padding: 24px 20px; }
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
