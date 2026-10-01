import { Injectable, inject } from '@angular/core';
import { Task } from '@core/models/task.models';
import { I18nService } from '@core/services/i18n.service';
import { optionsMemo, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import {
  getDeadlineInfo,
  getPriorityLabel,
  getProjectName,
  getStatusColor,
  getStatusName,
  getTypeBg,
  getTypeColor,
  getTypeIcon,
  getTypeLabel,
  isOverdue,
  TaskProjectRef,
} from '../tasks.models';
import { TaskDictionariesService } from './task-dictionaries.service';

/**
 * How the task screen names and colours a row: its type, status, project,
 * priority and deadline. Provided by the screen; a row names its own project.
 */
@Injectable()
export class TaskPresenter {
  private readonly dictionaries = inject(TaskDictionariesService);
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly i18n = inject(I18nService);

  // Stable function inputs for the child views, so they are not re-rendered on every check.
  readonly getDeadlineInfoFn = (e: string | null | undefined, id: number) => this.getDeadlineInfo(e, id);
  readonly getPriorityLabelFn = (p: string) => this.getPriorityLabel(p);
  readonly getTypeColorFn = (t: Task) => this.getTypeColor(t);
  readonly getTypeIconFn = (t: Task) => this.getTypeIcon(t);
  readonly getProjectNameFn = (task: TaskProjectRef | null | undefined) => this.getProjectName(task);
  readonly isOverdueFn = (e: string | null | undefined, id: number) => this.isOverdue(e, id);
  readonly getTypeLabelFn = (t: Task) => this.getTypeLabel(t);
  readonly getTypeBgFn = (t: Task) => this.getTypeBg(t);
  readonly getStatusColorFn = (id: number | null | undefined) => this.getStatusColor(id);
  readonly getStatusNameFn = (id: number | null | undefined) => this.getStatusName(id);

  private readonly viewMemo = optionsMemo<SMTRadioOption<'table' | 'kanban'>[]>();

  getTypeLabel(task: Task) {
    return getTypeLabel(task, this.dictionaries.taskTypes(), this.i18n);
  }
  getTypeIcon(task: Task) {
    return getTypeIcon(task, this.dictionaries.taskTypes());
  }
  getTypeColor(task: Task) {
    return getTypeColor(task, this.dictionaries.taskTypes());
  }
  getTypeBg(task: Task) {
    return getTypeBg(task, this.dictionaries.taskTypes());
  }
  /** The task's own project name (plan 10/10, item 3.5): no list of projects is read for it. */
  getProjectName(task: TaskProjectRef | null | undefined) {
    return getProjectName(task);
  }
  getStatusName(statusId: number | null | undefined) {
    return getStatusName(statusId, this.dictionaries.statuses(), this.i18n);
  }
  getStatusColor(statusId: number | null | undefined) {
    return getStatusColor(statusId, this.dictionaries.statuses());
  }
  getPriorityLabel(priority: string) {
    return getPriorityLabel(priority, this.i18n);
  }
  isOverdue(endTime: string | null | undefined, statusId: number) {
    return isOverdue(endTime, statusId, this.dictionaries.statuses());
  }
  getDeadlineInfo(endTime: string | null | undefined, statusId: number) {
    return getDeadlineInfo(endTime, statusId, this.dictionaries.statuses(), this.i18n);
  }

  viewOptions(): SMTRadioOption<'table' | 'kanban'>[] {
    return this.viewMemo([this.i18n.currentLang()], () => [
      {
        value: 'table',
        label: this.i18n.translate('projects.spisok'),
        icon: 'table_rows',
        title: this.i18n.translate('tasks.tablichnyy_vid'),
      },
      {
        value: 'kanban',
        label: this.i18n.translate('tasks.kanban'),
        icon: 'view_kanban',
        title: this.i18n.translate('tasks.kanban_doska'),
      },
    ]);
  }
}
