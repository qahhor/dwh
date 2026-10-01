import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';

import { ActivatedRoute, Router } from '@angular/router';
import { canonicalRecordId, safeNumericRecordId } from '@core/services/search-target';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { CdkDragDrop } from '@angular/cdk/drag-drop';
import { PermissionService } from '@core/services/permission.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';

import { UiPaginationComponent } from '@shared/ui/ui-pagination.component';
import { Task, TaskFile } from '@core/models/task.models';

import { TranslatePipe } from '@core/services/i18n.service';
import { Subscription } from 'rxjs';
import { TaskDictionariesModalComponent } from './components/task-dictionaries-modal.component';
import { TaskKanbanViewComponent } from './components/task-kanban-view.component';
import { TaskTableViewComponent } from './components/task-table-view.component';
import { TaskFilterBarComponent } from './components/task-filter-bar.component';
import { TaskDetailModalComponent } from './components/task-detail-modal.component';
import { TaskCreateModalComponent } from './components/task-create-modal.component';
import { TaskEditModalComponent } from './components/task-edit-modal.component';
import { TaskDeadlineInfo, TaskCreateFormValue, TaskEditFormValue } from './tasks.models';
import { TaskDictionariesService } from './services/task-dictionaries.service';
import { TaskLookupsService } from './services/task-lookups.service';
import { TaskDetailsService } from './services/task-details.service';
import { TaskFormsService } from './services/task-forms.service';
import { TaskKanbanService } from './services/task-kanban.service';
import { TaskFilterService } from './services/task-filter.service';
import { TaskListStore } from './services/task-list.store';
import { TaskPresenter } from './services/task-presenter';
import { SMTRadioGroupComponent } from '@shared/ui-kit/components/forms/radio-group';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

export type { TaskDeadlineInfo, TaskCreateFormValue, TaskEditFormValue };

@Component({
  selector: 'app-tasks',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    UiPageHeaderComponent,
    SMTRadioGroupComponent,
    TranslatePipe,
    SMTButtonComponent,
    UiPaginationComponent,
    SMTAlertComponent,
    TaskDictionariesModalComponent,
    TaskKanbanViewComponent,
    TaskTableViewComponent,
    TaskFilterBarComponent,
    TaskDetailModalComponent,
    TaskCreateModalComponent,
    TaskEditModalComponent,
  ],
  // The list and its labels belong to this screen: a new visit starts a new pager.
  providers: [TaskListStore, TaskPresenter],
  templateUrl: './tasks.component.html',
  styleUrl: './tasks.component.css',
})
export class TasksComponent implements OnInit, OnDestroy {
  public readonly dictService = inject(TaskDictionariesService);
  public readonly lookupsService = inject(TaskLookupsService);
  public readonly detailsService = inject(TaskDetailsService);
  public readonly formsService = inject(TaskFormsService);
  public readonly kanbanService = inject(TaskKanbanService);
  public readonly filterService = inject(TaskFilterService);
  /** The list: metadata, views, pages, quick filters, projects and custom fields. */
  readonly list = inject(TaskListStore);
  /** Labels and colours of types, statuses, projects, priorities and deadlines. */
  readonly presenter = inject(TaskPresenter);
  readonly permService = inject(PermissionService);
  private readonly route = inject(ActivatedRoute);
  private readonly recordRouter = inject(Router, { optional: true });

  readonly routeRecordId = signal<string | null>(null);

  /** A user-typed custom field on the open card shows a name: unknown ones are asked for once. */
  private readonly userFieldNames = effect(() => {
    const attributes = this.selectedTask()?.attributes;
    if (!attributes) return;
    const ids = this.list
      .customFields()
      .filter((field) => field.fieldType === 'user_ref')
      .map((field) => Number(attributes[field.code]))
      .filter((id) => Number.isSafeInteger(id) && id > 0);
    untracked(() => this.lookupsService.resolveUserNames(ids));
  });

  /** The rows and the open card name their projects, so a project picker shows a chosen one without a request. */
  private readonly projectNames = effect(() => {
    const selected = this.detailsService.selectedTask();
    const rows = selected ? [...this.list.tasks(), selected] : this.list.tasks();
    untracked(() => this.lookupsService.retainTaskProjects(rows));
  });

  private recordRouteSubscription?: Subscription;
  private routeSubscription?: Subscription;

  /** Writable: kanban and inline edits update rows in place. */
  readonly tasks = this.list.tasks;
  readonly statuses = this.dictService.statuses;
  readonly taskTypes = this.dictService.taskTypes;

  readonly selectedTask = this.detailsService.selectedTask;
  readonly taskMembers = this.detailsService.taskMembers;
  readonly taskSubtasks = this.detailsService.taskSubtasks;
  readonly taskFiles = this.detailsService.taskFiles;
  readonly comments = this.detailsService.comments;
  readonly isCommentSubmitting = this.detailsService.isCommentSubmitting;

