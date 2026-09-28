import { Component, inject, input, output } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { Role } from '../../../../core/models/rbac.models';
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { SMTButtonComponent } from '../../../../shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '../../../../shared/ui-kit/components/modal';
import {
  SMTSelectComponent,
  SMTSelectOption,
  SMTSelectValueAccessor,
} from '../../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../../shared/ui-kit/components/forms/radio-group';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../../shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-role-modals',
  imports: [
    FormsModule,
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTSelectComponent,
    SMTSelectValueAccessor,
    SMTInputComponent,
    SMTInputValueAccessor,
  ],
  template: `
    <!-- Create Role Modal -->
    <smt-dialog
      [open]="isCreateModalOpen()"
      [smtTitle]="'iam.sozdanie_novoy_roli' | t"
      smtSize="sm"
      (closed)="closeCreate.emit()"
    >
      <ng-template smtDialogContent>
        <div body class="modal-body-form">
          <div class="modal-field">
            <label class="modal-label" for="role-create-name"
              >{{ 'iam.nazvanie_roli' | t }} <span class="req">*</span></label
            >
            <smt-input
              smtFieldId="role-create-name"
              name="roleCreateName"
              required
              [smtInvalid]="isCreateSubmitted() && !newRoleForm().name.trim()"
              [smtDescribedBy]="isCreateSubmitted() && !newRoleForm().name.trim() ? 'role-create-name-error' : null"
              [(ngModel)]="newRoleForm().name"
              [placeholder]="'iam.naprimer_starshiy_analitik_dannyh' | t"
            />
            @if (isCreateSubmitted() && !newRoleForm().name.trim()) {
              <span id="role-create-name-error" class="field-error">{{ 'iam.ukazhite_nazvanie_roli' | t }}</span>
            }
          </div>
          <div class="modal-field">
            <label class="modal-label" for="role-create-order">{{ 'iam.poryadok_otobrazheniya' | t }}</label>
            <smt-input
              smtFieldId="role-create-order"
              name="roleCreateOrder"
              type="number"
              class="font-mono"
              [(ngModel)]="newRoleForm().orderNo"
              placeholder="0"
            />
          </div>
        </div>
        <div footer>
          <button smt-button type="button" smtVariant="secondary" smtSize="md" (click)="closeCreate.emit()">
            {{ 'common.cancel' | t }}
          </button>
          <button
            smt-button
            type="button"
            smtVariant="primary"
            smtSize="md"
            [smtLoading]="isSubmittingRole()"
            (click)="submitCreate.emit()"
          >
            {{ 'common.create' | t }}
          </button>
        </div>
      </ng-template>
    </smt-dialog>

    <!-- Edit Role Modal -->
    <smt-dialog
      [open]="isEditModalOpen()"
      [smtTitle]="'iam.redaktirovanie_roli' | t"
      smtSize="sm"
      (closed)="closeEdit.emit()"
    >
      <ng-template smtDialogContent>
        @if (editingRole(); as r) {
          <div body class="modal-body-form">
            <div class="modal-field">
              <label class="modal-label" for="role-edit-name"
                >{{ 'iam.nazvanie_roli' | t }} <span class="req">*</span></label
              >
              <smt-input
                smtFieldId="role-edit-name"
                name="roleEditName"
                required
                [smtInvalid]="isEditSubmitted() && !editRoleForm().name.trim()"
                [smtDescribedBy]="isEditSubmitted() && !editRoleForm().name.trim() ? 'role-edit-name-error' : null"
                [(ngModel)]="editRoleForm().name"
              />
              @if (isEditSubmitted() && !editRoleForm().name.trim()) {
                <span id="role-edit-name-error" class="field-error">{{ 'iam.ukazhite_nazvanie_roli' | t }}</span>
              }
            </div>
            <div class="modal-field">
              <label class="modal-label" for="role-edit-state">{{ 'iam.status_aktivnosti' | t }}</label>
              <smt-select
                smtTriggerId="role-edit-state"
                name="roleEditState"
                [(ngModel)]="editRoleForm().state"
                [options]="stateOptions()"
                [allowClear]="false"
                [disabled]="r.pcode === 'admin'"
              />
              @if (r.pcode === 'admin') {
                <span class="modal-help">{{ 'iam.rol_superadministratora_vsegda_aktivna' | t }}</span>
              }
            </div>
            <div class="modal-field">
              <label class="modal-label" for="role-edit-order">{{ 'iam.poryadok_otobrazheniya' | t }}</label>
              <smt-input
                smtFieldId="role-edit-order"
                name="roleEditOrder"
                type="number"
                class="font-mono"
                [(ngModel)]="editRoleForm().orderNo"
              />
            </div>
          </div>
        }
        <div footer>
          <button smt-button type="button" smtVariant="secondary" smtSize="md" (click)="closeEdit.emit()">
            {{ 'common.cancel' | t }}
          </button>
          <button
            smt-button
            type="button"
            smtVariant="primary"
            smtSize="md"
            [smtLoading]="isSubmittingRole()"
            (click)="submitEdit.emit()"
          >
            {{ 'common.save' | t }}
          </button>
        </div>
      </ng-template>
    </smt-dialog>

    <!-- Delete Role Modal -->
    <smt-dialog
      [open]="isDeleteModalOpen()"
      [smtTitle]="'iam.udalenie_roli' | t"
      smtSize="sm"
      [dismissible]="!isSubmittingRole()"
      (closed)="closeDelete.emit()"
    >
      <ng-template smtDialogContent>
        @if (deletingRole(); as r) {
          <div body class="modal-delete-body">
            <p class="delete-title">
              {{ 'iam.vy_deystvitelno_hotite_udalit_polzovatelskuyu_ro' | t }} <strong>{{ r.name }}</strong
              >?
            </p>
            <span class="delete-desc">{{ 'iam.vse_naznachennye_prava_etoy_roli_budut_udaleny_e' | t }}</span>
          </div>
        }
        <div footer>
          <button
            smt-button
            type="button"
            smtVariant="secondary"
            smtSize="md"
            [disabled]="isSubmittingRole()"
            (click)="closeDelete.emit()"
          >
            {{ 'common.cancel' | t }}
          </button>
          <button
            smt-button
            type="button"
            smtVariant="danger"
            smtSize="md"
            [smtLoading]="isSubmittingRole()"
            (click)="confirmDelete.emit()"
          >
            {{ 'common.delete' | t }}
          </button>
        </div>
      </ng-template>
    </smt-dialog>

    <!-- Unsaved Changes Confirmation Modal -->
    <smt-dialog
      [open]="isDiscardPermissionsModalOpen()"
      [smtTitle]="'iam.nesohranennye_izmeneniya_prav' | t"
      smtSize="sm"
      [dismissible]="!isSaving()"
      (closed)="closeDiscard.emit()"
    >
      <ng-template smtDialogContent>
        @if (selectedRole(); as r) {
          <div body class="modal-delete-body">
            <p class="delete-title">
              {{
                'iam.u_vas_est_nesohranennye_izmeneniya_v_matrice' | t: { name: r.name, count: dirtyPermissionsCount() }
              }}
            </p>
          </div>
        }
        <div footer>
          <button
            smt-button
            type="button"
            smtVariant="secondary"
            smtSize="md"
            [disabled]="isSaving()"
            (click)="closeDiscard.emit()"
          >
            {{ 'common.cancel' | t }}
          </button>
          <button
            smt-button
            type="button"
            smtVariant="danger"
            smtSize="md"
            [disabled]="isSaving()"
            (click)="confirmDiscardAndSwitch.emit()"
          >
            {{ 'iam.sbrosit_i_pereyti' | t }}
          </button>
          <button
            smt-button
            type="button"
            smtVariant="primary"
            smtSize="md"
            [smtLoading]="isSaving()"
            (click)="saveAndSwitch.emit()"
          >
            {{ 'iam.sohranit_i_pereyti' | t }}
          </button>
        </div>
      </ng-template>
    </smt-dialog>
  `,
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
