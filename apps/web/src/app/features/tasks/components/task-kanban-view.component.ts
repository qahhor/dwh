import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { DragDropModule, CdkDragDrop } from '@angular/cdk/drag-drop';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { Task, Project, TaskStatus, TaskType } from '@core/models/task.models';

@Component({
  selector: 'app-task-kanban-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DragDropModule, TranslatePipe, SMTButtonComponent, DatePipe],
  templateUrl: './task-kanban-view.component.html',
  styleUrl: './task-kanban-view.component.css',
})
export class TaskKanbanViewComponent {
  readonly getDeadlineInfo = input.required<
    (
      endTime: string | null | undefined,
      statusId: number,
    ) => {
      state: string;
      label: string;
    }
  >();
  readonly getPriorityLabel = input.required<(priority: string) => string>();
  readonly getTypeColor = input.required<(task: Task) => string>();
  readonly getTypeIcon = input.required<(task: Task) => string>();
  readonly getTypeLabel = input.required<(task: Task) => string>();
  readonly getProjectName = input.required<(projectId: number | null | undefined) => string | null>();
  readonly isOverdue = input.required<(endTime: string | null | undefined, statusId: number) => boolean>();

  readonly tasks = input<Task[]>([]);
  readonly statuses = input<TaskStatus[]>([]);
  readonly projects = input<Project[]>([]);
  readonly taskTypes = input<TaskType[]>([]);
  readonly canCreateTask = input(false);
  readonly canUpdateTask = input(false);
  readonly hasActiveFilters = input(false);
  readonly isLoading = input(false);
  readonly listLoadError = input(false);

  readonly openTaskDetails = output<Task>();
  readonly taskStatusChange = output<{
    task: Task;
    targetStatusId: number;
  }>();
  readonly taskDragStart = output<Task>();
  readonly taskDragEnd = output<void>();
  readonly resetFilters = output<void>();
  readonly createTask = output<void>();

  private draggedTask: Task | null = null;

  getTasksByStatus(statusId: number): Task[] {
    return this.tasks().filter((t) => t.statusId === statusId);
  }

  isFirstStatus(statusId: number): boolean {
    return this.statuses().length > 0 && this.statuses()[0].id === statusId;
  }

  isLastStatus(statusId: number): boolean {
    return this.statuses().length > 0 && this.statuses()[this.statuses().length - 1].id === statusId;
  }

  moveTaskStatus(task: Task, direction: -1 | 1) {
    if (!this.canUpdateTask()) return;
    const currentIndex = this.statuses().findIndex((s) => s.id === task.statusId);
    if (currentIndex === -1) return;

    const targetIndex = currentIndex + direction;
    if (targetIndex >= 0 && targetIndex < this.statuses().length) {
      const targetStatus = this.statuses()[targetIndex];
      this.taskStatusChange.emit({ task, targetStatusId: targetStatus.id });
    }
  }

  onTaskDrop(event: CdkDragDrop<Task[]>, targetStatusId: number) {
    if (!this.canUpdateTask()) return;
    const task = event.item.data as Task;
    if (!task || task.statusId === targetStatusId) return;
    this.taskStatusChange.emit({ task, targetStatusId });
  }

  onHtml5DragStart(event: DragEvent, task: Task) {
    if (!this.canUpdateTask()) {
      event.preventDefault();
      return;
    }
    this.draggedTask = task;
    this.taskDragStart.emit(task);
    if (event.dataTransfer) {
      event.dataTransfer.setData('text/plain', String(task.id));
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  onHtml5DragEnd() {
    this.draggedTask = null;
    this.taskDragEnd.emit();
  }

  onHtml5DragOver(event: DragEvent) {
    if (!this.canUpdateTask()) return;
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'move';
    }
    const col = event.currentTarget as HTMLElement;
    if (col && !col.classList.contains('drag-over')) {
      col.classList.add('drag-over');
    }
  }

  onHtml5DragLeave(event: DragEvent) {
    if (!this.canUpdateTask()) return;
    const col = event.currentTarget as HTMLElement;
    if (col) {
      col.classList.remove('drag-over');
    }
  }

  onHtml5Drop(event: DragEvent, targetStatusId: number) {
    if (!this.canUpdateTask()) return;
    event.preventDefault();
    const col = event.currentTarget as HTMLElement;
    if (col) {
      col.classList.remove('drag-over');
    }

    const task = this.draggedTask;
    this.draggedTask = null;
    if (!task || task.statusId === targetStatusId) return;

    this.taskStatusChange.emit({ task, targetStatusId });
  }

  onTaskContainerClick(event: MouseEvent, task: Task) {
    const target = event.target as HTMLElement;
    if (
      target.closest('button') ||
      target.closest('select') ||
      target.closest('a') ||
      target.closest('.kanban-move-actions')
    ) {
      return;
    }
    this.openTaskDetails.emit(task);
  }
}
