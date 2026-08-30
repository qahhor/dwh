import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CpApiService, FleetItem } from '../core/cp-api.service';
import { ago, dt, errorText } from '../core/format';

@Component({
  selector: 'cp-fleet',
  standalone: true,
  template: `
    <div class="page-head">
      <div>
        <h1>Флот экземпляров</h1>
        <p>Экземпляр считается недоступным, если heartbeat молчит дольше
           {{ timeout() }} мин</p>
      </div>
      <button type="button" class="btn btn-secondary" (click)="load()" [disabled]="busy()" [attr.aria-busy]="busy()">
        {{ busy() ? 'Обновляем…' : 'Обновить' }}
      </button>
    </div>

    @if (error()) {
      <div class="alert alert-error" role="alert">{{ error() }}</div>
    }

    <div class="tiles">
      <div class="tile">
        <span class="tile-label">Всего экземпляров</span>
        <span class="tile-value">{{ total() }}</span>
      </div>
      <div class="tile" [class.tile-alarm]="problems() > 0">
        <span class="tile-label">Требуют внимания</span>
        <span class="tile-value">{{ problems() }}</span>
      </div>
      <div class="tile">
        <span class="tile-label">Обновлено</span>
        <span class="tile-value tile-value-sm">{{ refreshedAt() }}</span>
      </div>
    </div>

    <div class="card">
      <div class="table-scroll" role="region" aria-label="Таблица флота экземпляров" tabindex="0">
      <table aria-label="Флот экземпляров">
        <thead>
          <tr>
            <th>Клиент</th>
            <th>Контур</th>
            <th>Адрес</th>
            <th>Версия</th>
            <th>Схема</th>
            <th>Последний heartbeat</th>
            <th>Состояние</th>
          </tr>
        </thead>
        <tbody>
          @for (i of items(); track i.instanceId) {
            <tr>
              <td>
                <div>{{ i.clientName }}</div>
                <div class="sub mono">{{ i.clientCode }} · профиль {{ i.resourceProfile }}</div>
              </td>
              <td>{{ i.environment }}</td>
              <td class="mono">{{ i.url }}</td>
              <td class="tabular-nums">{{ i.appVersion ?? '—' }}</td>
              <td class="tabular-nums">{{ i.schemaVersion ?? '—' }}</td>
              <td>
                <div>{{ ago(i.lastHeartbeatAt) }}</div>
                @if (i.lastHeartbeatAt) {
                  <div class="sub">{{ dt(i.lastHeartbeatAt) }}</div>
                }
              </td>
              <td><span class="badge" [class]="badgeClass(i)">{{ healthText(i) }}</span></td>
            </tr>
          } @empty {
            <tr><td colspan="7" class="empty">
              Экземпляров нет. Зарегистрируйте первый на вкладке «Клиенты».
            </td></tr>
          }
        </tbody>
      </table>
      </div>
    </div>
  `,
  styles: [`
    .tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; margin-bottom: 20px; }
    .tile {
      background: var(--bg-surface); border: 1px solid var(--border-color);
      border-radius: var(--radius-lg); padding: 16px 18px;
      display: flex; flex-direction: column; gap: 6px;
    }
    .tile-alarm { border-color: var(--danger); }
    .tile-alarm .tile-value { color: var(--danger); }
    .tile-label { font-size: 12px; color: var(--text-muted); }
    .tile-value { font-size: 26px; font-weight: 600; color: var(--text-main); font-variant-numeric: tabular-nums; }
    .tile-value-sm { font-size: 15px; font-weight: 500; }
    .sub { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
  `]
})
export class FleetComponent implements OnInit, OnDestroy {
  private api = inject(CpApiService);
  private timer?: ReturnType<typeof setInterval>;

  items = signal<FleetItem[]>([]);
  total = signal(0);
  problems = signal(0);
  timeout = signal(10);
  refreshedAt = signal('—');
  busy = signal(false);
  error = signal('');

  ago = ago;
  dt = dt;

  ngOnInit(): void {
    void this.load();
    // Панель дежурного: держим её актуальной без ручного F5.
    this.timer = setInterval(() => void this.load(), 30_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  async load(): Promise<void> {
    this.busy.set(true);
    try {
      const data = await this.api.fleet();
      this.items.set(data.items);
      this.total.set(data.total);
      this.problems.set(data.problems);
      this.timeout.set(data.heartbeatTimeoutMinutes);
      this.refreshedAt.set(new Date().toLocaleTimeString('ru-RU'));
      this.error.set('');
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось получить состояние флота'));
    } finally {
      this.busy.set(false);
    }
  }

  healthText(i: FleetItem): string {
    return i.health === 'UP' ? 'работает'
      : i.health === 'DOWN' ? 'недоступен'
      : 'нет связи ни разу';
  }

  badgeClass(i: FleetItem): string {
    return i.health === 'UP' ? 'badge-success'
      : i.health === 'DOWN' ? 'badge-danger'
      : 'badge-muted';
  }
}
