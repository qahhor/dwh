import { Injectable, Injector, WritableSignal, inject, signal } from '@angular/core';
import { Subscription, Observable, map, of, switchMap, tap } from 'rxjs';
import { EntitiesApi, EntityRecord } from '@shared/entity/entities.api';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { I18nService } from '@core/services/i18n.service';
import { safeNumericRecordId } from '@core/services/search-target';
import { SaveErrorNotifier, isRevisionConflict } from '@shared/ui/save-errors';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { leaveQuestion } from '@shared/ui/leave-question';
import { focusFirstInvalid } from '@shared/ui/focus-first-invalid';
import { ProblemFieldErrors, problemFieldErrors } from '@shared/ui/problem-fields';
import { Project } from '@core/models/task.models';
import { ProjectCreateForm, ProjectEditForm } from '../projects.models';
import { PROJECTS, toProject } from '../projects.api';

/** The form ids of the project dialogs, so a server refusal can focus its first field. */
export const PROJECT_CREATE_FORM_ID = 'project-create-form';
export const PROJECT_EDIT_FORM_ID = 'project-edit-form';

/** No field errors: a form before a save and after a successful one. */
export const NO_PROJECT_ERRORS: ProblemFieldErrors = { fields: {}, other: [] };

/** The project fields the dialogs draw under smt-control; errors of other fields go to the dialog's alert. */
const PROJECT_FIELDS = ['name', 'description', 'state'];

@Injectable()
export class ProjectFormsService {
  private readonly entities = inject(EntitiesApi);
  private readonly permService = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly saveErrors = inject(SaveErrorNotifier);
  private readonly injector = inject(Injector);
  private readonly askDiscard = discardChangesQuestion();
  private readonly askLeave = leaveQuestion();

  readonly isSubmitting = signal<boolean>(false);
  readonly editLoading = signal<boolean>(false);
  readonly editLoadError = signal<boolean>(false);
  /** A refusal that names no field: shown as an alert inside the dialog (forms standard, section 4). */
  readonly createSaveError = signal<string | null>(null);
  readonly editSaveError = signal<string | null>(null);
  /** The server's field errors of the last save (forms standard, section 5). */
  readonly createErrors = signal<ProblemFieldErrors>(NO_PROJECT_ERRORS);
  readonly editErrors = signal<ProblemFieldErrors>(NO_PROJECT_ERRORS);

  readonly isCreateModalOpen = signal<boolean>(false);
  readonly isEditModalOpen = signal<boolean>(false);

  private editDetailRequest?: Subscription;
  private createSaveRequest?: Subscription;
  private editSaveRequest?: Subscription;
  private editDetailRequestId = 0;
  private createSaveRequestId = 0;
  private editSaveRequestId = 0;
  private destroyed = false;

  isCreateSubmitted = false;
  isEditSubmitted = false;

  createForm: ProjectCreateForm = { name: '', description: '', attributes: {} };
  editForm: ProjectEditForm = { name: '', description: '', state: 'A', attributes: {} };
  editingProject: Project | null = null;
  private createFormBaseline: ProjectCreateForm = { name: '', description: '', attributes: {} };
  private editFormBaseline: ProjectEditForm | null = null;
  private editTargetId: number | null = null;

  onProjectCreated?: (created: Project) => void;
  onProjectUpdated?: () => void;

  destroy() {
    this.destroyed = true;
    this.editDetailRequestId++;
    this.createSaveRequestId++;
    this.editSaveRequestId++;
    this.editDetailRequest?.unsubscribe();
    this.createSaveRequest?.unsubscribe();
    this.editSaveRequest?.unsubscribe();
  }

  canCreateProject(): boolean {
    return this.permService.canCreate('tasks.projects');
  }

  canUpdateProject(): boolean {
    return this.permService.canUpdate('tasks.projects');
  }

