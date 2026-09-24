import { Component, computed, EventEmitter, inject, Input, Output, Signal, signal, TemplateRef, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { UiPaginationComponent } from '../../../../shared/ui/ui-pagination.component';
import { SMTTableComponent } from '../../../../shared/ui-kit/components/table/table.component';
import { OrderBy, TableConfig } from '../../../../shared/ui-kit/components/table/table.types';
import { Project, ProjectTaskStats } from '../../../../core/models/task.models';
import { ProjectSort, ProjectSortColumn } from '../projects-order';

/**
 * The project list on the kit table. Every project is loaded at once, so the
 * sortable columns order the whole filtered list (the page owns that order),
 * not just the page on screen.
 */
@Component({
  selector: 'app-project-table-view',
  standalone: true,
  imports: [
    CommonModule,
    TranslatePipe,
    UiPaginationComponent,
    SMTTableComponent
  ],
  template: `
    <div class="table-card" role="region" [attr.aria-label]="'projects.tablica_proektov' | t">
      <smt-table
        [smtData]="paginatedProjects"
        [smtConfig]="tableConfig()"
        [smtEmptyTemplate]="emptyState()"
        [smtColumnResizeEnabled]="false"
        [smtVirtualRows]="false"
        (smtSortChange)="onSortChange($event)" />

      <ui-pagination
        [totalItems]="totalCount"
        [currentPage]="currentPage"
        [pageSize]="pageSize"
        (pageChange)="pageChange.emit($event)"
        (pageSizeChange)="pageSizeChange.emit($event)"
      ></ui-pagination>
    </div>

    <ng-template #idCell let-p>
      <span class="tabular-nums font-mono text-muted">#{{ p.id }}</span>
    </ng-template>
    <ng-template #nameCell let-p>
      <div class="project-title-cell">
        <span class="material-symbols-outlined folder-icon" aria-hidden="true">folder</span>
        <div class="project-info-group">
          @if (canViewTasks) {
            <button type="button" class="project-name" (click)="viewTasks.emit(p)">{{ p.name }}</button>
          } @else {
            <span class="project-name-text">{{ p.name }}</span>
          }
          @if (p.description) { <span class="project-desc-line">{{ p.description }}</span> }
        </div>
      </div>
    </ng-template>
    <ng-template #stateCell let-p>
      <span class="status-pill" [class.active]="p.state === 'A'">
        <span class="status-dot" [class.active]="p.state === 'A'"></span>
        {{ (p.state === 'A' ? 'projects.state_active' : 'projects.state_archived') | t }}
      </span>
    </ng-template>
    <ng-template #progressCell let-p>
      @if (hasProjectStats(p.id)) {
        <div class="progress-cell">
          <div class="progress-labels">
            <span class="progress-count tabular-nums">
              {{ 'projects.closed_ratio' | t:{done: getProjectDoneCount(p.id), total: getProjectTotalCount(p.id)} }}
            </span>
            <span class="progress-percent tabular-nums">{{ getProjectPercent(p.id) }}%</span>
          </div>
          <div
            class="progress-bar-bg"
            role="progressbar"
            [attr.aria-label]="'projects.closed_progress_named' | t:{name: p.name}"
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
      } @else {
        <span class="stats-unknown">{{ 'projects.stats_unknown' | t }}</span>
      }
    </ng-template>
    <ng-template #createdCell let-p>
      <span class="tabular-nums text-muted text-xs">{{ p.createdAt | date:'dd.MM.yyyy' }}</span>
    </ng-template>
    <ng-template #actionsCell let-p>
      <div class="row-action-btns">
        @if (canViewTasks) {
          <button
            type="button"
            class="action-link-btn"
            [attr.aria-label]="'projects.open_tasks_named' | t:{name: p.name}"
            [title]="'projects.pereyti_k_zadacham_proekta' | t"
            (click)="viewTasks.emit(p)"
          >
            <span class="material-symbols-outlined" aria-hidden="true">task_alt</span>
            {{ 'nav.tasks' | t }}
          </button>
        }
        @if (canUpdateProject) {
          <button
            type="button"
            class="icon-ghost-btn"
            [attr.aria-label]="'projects.edit_named' | t:{name: p.name}"
            [title]="'projects.redaktirovat_proekt' | t"
            (click)="editProject.emit(p)"
          >
            <span class="material-symbols-outlined" aria-hidden="true">edit</span>
          </button>
          <button
            type="button"
            class="icon-ghost-btn members-btn"
            [attr.aria-label]="'projects.manage_members_named' | t:{name: p.name}"
            [title]="'projects.uchastniki_proekta' | t"
            (click)="manageMembers.emit(p)"
          >
            <span class="material-symbols-outlined" aria-hidden="true">group</span>
          </button>
        }
      </div>
    </ng-template>
    <ng-template #emptyStateTpl>
      <div class="empty-state-cell">
        <span class="material-symbols-outlined empty-icon" aria-hidden="true">folder_off</span>
        <p>{{ 'projects.proekty_ne_naydeny' | t }}</p>
      </div>
    </ng-template>
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .table-card { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
    .project-title-cell {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .folder-icon { font-size: 20px; color: var(--warning); flex-shrink: 0; }
    .project-info-group { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .project-name {
      border: 0;
      background: transparent;
      cursor: pointer;
      font: inherit;
      min-height: 28px;
      padding: 0 2px;
      text-align: left;
      display: inline-flex;
      align-items: center;
      font-weight: 600;
      color: var(--text-main);
    }
    .project-name:hover { text-decoration: underline; }
    .project-name-text { font-weight: 600; color: var(--text-main); }
    .project-desc-line {
      font-size: 11px;
      color: var(--text-muted);
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .status-pill {
      font-size: 11px;
      font-weight: 500;
      padding: 2px 7px;
      border-radius: 10px;
      display: inline-flex;
      align-items: center;
      gap: 4px;
      background-color: var(--bg-hover);
      color: var(--text-muted);
    }
    .status-pill.active { background-color: rgba(16,185,129,0.1); color: var(--success); }
    .status-dot { width: 5px; height: 5px; border-radius: 50%; background-color: var(--text-muted); }
    .status-dot.active { background-color: var(--success); }

    /* Progress Cell */
    .progress-cell { display: flex; flex-direction: column; gap: 4px; }
    .progress-labels { display: flex; justify-content: space-between; font-size: 11px; }
    .progress-count { color: var(--text-muted); font-size: 10px; }
    .progress-percent { font-weight: 600; color: var(--text-main); font-size: 10px; }
    .progress-bar-bg {
      height: 5px;
      background-color: var(--bg-hover);
      border-radius: 3px;
      overflow: hidden;
      border: 1px solid var(--border-color);
    }
    .progress-bar-fill {
      height: 100%;
      background-color: var(--primary);
      border-radius: 2px;
      transition: width 0.3s ease;
    }
    .progress-bar-fill.complete { background-color: var(--success); }
    .stats-unknown { color: var(--text-muted); font-size: 11px; }

    .row-action-btns { display: inline-flex; align-items: center; gap: 6px; }
    .action-link-btn {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 3px 8px;
      min-height: 28px;
      border-radius: var(--radius-xs);
      border: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      color: var(--text-main);
      font-size: 11px;
      font-weight: 500;
      cursor: pointer;
    }
    .action-link-btn:hover { border-color: var(--primary); color: var(--primary); }
    .action-link-btn .material-symbols-outlined { font-size: 14px; }

    .icon-ghost-btn {
      min-width: 28px;
      min-height: 28px;
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      padding: 4px;
      border-radius: 4px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }
    .icon-ghost-btn:hover { color: var(--text-main); background-color: var(--bg-hover); }
    .icon-ghost-btn .material-symbols-outlined { font-size: 16px; }

    .empty-state-cell {
      padding: 24px;
      text-align: center;
      color: var(--text-muted);
    }
    .empty-icon { font-size: 36px; color: var(--text-light); margin-bottom: 6px; }

    .tabular-nums { font-variant-numeric: tabular-nums; }
    .font-mono { font-family: monospace; }
    .text-right { text-align: right; }
    .text-muted { color: var(--text-muted); }
    .text-xs { font-size: 11px; }
  `]
})
export class ProjectTableViewComponent {
  @Input() paginatedProjects: Project[] = [];
  @Input() totalCount = 0;
  @Input() currentPage = 1;
  @Input() pageSize = 10;
  @Input() set canViewTasks(value: boolean) { this.viewTasksAllowed.set(value); }
  get canViewTasks(): boolean { return this.viewTasksAllowed(); }
  @Input() canUpdateProject = false;
  @Input() projectStats: Record<number, ProjectTaskStats> = {};
  @Input() statsLoading = false;
  @Input() statsLoadError = false;
  @Input() statsLoaded = false;
  /** The order the page applies to the whole list; shown on the column headers. */
  @Input() set sort(value: ProjectSort | undefined) { this.currentSort.set(value); }