  readonly isEditModalOpen = this.formsService.isEditModalOpen;
  readonly isEditDiscardConfirmationOpen = this.formsService.isEditDiscardConfirmationOpen;

  detailRecordId(): string | null {
    return this.routeRecordId() ?? (this.selectedTask() ? String(this.selectedTask()!.id) : null);
  }

  // Kept on the page: the record-navigation tests reach them through it. The values live in root services.
  get searchQuery() {
    return this.filterService.searchQuery;
  }
  set searchQuery(v) {
    this.filterService.searchQuery = v;
  }
  get selectedPriority() {
    return this.filterService.selectedPriority;
  }
  set selectedPriority(v) {
    this.filterService.selectedPriority = v;
  }
  get commentDraft() {
    return this.detailsService.commentDraft;
  }
  set commentDraft(v: string) {
    this.detailsService.commentDraft = v;
  }
  get createForm(): TaskCreateFormValue {
    return this.formsService.createForm;
  }
  get editForm(): TaskEditFormValue {
    return this.formsService.editForm;
  }

  ngOnInit() {
    this.routeSubscription = this.route.queryParams.subscribe((params) => {
      if (params['project_id']) this.filterService.selectedProjectId = Number(params['project_id']);
    });
    this.dictService.loadStatuses();
    this.dictService.loadTypes();
    this.list.loadTasks(true);

    this.recordRouteSubscription = this.route.paramMap?.subscribe((params) => {
      this.detailsService.clearTaskDetails();
      const id = params.get('id');
      this.routeRecordId.set(id);
      if (id === null) return;
      if (!canonicalRecordId(id)) {
        this.detailsService.detailNotFound.set(true);
        this.detailsService.detailLoadError.set(true);
        return;
      }
      this.detailsService.loadTaskFullDetails(id, () => this.routeRecordId());
      this.detailsService.loadComments(id, () => this.routeRecordId());
    });
  }

  ngOnDestroy() {
    this.routeSubscription?.unsubscribe();
    this.recordRouteSubscription?.unsubscribe();
    this.filterService.cleanup();
    this.detailsService.cleanup();
    this.formsService.cleanup();
  }

  canCreateTask() {
    return (
      this.permService.canCreate('tasks.items') ||
      this.permService.canCreate('tasks') ||
      this.permService.canCreate('ms_tasks')
    );
  }
  canUpdateTask() {
    return (
      this.permService.canUpdate('tasks.items') ||
      this.permService.canUpdate('tasks') ||
      this.permService.canUpdate('ms_tasks')
    );
  }
  canCommentTask() {
    return (
      this.permService.canCreate('tasks.comments') &&
      (this.routeRecordId() === null || safeNumericRecordId(this.selectedTask()?.id))
    );
  }

  updatePriority(taskId: number, newPriority: string) {
    this.kanbanService.updatePriority(
      taskId,
      newPriority,
      this.taskRevision(taskId),
      () => {
        this.tasks.update((list) => list.map((t) => (t.id === taskId ? { ...t, priority: newPriority } : t)));
        if (this.selectedTask()?.id === taskId) {
          this.selectedTask.update((t) => (t ? { ...t, priority: newPriority } : null));
        }
        this.bumpRevision(taskId);
      },
      () => this.reloadAfterConflict(taskId),
    );
  }

  updateStatus(taskId: number, newStatusId: number) {
    this.kanbanService.updateStatus(
      taskId,
      newStatusId,
      this.taskRevision(taskId),
      () => this.applyStatusToVisibleTasks(taskId, newStatusId),
      () => {
        if (this.selectedTask()?.id === taskId) {
          this.selectedTask.update((t) => (t ? { ...t, statusId: newStatusId } : null));
        }
        this.bumpRevision(taskId);
      },
      () => this.reloadAfterConflict(taskId),
    );
  }

  executeStatusChange(task: Task, targetStatusId: number) {
    this.kanbanService.executeStatusChange(
      task,
      targetStatusId,
      this.presenter.getStatusName(targetStatusId),
      () => this.applyStatusToVisibleTasks(task.id, targetStatusId),
      () => {
        if (this.selectedTask()?.id === task.id) {
          this.selectedTask.update((t) => (t ? { ...t, statusId: targetStatusId } : null));
        }
      },
      () => this.list.loadTasks(true),
      () => this.bumpRevision(task.id),
    );
  }

  exportTasks(format: 'xlsx' | 'csv'): void {
    this.filterService.showExportMenu = false;
    window.open(`/api/v1/reports/tasks/export?format=${format}`, '_blank');
  }

