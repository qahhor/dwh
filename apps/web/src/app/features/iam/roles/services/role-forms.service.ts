import { Injectable, signal, inject } from '@angular/core';
import { disabled, form, required, validate } from '@angular/forms/signals';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { I18nService } from '@core/services/i18n.service';
import { Role } from '@core/models/rbac.models';
import { safeNumericRecordId } from '@core/services/search-target';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { discardChangesQuestion, formChanged } from '@shared/ui/discard-changes';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { SaveErrorNotifier } from '@shared/ui/save-errors';

/** The create dialog's fields. */
export interface NewRoleForm {
  name: string;
  orderNo: number;
}

/** The edit dialog's fields. */
export interface EditRoleForm {
  name: string;
  state: string;
  orderNo: number;
}

/** What a refused save tells the dialog: messages by field, and messages of fields the dialog does not draw. */
export interface RoleFormErrors {
  readonly fields: Readonly<Record<string, string>>;
  readonly other: readonly string[];
}

const NO_ERRORS: RoleFormErrors = { fields: {}, other: [] };
const ROLE_FIELDS = ['name', 'state', 'orderNo'];

/**
 * The create, edit and delete dialogs of a role (docs/guidelines/forms-ux-standard.md). The two forms are Signal
 * Forms: the name is required, errors appear under the fields on blur and on submit, a refused save puts the
 * server's field messages under the fields, and closing a changed form asks first.
 */
@Injectable({ providedIn: 'root' })
export class RoleFormsService {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly saveErrors = inject(SaveErrorNotifier);

  readonly isCreateModalOpen = signal<boolean>(false);
  readonly isEditModalOpen = signal<boolean>(false);
  readonly isDeleteModalOpen = signal<boolean>(false);
  readonly isSubmittingRole = signal<boolean>(false);

  readonly editingRole = signal<Role | null>(null);

  readonly newRole = signal<NewRoleForm>({ name: '', orderNo: 0 });
  readonly editRole = signal<EditRoleForm>({ name: '', state: 'A', orderNo: 0 });
  readonly createErrors = signal<RoleFormErrors>(NO_ERRORS);
  readonly editErrors = signal<RoleFormErrors>(NO_ERRORS);

  deletingRole: Role | null = null;

  private editInitial: EditRoleForm = { name: '', state: 'A', orderNo: 0 };
  private readonly askDiscard = discardChangesQuestion();

  private readonly nameRequired = () => this.uiI18n.translate('iam.roles.editor.enter_role_name');

  readonly createForm = form(this.newRole, (path) => {
    required(path.name, { message: this.nameRequired });
    validate(path.name, ({ value }) =>
      !value() || value().trim() ? null : { kind: 'required', message: this.nameRequired() },
    );
  });

  readonly editForm = form(this.editRole, (path) => {
    required(path.name, { message: this.nameRequired });
    validate(path.name, ({ value }) =>
      !value() || value().trim() ? null : { kind: 'required', message: this.nameRequired() },
    );
    // The superadministrator role is always active.
    disabled(path.state, () => this.editingRole()?.pcode === 'admin');
  });

  openCreateModal() {
    this.newRole.set({ name: '', orderNo: 0 });
    this.createForm().reset();
    this.createErrors.set(NO_ERRORS);
    this.isCreateModalOpen.set(true);
  }

  /** Escape, the backdrop, the close button and Cancel: a typed role is lost only after a question. */
  requestCloseCreate(): void {
    if (this.isSubmittingRole()) return;
    const dirty = formChanged({ name: '', orderNo: 0 }, this.newRole());
    this.askDiscard(dirty).subscribe((discard) => {
      if (discard) this.isCreateModalOpen.set(false);
    });
  }

