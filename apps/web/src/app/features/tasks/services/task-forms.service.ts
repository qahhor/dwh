import { Injectable, Injector, WritableSignal, inject, signal } from '@angular/core';
import { Observable, Subscription, combineLatest, take, tap } from 'rxjs';
import { ToastService } from '@core/services/toast.service';
import { I18nService } from '@core/services/i18n.service';
import { Task, TaskMember } from '@core/models/task.models';
import { safeNumericRecordId } from '@core/services/search-target';
import { SaveErrorNotifier } from '@shared/ui/save-errors';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { focusFirstInvalid } from '@shared/ui/focus-first-invalid';
import { leaveQuestion } from '@shared/ui/leave-question';
import { ProblemFieldErrors, problemFieldErrors } from '@shared/ui/problem-fields';
import { TasksApi } from '../tasks.api';
import { toLocalDateTime, toTaskInstant } from '../task-form-value';
import { TaskCreateFormValue, TaskEditFormValue, createDefaultTaskCreateForm, sameIdSet } from '../tasks.models';

/** The form ids of the task dialogs, so a server refusal can focus its first field. */
export const TASK_CREATE_FORM_ID = 'task-create-form';
export const TASK_EDIT_FORM_ID = 'task-edit-form';

/** No field errors: the state of a form before a save and after a successful one. */
export const NO_FIELD_ERRORS: ProblemFieldErrors = { fields: {}, other: [] };

/**
 * The task fields the dialogs draw under smt-control, by the dialog's names; the server names some of them by the
 * record's keys (ADR-0032 8). Errors of other fields (description, begin time, custom fields) go to the summary.
 */
const TASK_FIELDS = [
  'title',
  'taskType',
  'priority',
  'projectId',
  'parentTaskId',
  'responsibleUserId',
  'endTime',
  'executorUserIds',
  'observerUserIds',
];
const TASK_FIELD_RENAME = {
  typeCode: 'taskType',
  responsibleId: 'responsibleUserId',
  executorIds: 'executorUserIds',
  observerIds: 'observerUserIds',
};

@Injectable({
  providedIn: 'root',
})
export class TaskFormsService {
  private readonly tasksApi = inject(TasksApi);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly saveErrors = inject(SaveErrorNotifier);
  private readonly injector = inject(Injector);

  readonly isCreateModalOpen = signal<boolean>(false);

