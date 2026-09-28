import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { Role } from '@core/models/rbac.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-role-modals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTSelectComponent,
    SMTInputComponent,
  ],
  templateUrl: './role-modals.component.html',
  styles: [
    `
      .modal-body-form {
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .modal-field {
        display: flex;
        flex-direction: column;
        gap: 5px;
      }
      .modal-label {
        font-size: 12px;
        font-weight: 500;
        color: var(--text-main);
      }
      .modal-help {
        font-size: 11px;
        color: var(--text-muted);
      }
      .field-error {
        font-size: 10px;
        color: var(--danger);
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

      .req {
        color: var(--danger);
      }
      .font-mono {
        font-family: monospace;
      }
    `,
  ],
})
export class RoleModalsComponent {
  private readonly i18n = inject(I18nService);

  readonly isCreateModalOpen = input(false);
  readonly isCreateSubmitted = input(false);

  readonly isEditModalOpen = input(false);
  readonly isEditSubmitted = input(false);
  readonly editingRole = input<Role | null>(null);

  readonly isDeleteModalOpen = input(false);
  readonly deletingRole = input<Role | null>(null);

  readonly isDiscardPermissionsModalOpen = input(false);
  readonly selectedRole = input<Role | null>(null);
  readonly dirtyPermissionsCount = input(0);

  readonly isSubmittingRole = input(false);
  readonly isSaving = input(false);
  readonly newRoleForm = input({ name: '', orderNo: 0 });
  readonly editRoleForm = input({ name: '', state: 'A', orderNo: 0 });

  readonly closeCreate = output<void>();
  readonly submitCreate = output<void>();

  readonly closeEdit = output<void>();
  readonly submitEdit = output<void>();

  readonly closeDelete = output<void>();
  readonly confirmDelete = output<void>();

  readonly closeDiscard = output<void>();
  readonly confirmDiscardAndSwitch = output<void>();
  readonly saveAndSwitch = output<void>();

  private readonly stateMemo = optionsMemo<SMTSelectOption<string>[]>();

  stateOptions(): SMTSelectOption<string>[] {
    return this.stateMemo([this.i18n.currentLang()], () => [
      { id: 'A', label: this.i18n.translate('iam.aktivna_a') },
      { id: 'P', label: this.i18n.translate('iam.otklyuchena_p') },
    ]);
  }
}
