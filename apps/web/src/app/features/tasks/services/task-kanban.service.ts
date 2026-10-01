import { Injectable, inject, WritableSignal } from '@angular/core';
import { CdkDragDrop } from '@angular/cdk/drag-drop';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { I18nService } from '@core/services/i18n.service';
import { Task, TaskStatus } from '@core/models/task.models';
import { safeNumericRecordId } from '@core/services/search-target';
import { SaveErrorNotifier } from '@shared/ui/save-errors';

@Injectable({
  providedIn: 'root',
})
export class TaskKanbanService {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly saveErrors = inject(SaveErrorNotifier);

  draggedTask: Task | null = null;

  onTaskDrop(
    event: CdkDragDrop<Task[]>,
    targetStatusId: number,
    canUpdate: boolean,
    onStatusChange: (task: Task, targetStatusId: number) => void,
  ): void {
    if (!canUpdate) return;
    const task = event.item.data as Task;
    if (!task || task.statusId === targetStatusId) return;
    onStatusChange(task, targetStatusId);
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
    targetStatusId: number,
    canUpdate: boolean,
    onStatusChange: (task: Task, targetStatusId: number) => void,
  ): void {
    if (!canUpdate) return;
    event.preventDefault();
    const col = event.currentTarget as HTMLElement;
    if (col) {
      col.classList.remove('drag-over');
    }

    const task = this.draggedTask;
    this.draggedTask = null;
    if (!task || task.statusId === targetStatusId) return;

    onStatusChange(task, targetStatusId);
  }

  getTasksByStatus(statusId: number, tasks: Task[]): Task[] {
    return tasks.filter((t) => t.statusId === statusId);
  }

  isFirstStatus(statusId: number, statuses: TaskStatus[]): boolean {
    return statuses.length > 0 && statuses[0].id === statusId;
  }

  isLastStatus(statusId: number, statuses: TaskStatus[]): boolean {
    return statuses.length > 0 && statuses[statuses.length - 1].id === statusId;
  }

  moveTaskStatus(
    task: Task,
    direction: -1 | 1,
    statuses: TaskStatus[],
    canUpdate: boolean,
    onUpdateStatus: (taskId: number, targetStatusId: number) => void,
  ): void {
    if (!canUpdate) return;
    const currentIndex = statuses.findIndex((s) => s.id === task.statusId);
    if (currentIndex === -1) return;

    const targetIndex = currentIndex + direction;
    if (targetIndex >= 0 && targetIndex < statuses.length) {
      const targetStatus = statuses[targetIndex];
      onUpdateStatus(task.id, targetStatus.id);
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
    this.api
      .patch(`/tasks/${taskId}`, { priority: newPriority, expectedRevision: revision }, { notifyError: false })
      .subscribe({
        next: () => {
          this.toast.success(this.uiI18n.translate('tasks.priority_updated'));
          onLocalUpdate();
        },
        error: (err: unknown) => {
          this.saveErrors.show(err, { fallbackKey: 'tasks.ne_udalos_izmenit_prioritet', reload: onReload });
        },
      });
  }

  updateStatus(
    taskId: number,
    newStatusId: number,
    revision: number | undefined,
    onApplyVisible: () => void,
    onUpdateSelected: () => void,
    onReload: () => void,
  ): void {
    if (!safeNumericRecordId(taskId) || !safeNumericRecordId(newStatusId)) return;
    this.api
      .post(`/tasks/${taskId}/status`, { statusId: newStatusId, expectedRevision: revision }, { notifyError: false })
      .subscribe({
        next: () => {
          this.toast.success(this.uiI18n.translate('tasks.status_zadachi_obnovlen'));
          onApplyVisible();
          onUpdateSelected();
        },
        error: (err: unknown) => {
          this.saveErrors.show(err, { fallbackKey: 'tasks.ne_udalos_izmenit_status', reload: onReload });
        },
      });
  }

  executeStatusChange(
    task: Task,
    targetStatusId: number,
    statusName: string,
    onApplyVisible: () => void,
    onUpdateSelected: () => void,
    onErrorReload: () => void,
    onSaved: () => void = () => {},
  ): void {
    if (!safeNumericRecordId(task.id) || !safeNumericRecordId(targetStatusId)) return;
    onApplyVisible();
    onUpdateSelected();

    const body = { statusId: targetStatusId, expectedRevision: task.revision };
    this.api.post(`/tasks/${task.id}/status`, body, { notifyError: false }).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('tasks.task_moved_to_status', { id: task.id, status: statusName }));
        onSaved();
      },
      error: (err: unknown) => {
        // The card was moved before the answer: the tasks are read again at once, so no button is offered.
        this.saveErrors.show(err, { fallbackKey: 'tasks.ne_udalos_izmenit_status_zadachi' });
        onErrorReload();
      },
    });
  }

  applyStatusToVisibleTasks(
    taskId: number,
    statusId: number,
    statuses: TaskStatus[],
    statusFilterMode: 'active' | 'all' | number,
    tasks: WritableSignal<Task[]>,
  ): void {
    const targetStatus = statuses.find((status) => status.id === statusId);
    const leavesCurrentFilter =
      (statusFilterMode === 'active' && targetStatus?.isTerminal === true) ||
      (typeof statusFilterMode === 'number' && statusFilterMode !== statusId);
    tasks.update((list) =>
      leavesCurrentFilter
        ? list.filter((task) => task.id !== taskId)
        : list.map((task) => (task.id === taskId ? { ...task, statusId } : task)),
    );
  }
}
