import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';

import { Router } from '@angular/router';
import { Observable, catchError, forkJoin, map, of, tap } from 'rxjs';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';

import { AnalyticsApi } from './analytics.api';
import { AnalyticsSummary, TrendDataPoint, ProjectDistribution, UserWorkload } from './analytics.models';

import { AnalyticsMetricsTilesComponent } from './components/analytics-metrics-tiles.component';
import { AnalyticsTrendChartComponent } from './components/analytics-trend-chart.component';
import { AnalyticsProjectsCardComponent } from './components/analytics-projects-card.component';
import { AnalyticsWorkloadTableComponent } from './components/analytics-workload-table.component';
import { AnalyticsWidgetsComponent } from './components/analytics-widgets.component';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';

export * from './analytics.models';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

/** The period shown when the screen opens. */
const FIRST_RANGE = '7d';

@Component({
  selector: 'app-analytics',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    UiPageHeaderComponent,
    SMTRadioGroupComponent,
    SMTAlertComponent,
    TranslatePipe,
    SMTButtonComponent,
    AnalyticsMetricsTilesComponent,
    AnalyticsTrendChartComponent,
    AnalyticsProjectsCardComponent,
    AnalyticsWorkloadTableComponent,
    AnalyticsWidgetsComponent,
  ],
  template: `
    <div class="analytics-container">
      <!-- Header -->
      <ui-page-header [title]="'analytics.dashboard.title' | t">
        <!-- Time Range Selector -->
        <smt-radio-group
          smtAppearance="segmented"
          class="range-picker"
          [options]="rangeOptions()"
          [value]="selectedRange"
          [smtAriaLabel]="'analytics.dashboard.period' | t"
          (valueChange)="setRange($event ?? selectedRange)"
        />
        <button
          smt-button
          type="button"
          smtVariant="secondary"
          smtSize="sm"
          smtIcon="download"
          (click)="exportReport()"
          [title]="'analytics.dashboard.export_tasks_excel' | t"
        >
          {{ 'analytics.dashboard.export' | t }}
        </button>
        <button
          smt-button
          type="button"
          smtVariant="secondary"
          smtSize="sm"
          smtIcon="refresh"
          [smtLoading]="loading()"
          [title]="'common.refresh' | t"
          (click)="loadAll()"
        >
          {{ 'common.refresh' | t }}
        </button>
      </ui-page-header>

      <!-- Error Alert -->
      @if (error()) {
        <smt-alert smtTone="danger">
          <span>{{ error() }}</span>
          <button type="button" class="alert-retry" data-testid="analytics-retry" (click)="setRange(selectedRange)">
            {{ 'common.retry' | t }}
          </button>
        </smt-alert>
      }

      <!-- KPI Metrics Row -->
      <app-analytics-metrics-tiles [summary]="summary()"></app-analytics-metrics-tiles>

      <!-- The viewer's own widgets: reports saved on entity lists (ADR-0032 10.2) -->
      <app-analytics-widgets />

      <!-- Main Analytics Grid: Trend Chart & Project Distribution -->
      <div class="analytics-grid">
        <app-analytics-trend-chart
          [trends]="trends()"
          [loading]="loading()"
          [displayedRange]="displayedRange()"
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
  styles: [
    `
      .alert-retry {
        margin-left: 8px;
        padding: 0;
        border: none;
        background: transparent;
        color: inherit;
        font: inherit;
        font-weight: 600;
        text-decoration: underline;
        cursor: pointer;
      }
      .alert-retry:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }
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
        .range-picker {
          max-width: 100%;
          overflow-x: auto;
        }
      }
    `,
  ],
})
export class AnalyticsComponent {
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);

  private readonly uiI18n = inject(I18nService);
  private readonly analytics = inject(AnalyticsApi);
  private router = inject(Router);

  /* Each section keeps what it showed while a read is pending or failed, and a
     full refresh replaces all of them together. */
  readonly summary = linkedSignal<AnalyticsLoaded | undefined, AnalyticsSummary | null>({
    source: () => this.loaded(),
    computation: (loaded, previous) => (loaded?.snapshot ? loaded.snapshot.summary : (previous?.value ?? null)),
  });
  readonly trends = linkedSignal<AnalyticsLoaded | undefined, TrendDataPoint[]>({
    source: () => this.loaded(),
    computation: (loaded, previous) => loaded?.trends ?? previous?.value ?? [],
  });
  readonly projects = linkedSignal<AnalyticsLoaded | undefined, ProjectDistribution[]>({
    source: () => this.loaded(),
    computation: (loaded, previous) => (loaded?.snapshot ? loaded.snapshot.projects : (previous?.value ?? [])),
  });
  readonly workload = linkedSignal<AnalyticsLoaded | undefined, UserWorkload[]>({
    source: () => this.loaded(),
    computation: (loaded, previous) => (loaded?.snapshot ? loaded.snapshot.workload : (previous?.value ?? [])),
  });
  /** The period of the trend on screen, which lags the chosen one while its read is pending. */
  readonly displayedRange = linkedSignal<AnalyticsLoaded | undefined, string>({
    source: () => this.loaded(),
    computation: (loaded, previous) => loaded?.range ?? previous?.value ?? FIRST_RANGE,
  });

  /** The read on screen; a new one cancels the read still in flight. */
  private readonly read = signal<AnalyticsRead>({ range: FIRST_RANGE, full: true });

  readonly error = computed(() => {
    const result = this.result.value();
    return result && 'failure' in result ? result.failure : '';
  });
  private readonly loaded = computed(() => {
    const result = this.result.value();
    return result && !('failure' in result) ? result : undefined;
  });

  selectedRange = FIRST_RANGE;
  /** A failed or superseded refresh must be retried as a whole snapshot. */
  private refreshRequired = true;
  private readonly result = rxResource({ params: this.read, stream: ({ params }) => this.fetch(params) });

  readonly loading = this.result.isLoading;

  private readonly rangeMemo = optionsMemo<SMTRadioOption<string>[]>();

  setRange(range: string): void {
    this.selectedRange = range;
    this.read.set({ range, full: this.refreshRequired });
  }

  navigateToProject(projectId: number): void {
    this.router.navigate(['/tasks'], { queryParams: { project: projectId } });
  }

  exportReport(format: 'xlsx' | 'csv' = 'xlsx'): void {
    window.open(`/api/v1/reports/tasks/export?format=${format}`, '_blank');
  }

  loadAll(): void {
    this.refreshRequired = true;
    this.read.set({ range: this.selectedRange, full: true });
  }

  /** The periods as one segmented bar. */
  rangeOptions(): SMTRadioOption<string>[] {
    return this.rangeMemo([this.optionText.currentLang()], () => [
      { value: '7d', label: this.optionText.translate('analytics.dashboard.period_7_days') },
      { value: '30d', label: this.optionText.translate('analytics.dashboard.period_30_days') },
      { value: '90d', label: this.optionText.translate('analytics.dashboard.period_90_days') },
    ]);
  }

  /** The whole snapshot, or only the trend of a new period once a snapshot is on screen. */
  private fetch({ range, full }: AnalyticsRead): Observable<AnalyticsLoaded | AnalyticsFailure> {
    const trends = this.analytics.trends(range);
    const read: Observable<AnalyticsLoaded> = full
      ? forkJoin({
          summary: this.analytics.summary(),
          trends,
          projects: this.analytics.projects(),
          workload: this.analytics.workload(),
        }).pipe(
          tap(() => (this.refreshRequired = false)),
          map(({ trends: points, ...snapshot }) => ({ range, trends: points, snapshot })),
        )
      : trends.pipe(map((points) => ({ range, trends: points })));
    return read.pipe(
      catchError((e: { error?: { detail?: string } }) =>
        of({
          failure: e?.error?.detail || this.uiI18n.translate('analytics.dashboard.load_failed'),
        }),
      ),
    );
  }
}

/** One read of the dashboard. */
interface AnalyticsRead {
  range: string;
  full: boolean;
}

/** What a read brought: the trend of its period, and the other sections when it was a full refresh. */
interface AnalyticsLoaded {
  range: string;
  trends: TrendDataPoint[];
  snapshot?: { summary: AnalyticsSummary; projects: ProjectDistribution[]; workload: UserWorkload[] };
}

interface AnalyticsFailure {
  failure: string;
}