  openCreateModal() {
    if (
      this.destroyed ||
      !this.canCreateProject() ||
      this.isSubmitting() ||
      this.isCreateModalOpen() ||
      this.isEditModalOpen()
    )
      return;
    this.isCreateSubmitted = false;
    this.createForm = { name: '', description: '' };
    this.createFormBaseline = { ...this.createForm };
    this.createSaveError.set(null);
    this.createErrors.set(NO_PROJECT_ERRORS);
    this.isCreateModalOpen.set(true);
  }

  /** Escape, the backdrop, the cross and "Cancel" ask before a changed draft is dropped (forms standard, 8). */
  requestCloseCreate() {
    if (this.destroyed || this.isSubmitting() || !this.isCreateModalOpen()) return;
    this.askDiscard(this.isCreateDraftDirty()).subscribe((discard) => {
      if (discard && this.isCreateModalOpen() && !this.isSubmitting()) this.closeCreateModal();
    });
  }

  /** An empty name shows its error under the field; the dialog's form moves focus to it. */
  submitCreateProject() {
    if (
      this.destroyed ||
      !this.canCreateProject() ||
      !this.isCreateModalOpen() ||
      this.isEditModalOpen() ||
      this.isSubmitting()
    )
      return;
    this.isCreateSubmitted = true;
    if (!this.createForm.name.trim()) return;

    const requestId = ++this.createSaveRequestId;
    this.createSaveError.set(null);
    this.createErrors.set(NO_PROJECT_ERRORS);
    this.isSubmitting.set(true);
    const payload: { name: string; description: string; attributes?: Record<string, unknown> } = {
      name: this.createForm.name.trim(),
      description: this.createForm.description.trim(),
    };
    if (this.createForm.attributes && Object.keys(this.createForm.attributes).length > 0) {
      payload.attributes = this.createForm.attributes;
    }
    this.createSaveRequest = this.entities
      .create(PROJECTS, payload)
      .pipe(map(toProject))
      .subscribe({
        next: (created) => {
          if (
            this.destroyed ||
            requestId !== this.createSaveRequestId ||
            !this.isCreateModalOpen() ||
            this.isEditModalOpen()
          )
            return;
          this.isSubmitting.set(false);
          this.closeCreateModal();
          this.toast.success(this.uiI18n.translate('projects.editor.created'));
          this.onProjectCreated?.(created);
        },
        error: (err) => {
          if (
            this.destroyed ||
            requestId !== this.createSaveRequestId ||
            !this.isCreateModalOpen() ||
            this.isEditModalOpen()
          )
            return;
          this.isSubmitting.set(false);
          this.showRefusal(
            err,
            this.createErrors,
            this.createSaveError,
            PROJECT_CREATE_FORM_ID,
            'projects.create_save_error',
          );
        },
      });
  }

  openEditModal(p: Project) {
    if (!safeNumericRecordId(p.id)) return;
    if (
      this.destroyed ||
      !this.canUpdateProject() ||
      this.isSubmitting() ||
      this.isCreateModalOpen() ||
      this.isEditModalOpen()
    )
      return;
    this.isEditSubmitted = false;
    this.editTargetId = p.id;
    this.editingProject = null;
    this.editFormBaseline = null;
    this.editSaveError.set(null);
    this.editErrors.set(NO_PROJECT_ERRORS);
    this.isEditModalOpen.set(true);
    this.loadEditDetails(p.id);
  }

  retryEditLoad() {
    if (
      this.destroyed ||
      this.isSubmitting() ||
      this.editLoading() ||
      !this.isEditModalOpen() ||
      this.isCreateModalOpen() ||
      this.editTargetId == null
    )
      return;
    this.loadEditDetails(this.editTargetId);
  }

  /** A changed edit asks the common "discard changes?" question first; an unchanged one closes at once. */
  requestCloseEdit() {
    if (this.destroyed || this.isSubmitting() || !this.isEditModalOpen()) return;
    this.askDiscard(this.isEditDraftDirty()).subscribe((discard) => {
      if (discard && this.isEditModalOpen() && !this.isSubmitting()) this.closeEditModal();
    });
  }