  /** Returns false when the form is invalid; the errors are then shown under the fields. */
  submitCreateRole(onSuccess: (newRole: Role) => void): boolean {
    if (this.isSubmittingRole()) return true;
    markSMTFormFieldsTouched(this.createForm);
    this.createErrors.set(NO_ERRORS);
    if (!this.createForm().valid()) return false;

    const draft = this.newRole();
    this.isSubmittingRole.set(true);
    this.api
      .post<Role>(
        '/iam/roles',
        { name: draft.name.trim(), orderNo: Number(draft.orderNo) || 0 },
        { notifyError: false },
      )
      .subscribe({
        next: (newRole) => {
          this.isSubmittingRole.set(false);
          this.isCreateModalOpen.set(false);
          this.toast.success(this.uiI18n.translate('iam.roles.editor.created'));
          onSuccess(newRole);
        },
        error: (err: unknown) => {
          this.isSubmittingRole.set(false);
          if (!this.showFieldErrors(err, this.createErrors)) {
            this.saveErrors.show(err, { fallbackKey: 'common.operation_failed' });
          }
        },
      });
    return true;
  }

  openEditRoleModal(role: Role) {
    this.editingRole.set(role);
    this.editInitial = { name: role.name, state: role.state, orderNo: role.orderNo };
    this.editRole.set({ ...this.editInitial });
    this.editForm().reset();
    this.editErrors.set(NO_ERRORS);
    this.isEditModalOpen.set(true);
  }

  /** Escape, the backdrop, the close button and Cancel: changes are lost only after a question. */
  requestCloseEdit(): void {
    if (this.isSubmittingRole()) return;
    this.askDiscard(formChanged(this.editInitial, this.editRole())).subscribe((discard) => {
      if (discard) this.isEditModalOpen.set(false);
    });
  }

  /**
   * `onReload` reads the roles again after a save refused over a newer revision; the dialog closes first.
   * Returns false when the form is invalid; the errors are then shown under the fields.
   */
  submitEditRole(onSuccess: () => void, onReload?: () => void): boolean {
    const role = this.editingRole();
    if (!role || this.isSubmittingRole()) return true;
    markSMTFormFieldsTouched(this.editForm);
    this.editErrors.set(NO_ERRORS);
    if (!this.editForm().valid()) return false;

    const draft = this.editRole();
    this.isSubmittingRole.set(true);
    this.api
      .patch(
        `/iam/roles/${role.id}`,
        { name: draft.name.trim(), state: draft.state, orderNo: Number(draft.orderNo) || 0 },
        { notifyError: false, ifMatch: role.revision },
      )
      .subscribe({
        next: () => {
          this.isSubmittingRole.set(false);
          this.isEditModalOpen.set(false);
          this.toast.success(this.uiI18n.translate('iam.roles.editor.updated'));
          onSuccess();
        },
        error: (err: unknown) => {
          this.isSubmittingRole.set(false);
          if (this.showFieldErrors(err, this.editErrors)) return;
          this.saveErrors.show(err, {
            fallbackKey: 'common.operation_failed',
            reload: onReload
              ? () => {
                  this.isEditModalOpen.set(false);
                  onReload();
                }
              : undefined,
          });
        },
      });
    return true;
  }

  openDeleteRoleModal(role: Role, isSaving: boolean, isScopeBusy: boolean) {
    if (isSaving || isScopeBusy || this.isSubmittingRole() || this.isDeleteModalOpen() || !safeNumericRecordId(role.id))
      return;
    this.deletingRole = { ...role };
    this.isDeleteModalOpen.set(true);
  }

  closeDeleteRoleModal(): void {
    if (this.isSubmittingRole()) return;
    this.isDeleteModalOpen.set(false);
    this.deletingRole = null;
  }

  deleteRole(target: Role, onSuccess: () => void): void {
    if (this.isSubmittingRole() || !this.isDeleteModalOpen()) return;
    this.isSubmittingRole.set(true);
    this.api.delete(`/iam/roles/${target.id}`).subscribe({
      next: () => {
        this.isSubmittingRole.set(false);
        this.isDeleteModalOpen.set(false);
        this.deletingRole = null;
        this.toast.success(this.uiI18n.translate('iam.roles.editor.deleted'));
        onSuccess();
      },
      error: () => {
        this.isSubmittingRole.set(false);
      },
    });
  }

  /** Puts the server's field messages into `target`; false when the refusal named no field. */
  private showFieldErrors(err: unknown, target: { set(value: RoleFormErrors): void }): boolean {
    const errors = problemFieldErrors(err, { known: ROLE_FIELDS });
    if (Object.keys(errors.fields).length === 0 && errors.other.length === 0) return false;
    target.set(errors);
    return true;
  }
}
