import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  Signal,
  TemplateRef,
  viewChild,
  output,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { UiServerTableComponent } from '@shared/ui/ui-server-table.component';
import { OrderBy, TableConfig } from '@shared/ui-kit/components/table/table.types';
import { ProjectTaskStats } from '@core/models/task.models';
import { QueryListMeta } from '@core/models/query-meta.models';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { ListViewState } from '@shared/list-views/list-views';
import { registryTableConfig } from '@shared/ui/registry-table-config';
import { ProjectListItem } from '../projects.models';

/**
 * The project list a page at a time on the registry table (`query-meta/ms.projects`, roadmap item 51): the
 * server sorts the whole list, by progress too, and the columns, views, filter and export come from its field
 * list. Progress is a field only for someone who may view tasks, so without that right it has no column.
 */
@Component({
  selector: 'app-project-table-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, UiServerTableComponent, DatePipe],
  template: `
    <div class="table-card" role="region" [attr.aria-label]="'projects.list.table' | t">
      @if (tableConfig(); as config) {
        <ui-server-table
          [pager]="pager()"
          [config]="config"
          [views]="views()"
          [filterMeta]="meta()"
          [exportable]="true"
          [exportSearch]="exportSearch()"
          [exportOptions]="exportOptions()"
          [lockedColumns]="['name', 'actions']"
          [loadingLabel]="'projects.loading_projects' | t"
          [errorLabel]="'projects.load_projects_error' | t"
          errorId="projects-load-error"
          [emptyTemplate]="emptyState()"
          (sortChange)="sortChange.emit($event)"
        />
      }
    </div>

    <ng-template #idCell let-p>
      <span class="tabular-nums font-mono text-muted">#{{ p.id }}</span>
    </ng-template>
    <ng-template #nameCell let-p>
      <div class="project-title-cell">
        <span class="material-symbols-outlined folder-icon" aria-hidden="true">folder</span>
        <div class="project-info-group">
          @if (canViewTasks()) {
            <button type="button" class="project-name" (click)="viewTasks.emit(p)">{{ p.name }}</button>
          } @else {
            <span class="project-name-text">{{ p.name }}</span>
          }
          @if (p.description) {
            <span class="project-desc-line">{{ p.description }}</span>
          }
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
              {{ 'projects.closed_ratio' | t: { done: getProjectDoneCount(p.id), total: getProjectTotalCount(p.id) } }}
            </span>
            <span class="progress-percent tabular-nums">{{ getProjectPercent(p.id) }}%</span>
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
      } @else {
        <span class="stats-unknown">{{ 'projects.stats_unknown' | t }}</span>
      }
    </ng-template>
    <ng-template #createdCell let-p>
      <span class="tabular-nums text-muted text-xs">{{ p.createdAt | date: 'dd.MM.yyyy' }}</span>
    </ng-template>
    <ng-template #actionsCell let-p>
      <div class="row-action-btns">
        @if (canViewTasks()) {
          <button
            type="button"
            class="action-link-btn"
            [attr.aria-label]="'projects.open_tasks_named' | t: { name: p.name }"
            [title]="'projects.list.go_to_project_tasks' | t"
            (click)="viewTasks.emit(p)"
          >
            <span class="material-symbols-outlined" aria-hidden="true">task_alt</span>
            {{ 'nav.tasks' | t }}
          </button>
        }
        @if (canUpdateProject()) {
          <button
            type="button"
            class="icon-ghost-btn"
            [attr.aria-label]="'projects.edit_named' | t: { name: p.name }"
            [title]="'projects.common.edit_project' | t"
            (click)="editProject.emit(p)"
          >
            <span class="material-symbols-outlined" aria-hidden="true">edit</span>
          </button>
          <button
            type="button"
            class="icon-ghost-btn members-btn"
            [attr.aria-label]="'projects.manage_members_named' | t: { name: p.name }"
            [title]="'projects.common.project_members' | t"
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
        <p>{{ 'projects.list.empty' | t }}</p>
      </div>
    </ng-template>
  `,
  styleUrl: './project-table-view.component.css',
})
export class ProjectTableViewComponent {
  private readonly i18n = inject(I18nService);

  readonly pager = input.required<KeysetPager<ProjectListItem>>();

  readonly meta = input<QueryListMeta | null>(null);
  readonly views = input<ListViewState | null>(null);
  /** The search text and state filter on screen, so an export matches the list shown. */
  readonly exportSearch = input<string | null>(null);
  readonly exportOptions = input<Record<string, string> | null>(null);

  readonly canUpdateProject = input(false);
  readonly projectStats = input<Record<number, ProjectTaskStats>>({});
  readonly statsLoaded = input(false);

  readonly canViewTasks = input<boolean>(false);

  readonly viewTasks = output<ProjectListItem>();
  readonly editProject = output<ProjectListItem>();
  readonly manageMembers = output<ProjectListItem>();
  readonly sortChange = output<
    | {
        column: string;
        sortBy: OrderBy;
      }
    | undefined
  >();

  readonly emptyState = viewChild.required<TemplateRef<unknown>>('emptyStateTpl');
  private readonly idCell = viewChild.required<TemplateRef<unknown>>('idCell');
  private readonly nameCell = viewChild.required<TemplateRef<unknown>>('nameCell');
  private readonly stateCell = viewChild.required<TemplateRef<unknown>>('stateCell');
  private readonly progressCell = viewChild.required<TemplateRef<unknown>>('progressCell');
  private readonly createdCell = viewChild.required<TemplateRef<unknown>>('createdCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');

  /** Registry columns with the screen's cells, plus the row actions, which are not a field. */
  readonly tableConfig = computed<TableConfig<ProjectListItem> | null>(() => {
    const meta = this.meta();
    if (!meta) return null;
    const header = (value: string) => ({ type: 'primitive' as const, value });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    // Every row is its own grid, so tracks are fixed or shares of the width, never content-sized.
    const fixed = meta.fields.some((field) => field.key === 'progress') ? 710 : 490;
    const base = registryTableConfig<ProjectListItem>(meta, {
      translate: (key) => this.i18n.translate(key),
      trackBy: (_index, project) => project.id,
      ariaLabel: this.i18n.translate('projects.list.list'),
      sort: this.views()?.sort() ?? null,
      cells: {
        id: cell(this.idCell),
        name: cell(this.nameCell),
        state: cell(this.stateCell),
        progress: cell(this.progressCell),
        createdAt: cell(this.createdCell),
      },
      widths: {
        id: '70px',
        name: `max(220px, calc(100% - ${fixed}px))`,
        state: '120px',
        progress: '220px',
        createdAt: '120px',
      },
      align: { id: 'left' },
    });
    return {
      ...base,
      layout: 'fit',
      rowClass: () => 'project-row',
      columns: {
        ...base.columns,
        actions: {
          key: 'actions',
          header: header(this.i18n.translate('common.actions')),
          content: cell(this.actionsCell),
          width: '180px',
          align: 'right',
        },
      },
      columnsOrder: [...base.columnsOrder, 'actions'],
    };
  });

  private readonly viewTasksAllowed = computed(() => this.canViewTasks());

  hasProjectStats(projectId: number): boolean {
    return this.canViewTasks() && this.statsLoaded() && this.projectStats()[projectId] !== undefined;
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
