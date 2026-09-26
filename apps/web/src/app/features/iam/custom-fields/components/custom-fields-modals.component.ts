import { Component, EventEmitter, inject, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CustomField, CustomFieldFormData } from '../custom-fields.models';
import { UiModalComponent } from '../../../../shared/ui/ui-modal.component';
import { UiButtonComponent } from '../../../../shared/ui/ui-button.component';
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../../shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '../../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../../shared/ui-kit/components/forms/radio-group';
import { SMTTextareaComponent, SMTTextareaValueAccessor } from '../../../../shared/ui-kit/components/forms/textarea';
import { SMTCheckboxComponent, SMTCheckboxValueAccessor } from '../../../../shared/ui-kit/components/forms/checkbox';

const ENTITY_TYPES: readonly [string, string][] = [
  ['USER', 'iam.polzovatel_user'],
  ['PROJECT', 'iam.proekt_project'],
  ['TASK', 'iam.zadacha_task'],
  ['NOTE', 'iam.zametka_note'],
];

const FIELD_TYPES: readonly [string, string][] = [
  ['string', 'iam.tekst_string'],
  ['number', 'iam.chislo_number'],
  ['boolean', 'iam.logicheskiy_pereklyuchatel_boolean'],
  ['date', 'iam.data_date'],
  ['select', 'iam.vypadayuschiy_spisok_select'],
  ['user_ref', 'iam.ssylka_na_polzovatelya_user_ref'],
];

