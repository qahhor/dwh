import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiBadgeComponent } from '../../shared/ui/ui-badge.component';

export interface AnalyticsSummary {
  totalTasks: number;
  activeTasks: number;
  completedTasks: number;
  overdueTasks: number;
  completionRatePercent: number;
  createdLast7d: number;
  completedLast7d: number;
  activeProjectsCount: number;
  activeUsersCount: number;
}

export interface TrendDataPoint {
  date: string;
  createdCount: number;
  completedCount: number;
}

export interface ProjectDistribution {
  projectId: number;
  projectName: string;
  totalTasks: number;
  activeTasks: number;
  completedTasks: number;
  progressPercent: number;
}

export interface UserWorkload {
  userId: number;
  userName: string;
  userLogin: string;
  assignedTasks: number;
  completedTasks: number;
}

@Component({
  selector: 'app-analytics',
  standalone: true,
  imports: [CommonModule, FormsModule, UiBadgeComponent],
  template: `
    <div class="analytics-container">
      <!-- Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">Аналитика и дашборды</h1>
          <span class="count-badge">PostgreSQL 18 Analytics</span>
        </div>

        <div class="header-right">
          <!-- Time Range Selector -->
          <div class="status-tabs" role="group" aria-label="Период аналитики">
            <button
              type="button"
              class="status-tab"
              [class.active]="selectedRange === '7d'"
              (click)="setRange('7d')"
            >
              7 дней
            </button>
            <button
              type="button"
              class="status-tab"
              [class.active]="selectedRange === '30d'"
              (click)="setRange('30d')"
            >
              30 дней
            </button>
            <button
              type="button"
              class="status-tab"
              [class.active]="selectedRange === '90d'"
              (click)="setRange('90d')"
            >
              90 дней
            </button>
          </div>

          <button
            type="button"
            class="btn btn-secondary"
            (click)="exportReport()"
            title="Экспорт списка задач в Excel"
          >
            <span class="material-symbols-outlined" aria-hidden="true">download</span>
            <span>Экспорт</span>
          </button>

          <button
            type="button"
            class="btn btn-secondary"
            (click)="loadAll()"
            [disabled]="loading()"
          >
            <span class="material-symbols-outlined" [class.spin]="loading()" aria-hidden="true">refresh</span>
            <span>Обновить</span>
          </button>
        </div>
      </div>

      <!-- Error Alert -->
      <div *ngIf="error()" class="alert alert-error" role="alert">
        <span class="material-symbols-outlined">error</span>
        <span>{{ error() }}</span>
      </div>

      <!-- KPI Metrics Row -->
      <div class="tiles">
        <!-- 1. Всего задач -->
        <div class="tile">
          <div class="tile-header">
            <span class="tile-label">Всего задач</span>
            <span class="material-symbols-outlined tile-ico" style="color: var(--primary);">task_alt</span>
          </div>
          <div class="tile-value">{{ summary()?.totalTasks || 0 }}</div>
          <div class="tile-meta">
            <span class="text-success" style="font-weight: 600;">{{ summary()?.activeTasks || 0 }} активных</span>
            <span class="meta-dot">·</span>
            <span class="text-muted">{{ summary()?.completedTasks || 0 }} завершено</span>
          </div>
        </div>

        <!-- 2. Эффективность закрытия -->
        <div class="tile">
          <div class="tile-header">
            <span class="tile-label">Эффективность закрытия</span>
            <span class="material-symbols-outlined tile-ico" style="color: var(--success);">trending_up</span>
          </div>
          <div class="tile-value">{{ summary()?.completionRatePercent || 0 }}%</div>
          <div class="tile-meta">
            <span class="text-success" style="font-weight: 600;">+{{ summary()?.completedLast7d || 0 }} за 7 дней</span>
            <span class="meta-dot">·</span>
            <span class="text-muted">{{ summary()?.createdLast7d || 0 }} создано</span>
          </div>
        </div>

        <!-- 3. Просроченные задачи -->
        <div class="tile" [class.tile-alarm]="(summary()?.overdueTasks || 0) > 0">
          <div class="tile-header">
            <span class="tile-label">Просрочено дедлайнов</span>
            <span class="material-symbols-outlined tile-ico" [style.color]="(summary()?.overdueTasks || 0) > 0 ? 'var(--danger)' : 'var(--text-light)'">
              {{ (summary()?.overdueTasks || 0) > 0 ? 'warning' : 'verified' }}
            </span>
          </div>
          <div class="tile-value" [style.color]="(summary()?.overdueTasks || 0) > 0 ? 'var(--danger)' : 'var(--text-main)'">
            {{ summary()?.overdueTasks || 0 }}
          </div>
          <div class="tile-meta">
            <span *ngIf="(summary()?.overdueTasks || 0) > 0" class="text-danger" style="font-weight: 600;">Требуют внимания</span>
            <span *ngIf="(summary()?.overdueTasks || 0) === 0" class="text-success" style="font-weight: 600;">Все задачи в графике</span>
          </div>
        </div>

        <!-- 4. Активность проектов -->
        <div class="tile">
          <div class="tile-header">
            <span class="tile-label">Проекты и ресурсы</span>
            <span class="material-symbols-outlined tile-ico" style="color: var(--warning);">folder_special</span>
          </div>
          <div class="tile-value">{{ summary()?.activeProjectsCount || 0 }}</div>
          <div class="tile-meta">
            <span class="text-muted">{{ summary()?.activeUsersCount || 0 }} активных пользователей</span>
          </div>
        </div>
      </div>

      <!-- Main Analytics Grid -->
      <div class="analytics-grid">
        <!-- Trend Chart Card -->
        <div class="analytics-card chart-card">
          <div class="card-header-row">
            <div>
              <h2 class="card-title">Динамика потока задач</h2>
              <p class="card-subtitle">Созданные vs Завершенные задачи по дням ({{ selectedRange }})</p>
            </div>
            <div class="chart-legend">
              <div class="legend-item">
                <span class="legend-dot" style="background-color: var(--primary);"></span>
                <span>Создано</span>
              </div>
              <div class="legend-item">
                <span class="legend-dot" style="background-color: var(--success);"></span>
                <span>Завершено</span>
              </div>
            </div>
          </div>

          <!-- SVG Area / Line Chart -->
          <div class="svg-chart-container" *ngIf="trends().length > 0">
            <svg class="trend-svg" viewBox="0 0 700 240" preserveAspectRatio="none">
              <defs>
                <linearGradient id="createdGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stop-color="var(--primary)" stop-opacity="0.25"/>
                  <stop offset="100%" stop-color="var(--primary)" stop-opacity="0.0"/>
                </linearGradient>
                <linearGradient id="completedGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stop-color="var(--success)" stop-opacity="0.25"/>
                  <stop offset="100%" stop-color="var(--success)" stop-opacity="0.0"/>
                </linearGradient>
              </defs>

              <!-- Gridlines -->
              <line x1="40" y1="40" x2="680" y2="40" stroke="var(--border-subtle)" stroke-dasharray="3,3"/>
              <line x1="40" y1="90" x2="680" y2="90" stroke="var(--border-subtle)" stroke-dasharray="3,3"/>
              <line x1="40" y1="140" x2="680" y2="140" stroke="var(--border-subtle)" stroke-dasharray="3,3"/>
              <line x1="40" y1="190" x2="680" y2="190" stroke="var(--border-subtle)"/>

              <!-- Area Fills -->
              <path [attr.d]="createdAreaPath()" fill="url(#createdGrad)"/>
              <path [attr.d]="completedAreaPath()" fill="url(#completedGrad)"/>

              <!-- Line Strokes -->
              <path [attr.d]="createdLinePath()" fill="none" stroke="var(--primary)" stroke-width="2.5" stroke-linecap="round"/>
              <path [attr.d]="completedLinePath()" fill="none" stroke="var(--success)" stroke-width="2.5" stroke-linecap="round"/>

              <!-- Data Dots -->
              <g *ngFor="let pt of chartPoints(); let i = index">
                <circle [attr.cx]="pt.x" [attr.cy]="pt.yCreated" r="3.5" fill="var(--bg-surface)" stroke="var(--primary)" stroke-width="2"/>
                <circle [attr.cx]="pt.x" [attr.cy]="pt.yCompleted" r="3.5" fill="var(--bg-surface)" stroke="var(--success)" stroke-width="2"/>
                <!-- X axis date labels for some points -->
                <text
                  *ngIf="shouldShowDateLabel(i, chartPoints().length)"
                  [attr.x]="pt.x"
                  y="215"
                  font-size="10"
                  text-anchor="middle"
                  fill="var(--text-muted)"
                  font-family="inherit"
                >
                  {{ pt.label }}
                </text>
              </g>
            </svg>
          </div>

          <div *ngIf="trends().length === 0 && !loading()" class="empty-chart">
            <span class="material-symbols-outlined" style="font-size: 32px; color: var(--text-light);">show_chart</span>
            <p>Нет данных за выбранный период</p>
          </div>
        </div>

        <!-- Project Distribution Breakdown -->
        <div class="analytics-card">
          <div class="card-header-row">
            <div>
              <h2 class="card-title">Прогресс по проектам</h2>
              <p class="card-subtitle">Статусы и процент выполнения</p>
            </div>
          </div>

          <div class="project-list" *ngIf="projects().length > 0">
            <div *ngFor="let p of projects()" class="project-item">
              <div class="project-info-row">
                <div class="project-name-group">
                  <span class="material-symbols-outlined" style="font-size: 18px; color: var(--primary);">folder</span>
                  <span class="project-name">{{ p.projectName }}</span>
                </div>
                <div class="project-stats">
                  <span class="project-pct">{{ p.progressPercent }}%</span>
                  <span class="project-tasks-count">({{ p.completedTasks }}/{{ p.totalTasks }})</span>
                </div>
              </div>
              <div class="progress-bar-bg">
                <div class="progress-bar-fill" [style.width.%]="p.progressPercent"></div>
              </div>
            </div>
          </div>

          <div *ngIf="projects().length === 0 && !loading()" class="empty-chart">
            <span class="material-symbols-outlined" style="font-size: 32px; color: var(--text-light);">folder_open</span>
            <p>Активные проекты не найдены</p>
          </div>
        </div>
      </div>

      <!-- Bottom Grid: Team Workload Table -->
      <div class="table-card" style="margin-top: 20px;">
        <div class="card-header-row" style="padding: 16px 20px; border-bottom: 1px solid var(--border-color);">
          <div>
            <h2 class="card-title">Утилизация и загрузка команды</h2>
            <p class="card-subtitle">Распределение активных и выполненных задач по исполнителям</p>
          </div>
        </div>

        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th style="width: 240px;">Сотрудник</th>
                <th>Логин</th>
                <th>Назначено задач</th>
                <th>Завершено</th>
                <th>Эффективность</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let u of workload()">
                <td>
                  <div class="user-cell">
                    <div class="user-avatar-sm">{{ u.userName.charAt(0).toUpperCase() }}</div>
                    <span class="user-name-text">{{ u.userName }}</span>
                  </div>
                </td>
                <td>
                  <span class="mono badge badge-neutral">{{ u.userLogin }}</span>
                </td>
                <td style="font-weight: 600;">{{ u.assignedTasks }}</td>
                <td class="text-success" style="font-weight: 600;">{{ u.completedTasks }}</td>
                <td>
                  <ui-badge [variant]="u.assignedTasks > 0 && (u.completedTasks / u.assignedTasks) >= 0.7 ? 'success' : 'neutral'">
                    {{ u.assignedTasks > 0 ? ((u.completedTasks / u.assignedTasks) * 100 | number:'1.0-0') : 0 }}%
                  </ui-badge>
                </td>
              </tr>
              <tr *ngIf="workload().length === 0 && !loading()">
                <td colspan="5" class="empty">
                  <span>Данные по загрузке сотрудников отсутствуют.</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .analytics-container {
      display: flex;
      flex-direction: column;
      gap: 0;
    }

    .view-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 20px;
      gap: 16px;
      flex-wrap: wrap;
    }

    .header-left {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .view-title {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-main);
      letter-spacing: -0.3px;
    }

    .count-badge {
      font-size: 12px;
      font-weight: 600;
      padding: 2px 8px;
      border-radius: 9999px;
      background-color: var(--bg-hover);
      color: var(--text-muted);
      border: 1px solid var(--border-color);
    }

    .header-right {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .status-tabs {
      display: inline-flex;
      align-items: center;
      background-color: var(--bg-hover);
      border-radius: var(--radius-sm);
      padding: 2px;
      gap: 2px;
      border: 1px solid var(--border-color);
    }

    .status-tab {
      height: 28px;
      padding: 0 10px;
      font-size: 12px;
      font-weight: 500;
      color: var(--text-muted);
      background: transparent;
      border: none;
      border-radius: var(--radius-xs);
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 5px;
      transition: all 0.12s ease;
      user-select: none;
    }

    .status-tab:hover:not(.active) {
      color: var(--text-main);
    }

    .status-tab.active {
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-weight: 600;
      box-shadow: var(--shadow-sm);
    }

    .btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      padding: 6px 14px;
      height: 34px;
      border-radius: var(--radius-sm);
      border: 1px solid transparent;
      background-color: var(--primary);
      color: #ffffff;
      font-size: 13px;
      font-weight: 500;
      font-family: inherit;
      cursor: pointer;
      transition: all 0.15s ease;
      white-space: nowrap;
      user-select: none;
    }

    .btn:hover:not(:disabled) {
      background-color: var(--primary-hover);
    }

    .btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .btn-secondary {
      background-color: var(--bg-surface);
      border-color: var(--border-color);
      color: var(--text-main);
    }

    .btn-secondary:hover:not(:disabled) {
      background-color: var(--bg-hover);
      border-color: var(--border-color);
    }

    .tiles {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 16px;
      margin-bottom: 20px;
    }

    @media (max-width: 1200px) {
      .tiles {
        grid-template-columns: repeat(2, 1fr);
      }
    }

    @media (max-width: 640px) {
      .tiles {
        grid-template-columns: 1fr;
      }
    }

    .tile {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      padding: 16px 18px;
      display: flex;
      flex-direction: column;
      gap: 6px;
      box-shadow: var(--shadow-sm);
      transition: all 0.15s ease;
    }

    .tile:hover {
      box-shadow: var(--shadow-md);
    }

    .tile-label {
      font-size: 12px;
      font-weight: 500;
      color: var(--text-muted);
    }

    .tile-value {
      font-size: 24px;
      font-weight: 700;
      color: var(--text-main);
      line-height: 1.1;
    }

    .tile-alarm {
      border-color: rgba(220, 38, 38, 0.4);
      background: linear-gradient(to bottom right, var(--bg-surface), var(--danger-bg));
    }

    .spin {
      animation: spin 1s linear infinite;
    }
    @keyframes spin {
      100% { transform: rotate(360deg); }
    }

    .tile-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .tile-ico {
      font-size: 20px;
    }
    .tile-meta {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 11px;
      margin-top: 4px;
    }
    .meta-dot {
      color: var(--text-light);
    }
    .text-success { color: var(--success); }
    .text-danger { color: var(--danger); }
    .text-muted { color: var(--text-muted); }

    .analytics-grid {
      display: grid;
      grid-template-columns: 1.5fr 1fr;
      gap: 20px;
    }

    @media (max-width: 1024px) {
      .analytics-grid {
        grid-template-columns: 1fr;
      }
    }

    .analytics-card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      padding: 18px 22px;
      box-shadow: var(--shadow-sm);
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .card-header-row {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
    }

    .card-title {
      font-size: 15px;
      font-weight: 700;
      color: var(--text-main);
    }

    .card-subtitle {
      font-size: 12px;
      color: var(--text-muted);
      margin-top: 2px;
    }

    .chart-legend {
      display: flex;
      align-items: center;
      gap: 14px;
    }
    .legend-item {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 12px;
      font-weight: 500;
      color: var(--text-main);
    }
    .legend-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
    }

    .svg-chart-container {
      width: 100%;
      height: 240px;
      position: relative;
    }

    .trend-svg {
      width: 100%;
      height: 100%;
      overflow: visible;
    }

    .empty-chart {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 50px 20px;
      gap: 8px;
      color: var(--text-muted);
      font-size: 13px;
    }

    /* Projects list */
    .project-list {
      display: flex;
      flex-direction: column;
      gap: 14px;
      max-height: 250px;
      overflow-y: auto;
    }

    .project-item {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .project-info-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
    }

    .project-name-group {
      display: flex;
      align-items: center;
      gap: 6px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .project-name {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-main);
    }

    .project-stats {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-shrink: 0;
    }

    .project-pct {
      font-size: 12px;
      font-weight: 700;
      color: var(--text-main);
    }

    .project-tasks-count {
      font-size: 11px;
      color: var(--text-muted);
    }

    .progress-bar-bg {
      width: 100%;
      height: 6px;
      background-color: var(--bg-hover);
      border-radius: 9999px;
      overflow: hidden;
    }

    .progress-bar-fill {
      height: 100%;
      background-color: var(--primary);
      border-radius: 9999px;
      transition: width 0.3s ease;
    }

    .user-cell {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .user-avatar-sm {
      width: 26px;
      height: 26px;
      border-radius: 50%;
      background-color: var(--primary-subtle);
      color: var(--primary-text);
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 11px;
      font-weight: 700;
    }

    .user-name-text {
      font-weight: 600;
      color: var(--text-main);
    }
  `]
})
export class AnalyticsComponent implements OnInit {
  private http = inject(HttpClient);

