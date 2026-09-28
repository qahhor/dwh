import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  Signal,
  signal,
  TemplateRef,
  viewChild,
  output,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { UiServerTableComponent } from '@shared/ui/ui-server-table.component';
import { UiBulkResultComponent } from '@shared/ui/ui-bulk-result.component';
import { BulkResult } from '@shared/bulk/bulk';
import { TasksApi } from '../tasks.api';
import { ToastService } from '@core/services/toast.service';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { OrderBy, TableConfig } from '@shared/ui-kit/components/table/table.types';
import { QueryListMeta } from '@core/models/query-meta.models';
import { ListViewState } from '@shared/list-views/list-views';
import { registryTableConfig } from '@shared/ui/registry-table-config';
import { Task, Project, TaskStatus, TaskType } from '@core/models/task.models';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';

/**
 * The task list, a page at a time from the server, on the shared server table.
 *
 * A click anywhere on a row opens the task, except on the row's own controls:
 * the priority and status selects change the task in place. The server orders
 * tasks itself and pages by cursor, so there is no column sorting: sorting one
 * page would only look like sorting the list.
 *
 * With the right to change tasks, rows can be chosen and changed together:
 * a new status or priority for every chosen task, through `POST /tasks/bulk`.
 * Each task is changed on its own, so a task that cannot be changed is named
 * with its reason while the rest go through.
 */