  @Output() viewTasks = new EventEmitter<Project>();
  @Output() editProject = new EventEmitter<Project>();
  @Output() manageMembers = new EventEmitter<Project>();
  @Output() pageChange = new EventEmitter<number>();
  @Output() pageSizeChange = new EventEmitter<number>();
  @Output() sortChange = new EventEmitter<ProjectSort | undefined>();

  private readonly i18n = inject(I18nService);
  private readonly viewTasksAllowed = signal(false);
  private readonly currentSort = signal<ProjectSort | undefined>(undefined);
  private readonly idCell = viewChild.required<TemplateRef<unknown>>('idCell');
  private readonly nameCell = viewChild.required<TemplateRef<unknown>>('nameCell');
  private readonly stateCell = viewChild.required<TemplateRef<unknown>>('stateCell');
  private readonly progressCell = viewChild.required<TemplateRef<unknown>>('progressCell');
  private readonly createdCell = viewChild.required<TemplateRef<unknown>>('createdCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');
  readonly emptyState = viewChild.required<TemplateRef<unknown>>('emptyStateTpl');

  readonly tableConfig = computed<TableConfig<Project>>(() => {
    const header = (value: string) => ({ type: 'primitive' as const, value });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    const sort = this.currentSort();
    const sorted = (column: ProjectSortColumn) => ({ hasSorting: true, sortedBy: sort?.column === column ? sort.sortBy : undefined });
    const withProgress = this.viewTasksAllowed();
    // Every row is its own grid, so tracks are fixed or shares of the width, never content-sized.
    const fixed = withProgress ? 710 : 490;
    return {
      trackBy: (_index, project) => project.id,
      layout: 'fit',
      ariaLabel: this.i18n.translate('projects.spisok_proektov'),
      rowClass: () => 'project-row',
      columnsOrder: withProgress
        ? ['id', 'name', 'state', 'progress', 'created', 'actions']
        : ['id', 'name', 'state', 'created', 'actions'],
      columns: {
        id: { header: header('ID'), content: cell(this.idCell), width: '70px', ...sorted('id') },
        name: { header: header(this.i18n.translate('projects.proekt')), content: cell(this.nameCell), width: `max(220px, calc(100% - ${fixed}px))`, ...sorted('name') },
        state: { header: header(this.i18n.translate('common.status')), content: cell(this.stateCell), width: '120px', ...sorted('state') },
        progress: { header: header(this.i18n.translate('projects.closed_tasks')), content: cell(this.progressCell), width: '220px', ...sorted('progress') },
        created: { header: header(this.i18n.translate('iam.sozdan')), content: cell(this.createdCell), width: '120px', ...sorted('created') },
        actions: { header: header(this.i18n.translate('common.actions')), content: cell(this.actionsCell), width: '180px', align: 'right' },
      },
    };
  });

  onSortChange(event: { column: string; sortBy: OrderBy } | undefined): void {
    this.sortChange.emit(event ? { column: event.column as ProjectSortColumn, sortBy: event.sortBy } : undefined);
  }

  hasProjectStats(projectId: number): boolean {
    return this.canViewTasks
      && this.statsLoaded
      && !this.statsLoading
      && !this.statsLoadError
      && this.projectStats[projectId] !== undefined;
  }

  getProjectTotalCount(projectId: number): number {
    return this.projectStats[projectId]?.totalTasks ?? 0;
  }

  getProjectDoneCount(projectId: number): number {
    return this.projectStats[projectId]?.doneTasks ?? 0;
  }

  getProjectPercent(projectId: number): number {
    const stats = this.projectStats[projectId];
    if (!stats) return 0;
    const total = stats.totalTasks;
    if (total === 0) return 0;
    return Math.round((stats.doneTasks / total) * 100);
  }
}
