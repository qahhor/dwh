import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslatePipe } from '@core/services/i18n.service';
import { UiPaginationComponent } from '@shared/ui/ui-pagination.component';
import { Project, ProjectTaskStats } from '@core/models/task.models';

@Component({
  selector: 'app-project-cards-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, UiPaginationComponent, DatePipe],
  template: `
    <div class="cards-view-wrapper">
      <div class="projects-grid">
        @for (p of paginatedProjects(); track p) {
          <div class="project-card">
            <div class="card-top">
              <div class="project-icon-box">
                <span class="material-symbols-outlined" aria-hidden="true">folder</span>
              </div>
              <div class="card-top-right">
                <span class="status-pill" [class.active]="p.state === 'A'">
                  <span class="status-dot" [class.active]="p.state === 'A'"></span>
                  {{ (p.state === 'A' ? 'projects.state_active' : 'projects.state_archived') | t }}
                </span>
                @if (canUpdateProject()) {
                  <button
                    type="button"
                    class="edit-btn"
                    [attr.aria-label]="'projects.manage_members_named' | t: { name: p.name }"
                    [title]="'projects.uchastniki_proekta' | t"
                    (click)="manageMembers.emit(p)"
                  >
                    <span class="material-symbols-outlined" aria-hidden="true">group</span>
                  </button>
                }
                @if (canUpdateProject()) {
                  <button
                    type="button"
                    class="edit-btn"
                    [attr.aria-label]="'projects.edit_named' | t: { name: p.name }"
                    [title]="'projects.redaktirovat_proekt' | t"
                    (click)="editProject.emit(p)"
                  >
                    <span class="material-symbols-outlined" aria-hidden="true">edit</span>
                  </button>
                }
              </div>
            </div>

            <div class="card-content">
              <h3 class="project-title">
                @if (canViewTasks()) {
                  <button type="button" class="project-title-btn" (click)="viewTasks.emit(p)">
                    {{ p.name }}
                  </button>
                } @else {
                  <span class="project-name-text">{{ p.name }}</span>
                }
              </h3>
              <p class="project-desc">{{ p.description || ('projects.description_missing' | t) }}</p>
            </div>

            @if (canViewTasks() && hasProjectStats(p.id)) {
              <div class="card-progress">
                <div class="progress-labels">
                  <span class="progress-count tabular-nums">
                    {{
                      'projects.closed_ratio'
                        | t: { done: getProjectDoneCount(p.id), total: getProjectTotalCount(p.id) }
                    }}
                  </span>
                  <span class="progress-percent tabular-nums"> {{ getProjectPercent(p.id) }}% </span>
                </div>
                <div
                  class="progress-bar-bg"
                  role="progressbar"
                  [attr.aria-label]="'projects.closed_progress_named' | t: { name: p.name }"
                  aria-valuemin="0"
                  aria-valuemax="100"
                  [attr.aria-valuenow]="getProjectPercent(p.id)"
                >
                  <div
                    class="progress-bar-fill"
                    [style.width.%]="getProjectPercent(p.id)"
                    [class.complete]="getProjectPercent(p.id) === 100 && getProjectTotalCount(p.id) > 0"
                  ></div>
                </div>
              </div>
            }

            <div class="card-foot">
              <span class="foot-date tabular-nums">{{
                'projects.created_at' | t: { date: (p.createdAt | date: 'dd.MM.yyyy') || '' }
              }}</span>
              @if (canViewTasks() && hasProjectStats(p.id)) {
                <button type="button" class="view-tasks-link" (click)="viewTasks.emit(p)">
                  {{ 'projects.tasks_count_arrow' | t: { count: getProjectTotalCount(p.id) } }}
                </button>
              }
              @if (canViewTasks() && !hasProjectStats(p.id)) {
                <span class="stats-unknown">{{ 'projects.stats_unknown' | t }}</span>
              }
            </div>
          </div>
        }

        @if (totalCount() === 0) {
          <div class="empty-projects-cell">
            <span class="material-symbols-outlined empty-icon" aria-hidden="true">folder_off</span>
            <p>{{ 'projects.proekty_ne_naydeny' | t }}</p>
          </div>
        }
      </div>

      <ui-pagination
        [totalItems]="totalCount()"
        [currentPage]="currentPage()"
        [pageSize]="pageSize()"
        [showPageSize]="false"
        [cursorMode]="true"
        [hasNextPage]="hasNextPage()"
        (pageChange)="pageChange.emit($event)"
      ></ui-pagination>
    </div>
  `,
  styleUrl: './project-cards-view.component.css',
})
export class ProjectCardsViewComponent {
  readonly paginatedProjects = input<Project[]>([]);
  readonly totalCount = input(0);
  readonly currentPage = input(1);
  readonly pageSize = input(10);
  /** The server pages by cursor (ms.projects): the next page exists while it says so. */
  readonly hasNextPage = input(false);
  readonly canViewTasks = input(false);
  readonly canUpdateProject = input(false);
  readonly projectStats = input<Record<number, ProjectTaskStats>>({});
  readonly statsLoading = input(false);
  readonly statsLoadError = input(false);
  readonly statsLoaded = input(false);

  readonly viewTasks = output<Project>();
  readonly editProject = output<Project>();
  readonly manageMembers = output<Project>();
  readonly pageChange = output<number>();

  hasProjectStats(projectId: number): boolean {
    return (
      this.canViewTasks() &&
      this.statsLoaded() &&
      !this.statsLoading() &&
      !this.statsLoadError() &&
      this.projectStats()[projectId] !== undefined
    );
  }

  getProjectTotalCount(projectId: number): number {
    return this.projectStats()[projectId]?.totalTasks ?? 0;
  }

  getProjectDoneCount(projectId: number): number {
    return this.projectStats()[projectId]?.doneTasks ?? 0;
  }

  getProjectPercent(projectId: number): number {
    const stats = this.projectStats()[projectId];
    if (!stats) return 0;
    const total = stats.totalTasks;
    if (total === 0) return 0;
    return Math.round((stats.doneTasks / total) * 100);
  }
}
