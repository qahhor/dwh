import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Client, CpApiService } from '../core/cp-api.service';
import { dt, errorText } from '../core/format';

@Component({
  selector: 'cp-clients',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <div>
        <h1>Клиенты</h1>
        <p>Каждому клиенту — независимый экземпляр (ADR-0004). Регистрация выдаёт
           heartbeat-токен, который показывается один раз.</p>
      </div>
    </div>

    @if (error()) { <div class="alert alert-error">{{ error() }}</div> }

    @if (issuedToken(); as token) {
      <div class="card token-card">
        <div class="card-head">Heartbeat-токен экземпляра — сохраните сейчас</div>
        <div class="card-body">
          <p class="token-note">
            Токен показывается один раз: в базе хранится только его hash.
            Пропишите его экземпляру в переменную <span class="mono">DWH_CP_HEARTBEAT_TOKEN</span>.
          </p>
          <div class="token-row">
            <code class="mono token">{{ token }}</code>
            <button class="btn btn-secondary btn-sm" (click)="copyToken(token)">
              {{ copied() ? 'Скопировано' : 'Копировать' }}
            </button>
            <button class="btn btn-secondary btn-sm" (click)="issuedToken.set('')">Скрыть</button>
          </div>
        </div>
      </div>
    }

    @if (canManage()) {
      <div class="card">
        <div class="card-head">Новый клиент</div>
        <div class="card-body">
          <form class="form-row" (ngSubmit)="createClient()">
            <label class="field">
              <span>Код</span>
              <input name="code" [(ngModel)]="code" placeholder="acme" required>
            </label>
            <label class="field">
              <span>Название</span>
              <input name="name" [(ngModel)]="name" placeholder="ООО «Акме»" required>
            </label>
            <label class="field">
              <span>Профиль ресурсов</span>
              <select name="profile" [(ngModel)]="profile">
                <option value="S">S — малый</option>
                <option value="M">M — средний</option>
                <option value="L">L — крупный</option>
              </select>
            </label>
            <button class="btn" type="submit" [disabled]="busy()">Создать</button>
          </form>
        </div>
      </div>

      <div class="card">
        <div class="card-head">Регистрация экземпляра</div>
        <div class="card-body">
          <form class="form-row" (ngSubmit)="registerInstance()">
            <label class="field">
              <span>Клиент</span>
              <select name="instClient" [(ngModel)]="instClient">
                <option value="">— выберите —</option>
                @for (c of clients(); track c.id) {
                  <option [value]="c.code">{{ c.name }} ({{ c.code }})</option>
                }
              </select>
            </label>
            <label class="field">
              <span>Контур</span>
              <select name="instEnv" [(ngModel)]="instEnv">
                <option value="production">production</option>
                <option value="staging">staging</option>
              </select>
            </label>
            <label class="field">
              <span>Адрес</span>
              <input name="instUrl" [(ngModel)]="instUrl" placeholder="https://acme.smartup.uz">
            </label>
            <button class="btn" type="submit" [disabled]="busy()">Зарегистрировать</button>
          </form>
        </div>
      </div>
    }

    <div class="card">
      <div class="card-head">Список клиентов</div>
      <table>
        <thead>
          <tr><th>Код</th><th>Название</th><th>Профиль</th><th>Создан</th></tr>
        </thead>
        <tbody>
          @for (c of clients(); track c.id) {
            <tr>
              <td class="mono">{{ c.code }}</td>
              <td>{{ c.name }}</td>
              <td>{{ c.resourceProfile }}</td>
              <td>{{ dt(c.createdAt) }}</td>
            </tr>
          } @empty {
            <tr><td colspan="4" class="empty">Клиентов пока нет</td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    .token-card { border-color: var(--warning); }
    .token-note { font-size: 13px; color: var(--text-muted); margin-bottom: 12px; }
    .token-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .token {
      flex: 1; min-width: 280px; padding: 9px 12px;
      background: var(--bg-app); border: 1px solid var(--border-color);
      border-radius: var(--radius-md); word-break: break-all;
    }
  `]
})
export class ClientsComponent implements OnInit {
  private api = inject(CpApiService);

  clients = signal<Client[]>([]);
  issuedToken = signal('');
  copied = signal(false);
  busy = signal(false);
  error = signal('');

  code = '';
  name = '';
  profile = 'S';
  instClient = '';
  instEnv = 'production';
  instUrl = '';

  dt = dt;

  /** Создание клиента и регистрация экземпляра доступны только cp-admin. */
  canManage = computed(() => (this.api.user()?.roles ?? []).includes('cp-admin'));

  ngOnInit(): void {
    void this.load();
  }

  async load(): Promise<void> {
    try {
      this.clients.set(await this.api.clients());
      this.error.set('');
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось загрузить список клиентов'));
    }
  }

  async createClient(): Promise<void> {
    if (!this.code || !this.name) {
      this.error.set('Заполните код и название клиента');
      return;
    }
    this.busy.set(true);
    try {
      await this.api.createClient(this.code, this.name, this.profile);
      this.code = '';
      this.name = '';
      this.error.set('');
      await this.load();
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось создать клиента'));
    } finally {
      this.busy.set(false);
    }
  }

  async registerInstance(): Promise<void> {
    if (!this.instClient || !this.instUrl) {
      this.error.set('Выберите клиента и укажите адрес экземпляра');
      return;
    }
    this.busy.set(true);
    try {
      const res = await this.api.registerInstance(this.instClient, this.instEnv, this.instUrl);
      this.issuedToken.set(res.heartbeatToken);
      this.copied.set(false);
      this.instUrl = '';
      this.error.set('');
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось зарегистрировать экземпляр'));
    } finally {
      this.busy.set(false);
    }
  }

  async copyToken(token: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(token);
      this.copied.set(true);
    } catch {
      // Буфер обмена недоступен (например, страница открыта не по https) —
      // токен виден на экране, оператор скопирует руками.
      this.error.set('Буфер обмена недоступен — скопируйте токен вручную');
    }
  }
}