@Component({
  selector: 'app-task-table-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    UiServerTableComponent,
    UiBulkResultComponent,
    SMTSelectComponent,
    DatePipe,
  ],
  templateUrl: './task-table-view.component.html',
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .bulk-actions {
        display: flex;
        align-items: center;
        gap: 8px;
        flex-wrap: wrap;
      }
      .bulk-field {
        display: inline-flex;
        align-items: center;
        gap: 6px;
      }
      .bulk-label {
        color: var(--text-muted);
        font-size: 12px;
      }
      .bulk-select {
        width: 180px;
      }
      .table-card {
        min-width: 0;
      }
      /* The row belongs to the kit table's template, so it is reached from here.
       An inset shadow marks it without widening the row's grid. */
      :host ::ng-deep .smt-data-row.task-row-overdue {
        background-color: rgba(239, 68, 68, 0.04);
        box-shadow: inset 3px 0 0 var(--danger);
      }
      :host ::ng-deep .smt-data-row.task-row-overdue:hover {
        background-color: rgba(239, 68, 68, 0.08);
      }
      .overdue-tag {
        font-size: 9px;
        font-weight: 600;
        color: var(--danger-text);
        background-color: var(--danger-bg);
        padding: 1px 5px;
        border-radius: 3px;
        text-transform: uppercase;
        letter-spacing: 0.3px;
      }

      .task-type-badge {
        display: inline-flex;
        align-items: center;
        gap: 3px;
        font-size: 11px;
        font-weight: 500;
        padding: 1px 6px;
        border-radius: 4px;
      }
      .task-type-badge .type-icon {
        font-size: 13px;
      }

      .task-title-cell {
        display: flex;
        align-items: center;
        gap: 8px;
        flex-wrap: wrap;
      }
      .task-title {
        font-weight: 500;
      }
      .task-title-open {
        border: 0;
        padding: 0;
        background: transparent;
        color: var(--text-main);
        font: inherit;
        text-align: left;
        cursor: pointer;
      }
      .task-title-open:hover {
        color: var(--primary);
        text-decoration: underline;
      }
      .parent-chip {
        font-size: 10px;
        background-color: var(--bg-hover);
        color: var(--text-muted);
        padding: 1px 5px;
        border-radius: 4px;
        border: 1px solid var(--border-color);
      }

      .project-tag {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        font-size: 12px;
        color: var(--text-muted);
      }
      .folder-ico {
        font-size: 14px;
        color: var(--warning);
      }

      /* Inline Status Select */
      .inline-status-wrapper {
        display: flex;
        align-items: center;
        gap: 5px;
        min-width: 0;
      }
      .inline-status-select {
        flex: 1;
        min-width: 0;
      }

      .status-dot {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        display: inline-block;
      }

      .deadline-pill {
        display: inline-flex;
        align-items: center;
        gap: 3px;
        font-size: 11px;
        color: var(--text-muted);
        background-color: var(--bg-hover);
        padding: 1px 6px;
        border-radius: 4px;
        border: 1px solid var(--border-color);
        white-space: nowrap;
      }
      .deadline-pill .ico {
        font-size: 13px;
      }
      .deadline-pill.overdue {
        color: var(--danger-text);
        background-color: var(--danger-bg);
        border-color: rgba(239, 68, 68, 0.3);
        font-weight: 600;
      }
      .deadline-pill.deadline-today {
        background-color: rgba(245, 158, 11, 0.15);
        border-color: rgba(245, 158, 11, 0.35);
        color: var(--warning-text);
        font-weight: 600;
      }
      .deadline-pill.deadline-tomorrow {
        background-color: rgba(59, 130, 246, 0.12);
        border-color: rgba(59, 130, 246, 0.3);
        color: var(--info-text);
      }

      .row-action-btns {
        display: inline-flex;
        gap: 4px;
      }
      .icon-ghost-btn {
        border: none;
        background: transparent;
        color: var(--text-muted);
        cursor: pointer;
        padding: 4px;
        border-radius: 4px;
        display: inline-flex;
        align-items: center;
        justify-content: center;
      }
      .icon-ghost-btn:hover {
        color: var(--text-main);
        background-color: var(--bg-hover);
      }
      .icon-ghost-btn .material-symbols-outlined {
        font-size: 17px;
      }

      .empty-state-cell {
        padding: 40px;
        text-align: center;
        color: var(--text-muted);
      }
      .empty-state-cell .icon {
        font-size: 36px;
        color: var(--text-light);
        margin-bottom: 6px;
      }
      .font-mono {
        font-family: ui-monospace, monospace;
      }
      .tabular-nums {
        font-variant-numeric: tabular-nums;
      }
      .text-danger {
        color: var(--danger);
      }
      .text-muted {
        color: var(--text-muted);
      }
      .text-right {
        text-align: right;
      }
    `,
  ],
})
export class TaskTableViewComponent {
  private readonly i18n = inject(I18nService);
  private readonly tasksApi = inject(TasksApi);
  private readonly toast = inject(ToastService);

  readonly pager = input.required<KeysetPager<Task>>();

  readonly isOverdue = input.required<(endTime: string | null | undefined, statusId: number) => boolean>();
  readonly getTypeColor = input.required<(task: Task) => string>();
  readonly getTypeBg = input.required<(task: Task) => string>();
  readonly getTypeIcon = input.required<(task: Task) => string>();
  readonly getTypeLabel = input.required<(task: Task) => string>();
  readonly getProjectName = input.required<(projectId: number | null | undefined) => string | null>();
  readonly getStatusColor = input.required<(statusId: number | null | undefined) => string>();
  readonly getDeadlineInfo = input.required<
    (
      endTime: string | null | undefined,
      statusId: number,
    ) => {
      state: string;
      label: string;
    }
  >();

  readonly meta = input<QueryListMeta | null>(null);
  readonly views = input<ListViewState | null>(null);
  /** The search text and quick filters on screen, so an export matches the list shown. */
  readonly exportSearch = input<string | null>(null);
  readonly exportOptions = input<Record<string, string> | null>(null);
  readonly projects = input<Project[]>([]);
  readonly taskTypes = input<TaskType[]>([]);
  readonly canCreateTask = input(false);
  readonly hasActiveFilters = input(false);
  readonly statuses = input<TaskStatus[]>([]);
  readonly canUpdateTask = input(false);

  readonly openTaskDetails = output<Task>();
  readonly openEditModal = output<Task>();
  readonly updatePriority = output<{
    taskId: number;
    priority: string;
  }>();
  readonly updateStatus = output<{
    taskId: number;
    statusId: number;
  }>();
  readonly resetFilters = output<void>();
  readonly createTask = output<void>();
  readonly sortChange = output<
    | {
        column: string;
        sortBy: OrderBy;
      }
    | undefined
  >();

  readonly emptyState = viewChild.required<TemplateRef<unknown>>('emptyStateTpl');
  private readonly idCell = viewChild.required<TemplateRef<unknown>>('idCell');
  private readonly typeCell = viewChild.required<TemplateRef<unknown>>('typeCell');
  private readonly titleCell = viewChild.required<TemplateRef<unknown>>('titleCell');
  private readonly projectCell = viewChild.required<TemplateRef<unknown>>('projectCell');
  private readonly priorityCell = viewChild.required<TemplateRef<unknown>>('priorityCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('statusCell');
  private readonly deadlineCell = viewChild.required<TemplateRef<unknown>>('deadlineCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');

  /** Rows chosen on the page on screen; the table clears them when the page changes. */
  readonly selectedTasks = signal<Task[]>([]);
  readonly bulkStatusId = signal<number | null>(null);
  readonly bulkPriority = signal<string | null>(null);
  readonly bulkBusy = signal(false);
  readonly bulkAction = signal<'status' | 'priority' | null>(null);
  /** Set when some tasks failed; the dialog names them. */
  readonly bulkResult = signal<BulkResult | null>(null);

  /** Registry columns with the screen's cells (status, project and deadline by name), plus the type and actions. */
  readonly tableConfig = computed<TableConfig<Task> | null>(() => {
    const meta = this.meta();
    if (!meta) return null;
    const header = (value: string) => ({ type: 'primitive' as const, value });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    // Every row is its own grid, so tracks are fixed or shares of the width, never content-sized.
    const rest = '(100% - 750px)';
    const base = registryTableConfig<Task>(meta, {
      translate: (key) => this.i18n.translate(key),
      trackBy: (_index, task) => task.id,
      ariaLabel: this.i18n.translate('tasks.spisok_zadach'),
      sort: this.views()?.sort() ?? null,
      cells: {
        id: cell(this.idCell),
        title: cell(this.titleCell),
        projectId: cell(this.projectCell),
        priority: cell(this.priorityCell),
        statusId: cell(this.statusCell),
        endTime: cell(this.deadlineCell),
      },
      widths: {
        id: '70px',
        title: `max(220px, calc(${rest} * 0.6))`,
        projectId: `max(140px, calc(${rest} * 0.4))`,
        priority: '130px',
        statusId: '150px',
        endTime: '180px',
      },
      align: { id: 'left' },
    });
    const order = [...base.columnsOrder];
    order.splice(order.includes('id') ? order.indexOf('id') + 1 : 0, 0, 'type');
    return {
      ...base,
      layout: 'fit',
      rowClass: (task) => (this.isOverdue()(task.endTime, task.statusId) ? 'task-row-overdue' : null),
      columns: {
        ...base.columns,
        type: {
          key: 'type',
          header: header(this.i18n.translate('settings.tip')),
          content: cell(this.typeCell),
          width: '120px',
        },
        actions: {
          key: 'actions',
          header: header(this.i18n.translate('common.actions')),
          content: cell(this.actionsCell),
          width: '100px',
          align: 'right',
        },
      },
      columnsOrder: [...order, 'actions'],
    };
  });

  private readonly statusMemo = optionsMemo<SMTSelectOption<number>[]>();
  private readonly priorityMemo = optionsMemo<SMTSelectOption<string>[]>();
  /** Titles of the tasks sent, so the result can name a task after the page reloads. */
  private bulkTitles = new Map<number, string>();
  readonly bulkItemLabel = (id: number) => {
    const title = this.bulkTitles.get(id);
    return title ? `#${id} ${title}` : `#${id}`;
  };

  /** Statuses as smt-select options; the same array while the statuses stay the same. */
  statusOptions(): SMTSelectOption<number>[] {
    return this.statusMemo([this.statuses()], () =>
      this.statuses().map((status) => ({ id: status.id, label: status.name })),
    );
  }

  /** Priorities, lowest first, each with its colour mark; translated again when the language changes. */
  priorityOptions(): SMTSelectOption<string>[] {
    return this.priorityMemo([this.i18n.currentLang()], () => [
      { id: 'low', label: this.i18n.translate('task.priority.low'), icon: 'flag', color: 'var(--success-text)' },
      { id: 'medium', label: this.i18n.translate('tasks.sredniy'), icon: 'flag', color: 'var(--info-text)' },
      { id: 'high', label: this.i18n.translate('task.priority.high'), icon: 'flag', color: 'var(--warning-text)' },
      { id: 'critical', label: this.i18n.translate('tasks.kriticheskiy'), icon: 'flag', color: 'var(--danger-text)' },
    ]);
  }

  onPriorityChange(taskId: number, priority: string | null): void {
    if (priority !== null) this.updatePriority.emit({ taskId, priority });
  }

  onStatusChange(taskId: number, statusId: number | null): void {
    if (statusId !== null) this.updateStatus.emit({ taskId, statusId });
  }

  /** One status or priority for every chosen task; the page reloads with what the server now holds. */
  applyBulk(action: 'status' | 'priority'): void {
    const tasks = this.selectedTasks();
    if (tasks.length === 0 || this.bulkBusy()) return;
    const params = action === 'status' ? { statusId: this.bulkStatusId() } : { priority: this.bulkPriority() };
    this.bulkTitles = new Map(tasks.map((task) => [task.id, task.title]));
    this.bulkBusy.set(true);
    this.bulkAction.set(action);
    this.tasksApi
      .bulk(
        action,
        tasks.map((task) => task.id),
        params,
      )
      .subscribe({
        next: (result) => {
          this.bulkBusy.set(false);
          this.bulkAction.set(null);
          this.bulkStatusId.set(null);
          this.bulkPriority.set(null);
          if (result.succeeded > 0) {
            this.toast.success(this.i18n.translate('tasks.bulk.done', { count: result.succeeded }));
          }
          if (result.failed > 0) this.bulkResult.set(result);
          this.pager().reload();
        },
        error: () => {
          this.bulkBusy.set(false);
          this.bulkAction.set(null);
          this.toast.error(this.i18n.translate('tasks.bulk.error'));
        },
      });
  }
}
