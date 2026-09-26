import { Component, EventEmitter, inject, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Role } from '../../../../core/models/rbac.models';
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { UiButtonComponent } from '../../../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../../../shared/ui/ui-modal.component';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '../../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../../shared/ui-kit/components/forms/radio-group';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../../shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-role-modals',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslatePipe, UiButtonComponent, UiModalComponent, SMTSelectComponent, SMTSelectValueAccessor, SMTInputComponent, SMTInputValueAccessor],
  template: `
    <!-- Create Role Modal -->
    <ui-modal
      [isOpen]="isCreateModalOpen"
      [title]="'iam.sozdanie_novoy_roli' | t"
      size="sm"
      (close)="closeCreate.emit()"
    >
      <div body class="modal-body-form">
        <div class="modal-field">
          <label class="modal-label" for="role-create-name">{{ 'iam.nazvanie_roli' | t }} <span class="req">*</span></label>
          <smt-input
            smtFieldId="role-create-name"
            name="roleCreateName"
            required
            [smtInvalid]="isCreateSubmitted && !newRoleForm.name.trim()"
            [smtDescribedBy]="isCreateSubmitted && !newRoleForm.name.trim() ? 'role-create-name-error' : null"
            [(ngModel)]="newRoleForm.name"
            [placeholder]="'iam.naprimer_starshiy_analitik_dannyh' | t" />
          <span id="role-create-name-error" class="field-error" *ngIf="isCreateSubmitted && !newRoleForm.name.trim()">{{ 'iam.ukazhite_nazvanie_roli' | t }}</span>
        </div>
        <div class="modal-field">
          <label class="modal-label" for="role-create-order">{{ 'iam.poryadok_otobrazheniya' | t }}</label>
          <smt-input
            smtFieldId="role-create-order"
            name="roleCreateOrder"
            type="number"
            class="font-mono"
            [(ngModel)]="newRoleForm.orderNo"
            placeholder="0" />
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="closeCreate.emit()">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmittingRole" (onClick)="submitCreate.emit()">{{ 'common.create' | t }}</ui-button>
      </div>
    </ui-modal>

    <!-- Edit Role Modal -->
    <ui-modal
      [isOpen]="isEditModalOpen"
      [title]="'iam.redaktirovanie_roli' | t"
      size="sm"
      (close)="closeEdit.emit()"
    >
      <div body class="modal-body-form" *ngIf="editingRole as r">
        <div class="modal-field">
          <label class="modal-label" for="role-edit-name">{{ 'iam.nazvanie_roli' | t }} <span class="req">*</span></label>
          <smt-input smtFieldId="role-edit-name" name="roleEditName" required
            [smtInvalid]="isEditSubmitted && !editRoleForm.name.trim()"
            [smtDescribedBy]="isEditSubmitted && !editRoleForm.name.trim() ? 'role-edit-name-error' : null"
            [(ngModel)]="editRoleForm.name" />
          <span id="role-edit-name-error" class="field-error" *ngIf="isEditSubmitted && !editRoleForm.name.trim()">{{ 'iam.ukazhite_nazvanie_roli' | t }}</span>
        </div>
        <div class="modal-field">
          <label class="modal-label" for="role-edit-state">{{ 'iam.status_aktivnosti' | t }}</label>
          <smt-select
            smtTriggerId="role-edit-state"
            name="roleEditState"
            [(ngModel)]="editRoleForm.state"
            [options]="stateOptions()"
            [allowClear]="false"
            [disabled]="r.pcode === 'admin'" />
          <span class="modal-help" *ngIf="r.pcode === 'admin'">{{ 'iam.rol_superadministratora_vsegda_aktivna' | t }}</span>
        </div>
        <div class="modal-field">
          <label class="modal-label" for="role-edit-order">{{ 'iam.poryadok_otobrazheniya' | t }}</label>
          <smt-input smtFieldId="role-edit-order" name="roleEditOrder" type="number" class="font-mono" [(ngModel)]="editRoleForm.orderNo" />
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="closeEdit.emit()">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmittingRole" (onClick)="submitEdit.emit()">{{ 'common.save' | t }}</ui-button>
      </div>
    </ui-modal>

    <!-- Delete Role Modal -->
    <ui-modal
      [isOpen]="isDeleteModalOpen"
      [title]="'iam.udalenie_roli' | t"
      size="sm"
      [dismissible]="!isSubmittingRole"
      (close)="closeDelete.emit()"
    >
      <div body class="modal-delete-body" *ngIf="deletingRole as r">
        <p class="delete-title">
          {{ 'iam.vy_deystvitelno_hotite_udalit_polzovatelskuyu_ro' | t }} <strong>{{ r.name }}</strong>?
        </p>
        <span class="delete-desc">{{ 'iam.vse_naznachennye_prava_etoy_roli_budut_udaleny_e' | t }}</span>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" [disabled]="isSubmittingRole" (onClick)="closeDelete.emit()">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="danger" size="md" [loading]="isSubmittingRole" (onClick)="confirmDelete.emit()">{{ 'common.delete' | t }}</ui-button>
      </div>
    </ui-modal>

    <!-- Unsaved Changes Confirmation Modal -->
    <ui-modal
      [isOpen]="isDiscardPermissionsModalOpen"
      [title]="'iam.nesohranennye_izmeneniya_prav' | t"
      size="sm"
      [dismissible]="!isSaving"
      (close)="closeDiscard.emit()"
    >
      <div body class="modal-delete-body" *ngIf="selectedRole as r">
        <p class="delete-title">
          {{ 'iam.u_vas_est_nesohranennye_izmeneniya_v_matrice' | t:{name: r.name, count: dirtyPermissionsCount} }}
        </p>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" [disabled]="isSaving" (onClick)="closeDiscard.emit()">
          {{ 'common.cancel' | t }}
        </ui-button>
        <ui-button variant="danger" size="md" [disabled]="isSaving" (onClick)="confirmDiscardAndSwitch.emit()">
          {{ 'iam.sbrosit_i_pereyti' | t }}
        </ui-button>
        <ui-button variant="primary" size="md" [loading]="isSaving" (onClick)="saveAndSwitch.emit()">
          {{ 'iam.sohranit_i_pereyti' | t }}
        </ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .modal-body-form { display: flex; flex-direction: column; gap: 14px; }
    .modal-field { display: flex; flex-direction: column; gap: 5px; }
    .modal-label { font-size: 12px; font-weight: 500; color: var(--text-main); }
    .modal-help { font-size: 11px; color: var(--text-muted); }
    .field-error { font-size: 10px; color: var(--danger); }

    .modal-delete-body { display: flex; flex-direction: column; gap: 6px; }
    .delete-title { font-size: 13px; margin: 0; }
    .delete-desc { font-size: 11px; color: var(--text-muted); }

    .req { color: var(--danger); }
    .font-mono { font-family: monospace; }
  `]
})
export class RoleModalsComponent {
  private readonly i18n = inject(I18nService);
  private readonly stateMemo = optionsMemo<SMTSelectOption<string>[]>();

