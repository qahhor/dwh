import { ChangeDetectionStrategy, Component, effect, inject, Injector, input, model, output } from '@angular/core';
import { FieldTree, FormField } from '@angular/forms/signals';

import { Role } from '@core/models/rbac.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { focusFirstInvalid, UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFormErrorItem, UiFormErrorSummaryComponent } from '@shared/ui/ui-form-error-summary.component';
import { EditRoleForm, NewRoleForm, RoleFormErrors } from '../services/role-forms.service';

const NO_ERRORS: RoleFormErrors = { fields: {}, other: [] };

/**
 * The role dialogs (docs/guidelines/forms-ux-standard.md): create and edit are forms whose fields come from
 * RoleFormsService; delete and the unsaved-rights question are confirmations. Every dialog asks the screen to close
 * through an output, so the screen decides (a changed form asks first).
 */
@Component({
  selector: 'app-role-modals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    FormField,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTControlComponent,
    SMTSelectComponent,
    SMTInputComponent,
    UiFocusFirstInvalidDirective,
    UiFormActionsComponent,
    UiFormErrorSummaryComponent,
  ],
  templateUrl: './role-modals.component.html',
  styles: [
    `
      .modal-body-form {
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
      .modal-delete-body {
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      .delete-title {
        font-size: 13px;
        margin: 0;
      }
      .delete-desc {
        font-size: 11px;
        color: var(--text-muted);
      }
      .font-mono {
        font-family: monospace;
      }
    `,
  ],
})
export class RoleModalsComponent {
  private readonly i18n = inject(I18nService);
  private readonly injector = inject(Injector);

  readonly createForm = input.required<FieldTree<NewRoleForm>>();
  readonly editForm = input.required<FieldTree<EditRoleForm>>();

  readonly isCreateModalOpen = input(false);

  readonly isEditModalOpen = input(false);
  readonly editingRole = input<Role | null>(null);

  readonly isDeleteModalOpen = input(false);
  readonly deletingRole = input<Role | null>(null);

  readonly isDiscardPermissionsModalOpen = input(false);
  readonly selectedRole = input<Role | null>(null);
  readonly dirtyPermissionsCount = input(0);

  readonly isSubmittingRole = input(false);
  readonly isSaving = input(false);

  readonly closeCreate = output<void>();
  readonly submitCreate = output<void>();

  readonly closeEdit = output<void>();
  readonly submitEdit = output<void>();

  readonly closeDelete = output<void>();
  readonly confirmDelete = output<void>();

  readonly closeDiscard = output<void>();
  readonly confirmDiscardAndSwitch = output<void>();
  readonly saveAndSwitch = output<void>();

  readonly createErrors = model<RoleFormErrors>(NO_ERRORS);
  readonly editErrors = model<RoleFormErrors>(NO_ERRORS);

  private readonly editedErrors: Partial<Record<'create' | 'edit', RoleFormErrors>> = {};

  private readonly stateMemo = optionsMemo<SMTSelectOption<string>[]>();

  constructor() {
    // A refused save marks fields invalid from the server's answer: focus goes to the first of them.
    effect(() => this.focusServerErrors('create', this.createErrors()));
    effect(() => this.focusServerErrors('edit', this.editErrors()));
  }

  /** Keep the summary and field in sync without stealing focus while typing. */
  edited(mode: 'create' | 'edit', field: string): void {
    const target = mode === 'create' ? this.createErrors : this.editErrors;
    const errors = target();
    if (!(field in errors.fields)) return;
    const next = {
      ...errors,
      fields: Object.fromEntries(Object.entries(errors.fields).filter(([name]) => name !== field)),
    };
    this.editedErrors[mode] = next;
    target.set(next);
  }

  stateOptions(): SMTSelectOption<string>[] {
    return this.stateMemo([this.i18n.currentLang()], () => [
      { id: 'A', label: this.i18n.translate('iam.roles.editor.state_active') },
      { id: 'P', label: this.i18n.translate('iam.roles.editor.state_passive') },
    ]);
  }

  /** The server's messages for the summary: fields the dialog draws link to them, others stand alone. */
  summary(errors: RoleFormErrors, prefix: 'role-create' | 'role-edit'): UiFormErrorItem[] {
    const ids: Record<string, string> = { name: 'name', orderNo: 'order', state: 'state' };
    return [
      ...Object.entries(errors.fields).map(([field, message]) => ({ fieldId: `${prefix}-${ids[field]}`, message })),
      ...errors.other.map((message) => ({ message })),
    ];
  }

  private focusServerErrors(mode: 'create' | 'edit', errors: RoleFormErrors): void {
    if (errors === this.editedErrors[mode] || Object.keys(errors.fields).length === 0) return;
    const form = document.getElementById(`role-${mode}-form`);
    if (form) focusFirstInvalid(form, this.injector);
  }
}