@Component({
  selector: 'app-custom-fields-modals',
  standalone: true,
  imports: [SMTInputComponent, SMTInputValueAccessor, SMTSelectComponent, SMTSelectValueAccessor,
    SMTTextareaComponent, SMTTextareaValueAccessor, SMTCheckboxComponent, SMTCheckboxValueAccessor,
    CommonModule,
    FormsModule,
    UiModalComponent,
    UiButtonComponent,
    TranslatePipe
  ],
  template: `
    <!-- Create / Edit Modal -->
    <ui-modal
      *ngIf="showModal"
      [isOpen]="showModal"
      [title]="(editingField ? 'iam.edit_field' : 'iam.new_custom_field') | t"
      [hasFooter]="true"
      (close)="closeModal.emit()"
    >
      <form id="customFieldForm" body class="modal-form" (ngSubmit)="saveField.emit()">
        <!-- Entity Target (only in creation) -->
        <div class="form-group" *ngIf="!editingField">
          <label class="form-label" for="custom-field-entity">
            {{ 'iam.celevaya_suschnost' | t }} <span class="req" aria-hidden="true">*</span>
          </label>
          <smt-select
            smtTriggerId="custom-field-entity"
            name="entityType"
            [(ngModel)]="formData.entityType"
            [options]="entityOptions()"
            [allowClear]="false"
            required />
        </div>

        <!-- Code & Name -->
        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label" for="custom-field-code">
              {{ 'iam.kod_polya_slug' | t }} <span class="req" aria-hidden="true">*</span>
            </label>
            <!-- The native input event bubbles from the inner field; the page cleans the code from it. -->
            <smt-input
              class="font-mono"
              smtFieldId="custom-field-code"
              name="code"
              [(ngModel)]="formData.code"
              (input)="codeInput.emit($event)"
              [disabled]="!!editingField"
              [placeholder]="'iam.naprimer_inn_budget' | t"
              [maxLength]="64"
              autocomplete="off"
              [smtInvalid]="!!formError"
              [smtDescribedBy]="formError ? 'custom-field-form-error' : null"
              required />
            <span class="form-hint" *ngIf="!editingField">{{ 'iam.kod_polya_help' | t }}</span>
            <span class="form-hint readonly-hint" *ngIf="editingField">{{ 'iam.kod_polya_readonly' | t }}</span>
          </div>

          <div class="form-group flex-1">
            <label class="form-label" for="custom-field-name">
              {{ 'iam.nazvanie_polya' | t }} <span class="req" aria-hidden="true">*</span>
            </label>
            <smt-input
              smtFieldId="custom-field-name"
              name="name"
              [(ngModel)]="formData.name"
              [placeholder]="'iam.naprimer_inn_byudzhet_proekta' | t"
              [maxLength]="100"
              [smtInvalid]="!!formError"
              [smtDescribedBy]="formError ? 'custom-field-form-error' : null"
              required />
          </div>
        </div>

        <!-- Type, Default Value & Order -->
        <div class="form-row">
          <div class="form-group flex-1" *ngIf="!editingField">
            <label class="form-label" for="custom-field-type">
              {{ 'iam.tip_dannyh' | t }} <span class="req" aria-hidden="true">*</span>
            </label>
            <smt-select
              smtTriggerId="custom-field-type"
              name="fieldType"
              [(ngModel)]="formData.fieldType"
              [options]="fieldTypeOptions()"
              [allowClear]="false"
              required />
          </div>

          <div class="form-group flex-1">
            <label class="form-label" for="custom-field-default">
              {{ 'iam.znachenie_po_umolchaniyu' | t }}
            </label>
            <smt-input
              smtFieldId="custom-field-default"
              name="defaultValue"
              [(ngModel)]="formData.defaultValue"
              [placeholder]="'iam.ne_obyazatelno' | t"
              [maxLength]="255" />
          </div>

          <div class="form-group order-input-group">
            <label class="form-label" for="custom-field-order">
              {{ 'iam.poryadok_sortirovki' | t }}
            </label>
            <smt-input
              class="font-mono"
              smtFieldId="custom-field-order"
              name="orderNo"
              type="number"
              [(ngModel)]="formData.orderNo"
              [placeholder]="'iam.poryadok_sortirovki_hint' | t"
              [smtMin]="0"
              [smtMax]="99999" />
          </div>
        </div>

        <!-- Options for select type -->
        <div class="form-group" *ngIf="formData.fieldType === 'select'">
          <label class="form-label" for="custom-field-options">
            {{ 'iam.varianty_spiska' | t }} <span class="req" aria-hidden="true">*</span>
          </label>
          <smt-textarea
            smtFieldId="custom-field-options"
            name="optionsText"
            [(ngModel)]="formData.optionsText"
            [rows]="4"
            [placeholder]="'iam.po_odnomu_variantu_v_stroke' | t"
            [smtInvalid]="!!formError"
            [smtDescribedBy]="formError ? 'custom-field-form-error' : ''"
            required />
          <span class="form-hint">{{ 'iam.po_odnomu_variantu_v_stroke_dlya_otdelnogo_koda_' | t }}</span>
        </div>

        <!-- Required flag, saved with the form -->
        <div class="form-group checkbox-group">
          <div smt-checkbox name="isRequired" [(ngModel)]="formData.isRequired">{{ 'iam.obyazatelnoe_dlya_zapolneniya' | t }}</div>
        </div>

        <p *ngIf="formError" id="custom-field-form-error" class="form-error" role="alert">{{ formError }}</p>
      </form>

      <div footer class="modal-footer-actions">
        <ui-button type="button" variant="secondary" (onClick)="closeModal.emit()">{{ 'common.cancel' | t }}</ui-button>
        <ui-button type="submit" form="customFieldForm" variant="primary" [loading]="saving" (onClick)="saveField.emit()">{{ 'common.save' | t }}</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    :host {
      display: contents;
    }

    .modal-form {
      display: flex;
      flex-direction: column;
      gap: 16px;
      padding: 4px 0;
    }

    .form-row {
      display: flex;
      gap: 16px;
    }

    .flex-1 { flex: 1; }

    .order-input-group {
      flex: 0 0 110px;
    }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .form-label {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-main);
    }

    .req { color: var(--danger-text); }

    .form-hint {
      color: var(--text-light);
      font-size: 12px;
      line-height: 1.4;
    }

    .readonly-hint {
      color: var(--warning);
    }

    .form-error {
      margin: 0;
      color: var(--danger-text);
      font-size: 13px;
      padding: 8px 12px;
      background: var(--danger-bg);
      border-radius: 6px;
      border: 1px solid var(--danger-border);
    }

    .checkbox-group {
      margin-top: 4px;
    }

    .modal-footer-actions {
      display: flex;
      justify-content: flex-end;
      gap: 12px;
      width: 100%;
    }

    .delete-confirmation {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .delete-confirmation p { margin: 0; font-size: 14px; }
    .delete-confirmation span { color: var(--text-muted); font-size: 12px; }

    @media (max-width: 768px) {
      .form-row {
        flex-direction: column;
        gap: 12px;
      }

      .order-input-group {
        flex: 1 1 auto;
      }
    }
  `]
})
export class CustomFieldsModalsComponent {
  private readonly i18n = inject(I18nService);

  @Input() showModal = false;
  @Input() editingField: CustomField | null = null;
  @Input() formData!: CustomFieldFormData;
  @Input() formError = '';
  @Input() saving = false;

  @Output() closeModal = new EventEmitter<void>();
  @Output() saveField = new EventEmitter<void>();
  @Output() codeInput = new EventEmitter<Event>();
  private readonly entityMemo = optionsMemo<SMTSelectOption<string>[]>();
  private readonly fieldTypeMemo = optionsMemo<SMTSelectOption<string>[]>();

  entityOptions(): SMTSelectOption<string>[] {
    return this.entityMemo([this.i18n.currentLang()], () =>
      ENTITY_TYPES.map(([id, key]) => ({ id, label: this.i18n.translate(key) })));
  }

  fieldTypeOptions(): SMTSelectOption<string>[] {
    return this.fieldTypeMemo([this.i18n.currentLang()], () =>
      FIELD_TYPES.map(([id, key]) => ({ id, label: this.i18n.translate(key) })));
  }
}