  readonly isEditModalOpen = signal<boolean>(false);
  readonly editLoading = signal<boolean>(false);
  readonly editLoadError = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);

  /** The server's refusal of the last create or edit save, by field (forms standard, section 5). */
  readonly createErrors = signal<ProblemFieldErrors>(NO_FIELD_ERRORS);
  readonly editErrors = signal<ProblemFieldErrors>(NO_FIELD_ERRORS);

  private readonly askDiscard = discardChangesQuestion();
  private readonly askLeave = leaveQuestion();

  isCreateSubmitted = false;
  createForm: TaskCreateFormValue = createDefaultTaskCreateForm();
  createFormBaseline = '';

  isEditSubmitted = false;
  editingTask: Task | null = null;
  editTargetId: number | null = null;
  editReturnTask: Task | null = null;
  editFormBaseline = '';
  editAssignmentBaseline: {
    parentTaskId: number | null;
    responsibleUserId: number | null;
    executorUserIds: number[];
    observerUserIds: number[];
  } | null = null;

  editForm: TaskEditFormValue = {
    title: '',
    taskType: 'task',
    descriptionMarkdown: '',
    projectId: null,
    priority: 'medium',
    responsibleUserId: null,
    parentTaskId: null,
    executorUserIds: [],
    observerUserIds: [],
    beginTime: '',
    endTime: '',
    attributes: {},
  };

  private editRequestId = 0;
  /** What the last load of the edited task keeps for the screen, so that reading it again does the same. */
  private editRetain: {
    member: (m: TaskMember) => void;
    parent: (id: number, title: string) => void;
  } | null = null;
  private editRequest?: Subscription;
  private editSaveRequest?: Subscription;

  openCreateTaskModal(defaultType = 'task', projectId: number | null = null): void {
    this.isCreateSubmitted = false;
    this.createErrors.set(NO_FIELD_ERRORS);
    this.createForm = createDefaultTaskCreateForm(projectId, defaultType);
    this.createFormBaseline = JSON.stringify(this.createForm);
    this.isCreateModalOpen.set(true);
  }

  openAddSubtaskModal(
    parentTask: Task,
    defaultType = 'task',
    onRetainParent: (id: number, title: string) => void,
  ): void {
    if (!safeNumericRecordId(parentTask.id)) return;
    this.isCreateSubmitted = false;
    this.createErrors.set(NO_FIELD_ERRORS);
    this.createForm = {
      title: '',
      taskType: defaultType,
      descriptionMarkdown: '',
      projectId: parentTask.projectId || null,
      priority: parentTask.priority || 'medium',
      responsibleUserId: null,
      parentTaskId: parentTask.id,
      executorUserIds: [],
      observerUserIds: [],
      beginTime: '',
      endTime: '',
      attributes: {},
    };
    onRetainParent(parentTask.id, parentTask.title);
    this.createFormBaseline = JSON.stringify(this.createForm);
    this.isCreateModalOpen.set(true);
  }

  /** Escape, the backdrop, the cross and "Cancel" ask before a changed draft is dropped (forms standard, 8). */
  requestCloseCreate(): void {
    if (this.isSubmitting()) return;
    const dirty = this.createFormBaseline !== JSON.stringify(this.createForm);
    this.askDiscard(dirty).subscribe((discard) => {
      if (discard) this.isCreateModalOpen.set(false);
    });
  }

  /** An empty title shows its error under the field; the dialog's form moves focus to it. */
  submitCreateTask(onSuccess: (parentTaskId: number | null) => void): void {
    if (this.isSubmitting()) return;
    this.isCreateSubmitted = true;
    if (!this.createForm.title.trim()) return;
    this.createErrors.set(NO_FIELD_ERRORS);

    // The fields of `ms.tasks` by their keys (ADR-0032 8); the custom fields stay in `attributes`.
    const payload = {
      title: this.createForm.title.trim(),
      descriptionMarkdown: this.createForm.descriptionMarkdown?.trim() || '',
      typeCode: this.createForm.taskType || 'task',
      projectId: this.createForm.projectId ? Number(this.createForm.projectId) : null,
      priority: this.createForm.priority || 'medium',
      responsibleId: this.createForm.responsibleUserId ? Number(this.createForm.responsibleUserId) : null,
      parentTaskId: this.createForm.parentTaskId ? Number(this.createForm.parentTaskId) : null,
      executorIds: this.createForm.executorUserIds,
      observerIds: this.createForm.observerUserIds,
      beginTime: toTaskInstant(this.createForm.beginTime),
      endTime: toTaskInstant(this.createForm.endTime),
      attributes: { ...this.createForm.attributes },
    };

    const parentId = this.createForm.parentTaskId ? Number(this.createForm.parentTaskId) : null;
    this.isSubmitting.set(true);
    this.tasksApi.create(payload).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isCreateModalOpen.set(false);
        this.toast.success(this.uiI18n.translate('tasks.editor.created'));
        onSuccess(parentId);
      },
      error: (err: unknown) => {
        this.isSubmitting.set(false);
        if (this.showFieldErrors(err, this.createErrors, TASK_CREATE_FORM_ID)) return;
        this.saveErrors.show(err, { fallbackKey: 'tasks.editor.save_failed' });
      },
    });
  }

  openEditModal(
    task: Task,
    closeDetailsIfMatches: () => Task | null,
    onRetainMember: (m: TaskMember) => void,
    onRetainParent: (id: number, title: string) => void,
  ): void {
    if (!safeNumericRecordId(task.id)) return;
    if (this.isSubmitting() || this.isEditModalOpen()) return;
    this.isEditSubmitted = false;
    this.editErrors.set(NO_FIELD_ERRORS);
    this.editReturnTask = closeDetailsIfMatches();
    this.editTargetId = task.id;
    this.editingTask = null;
    this.editFormBaseline = '';
    this.editAssignmentBaseline = null;
    this.isEditModalOpen.set(true);
    this.loadEditDetails(task.id, onRetainMember, onRetainParent);
  }

  loadEditDetails(
    taskId: number,
    onRetainMember: (m: TaskMember) => void,
    onRetainParent: (id: number, title: string) => void,
  ): void {
    const requestId = ++this.editRequestId;
    this.editRetain = { member: onRetainMember, parent: onRetainParent };
    this.editRequest?.unsubscribe();
    this.editLoading.set(true);
    this.editLoadError.set(false);
    this.editingTask = null;

    // The record holds the people by id; their names come with the participants, so the pickers show them at once.
    this.editRequest = combineLatest({ task: this.tasksApi.get(taskId), members: this.tasksApi.members(taskId) })
      .pipe(take(1))
      .subscribe({
        next: (res) => {
          if (requestId !== this.editRequestId || this.editTargetId !== taskId || !this.isEditModalOpen()) return;
          if (!res?.task || res.task.id !== taskId || !Array.isArray(res.members)) {
            this.editLoading.set(false);
            this.editLoadError.set(true);
            return;
          }
          const freshTask = res.task;
          res.members.forEach((member) => onRetainMember(member));

          this.editingTask = freshTask;
          this.editForm = {
            title: freshTask.title,
            taskType: freshTask.typeCode || 'task',
            descriptionMarkdown: freshTask.descriptionMarkdown || '',
            projectId: freshTask.projectId ?? null,
            priority: freshTask.priority || 'medium',
            responsibleUserId: freshTask.responsibleId ?? null,
            parentTaskId: freshTask.parentTaskId ?? null,
            executorUserIds: [...(freshTask.executorIds ?? [])],
            observerUserIds: [...(freshTask.observerIds ?? [])],
            beginTime: toLocalDateTime(freshTask.beginTime),
            endTime: toLocalDateTime(freshTask.endTime),
            attributes: { ...(freshTask.attributes || {}) },
          };
          this.editFormBaseline = this.serializeEditForm();
          this.editAssignmentBaseline = {
            parentTaskId: this.editForm.parentTaskId,
            responsibleUserId: this.editForm.responsibleUserId,
            executorUserIds: [...this.editForm.executorUserIds],
            observerUserIds: [...this.editForm.observerUserIds],
          };
          this.editLoading.set(false);
        },
        error: () => {
          if (requestId !== this.editRequestId || this.editTargetId !== taskId || !this.isEditModalOpen()) return;
          this.editLoading.set(false);
          this.editLoadError.set(true);
        },
      });
  }

  retryEditLoad(onRetainMember: (m: TaskMember) => void, onRetainParent: (id: number, title: string) => void): void {
    if (this.editTargetId != null && !this.isSubmitting()) {
      this.loadEditDetails(this.editTargetId, onRetainMember, onRetainParent);
    }
  }

  /** Reads the edited task again after a save was refused over a newer revision; the edits made are dropped. */
  reloadEdit(): void {
    const retain = this.editRetain;
    if (this.editTargetId == null || this.isSubmitting() || !this.isEditModalOpen() || !retain) return;
    this.loadEditDetails(this.editTargetId, retain.member, retain.parent);
  }

  /** A changed edit asks the common "discard changes?" question first; an unchanged one closes back to the card. */
  requestCloseEdit(onOpenDetails: (t: Task) => void): void {
    if (this.isSubmitting()) return;
    const dirty = !!this.editingTask && this.editFormBaseline !== this.serializeEditForm();
    this.askDiscard(dirty).subscribe((discard) => {
      if (discard && this.isEditModalOpen() && !this.isSubmitting()) this.closeEditModal(true, onOpenDetails);
    });
  }

  /** Leaving the page with a changed dialog or comment asks once; on "discard" the dialogs close without a return. */
  canLeaveRecordPage(
    isCommentSubmitting: () => boolean,
    commentDraft: () => string,
    onOpenDetails: (t: Task) => void,
  ): boolean | Observable<boolean> {
    if (this.isSubmitting() || isCommentSubmitting()) return false;
    const dirtyEdit = this.isEditModalOpen() && this.editingTask && this.editFormBaseline !== this.serializeEditForm();
    const dirtyCreate = this.isCreateModalOpen() && this.createFormBaseline !== JSON.stringify(this.createForm);
    if (dirtyEdit || dirtyCreate || commentDraft().trim()) {
      return this.askLeave().pipe(
        tap((leave) => {
          if (!leave) return;
          this.isCreateModalOpen.set(false);
          if (this.isEditModalOpen()) this.closeEditModal(false, onOpenDetails);
        }),
      );
    }
    if (this.isEditModalOpen()) this.closeEditModal(false, onOpenDetails);
    this.isCreateModalOpen.set(false);
    return true;
  }

  closeEditModal(returnToDetails: boolean, onOpenDetails: (t: Task) => void): void {
    const returnTask = returnToDetails ? this.editReturnTask : null;
    this.editRequestId++;
    this.editRequest?.unsubscribe();
    this.isEditModalOpen.set(false);
    this.editErrors.set(NO_FIELD_ERRORS);
    this.editLoading.set(false);
    this.editLoadError.set(false);
    this.editingTask = null;
    this.editTargetId = null;
    this.editReturnTask = null;
    this.editFormBaseline = '';
    this.editAssignmentBaseline = null;
    if (returnTask) onOpenDetails(returnTask);
  }

  serializeEditForm(): string {
    return JSON.stringify(this.editForm);
  }

  submitEditTask(onSuccess: (returnTask: Task | null, editedTaskId: number) => void): void {
    if (!this.editingTask || this.isSubmitting() || this.editLoading() || this.editLoadError()) return;
    this.isEditSubmitted = true;
    if (!this.editForm.title.trim()) return;
    this.editErrors.set(NO_FIELD_ERRORS);

    const payload: Record<string, unknown> = {
      title: this.editForm.title.trim(),
      descriptionMarkdown: this.editForm.descriptionMarkdown?.trim() || '',
      typeCode: this.editForm.taskType || 'task',
      projectId: this.editForm.projectId ? Number(this.editForm.projectId) : null,
      priority: this.editForm.priority || 'medium',
      beginTime: toTaskInstant(this.editForm.beginTime, this.editingTask.beginTime),
      endTime: toTaskInstant(this.editForm.endTime, this.editingTask.endTime),
      attributes: { ...this.editForm.attributes },
    };
    const currentAssignments = {
      parentTaskId: this.editForm.parentTaskId == null ? null : Number(this.editForm.parentTaskId),
      responsibleUserId: this.editForm.responsibleUserId == null ? null : Number(this.editForm.responsibleUserId),
      executorUserIds: [...this.editForm.executorUserIds],
      observerUserIds: [...this.editForm.observerUserIds],
    };
    if (!this.editAssignmentBaseline || currentAssignments.parentTaskId !== this.editAssignmentBaseline.parentTaskId) {
      payload['parentTaskId'] = currentAssignments.parentTaskId;
    }
    if (
      !this.editAssignmentBaseline ||
      currentAssignments.responsibleUserId !== this.editAssignmentBaseline.responsibleUserId
    ) {
      payload['responsibleId'] = currentAssignments.responsibleUserId;
    }
    if (
      !this.editAssignmentBaseline ||
      !sameIdSet(currentAssignments.executorUserIds, this.editAssignmentBaseline.executorUserIds)
    ) {
      payload['executorIds'] = currentAssignments.executorUserIds;
    }
    if (
      !this.editAssignmentBaseline ||
      !sameIdSet(currentAssignments.observerUserIds, this.editAssignmentBaseline.observerUserIds)
    ) {
      payload['observerIds'] = currentAssignments.observerUserIds;
    }

    const editedTask = this.editingTask;
    const returnTask = this.editReturnTask;
    this.isSubmitting.set(true);
    this.editSaveRequest = this.tasksApi.patch(editedTask.id, payload, editedTask.revision).subscribe({
      next: () => {
        if (this.editingTask?.id !== editedTask.id) return;
        this.isSubmitting.set(false);
        this.closeEditModal(false, () => {});
        this.toast.success(this.uiI18n.translate('tasks.editor.updated'));
        onSuccess(returnTask, editedTask.id);
      },
      error: (err: unknown) => {
        if (this.editingTask?.id !== editedTask.id) return;
        this.isSubmitting.set(false);
        if (this.showFieldErrors(err, this.editErrors, TASK_EDIT_FORM_ID)) return;
        this.saveErrors.show(err, {
          fallbackKey: 'tasks.editor.update_failed',
          reload: () => this.reloadEdit(),
        });
      },
    });
  }

  cleanup(): void {
    this.editRequestId++;
    this.editRequest?.unsubscribe();
    this.editSaveRequest?.unsubscribe();
  }

  /**
   * Puts a refusal's field errors under the dialog's fields and focuses the first; false when the refusal names no
   * field, so the caller reports it as a whole.
   */
  private showFieldErrors(err: unknown, target: WritableSignal<ProblemFieldErrors>, formId: string): boolean {
    const errors = problemFieldErrors(err, { known: TASK_FIELDS, rename: TASK_FIELD_RENAME });
    if (Object.keys(errors.fields).length === 0 && errors.other.length === 0) return false;
    target.set(errors);
    const form = document.getElementById(formId);
    if (form) focusFirstInvalid(form, this.injector);
    return true;
  }
}