  submitEditProject() {
    if (
      this.destroyed ||
      !this.canUpdateProject() ||
      !this.isEditModalOpen() ||
      this.isCreateModalOpen() ||
      !this.editingProject ||
      !this.editFormBaseline ||
      this.editLoading() ||
      this.editLoadError() ||
      this.isSubmitting()
    )
      return;
    this.isEditSubmitted = true;
    if (!this.editForm.name.trim()) return;

    const payload: Record<string, unknown> = {};
    const name = this.editForm.name.trim();
    const description = this.editForm.description.trim();
    if (name !== this.editFormBaseline.name) payload['name'] = name;
    if (description !== this.editFormBaseline.description) payload['description'] = description;
    if (JSON.stringify(this.editForm.attributes || {}) !== JSON.stringify(this.editFormBaseline.attributes || {})) {
      payload['attributes'] = this.editForm.attributes;
    }
    const archive = this.editForm.state !== this.editFormBaseline.state ? this.editForm.state === 'P' : null;
    if (Object.keys(payload).length === 0 && archive === null) {
      this.closeEditModal();
      return;
    }

    const editedProjectId = this.editingProject.id;
    const requestId = ++this.editSaveRequestId;
    this.editSaveError.set(null);
    this.editErrors.set(NO_PROJECT_ERRORS);
    this.isSubmitting.set(true);
    this.editSaveRequest = this.saveEdit(editedProjectId, payload, archive, this.editingProject.revision).subscribe({
      next: () => {
        if (
          this.destroyed ||
          requestId !== this.editSaveRequestId ||
          !this.isEditModalOpen() ||
          this.editingProject?.id !== editedProjectId
        )
          return;
        this.isSubmitting.set(false);
        this.closeEditModal();
        this.toast.success(this.uiI18n.translate('projects.editor.updated'));
        this.onProjectUpdated?.();
      },
      error: (err) => {
        if (
          this.destroyed ||
          requestId !== this.editSaveRequestId ||
          !this.isEditModalOpen() ||
          this.editingProject?.id !== editedProjectId
        )
          return;
        this.isSubmitting.set(false);
        if (isRevisionConflict(err)) {
          // Saved by someone else since it was opened: the dialog closes and the projects are read again.
          this.saveErrors.show(err, {
            fallbackKey: 'projects.edit_save_error',
            reload: () => {
              this.closeEditModal();
              this.onProjectUpdated?.();
            },
          });
          return;
        }
        this.showRefusal(err, this.editErrors, this.editSaveError, PROJECT_EDIT_FORM_ID, 'projects.edit_save_error');
      },
    });
  }

  isCreateDraftDirty(): boolean {
    return (
      this.createForm.name !== this.createFormBaseline.name ||
      this.createForm.description !== this.createFormBaseline.description ||
      (!!this.createForm.attributes && Object.keys(this.createForm.attributes).length > 0)
    );
  }

  isEditDraftDirty(): boolean {
    return (
      !!this.editFormBaseline &&
      (this.editForm.name !== this.editFormBaseline.name ||
        this.editForm.description !== this.editFormBaseline.description ||
        this.editForm.state !== this.editFormBaseline.state ||
        JSON.stringify(this.editForm.attributes || {}) !== JSON.stringify(this.editFormBaseline.attributes || {}))
    );
  }

  closeCreateModal() {
    this.createSaveRequestId++;
    this.createSaveRequest?.unsubscribe();
    this.isCreateModalOpen.set(false);
    this.createSaveError.set(null);
    this.createErrors.set(NO_PROJECT_ERRORS);
    this.createForm = { name: '', description: '' };
    this.createFormBaseline = { ...this.createForm };
  }

  closeEditModal() {
    this.editDetailRequestId++;
    this.editSaveRequestId++;
    this.editDetailRequest?.unsubscribe();
    this.editSaveRequest?.unsubscribe();
    this.isEditModalOpen.set(false);
    this.editLoading.set(false);
    this.editLoadError.set(false);
    this.editSaveError.set(null);
    this.editErrors.set(NO_PROJECT_ERRORS);
    this.editingProject = null;
    this.editTargetId = null;
    this.editFormBaseline = null;
  }