  summary = signal<AnalyticsSummary | null>(null);
  trends = signal<TrendDataPoint[]>([]);
  projects = signal<ProjectDistribution[]>([]);
  workload = signal<UserWorkload[]>([]);

  loading = signal(false);
  error = signal('');
  selectedRange = '7d';

  chartPoints = computed(() => {
    const list = this.trends();
    if (list.length === 0) return [];

    let maxVal = 1;
    for (const d of list) {
      if (d.createdCount > maxVal) maxVal = d.createdCount;
      if (d.completedCount > maxVal) maxVal = d.completedCount;
    }

    const width = 640;
    const startX = 40;
    const bottomY = 190;
    const topY = 40;
    const height = bottomY - topY;

    const step = list.length > 1 ? width / (list.length - 1) : width;

    return list.map((d, i) => {
      const x = startX + i * step;
      const yCreated = bottomY - (d.createdCount / maxVal) * height;
      const yCompleted = bottomY - (d.completedCount / maxVal) * height;
      const label = d.date.substring(5); // MM-DD
      return { x, yCreated, yCompleted, label, created: d.createdCount, completed: d.completedCount };
    });
  });

  createdLinePath = computed(() => {
    const pts = this.chartPoints();
    if (pts.length === 0) return '';
    return pts.map((p, i) => (i === 0 ? `M ${p.x} ${p.yCreated}` : `L ${p.x} ${p.yCreated}`)).join(' ');
  });

