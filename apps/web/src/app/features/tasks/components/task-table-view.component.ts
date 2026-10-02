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
import { SET_STATUS, TasksApi } from '../tasks.api';
import { ToastService } from '@core/services/toast.service';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { OrderBy, TableConfig } from '@shared/ui-kit/components/table/table.types';
import { QueryListMeta } from '@core/models/query-meta.models';
import { ListViewState } from '@shared/list-views/list-views';
import { registryTableConfig } from '@shared/ui/registry-table-config';
import { RefLookups } from '@shared/lookups/ref-lookup';
import { Task, TaskStatus, TaskType } from '@core/models/task.models';
import { TaskProjectRef } from '../tasks.models';
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
  styleUrl: './task-table-view.component.css',
})
export class TaskTableViewComponent {
  private readonly i18n = inject(I18nService);
  private readonly refLookups = inject(RefLookups);
  private readonly tasksApi = inject(TasksApi);
  private readonly toast = inject(ToastService);

  readonly pager = input.required<KeysetPager<Task>>();

  readonly isOverdue = input.required<(endTime: string | null | undefined, statusCode: string) => boolean>();
  readonly getTypeColor = input.required<(task: Task) => string>();
  readonly getTypeBg = input.required<(task: Task) => string>();
  readonly getTypeIcon = input.required<(task: Task) => string>();
  readonly getTypeLabel = input.required<(task: Task) => string>();
  /** The task's project as the row names it (`projectName`). */
  readonly getProjectName = input.required<(task: TaskProjectRef) => string | null>();
  readonly getStatusColor = input.required<(statusCode: string | null | undefined) => string>();
  readonly getDeadlineInfo = input.required<
    (
      endTime: string | null | undefined,
      statusCode: string,
    ) => {
      state: string;
      label: string;
    }
  >();

  readonly meta = input<QueryListMeta | null>(null);
  readonly views = input<ListViewState | null>(null);
  /** The search text and quick filters on screen, so an export matches the list shown. */
  readonly exportSearch = input<string | null>(null);
  readonly exportFilter = input<readonly unknown[]>([]);
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
    statusCode: string;
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
  readonly bulkStatusCode = signal<string | null>(null);
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
      ariaLabel: this.i18n.translate('tasks.table.list'),
      sort: this.views()?.sort() ?? null,
      cells: {
        id: cell(this.idCell),
        title: cell(this.titleCell),
        projectId: cell(this.projectCell),
        priority: cell(this.priorityCell),
        statusCode: cell(this.statusCell),
        endTime: cell(this.deadlineCell),
      },
      widths: {
        id: '70px',
        title: `max(220px, calc(${rest} * 0.6))`,
        projectId: `max(140px, calc(${rest} * 0.4))`,
        priority: '130px',
        statusCode: '150px',
        endTime: '180px',
      },
      align: { id: 'left' },
      refName: (ref, key) => this.refLookups.name(ref, key),
    });
    const order = [...base.columnsOrder];
    order.splice(order.includes('id') ? order.indexOf('id') + 1 : 0, 0, 'type');
    return {
      ...base,
      layout: 'fit',
      rowClass: (task) => (this.isOverdue()(task.endTime, task.statusCode) ? 'task-row-overdue' : null),
      columns: {
        ...base.columns,
        type: {
          key: 'type',
          header: header(this.i18n.translate('settings.common.type')),
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

  private readonly statusMemo = optionsMemo<SMTSelectOption<string>[]>();
  private readonly priorityMemo = optionsMemo<SMTSelectOption<string>[]>();
  /** Titles of the tasks sent, so the result can name a task after the page reloads. */
  private bulkTitles = new Map<number, string>();
  readonly bulkItemLabel = (id: number) => {
    const title = this.bulkTitles.get(id);
    return title ? `#${id} ${title}` : `#${id}`;
  };

  /** Statuses as smt-select options; the same array while the statuses stay the same. */
  statusOptions(): SMTSelectOption<string>[] {
    return this.statusMemo([this.statuses()], () =>
      this.statuses().map((status) => ({ id: status.code, label: status.name })),
    );
  }

  /** Priorities, lowest first, each with its colour mark; translated again when the language changes. */
  priorityOptions(): SMTSelectOption<string>[] {
    return this.priorityMemo([this.i18n.currentLang()], () => [
      { id: 'low', label: this.i18n.translate('task.priority.low'), icon: 'flag', color: 'var(--success-text)' },
      { id: 'medium', label: this.i18n.translate('tasks.common.medium'), icon: 'flag', color: 'var(--info-text)' },
      { id: 'high', label: this.i18n.translate('task.priority.high'), icon: 'flag', color: 'var(--warning-text)' },
      {
        id: 'critical',
        label: this.i18n.translate('tasks.common.critical'),
        icon: 'flag',
        color: 'var(--danger-text)',
      },
    ]);
  }

  onPriorityChange(taskId: number, priority: string | null): void {
    if (priority !== null) this.updatePriority.emit({ taskId, priority });
  }

  onStatusChange(taskId: number, statusCode: string | null): void {
    if (statusCode !== null) this.updateStatus.emit({ taskId, statusCode });
  }

  /**
   * One status or priority for every chosen task, as the record action `set_status` or a change of the priority of
   * each (ADR-0032 6.7); the page reloads with what the server now holds.
   */
  applyBulk(action: 'status' | 'priority'): void {
    const tasks = this.selectedTasks();
    if (tasks.length === 0 || this.bulkBusy()) return;
    const params = action === 'status' ? { status: this.bulkStatusCode() } : { priority: this.bulkPriority() };
    this.bulkTitles = new Map(tasks.map((task) => [task.id, task.title]));
    this.bulkBusy.set(true);
    this.bulkAction.set(action);
    this.tasksApi
      .bulk(
        action === 'status' ? SET_STATUS : 'update',
        tasks.map((task) => task.id),
        params,
      )
      .subscribe({
        next: (result) => {
          this.bulkBusy.set(false);
          this.bulkAction.set(null);
          this.bulkStatusCode.set(null);
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
