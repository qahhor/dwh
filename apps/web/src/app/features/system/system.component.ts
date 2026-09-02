import { ChangeDetectionStrategy, Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../core/services/api.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';

export interface SystemInfo {
  appVersion: string;
  schemaVersion: string;
  organization: {
    code: string;
    name: string;
    resourceProfile: string;
  };
  storageProvider: string;
  components: Record<string, { status: string }>;
  backup: {
    status: string;
    completedAt: string | null;
    failureCode: string | null;
  };
}

@Component({
  selector: 'app-system',
  standalone: true,
  imports: [CommonModule, UiButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="system-page" aria-labelledby="system-title">
      <header class="view-header">
        <div>
          <p class="eyebrow">Локальная установка</p>
          <h1 id="system-title">Состояние системы</h1>
          <p class="subtitle">Диагностика этой установки без внешней телеметрии и чувствительных данных.</p>
        </div>
        <ui-button
          variant="secondary"
          icon="refresh"
          [loading]="isLoading()"
          ariaLabel="Обновить состояние системы"
          (onClick)="loadSystemInfo()"
        >Обновить</ui-button>
      </header>

      <div *ngIf="isLoading() && !systemInfo()" class="state-panel" aria-busy="true" aria-live="polite">
        <span class="spinner" aria-hidden="true"></span>
        <span>Проверяем локальные компоненты…</span>
      </div>

      <div *ngIf="loadError() && !systemInfo()" class="state-panel error-state" role="alert">
        <span class="material-symbols-outlined" aria-hidden="true">cloud_off</span>
        <div>
          <h2>Не удалось загрузить состояние системы</h2>
          <p>Проверьте доступность сервера и повторите запрос.</p>
        </div>
        <ui-button
          variant="secondary"
          ariaLabel="Повторить загрузку состояния системы"
          (onClick)="loadSystemInfo()"
        >Повторить</ui-button>
      </div>

      <ng-container *ngIf="systemInfo() as info">
        <div class="summary-grid" aria-label="Сводка установки">
          <article class="summary-card">
            <span class="summary-label">Организация</span>
            <strong>{{ info.organization.name }}</strong>
            <span class="summary-meta mono">{{ info.organization.code }}</span>
          </article>
          <article class="summary-card">
            <span class="summary-label">Профиль ресурсов</span>
            <strong>{{ info.organization.resourceProfile }}</strong>
            <span class="summary-meta">Профиль текущей установки</span>
          </article>
          <article class="summary-card">
            <span class="summary-label">Хранилище файлов</span>
            <strong class="mono">{{ info.storageProvider }}</strong>
            <span class="summary-meta">Активный провайдер</span>
          </article>
          <article class="summary-card">
            <span class="summary-label">Версии</span>
            <strong class="mono">{{ info.appVersion }}</strong>
            <span class="summary-meta mono">Схема БД: {{ info.schemaVersion }}</span>
          </article>
        </div>

        <div class="content-grid">
          <article class="panel">
            <div class="panel-heading">
              <div>
                <h2>Компоненты</h2>
                <p>Текущее состояние обязательных и опциональных зависимостей.</p>
              </div>
            </div>
            <dl class="component-list" aria-label="Состояние компонентов">
              <div *ngFor="let component of componentEntries(info)" class="component-row">
                <dt>{{ componentLabel(component.name) }}</dt>
                <dd>
                  <span class="status-pill" [attr.data-status]="component.status" [class]="'status-pill status-' + statusClass(component.status)">
                    <span class="status-dot" aria-hidden="true"></span>
                    {{ statusLabel(component.status) }}
                  </span>
                </dd>
              </div>
            </dl>
          </article>

          <article class="panel" data-testid="backup-status">
            <div class="panel-heading">
              <div>
                <h2>Резервное копирование</h2>
                <p>Последний результат локального backup-sidecar.</p>
              </div>
              <span class="material-symbols-outlined panel-icon" aria-hidden="true">database</span>
            </div>
            <div class="backup-result" role="status">
              <span class="backup-icon material-symbols-outlined" aria-hidden="true">{{ backupIcon(info.backup.status) }}</span>
              <div>
                <strong>{{ backupStatusLabel(info.backup.status) }}</strong>
                <p *ngIf="info.backup.completedAt">{{ info.backup.completedAt | date:'dd.MM.yyyy, HH:mm:ss' }}</p>
                <p *ngIf="info.backup.status === 'FAILED' && info.backup.failureCode" class="failure-code mono">
                  Код ошибки: {{ info.backup.failureCode }}
                </p>
              </div>
            </div>
            <div class="cli-note">
              <span class="material-symbols-outlined" aria-hidden="true">terminal</span>
              <p><strong>Операции доступны только администраторам хоста:</strong> резервное копирование, восстановление и обновление выполняются через CLI.</p>
            </div>
          </article>
        </div>
      </ng-container>
    </section>
  `,
  styles: [`
    :host { display: block; }
    .system-page { display: flex; flex-direction: column; gap: 20px; max-width: 1400px; margin: 0 auto; }
    .view-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
    .eyebrow { margin: 0 0 4px; color: var(--primary); font-size: 12px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
    h1 { margin: 0; color: var(--text-main); font-size: 26px; line-height: 1.2; }
    .subtitle { max-width: 720px; margin: 8px 0 0; color: var(--text-muted); font-size: 13px; line-height: 1.5; }
    .summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
    .summary-card, .panel, .state-panel { background: var(--bg-surface); border: 1px solid var(--border-color); border-radius: var(--radius-lg); }
    .summary-card { display: flex; flex-direction: column; min-width: 0; gap: 6px; padding: 16px; }
    .summary-card strong { overflow: hidden; color: var(--text-main); font-size: 17px; text-overflow: ellipsis; white-space: nowrap; }
    .summary-label { color: var(--text-muted); font-size: 12px; }
    .summary-meta { color: var(--text-muted); font-size: 11px; }
    .mono { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
    .content-grid { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(320px, .75fr); gap: 16px; }
    .panel { padding: 18px; }
    .panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
    .panel-heading h2, .state-panel h2 { margin: 0; color: var(--text-main); font-size: 16px; }
    .panel-heading p, .state-panel p { margin: 4px 0 0; color: var(--text-muted); font-size: 12px; line-height: 1.45; }
    .panel-icon { color: var(--primary); }
    .component-list { display: flex; flex-direction: column; margin: 0; }
    .component-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 0; border-top: 1px solid var(--border-color); }
    .component-row dt { color: var(--text-main); font-size: 13px; font-weight: 500; }
    .component-row dd { margin: 0; }
    .status-pill { display: inline-flex; align-items: center; gap: 7px; padding: 4px 9px; border-radius: 999px; font-size: 11px; font-weight: 700; }
    .status-dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
    .status-up { color: var(--success); background: var(--success-bg); }
    .status-degraded { color: var(--warning); background: var(--warning-bg); }
    .status-down { color: var(--danger); background: var(--danger-bg); }
    .status-disabled, .status-unknown { color: var(--text-muted); background: var(--bg-hover); }
    .backup-result { display: flex; align-items: flex-start; gap: 12px; padding: 14px; border: 1px solid var(--border-color); border-radius: var(--radius-md); }
    .backup-icon { color: var(--primary); }
    .backup-result strong { color: var(--text-main); font-size: 13px; }
    .backup-result p { margin: 4px 0 0; color: var(--text-muted); font-size: 12px; }
    .failure-code { color: var(--danger) !important; }
    .cli-note { display: flex; align-items: flex-start; gap: 10px; margin-top: 12px; padding: 12px; border-radius: var(--radius-md); background: var(--bg-hover); color: var(--text-muted); }
    .cli-note .material-symbols-outlined { color: var(--primary); font-size: 19px; }
    .cli-note p { margin: 0; font-size: 12px; line-height: 1.5; }
    .state-panel { display: flex; align-items: center; justify-content: center; gap: 12px; min-height: 160px; padding: 24px; color: var(--text-muted); }
    .error-state { justify-content: flex-start; }
    .error-state > .material-symbols-outlined { color: var(--danger); font-size: 30px; }
    .error-state ui-button { margin-left: auto; }
    .spinner { width: 20px; height: 20px; border: 2px solid var(--border-color); border-top-color: var(--primary); border-radius: 50%; animation: spin .7s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    @media (max-width: 960px) { .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .content-grid { grid-template-columns: 1fr; } }
    @media (max-width: 600px) { .view-header { align-items: stretch; flex-direction: column; } .summary-grid { grid-template-columns: 1fr; } .panel { padding: 14px; } .error-state { align-items: flex-start; flex-wrap: wrap; } .error-state ui-button { margin-left: 42px; } }
    @media (prefers-reduced-motion: reduce) { .spinner { animation-duration: 1.5s; } }
  `]
})
export class SystemComponent implements OnInit {
  readonly systemInfo = signal<SystemInfo | null>(null);
  readonly isLoading = signal(true);
  readonly loadError = signal(false);

  constructor(private readonly api: ApiService) {}

  ngOnInit(): void {
    this.loadSystemInfo();
  }

  loadSystemInfo(): void {
    this.isLoading.set(true);
    this.loadError.set(false);
    this.api.get<SystemInfo>('/system/info').subscribe({
      next: info => {
        this.systemInfo.set(info);
        this.isLoading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.isLoading.set(false);
      }
    });
  }

  componentEntries(info: SystemInfo): Array<{ name: string; status: string }> {
    const preferredOrder = ['database', 'storage', 'typesense'];
    return Object.entries(info.components)
      .map(([name, component]) => ({ name, status: component.status || 'UNKNOWN' }))
      .sort((left, right) => {
        const leftIndex = preferredOrder.indexOf(left.name);
        const rightIndex = preferredOrder.indexOf(right.name);
        return (leftIndex < 0 ? preferredOrder.length : leftIndex)
          - (rightIndex < 0 ? preferredOrder.length : rightIndex)
          || left.name.localeCompare(right.name);
      });
  }

  componentLabel(name: string): string {
    return ({ database: 'PostgreSQL', storage: 'Файловое хранилище', typesense: 'Typesense' } as Record<string, string>)[name] ?? name;
  }

  statusLabel(status: string): string {
    return ({ UP: 'Работает', DEGRADED: 'Есть проблемы', DOWN: 'Недоступен', DISABLED: 'Отключён', UNKNOWN: 'Неизвестно' } as Record<string, string>)[status] ?? status;
  }

  statusClass(status: string): string {
    return ['UP', 'DEGRADED', 'DOWN', 'DISABLED'].includes(status) ? status.toLowerCase() : 'unknown';
  }

  backupStatusLabel(status: string): string {
    return ({
      NEVER: 'Резервная копия ещё не создавалась',
      FAILED: 'Последняя резервная копия завершилась ошибкой',
      SUCCESS: 'Последняя резервная копия создана'
    } as Record<string, string>)[status] ?? 'Состояние резервной копии неизвестно';
  }

  backupIcon(status: string): string {
    return ({ NEVER: 'schedule', FAILED: 'error', SUCCESS: 'check_circle' } as Record<string, string>)[status] ?? 'help';
  }
}