  completedLinePath = computed(() => {
    const pts = this.chartPoints();
    if (pts.length === 0) return '';
    return pts.map((p, i) => (i === 0 ? `M ${p.x} ${p.yCompleted}` : `L ${p.x} ${p.yCompleted}`)).join(' ');
  });

  createdAreaPath = computed(() => {
    const pts = this.chartPoints();
    if (pts.length === 0) return '';
    const line = this.createdLinePath();
    const last = pts[pts.length - 1];
    const first = pts[0];
    return `${line} L ${last.x} 190 L ${first.x} 190 Z`;
  });

  completedAreaPath = computed(() => {
    const pts = this.chartPoints();
    if (pts.length === 0) return '';
    const line = this.completedLinePath();
    const last = pts[pts.length - 1];
    const first = pts[0];
    return `${line} L ${last.x} 190 L ${first.x} 190 Z`;
  });

  ngOnInit(): void {
    void this.loadAll();
  }

  setRange(range: string): void {
    this.selectedRange = range;
    void this.loadTrends();
  }

  exportReport(): void {
    window.open('/api/v1/reports/tasks/export?format=xlsx', '_blank');
  }

  shouldShowDateLabel(index: number, total: number): boolean {
    if (total <= 7) return true;
    if (total <= 14) return index % 2 === 0;
    return index % Math.ceil(total / 6) === 0 || index === total - 1;
  }

  async loadAll(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      await Promise.all([
        this.loadSummary(),
        this.loadTrends(),
        this.loadProjects(),
        this.loadWorkload()
      ]);
    } catch (e: any) {
      this.error.set(e?.error?.detail || 'Не удалось загрузить данные аналитики');
    } finally {
      this.loading.set(false);
    }
  }

  private async loadSummary(): Promise<void> {
    const data = await this.http.get<AnalyticsSummary>('/api/v1/analytics/summary').toPromise();
    if (data) this.summary.set(data);
  }

  private async loadTrends(): Promise<void> {
    const data = await this.http.get<TrendDataPoint[]>(`/api/v1/analytics/trends?range=${this.selectedRange}`).toPromise();
    if (data) this.trends.set(data);
  }

  private async loadProjects(): Promise<void> {
    const data = await this.http.get<ProjectDistribution[]>('/api/v1/analytics/projects').toPromise();
    if (data) this.projects.set(data);
  }

  private async loadWorkload(): Promise<void> {
    const data = await this.http.get<UserWorkload[]>('/api/v1/analytics/workload').toPromise();
    if (data) this.workload.set(data);
  }
}