  // Details methods
  openTaskDetails(task: Task) {
    this.detailsService.openTaskDetails(
      task,
      () => this.isEditModalOpen(),
      () => this.routeRecordId(),
      (id) => this.recordRouter?.navigate(['/tasks/items', id], { queryParamsHandling: 'preserve' }),
    );
  }
  retryTaskDetails() {
    this.detailsService.retryTaskDetails(() => this.routeRecordId());
  }
  closeTaskDetails(returnToList = true) {
    this.detailsService.closeTaskDetails(
      returnToList,
      () => this.routeRecordId(),
      () => this.recordRouter?.navigate(['/tasks/items'], { queryParamsHandling: 'preserve' }),
    );
  }
  onTaskFileAttached(file: TaskFile) {
    this.detailsService.onTaskFileAttached(file);
  }
  onTaskFileRemoved(file: TaskFile) {
    this.detailsService.onTaskFileRemoved(file);
  }
  loadMoreComments() {
    this.detailsService.loadMoreComments(() => this.routeRecordId());
  }

  retryComments() {
    this.detailsService.retryComments(() => this.routeRecordId());
  }
  submitComment() {
    this.detailsService.submitComment(
      () => this.canCommentTask(),
      () => this.routeRecordId(),
    );
  }

  // Forms methods
  openCreateTaskModal() {
    const defaultType = this.taskTypes().length > 0 ? this.taskTypes()[0].code : 'task';
    this.formsService.openCreateTaskModal(defaultType, this.filterService.selectedProjectId);
  }
  openAddSubtaskModal(parentTask: Task) {
    const defaultType = this.taskTypes().length > 0 ? this.taskTypes()[0].code : 'task';
    this.formsService.openAddSubtaskModal(parentTask, defaultType, (id, title) =>
      this.lookupsService.retainParentOption(id, title),
    );
  }
  requestCloseCreate() {
    this.formsService.requestCloseCreate();
  }
  submitCreateTask() {
    this.formsService.submitCreateTask((parentId) => {
      this.list.loadTasks(true);
      if (this.selectedTask() && parentId === this.selectedTask()?.id) {
        this.detailsService.loadTaskFullDetails(this.selectedTask()!.id, () => this.routeRecordId());
      }
    });
  }
  openEditModal(task: Task) {
    this.formsService.openEditModal(
      task,
      () => {
        const ret = this.selectedTask()?.id === task.id ? this.selectedTask() : null;
        if (ret) this.closeTaskDetails(false);
        return ret;
      },
      (m) => this.lookupsService.retainTaskMember(m),
      (id, title) => this.lookupsService.retainParentOption(id, title),
    );
  }
  retryEditLoad() {
    this.formsService.retryEditLoad(
      (m) => this.lookupsService.retainTaskMember(m),
      (id, title) => this.lookupsService.retainParentOption(id, title),
    );
  }
  requestCloseEdit() {
    this.formsService.requestCloseEdit((t) => this.openTaskDetails(t));
  }
  confirmDiscardEdit() {
    this.formsService.confirmDiscardEdit((t) => this.openTaskDetails(t));
  }
  cancelDiscardEdit() {
    this.formsService.cancelDiscardEdit();
  }
  canLeaveRecordPage() {
    return this.formsService.canLeaveRecordPage(
      () => this.isCommentSubmitting(),
      () => this.commentDraft,
      (t) => this.openTaskDetails(t),
    );
  }
  submitEditTask() {
    this.formsService.submitEditTask((returnTask, editedTaskId) => {
      this.list.loadTasks(true);
      if (returnTask) this.openTaskDetails({ ...returnTask, id: editedTaskId });
    });
  }

  onTaskDrop(event: CdkDragDrop<Task[]>, targetStatusId: number) {
    this.kanbanService.onTaskDrop(event, targetStatusId, this.canUpdateTask(), (task, sId) =>
      this.executeStatusChange(task, sId),
    );
  }

  private applyStatusToVisibleTasks(taskId: number, statusId: number) {
    this.kanbanService.applyStatusToVisibleTasks(
      taskId,
      statusId,
      this.statuses(),
      this.filterService.statusFilterMode,
      this.tasks,
    );
  }

  /** The revision a change of the task is made from (plan item 3.6): the card's, else the list row's. */
  private taskRevision(taskId: number): number | undefined {
    const selected = this.selectedTask();
    return selected?.id === taskId ? selected.revision : this.tasks().find((t) => t.id === taskId)?.revision;
  }

  /** A change was refused over a newer revision: the list, and the card of that task if open, are read again. */
  private reloadAfterConflict(taskId: number): void {
    this.list.loadTasks(true);
    const selected = this.selectedTask();
    if (selected?.id === taskId) this.openTaskDetails(selected);
  }

  /** A saved change raised the task's revision by one. */
  private bumpRevision(taskId: number): void {
    const next = (t: Task) => (t.id === taskId && t.revision !== undefined ? { ...t, revision: t.revision + 1 } : t);
    this.tasks.update((list) => list.map(next));
    this.selectedTask.update((t) => (t ? next(t) : null));
  }
}