  /** Leaving the page with a changed dialog asks once; on "discard" the dialogs close. */
  canLeaveRecordPage(): boolean | Observable<boolean> | Promise<boolean> {
    if (this.isSubmitting()) return false;
    const dirty =
      (this.isCreateModalOpen() && this.isCreateDraftDirty()) || (this.isEditModalOpen() && this.isEditDraftDirty());
    if (dirty) {
      return this.askLeave().pipe(
        tap((leave) => {
          if (leave) this.closeDialogs();
        }),
      );
    }
    this.closeDialogs();
    return true;
  }

  private closeDialogs() {
    if (this.isCreateModalOpen()) this.closeCreateModal();
    if (this.isEditModalOpen()) this.closeEditModal();
  }

  /**
   * Field errors of a refusal go under the dialog's fields and focus the first; the rest, or a refusal that names no
   * field, is the dialog's alert (the server's words before our own).
   */
  private showRefusal(
    err: unknown,
    fieldErrors: WritableSignal<ProblemFieldErrors>,
    alert: WritableSignal<string | null>,
    formId: string,
    fallbackKey: string,
  ) {
    const errors = problemFieldErrors(err, { known: PROJECT_FIELDS });
    fieldErrors.set(errors);
    const hasFields = Object.keys(errors.fields).length > 0;
    if (hasFields) {
      const form = document.getElementById(formId);
      if (form) focusFirstInvalid(form, this.injector);
    }
    const detail = (err as { detail?: unknown } | null)?.detail;
    if (errors.other.length > 0) alert.set(errors.other.join(' '));
    else if (!hasFields) alert.set(typeof detail === 'string' && detail ? detail : this.uiI18n.translate(fallbackKey));
  }

  /**
   * The fields a save changes, from the revision on screen, then the archive switch from the revision the change
   * answered with (ADR-0024, ADR-0032 5.4): a paused project is an archived one.
   */
  private saveEdit(
    projectId: number,
    fields: Record<string, unknown>,
    archive: boolean | null,
    revision: number | undefined,
  ): Observable<EntityRecord | null> {
    const changed: Observable<EntityRecord | null> =
      Object.keys(fields).length > 0 ? this.entities.patch(PROJECTS, projectId, fields, revision) : of(null);
    return changed.pipe(
      switchMap((record: EntityRecord | null) =>
        archive === null
          ? of(record)
          : this.entities.setArchived(PROJECTS, projectId, archive, record?.revision ?? revision),
      ),
    );
  }

  private loadEditDetails(projectId: number) {
    const requestId = ++this.editDetailRequestId;
    this.editDetailRequest?.unsubscribe();
    this.editLoading.set(true);
    this.editLoadError.set(false);
    this.editSaveError.set(null);
    this.editErrors.set(NO_PROJECT_ERRORS);
    this.editingProject = null;
    this.editFormBaseline = null;

    this.editDetailRequest = this.entities
      .get(PROJECTS, projectId)
      .pipe(map(toProject))
      .subscribe({
        next: (project) => {
          if (
            this.destroyed ||
            requestId !== this.editDetailRequestId ||
            !this.isEditModalOpen() ||
            this.isCreateModalOpen() ||
            this.editTargetId !== projectId
          )
            return;
          this.editLoading.set(false);
          if (!project || project.id !== projectId) {
            this.editLoadError.set(true);
            return;
          }
          const normalized: ProjectEditForm = {
            name: project.name.trim(),
            description: (project.description || '').trim(),
            state: project.state,
          };
          if (project.attributes && Object.keys(project.attributes).length > 0) {
            normalized.attributes = { ...project.attributes };
          }
          this.editingProject = project;
          this.editForm = { ...normalized };
          this.editFormBaseline = {
            ...normalized,
            ...(normalized.attributes ? { attributes: { ...normalized.attributes } } : {}),
          };
        },
        error: () => {
          if (
            this.destroyed ||
            requestId !== this.editDetailRequestId ||
            !this.isEditModalOpen() ||
            this.editTargetId !== projectId
          )
            return;
          this.editLoading.set(false);
          this.editLoadError.set(true);
        },
      });
  }
}
