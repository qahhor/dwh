import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { CustomField, CustomFieldFormData } from '../custom-fields.models';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { SMTTextareaComponent, SMTTextareaValueAccessor } from '@shared/ui-kit/components/forms/textarea';
import { SMTCheckboxComponent, SMTCheckboxValueAccessor } from '@shared/ui-kit/components/forms/checkbox';

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
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTInputValueAccessor,
    SMTSelectComponent,
    SMTSelectValueAccessor,
    SMTTextareaComponent,
    SMTTextareaValueAccessor,
    SMTCheckboxComponent,
    SMTCheckboxValueAccessor,
    FormsModule,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTButtonComponent,
    TranslatePipe,
  ],
  templateUrl: './custom-fields-modals.component.html',
  styles: [
    `
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

      .flex-1 {
        flex: 1;
      }

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

      .req {
        color: var(--danger-text);
      }

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

      .delete-confirmation p {
        margin: 0;
        font-size: 14px;
      }
      .delete-confirmation span {
        color: var(--text-muted);
        font-size: 12px;
      }

      @media (max-width: 768px) {
        .form-row {
          flex-direction: column;
          gap: 12px;
        }

        .order-input-group {
          flex: 1 1 auto;
        }
      }
    `,
  ],
})
export class CustomFieldsModalsComponent {
  private readonly i18n = inject(I18nService);

  readonly formData = input.required<CustomFieldFormData>();

  readonly editingField = input<CustomField | null>(null);
  readonly saving = input(false);

  readonly showModal = input(false);
  readonly formError = input('');

  readonly closeModal = output<void>();
  readonly saveField = output<void>();
  readonly codeInput = output<Event>();

  private readonly entityMemo = optionsMemo<SMTSelectOption<string>[]>();
  private readonly fieldTypeMemo = optionsMemo<SMTSelectOption<string>[]>();

  entityOptions(): SMTSelectOption<string>[] {
    return this.entityMemo([this.i18n.currentLang()], () =>
      ENTITY_TYPES.map(([id, key]) => ({ id, label: this.i18n.translate(key) })),
    );
  }

  fieldTypeOptions(): SMTSelectOption<string>[] {
    return this.fieldTypeMemo([this.i18n.currentLang()], () =>
      FIELD_TYPES.map(([id, key]) => ({ id, label: this.i18n.translate(key) })),
    );
  }
}
