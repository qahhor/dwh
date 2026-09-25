import { Component, computed, EventEmitter, inject, Input, Output, Signal, signal, TemplateRef, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiServerTableComponent } from '../../../shared/ui/ui-server-table.component';
import { UiBulkResultComponent } from '../../../shared/ui/ui-bulk-result.component';
import { BulkResult } from '../../../shared/bulk/bulk';
import { ApiService } from '../../../core/services/api.service';
import { ToastService } from '../../../core/services/toast.service';
import { KeysetPager } from '../../../shared/paging/keyset-pager';
import { TableConfig } from '../../../shared/ui-kit/components/table/table.types';
import { Task, Project, TaskStatus, TaskType } from '../../../core/models/task.models';

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
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
    UiButtonComponent,
    UiServerTableComponent,
    UiBulkResultComponent
  ],
  template: `
    <div class="table-card" role="region" [attr.aria-label]="'tasks.tablica_zadach' | t" [attr.aria-busy]="pager.loading()">
      <ui-server-table
        [pager]="pager"
        [config]="tableConfig()"
        [loadingLabel]="'tasks.list_loading' | t"
        [errorLabel]="(pager.items().length ? 'tasks.list_load_error_stale' : 'tasks.list_load_error') | t"
        errorId="tasks-load-error"
        [countsPage]="true"
        [emptyTemplate]="emptyState()"
        [selectable]="canUpdateTask"
        [(selected)]="selectedTasks"
        (rowClick)="openTaskDetails.emit($event)">
        <div bulkActions class="bulk-actions">
          <label class="bulk-field">
            <span class="bulk-label">{{ 'tasks.bulk.status' | t }}</span>
            <select class="form-select bulk-select" data-testid="bulk-status" [disabled]="bulkBusy()"
              [value]="bulkStatusId()" (change)="bulkStatusId.set($any($event.target).value)">
              <option value="">{{ 'tasks.bulk.choose' | t }}</option>
              @for (s of statuses; track s.id) { <option [value]="s.id">{{ s.name }}</option> }
            </select>
          </label>
          <ui-button variant="secondary" size="sm" data-testid="bulk-status-apply" [disabled]="!bulkStatusId() || bulkBusy()"
            [loading]="bulkBusy() && bulkAction() === 'status'" (onClick)="applyBulk('status')">{{ 'tasks.bulk.apply' | t }}</ui-button>
          <label class="bulk-field">
            <span class="bulk-label">{{ 'common.priority' | t }}</span>
            <select class="form-select bulk-select" data-testid="bulk-priority" [disabled]="bulkBusy()"
              [value]="bulkPriority()" (change)="bulkPriority.set($any($event.target).value)">
              <option value="">{{ 'tasks.bulk.choose' | t }}</option>
              <option value="low">{{ 'task.priority.low' | t }}</option>
              <option value="medium">{{ 'tasks.sredniy' | t }}</option>
              <option value="high">{{ 'task.priority.high' | t }}</option>
              <option value="critical">{{ 'tasks.kriticheskiy' | t }}</option>
            </select>
          </label>
          <ui-button variant="secondary" size="sm" data-testid="bulk-priority-apply" [disabled]="!bulkPriority() || bulkBusy()"
            [loading]="bulkBusy() && bulkAction() === 'priority'" (onClick)="applyBulk('priority')">{{ 'tasks.bulk.apply' | t }}</ui-button>
        </div>
      </ui-server-table>
      <ui-bulk-result [result]="bulkResult()" [itemLabel]="bulkItemLabel" (closed)="bulkResult.set(null)" />
    </div>

    <ng-template #idCell let-t>
      <span class="tabular-nums font-mono" [class.text-danger]="isOverdue(t.endTime, t.statusId)" [class.text-muted]="!isOverdue(t.endTime, t.statusId)">#{{ t.id }}</span>
    </ng-template>
    <ng-template #typeCell let-t>
      <span class="task-type-badge" [style.color]="getTypeColor(t)" [style.background-color]="getTypeBg(t)">
        <span class="material-symbols-outlined type-icon" aria-hidden="true">{{ getTypeIcon(t) }}</span>
        {{ getTypeLabel(t) }}
      </span>
    </ng-template>
    <ng-template #titleCell let-t>
      <div class="task-title-cell">
        <button
          type="button"
          class="task-title task-title-open"
          [class.title-overdue]="isOverdue(t.endTime, t.statusId)"
          [attr.aria-label]="'tasks.open_task_named' | t:{id: t.id, title: t.title}"
          (click)="openTaskDetails.emit(t)"
        >{{ t.title }}</button>
        @if (isOverdue(t.endTime, t.statusId)) { <span class="overdue-tag">{{ 'tasks.prosrocheno' | t }}</span> }
        @if (t.parentTaskId) {
          <span class="parent-chip font-mono" [title]="'task.parent' | t">{{ 'tasks.subtask_number' | t:{id: t.parentTaskId} }}</span>
        }
      </div>
    </ng-template>
    <ng-template #projectCell let-t>
      @let projectName = getProjectName(t.projectId);
      @if (projectName) {
        <span class="project-tag">
          <span class="material-symbols-outlined folder-ico" aria-hidden="true">folder</span>
          {{ projectName }}
        </span>
      } @else if (!t.projectId) {
        <span class="text-muted">—</span>
      }
    </ng-template>
    <ng-template #priorityCell let-t>
      <select
        class="inline-priority-select"
        [attr.data-priority]="t.priority"
        [ngModel]="t.priority"
        (ngModelChange)="updatePriority.emit({ taskId: t.id, priority: $event })"
        [disabled]="!canUpdateTask"
        [attr.aria-label]="'common.priority' | t"
        [title]="'common.priority' | t"
      >
        <option value="low">{{ 'task.priority.low' | t }}</option>
        <option value="medium">{{ 'tasks.sredniy' | t }}</option>
        <option value="high">{{ 'task.priority.high' | t }}</option>
        <option value="critical">{{ 'tasks.kriticheskiy' | t }}</option>
      </select>
    </ng-template>
    <ng-template #statusCell let-t>
      <div class="inline-status-wrapper table-status">
        <span class="status-dot" [style.background-color]="getStatusColor(t.statusId)" aria-hidden="true"></span>
        <select
          class="inline-status-select"
          [ngModel]="t.statusId"
          (ngModelChange)="updateStatus.emit({ taskId: t.id, statusId: $event })"
          [disabled]="!canUpdateTask"
          [attr.aria-label]="'tasks.task_status_aria' | t:{id: t.id}"
          [title]="'tasks.nazhmite_dlya_smeny_statusa' | t"
        >
          @for (s of statuses; track s.id) { <option [ngValue]="s.id">{{ s.name }}</option> }
        </select>
      </div>
    </ng-template>
    <ng-template #deadlineCell let-t>
      @let dl = getDeadlineInfo(t.endTime, t.statusId);
      @if (dl.state !== 'none') {
        <span
          class="deadline-pill"
          [class.overdue]="dl.state === 'overdue'"
          [class.deadline-today]="dl.state === 'today'"
          [class.deadline-tomorrow]="dl.state === 'tomorrow'"
          [title]="'tasks.deadline_value' | t:{date: (t.endTime | date:'dd.MM.yyyy HH:mm') || ''}"
        >
          <span class="material-symbols-outlined ico" aria-hidden="true">{{ dl.state === 'overdue' ? 'warning' : (dl.state === 'today' ? 'alarm' : 'event') }}</span>
          {{ dl.label }}
        </span>
      } @else {
        <span class="text-muted">—</span>
      }
    </ng-template>
    <ng-template #actionsCell let-t>
      <div class="row-action-btns">
        @if (canUpdateTask) {
          <button
            type="button"
            class="icon-ghost-btn"
            [attr.aria-label]="'tasks.edit_task_number' | t:{id: t.id}"
            [title]="'tasks.redaktirovat_zadachu' | t"
            (click)="openEditModal.emit(t)"
          >
            <span class="material-symbols-outlined" aria-hidden="true">edit</span>
          </button>
        }
        <button
          type="button"
          class="icon-ghost-btn"
          [attr.aria-label]="'tasks.view_task_number' | t:{id: t.id}"
          [title]="'tasks.prosmotret_detali' | t"
          (click)="openTaskDetails.emit(t)"
        >
          <span class="material-symbols-outlined" aria-hidden="true">visibility</span>
        </button>
      </div>
    </ng-template>
    <ng-template #emptyStateTpl>
      <div class="empty-state-cell">
        <span class="material-symbols-outlined icon" aria-hidden="true">task</span>
        <p>{{ 'tasks.zadachi_ne_naydeny' | t }}</p>
        @if (hasActiveFilters) {
          <ui-button variant="secondary" size="sm" (onClick)="resetFilters.emit()">{{ 'tasks.sbrosit_vse_filtry' | t }}</ui-button>
        } @else if (canCreateTask) {
          <ui-button variant="primary" size="sm" icon="add" (onClick)="createTask.emit()">{{ 'task.new' | t }}</ui-button>
        }
      </div>
    </ng-template>
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .bulk-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .bulk-field { display: inline-flex; align-items: center; gap: 6px; }
    .bulk-label { color: var(--text-muted); font-size: 12px; }
    .bulk-select { min-width: 150px; height: 30px; font-size: 13px; }
    .table-card { min-width: 0; }
    /* The row belongs to the kit table's template, so it is reached from here.
       An inset shadow marks it without widening the row's grid. */
    :host ::ng-deep .smt-data-row.task-row-overdue { background-color: rgba(239, 68, 68, 0.04); box-shadow: inset 3px 0 0 var(--danger); }
    :host ::ng-deep .smt-data-row.task-row-overdue:hover { background-color: rgba(239, 68, 68, 0.08); }
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
    .task-type-badge .type-icon { font-size: 13px; }

    .task-title-cell {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
    }
    .task-title { font-weight: 500; }
    .task-title-open {
      border: 0;
      padding: 0;
      background: transparent;
      color: var(--text-main);
      font: inherit;
      text-align: left;
      cursor: pointer;
    }
    .task-title-open:hover { color: var(--primary); text-decoration: underline; }
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
    .folder-ico { font-size: 14px; color: var(--warning); }

    /* Inline Priority Select */
    .inline-priority-select {
      border: 1px solid transparent;
      border-radius: 10px;
      padding: 2px 6px;
      font-size: 11px;
      font-weight: 600;
      cursor: pointer;
      background-color: var(--bg-hover);
      color: var(--text-muted);
      outline: none;
      transition: all 0.12s ease;
      font-family: inherit;
    }
    .inline-priority-select:hover:not(:disabled) { border-color: var(--border-color); }
    .inline-priority-select[data-priority="low"] {
      background-color: rgba(16, 185, 129, 0.12);
      color: var(--success-text);
    }
    .inline-priority-select[data-priority="medium"],
    .inline-priority-select[data-priority="normal"] {
      background-color: rgba(59, 130, 246, 0.12);
      color: var(--info-text);
    }
    .inline-priority-select[data-priority="high"] {
      background-color: rgba(245, 158, 11, 0.15);
      color: var(--warning-text);
    }
    .inline-priority-select[data-priority="critical"],
    .inline-priority-select[data-priority="urgent"] {
      background-color: rgba(239, 68, 68, 0.15);
      color: var(--danger-text);
    }
    .inline-priority-select:disabled { cursor: default; }

    /* Inline Status Select */
    .inline-status-wrapper { display: inline-flex; align-items: center; gap: 5px; }
    .inline-status-select {
      height: 26px;
      padding: 1px 6px;
      font-size: 11px;
      font-weight: 500;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      cursor: pointer;
      outline: none;
    }
    .inline-status-select:focus { border-color: var(--primary); }

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
    .deadline-pill .ico { font-size: 13px; }
    .deadline-pill.overdue {
      color: var(--danger-text);
      background-color: var(--danger-bg);
      border-color: rgba(239,68,68,0.3);
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

    .row-action-btns { display: inline-flex; gap: 4px; }
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
    .icon-ghost-btn:hover { color: var(--text-main); background-color: var(--bg-hover); }
    .icon-ghost-btn .material-symbols-outlined { font-size: 17px; }

    .empty-state-cell {
      padding: 40px;
      text-align: center;
      color: var(--text-muted);
    }
    .empty-state-cell .icon { font-size: 36px; color: var(--text-light); margin-bottom: 6px; }
    .font-mono { font-family: ui-monospace, monospace; }
    .tabular-nums { font-variant-numeric: tabular-nums; }
    .text-danger { color: var(--danger); }
    .text-muted { color: var(--text-muted); }
    .text-right { text-align: right; }
  `]
})
export class TaskTableViewComponent {
  @Input({ required: true }) pager!: KeysetPager<Task>;
  @Input() statuses: TaskStatus[] = [];
  @Input() projects: Project[] = [];
  @Input() taskTypes: TaskType[] = [];
  @Input() canCreateTask = false;
  @Input() canUpdateTask = false;
  @Input() hasActiveFilters = false;

