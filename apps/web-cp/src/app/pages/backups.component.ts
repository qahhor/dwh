import { Component, OnInit, inject, signal } from '@angular/core';
import { BackupCheck, CpApiService } from '../core/cp-api.service';
import { dt, errorText } from '../core/format';

@Component({
  selector: 'cp-backups',
  standalone: true,
  template: `
    <div class="page-head">
      <div>
        <h1>Проверки бэкапов</h1>
        <p>Экземпляры сами отчитываются о восстановлении: бэкап без успешной
           проверки восстановлением бэкапом не считается.</p>
      </div>
      <button class="btn btn-secondary" (click)="load()" [disabled]="busy()">Обновить</button>
    </div>

    @if (error()) { <div class="alert alert-error">{{ error() }}</div> }

    <div class="card">
      <table>
        <thead>
          <tr>
            <th>Клиент</th><th>Результат</th><th>Длительность</th>
            <th>Детали</th><th>Проверено</th>
          </tr>
        </thead>
        <tbody>
          @for (b of checks(); track b.id) {
            <tr>
              <td class="mono">{{ b.clientCode }}</td>
              <td>
                <span class="badge" [class]="b.success ? 'badge-success' : 'badge-danger'">
                  {{ b.success ? 'успешно' : 'провалено' }}
                </span>
              </td>
              <td class="tabular-nums">{{ b.durationSec }} с</td>
              <td>{{ b.details || '—' }}</td>
              <td>{{ dt(b.verifiedAt) }}</td>
            </tr>
          } @empty {
            <tr><td colspan="5" class="empty">Отчётов о проверках ещё не поступало</td></tr>
          }
        </tbody>
      </table>
    </div>
  `
})
export class BackupsComponent implements OnInit {
  private api = inject(CpApiService);

  checks = signal<BackupCheck[]>([]);
  busy = signal(false);
  error = signal('');

  dt = dt;

  ngOnInit(): void {
    void this.load();
  }

  async load(): Promise<void> {
    this.busy.set(true);
    try {
      this.checks.set(await this.api.backupChecks());
      this.error.set('');
    } catch (e) {
      this.error.set(errorText(e, 'Не удалось загрузить журнал проверок'));
    } finally {
      this.busy.set(false);
    }
  }
}
