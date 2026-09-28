import { ChangeDetectionStrategy, Component, computed, signal, input, output } from '@angular/core';

import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { TranslatePipe } from '@core/services/i18n.service';
import { ProjectDistribution } from '../analytics.models';

@Component({
  selector: 'app-analytics-projects-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTInputComponent, TranslatePipe],
  template: `
    <div class="analytics-card">
      <div class="card-header-row">
        <div>
          <h2 class="card-title">{{ 'analytics.progress_po_proektam' | t }}</h2>
          <p class="card-subtitle">{{ 'analytics.statusy_i_procent_vypolneniya' | t }}</p>
        </div>
        <!-- Quick Project Filter -->
        @if (projects().length > 3) {
          <smt-input
            class="project-search-box"
            type="search"
            smtIcon="search"
            clearable
            smtSize="sm"
            [placeholder]="'analytics.poisk_proekta' | t"
            [smtAriaLabel]="'analytics.poisk_proekta' | t"
            [value]="searchProjectQuery()"
            (valueChange)="searchProjectQuery.set($any($event) ?? '')"
          />
        }
      </div>

      @if (filteredProjects().length > 0) {
        <div class="project-list">
          @for (p of filteredProjects(); track p) {
            <div
              class="project-item clickable"
              (click)="projectClick.emit(p.projectId)"
              [title]="'projects.open_project' | t"
            >
              <div class="project-info-row">
                <div class="project-name-group">
                  <span
                    class="material-symbols-outlined"
                    style="font-size: 18px;"
                    [style.color]="getProgressColor(p.progressPercent)"
                    aria-hidden="true"
                    >folder</span
                  >
                  <span class="project-name">{{ p.projectName }}</span>
                </div>
                <div class="project-stats">
                  <span class="project-pct" [style.color]="getProgressColor(p.progressPercent)"
                    >{{ p.progressPercent }}%</span
                  >
                  <span class="project-tasks-count font-mono">({{ p.completedTasks }}/{{ p.totalTasks }})</span>
                </div>
              </div>
              <div class="progress-bar-bg">
                <div
                  class="progress-bar-fill"
                  [style.width.%]="p.progressPercent"
                  [style.background-color]="getProgressColor(p.progressPercent)"
                ></div>
              </div>
            </div>
          }
        </div>
      }

      @if (filteredProjects().length === 0 && !loading() && !error()) {
        <div class="empty-chart">
          <span class="material-symbols-outlined" style="font-size: 32px; color: var(--text-light);" aria-hidden="true"
            >folder_open</span
          >
          <p>{{ 'analytics.aktivnye_proekty_ne_naydeny' | t }}</p>
        </div>
      }
    </div>
  `,
  styleUrl: './analytics-projects-card.component.css',
})
export class AnalyticsProjectsCardComponent {
  readonly loading = input(false);
  readonly error = input('');

  readonly projects = input<ProjectDistribution[]>([]);

  readonly projectClick = output<number>();

  searchProjectQuery = signal('');

  filteredProjects = computed(() => {
    const query = this.searchProjectQuery().trim().toLowerCase();
    const list = this._projects();
    if (!query) return list;
    return list.filter((p) => p.projectName.toLowerCase().includes(query));
  });
  private _projects = computed<ProjectDistribution[]>(() => this.projects() || []);

  getProgressColor(pct: number): string {
    if (pct >= 100) return 'var(--success)';
    if (pct >= 50) return 'var(--primary)';
    if (pct > 0) return '#f59e0b';
    return 'var(--text-muted)';
  }
}