  @Input() isCreateModalOpen = false;
  @Input() isCreateSubmitted = false;
  @Input() newRoleForm = { name: '', orderNo: 0 };

  @Input() isEditModalOpen = false;
  @Input() isEditSubmitted = false;
  @Input() editingRole: Role | null = null;
  @Input() editRoleForm = { name: '', state: 'A', orderNo: 0 };

  @Input() isDeleteModalOpen = false;
  @Input() deletingRole: Role | null = null;

  @Input() isDiscardPermissionsModalOpen = false;
  @Input() selectedRole: Role | null = null;
  @Input() dirtyPermissionsCount = 0;

  @Input() isSubmittingRole = false;
  @Input() isSaving = false;

  @Output() closeCreate = new EventEmitter<void>();
  @Output() submitCreate = new EventEmitter<void>();

  @Output() closeEdit = new EventEmitter<void>();
  @Output() submitEdit = new EventEmitter<void>();

  @Output() closeDelete = new EventEmitter<void>();
  @Output() confirmDelete = new EventEmitter<void>();

  @Output() closeDiscard = new EventEmitter<void>();
  @Output() confirmDiscardAndSwitch = new EventEmitter<void>();
  @Output() saveAndSwitch = new EventEmitter<void>();

  stateOptions(): SMTSelectOption<string>[] {
    return this.stateMemo([this.i18n.currentLang()], () => [
      { id: 'A', label: this.i18n.translate('iam.aktivna_a') },
      { id: 'P', label: this.i18n.translate('iam.otklyuchena_p') },
    ]);
  }
}
