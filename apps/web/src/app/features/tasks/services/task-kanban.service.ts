import { Injectable, inject, WritableSignal } from '@angular/core';
import { CdkDragDrop } from '@angular/cdk/drag-drop';
import { ToastService } from '@core/services/toast.service';
import { I18nService } from '@core/services/i18n.service';
import { Task, TaskStatus } from '@core/models/task.models';
import { safeNumericRecordId } from '@core/services/search-target';
import { SaveErrorNotifier } from '@shared/ui/save-errors';
import { TasksApi } from '../tasks.api';
import { TaskStatusFilter, statusFilterCode } from '../tasks.models';

@Injectable({
  providedIn: 'root',
})
export class TaskKanbanService {
  private readonly tasksApi = inject(TasksApi);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly saveErrors = inject(SaveErrorNotifier);

  draggedTask: Task | null = null;

  onTaskDrop(
    event: CdkDragDrop<Task[]>,
    targetStatusCode: string,
    canUpdate: boolean,
    onStatusChange: (task: Task, targetStatusCode: string) => void,
  ): void {
    if (!canUpdate) return;
    const task = event.item.data as Task;
    if (!task || task.statusCode === targetStatusCode) return;
    onStatusChange(task, targetStatusCode);
  }

  onHtml5DragStart(event: DragEvent, task: Task, canUpdate: boolean): void {
    if (!canUpdate) {
      event.preventDefault();
      return;
    }
    this.draggedTask = task;
    if (event.dataTransfer) {
      event.dataTransfer.setData('text/plain', String(task.id));
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  onHtml5DragOver(event: DragEvent, canUpdate: boolean): void {
    if (!canUpdate) return;
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'move';
    }
    const col = event.currentTarget as HTMLElement;
    if (col && !col.classList.contains('drag-over')) {
      col.classList.add('drag-over');
    }
  }

  onHtml5DragLeave(event: DragEvent, canUpdate: boolean): void {
    if (!canUpdate) return;
    const col = event.currentTarget as HTMLElement;
    if (col) {
      col.classList.remove('drag-over');
    }
  }

  onHtml5Drop(
    event: DragEvent,
    targetStatusCode: string,
    canUpdate: boolean,
    onStatusChange: (task: Task, targetStatusCode: string) => void,
  ): void {
    if (!canUpdate) return;
    event.preventDefault();
    const col = event.currentTarget as HTMLElement;
    if (col) {
      col.classList.remove('drag-over');
    }

    const task = this.draggedTask;
    this.draggedTask = null;
    if (!task || task.statusCode === targetStatusCode) return;

    onStatusChange(task, targetStatusCode);
  }

  getTasksByStatus(statusCode: string, tasks: Task[]): Task[] {
    return tasks.filter((t) => t.statusCode === statusCode);
  }

  isFirstStatus(statusCode: string, statuses: TaskStatus[]): boolean {
    return statuses.length > 0 && statuses[0].code === statusCode;
  }

  isLastStatus(statusCode: string, statuses: TaskStatus[]): boolean {
    return statuses.length > 0 && statuses[statuses.length - 1].code === statusCode;
  }

  moveTaskStatus(
    task: Task,
    direction: -1 | 1,
    statuses: TaskStatus[],
    canUpdate: boolean,
    onUpdateStatus: (taskId: number, targetStatusCode: string) => void,
  ): void {
    if (!canUpdate) return;
    const currentIndex = statuses.findIndex((s) => s.code === task.statusCode);
    if (currentIndex === -1) return;

    const targetIndex = currentIndex + direction;
    if (targetIndex >= 0 && targetIndex < statuses.length) {
      const targetStatus = statuses[targetIndex];
      onUpdateStatus(task.id, targetStatus.code);
    }
  }

  /**
   * Each change names the revision it was made from (plan item 3.6); a stale one is refused with 409, shown once with
   * a button that reads the tasks again (`onReload`).
   */
  updatePriority(
    taskId: number,
    newPriority: string,
    revision: number | undefined,
    onLocalUpdate: () => void,
    onReload: () => void,
  ): void {
    if (!safeNumericRecordId(taskId) || !newPriority) return;
    this.tasksApi.patch(taskId, { priority: newPriority }, revision).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('tasks.priority_updated'));
        onLocalUpdate();
      },
      error: (err: unknown) => {
        this.saveErrors.show(err, { fallbackKey: 'tasks.kanban.priority_change_failed', reload: onReload });
      },
    });
  }

  updateStatus(
    taskId: number,
    newStatusCode: string,
    revision: number | undefined,
    onApplyVisible: () => void,
    onUpdateSelected: () => void,
    onReload: () => void,
  ): void {
    if (!safeNumericRecordId(taskId) || !newStatusCode) return;
    this.tasksApi.setStatus(taskId, newStatusCode, revision).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('tasks.kanban.task_status_updated'));
        onApplyVisible();
        onUpdateSelected();
      },
      error: (err: unknown) => {
        this.saveErrors.show(err, { fallbackKey: 'tasks.kanban.status_change_failed', reload: onReload });
      },
    });
  }

  executeStatusChange(
    task: Task,
    targetStatusCode: string,
    statusName: string,
    onApplyVisible: () => void,
    onUpdateSelected: () => void,
    onErrorReload: () => void,
    onSaved: () => void = () => {},
  ): void {
    if (!safeNumericRecordId(task.id) || !targetStatusCode) return;
    onApplyVisible();
    onUpdateSelected();

    this.tasksApi.setStatus(task.id, targetStatusCode, task.revision).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('tasks.task_moved_to_status', { id: task.id, status: statusName }));
        onSaved();
      },
      error: (err: unknown) => {
        // The card was moved before the answer: the tasks are read again at once, so no button is offered.
        this.saveErrors.show(err, { fallbackKey: 'tasks.kanban.task_status_change_failed' });
        onErrorReload();
      },
    });
  }

  applyStatusToVisibleTasks(
    taskId: number,
    statusCode: string,
    statuses: TaskStatus[],
    statusFilterMode: TaskStatusFilter,
    tasks: WritableSignal<Task[]>,
  ): void {
    const targetStatus = statuses.find((status) => status.code === statusCode);
    const shownStatus = statusFilterCode(statusFilterMode);
    const leavesCurrentFilter =
      (statusFilterMode === 'active' && targetStatus?.terminal === true) ||
      (shownStatus !== null && shownStatus !== statusCode);
    tasks.update((list) =>
      leavesCurrentFilter
        ? list.filter((task) => task.id !== taskId)
        : list.map((task) => (task.id === taskId ? { ...task, statusCode } : task)),
    );
  }
}
