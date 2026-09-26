import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, Subscription, forkJoin } from 'rxjs';
import { SMTButtonComponent } from '../../shared/ui-kit/components/button';
import { TranslatePipe, I18nService } from '../../core/services/i18n.service';

import {
  AnalyticsSummary,
  TrendDataPoint,
  ProjectDistribution,
  UserWorkload
} from './analytics.models';

import { AnalyticsMetricsTilesComponent } from './components/analytics-metrics-tiles.component';
import { AnalyticsTrendChartComponent } from './components/analytics-trend-chart.component';
import { AnalyticsProjectsCardComponent } from './components/analytics-projects-card.component';
import { AnalyticsWorkloadTableComponent } from './components/analytics-workload-table.component';
import { SMTAlertComponent } from '../../shared/ui-kit/components/alert';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '../../shared/ui-kit/components/forms/radio-group';

export * from './analytics.models';

@Component({
  selector: 'app-analytics',
  standalone: true,
  imports: [
    SMTRadioGroupComponent, SMTAlertComponent, CommonModule,
    TranslatePipe,
    SMTButtonComponent,
    AnalyticsMetricsTilesComponent,
    AnalyticsTrendChartComponent,
    AnalyticsProjectsCardComponent,
    AnalyticsWorkloadTableComponent
  ],
  template: `
    <div class="analytics-container">
      <!-- Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'analytics.analitika_i_dashbordy' | t }}</h1>
          <span class="count-badge">PostgreSQL 18 Analytics</span>
        </div>

        <div class="header-right">
          <!-- Time Range Selector -->
          <smt-radio-group
            smtAppearance="segmented"
            class="range-picker"
            [options]="rangeOptions()"
            [value]="selectedRange"
            [smtAriaLabel]="'analytics.period_analitiki' | t"
            (valueChange)="setRange($event ?? selectedRange)" />

          <button smt-button type="button"
            smtVariant="secondary"
            smtSize="sm"
            smtIcon="download"
            (click)="exportReport()"
            [title]="'analytics.eksport_spiska_zadach_v_excel' | t"
          >
            {{ 'analytics.eksport' | t }}
          </button>

          <button smt-button type="button"
            smtVariant="secondary"
            smtSize="sm"
            smtIcon="refresh"
            [smtLoading]="loading()"
            [title]="'common.refresh' | t"
            (click)="loadAll()"
          >
            {{ 'common.refresh' | t }}
          </button>
        </div>
      </div>

      <!-- Error Alert -->
      <smt-alert smtTone="danger" *ngIf="error()">
        <span>{{ error() }}</span>
        <button type="button" class="alert-retry" data-testid="analytics-retry" (click)="setRange(selectedRange)">{{ 'common.retry' | t }}</button>
      </smt-alert>

      <!-- KPI Metrics Row -->
      <app-analytics-metrics-tiles
        [summary]="summary()"
      ></app-analytics-metrics-tiles>

      <!-- Main Analytics Grid: Trend Chart & Project Distribution -->
      <div class="analytics-grid">
        <app-analytics-trend-chart
          [trends]="trends()"
          [loading]="loading()"
          [displayedRange]="displayedRange"
          [error]="error()"
        ></app-analytics-trend-chart>

        <app-analytics-projects-card
          [projects]="projects()"
          [loading]="loading()"
          [error]="error()"
          (projectClick)="navigateToProject($event)"
        ></app-analytics-projects-card>
      </div>

      <!-- Bottom Grid: Team Workload Table -->
      <app-analytics-workload-table
        [workload]="workload()"
        [loading]="loading()"
        [error]="error()"
      ></app-analytics-workload-table>
    </div>
  `,
  styles: [`
    .alert-retry { margin-left: 8px; padding: 0; border: none; background: transparent; color: inherit; font: inherit; font-weight: 600; text-decoration: underline; cursor: pointer; }
    .alert-retry:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
    :host {
      display: block;
      min-width: 0;
    }

    .analytics-container {
      display: flex;
      flex-direction: column;
      min-width: 0;
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
      flex-wrap: wrap;
      min-width: 0;
      max-width: 100%;
      gap: 10px;
    }

    .view-title {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-main);
      letter-spacing: -0.3px;
      overflow-wrap: anywhere;
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


    .analytics-grid {
      display: grid;
      grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
      gap: 20px;
    }

    @media (max-width: 1024px) {
      .analytics-grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }

    @media (max-width: 640px) {
      .view-header {
        align-items: flex-start;
        flex-direction: column;
      }
      .header-right {
        width: 100%;
        flex-wrap: wrap;
      }
      .range-picker {
        max-width: 100%;
        overflow-x: auto;
      }
    }
  `]
})
export class AnalyticsComponent implements OnInit, OnDestroy {
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);

  private readonly uiI18n = inject(I18nService);
  private http = inject(HttpClient);
  private router = inject(Router);

  summary = signal<AnalyticsSummary | null>(null);
  trends = signal<TrendDataPoint[]>([]);
  projects = signal<ProjectDistribution[]>([]);
  workload = signal<UserWorkload[]>([]);

  loading = signal(false);
  error = signal('');

  private activeRequest?: Subscription;
  private refreshRequired = true;
  selectedRange = '7d';
  displayedRange = '7d';

  private readonly rangeMemo = optionsMemo<SMTRadioOption<string>[]>();

  ngOnInit(): void {
    this.loadAll();
  }

  ngOnDestroy(): void {
    this.activeRequest?.unsubscribe();
  }

  setRange(range: string): void {
    this.selectedRange = range;
    if (this.refreshRequired) this.loadAll();
    else this.loadTrends();
  }

  navigateToProject(projectId: number): void {
    this.router.navigate(['/tasks'], { queryParams: { project: projectId } });
  }

  exportReport(format: 'xlsx' | 'csv' = 'xlsx'): void {
    window.open(`/api/v1/reports/tasks/export?format=${format}`, '_blank');
  }

  loadAll(): void {
    const range = this.selectedRange;
    // A failed or superseded refresh must be retried as a whole snapshot.
    this.refreshRequired = true;
    this.loadRequest(forkJoin({
      summary: this.http.get<AnalyticsSummary>('/api/v1/analytics/summary'),
      trends: this.http.get<TrendDataPoint[]>(`/api/v1/analytics/trends?range=${range}`),
      projects: this.http.get<ProjectDistribution[]>('/api/v1/analytics/projects'),
      workload: this.http.get<UserWorkload[]>('/api/v1/analytics/workload')
    }), data => {
      this.summary.set(data.summary);
      this.trends.set(data.trends);
      this.projects.set(data.projects);
      this.workload.set(data.workload);
      this.displayedRange = range;
      this.refreshRequired = false;
    });
  }

  /** The periods as one segmented bar. */
  rangeOptions(): SMTRadioOption<string>[] {
    return this.rangeMemo([this.optionText.currentLang()], () => [
      { value: '7d', label: this.optionText.translate('analytics.7_dney') },
      { value: '30d', label: this.optionText.translate('analytics.30_dney') },
      { value: '90d', label: this.optionText.translate('analytics.90_dney') },
    ]);
  }

  private loadTrends(): void {
    const range = this.selectedRange;
    this.loadRequest(this.http.get<TrendDataPoint[]>(`/api/v1/analytics/trends?range=${range}`), data => {
      this.trends.set(data);
      this.displayedRange = range;
    });
  }

  private loadRequest<T>(request: Observable<T>, apply: (data: T) => void): void {
    this.activeRequest?.unsubscribe();
    this.loading.set(true);
    this.error.set('');
    this.activeRequest = request.subscribe({
      next: data => apply(data),
      error: e => {
        this.error.set(e?.error?.detail || this.uiI18n.translate('analytics.ne_udalos_zagruzit_dannye_analitiki'));
        this.loading.set(false);
      },
      complete: () => this.loading.set(false)
    });
  }
}