  @Input() isOverdue!: (endTime: string | null | undefined, statusId: number) => boolean;
  @Input() getTypeColor!: (task: Task) => string;
  @Input() getTypeBg!: (task: Task) => string;
  @Input() getTypeIcon!: (task: Task) => string;
  @Input() getTypeLabel!: (task: Task) => string;
  @Input() getProjectName!: (projectId: number | null | undefined) => string | null;
  @Input() getStatusColor!: (statusId: number | null | undefined) => string;
  @Input() getDeadlineInfo!: (endTime: string | null | undefined, statusId: number) => { state: string; label: string };

  @Output() openTaskDetails = new EventEmitter<Task>();
  @Output() openEditModal = new EventEmitter<Task>();
  @Output() updatePriority = new EventEmitter<{ taskId: number; priority: string }>();
  @Output() updateStatus = new EventEmitter<{ taskId: number; statusId: number }>();
  @Output() resetFilters = new EventEmitter<void>();
  @Output() createTask = new EventEmitter<void>();

  private readonly i18n = inject(I18nService);
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);

  /** Rows chosen on the page on screen; the table clears them when the page changes. */
  readonly selectedTasks = signal<Task[]>([]);
  readonly bulkStatusId = signal('');
  readonly bulkPriority = signal('');
  readonly bulkBusy = signal(false);
  readonly bulkAction = signal<'status' | 'priority' | null>(null);
  /** Set when some tasks failed; the dialog names them. */
  readonly bulkResult = signal<BulkResult | null>(null);
  /** Titles of the tasks sent, so the result can name a task after the page reloads. */
  private bulkTitles = new Map<number, string>();
  readonly bulkItemLabel = (id: number) => {
    const title = this.bulkTitles.get(id);
    return title ? `#${id} ${title}` : `#${id}`;
  };

  /** One status or priority for every chosen task; the page reloads with what the server now holds. */
  applyBulk(action: 'status' | 'priority'): void {
    const tasks = this.selectedTasks();
    if (tasks.length === 0 || this.bulkBusy()) return;
    const params = action === 'status' ? { statusId: Number(this.bulkStatusId()) } : { priority: this.bulkPriority() };
    this.bulkTitles = new Map(tasks.map(task => [task.id, task.title]));
    this.bulkBusy.set(true);
    this.bulkAction.set(action);
    this.api.post<BulkResult>('/tasks/bulk', { action, ids: tasks.map(task => task.id), params }, { notifyError: false })
      .subscribe({
        next: result => {
          this.bulkBusy.set(false);
          this.bulkAction.set(null);
          this.bulkStatusId.set('');
          this.bulkPriority.set('');
          if (result.succeeded > 0) {
            this.toast.success(this.i18n.translate('tasks.bulk.done', { count: result.succeeded }));
          }
          if (result.failed > 0) this.bulkResult.set(result);
          this.pager.reload();
        },
        error: () => {
          this.bulkBusy.set(false);
          this.bulkAction.set(null);
          this.toast.error(this.i18n.translate('tasks.bulk.error'));
        }
      });
  }
  private readonly idCell = viewChild.required<TemplateRef<unknown>>('idCell');
  private readonly typeCell = viewChild.required<TemplateRef<unknown>>('typeCell');
  private readonly titleCell = viewChild.required<TemplateRef<unknown>>('titleCell');
  private readonly projectCell = viewChild.required<TemplateRef<unknown>>('projectCell');
  private readonly priorityCell = viewChild.required<TemplateRef<unknown>>('priorityCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('statusCell');
  private readonly deadlineCell = viewChild.required<TemplateRef<unknown>>('deadlineCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');
  readonly emptyState = viewChild.required<TemplateRef<unknown>>('emptyStateTpl');

  readonly tableConfig = computed<TableConfig<Task>>(() => {
    const header = (value: string) => ({ type: 'primitive' as const, value });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    // Every row is its own grid, so tracks are fixed or shares of the width, never content-sized.
    const rest = '(100% - 750px)';
    return {
      trackBy: (_index, task) => task.id,
      layout: 'fit',
      ariaLabel: this.i18n.translate('tasks.spisok_zadach'),
      rowClass: task => this.isOverdue(task.endTime, task.statusId) ? 'task-row-overdue' : null,
      columnsOrder: ['id', 'type', 'title', 'project', 'priority', 'status', 'deadline', 'actions'],
      columns: {
        id: { header: header('ID'), content: cell(this.idCell), width: '70px' },
        type: { header: header(this.i18n.translate('settings.tip')), content: cell(this.typeCell), width: '120px' },
        title: { header: header(this.i18n.translate('tasks.zadacha')), content: cell(this.titleCell), width: `max(220px, calc(${rest} * 0.6))` },
        project: { header: header(this.i18n.translate('projects.proekt')), content: cell(this.projectCell), width: `max(140px, calc(${rest} * 0.4))` },
        priority: { header: header(this.i18n.translate('common.priority')), content: cell(this.priorityCell), width: '130px' },
        status: { header: header(this.i18n.translate('common.status')), content: cell(this.statusCell), width: '150px' },
        deadline: { header: header(this.i18n.translate('tasks.srok')), content: cell(this.deadlineCell), width: '180px' },
        actions: { header: header(this.i18n.translate('common.actions')), content: cell(this.actionsCell), width: '100px', align: 'right' },
      },
    };
  });
}
