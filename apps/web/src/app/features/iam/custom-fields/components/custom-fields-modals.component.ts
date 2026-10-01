import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { CustomField, CustomFieldFormData } from '../custom-fields.models';
import { CustomFieldsFormService } from '../services/custom-fields-form.service';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { SMTTextareaComponent } from '@shared/ui-kit/components/forms/textarea';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';

const ENTITY_TYPES: readonly [string, string][] = [
  ['USER', 'iam.custom_fields.editor.entity_user'],
  ['PROJECT', 'iam.custom_fields.editor.entity_project'],
  ['TASK', 'iam.custom_fields.editor.entity_task'],
  ['NOTE', 'iam.custom_fields.entity_note'],
];

const FIELD_TYPES: readonly [string, string][] = [
  ['string', 'iam.custom_fields.editor.type_string'],
  ['number', 'iam.custom_fields.editor.type_number'],
  ['boolean', 'iam.custom_fields.editor.type_boolean'],
  ['date', 'iam.data_date'],
  ['select', 'iam.custom_fields.editor.type_select'],
  ['user_ref', 'iam.custom_fields.editor.type_user_ref'],
];

@Component({
  selector: 'app-custom-fields-modals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTSelectComponent,
    SMTTextareaComponent,
    SMTCheckboxComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTButtonComponent,
    TranslatePipe,
  ],
  templateUrl: './custom-fields-modals.component.html',
  styleUrl: './custom-fields-modals.component.css',
})
export class CustomFieldsModalsComponent {
  private readonly i18n = inject(I18nService);

  private readonly formRules = inject(CustomFieldsFormService);

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

  /**
   * The page cleans the code from the input event; the field shows the cleaned text at once. When the
   * cleaned text equals the one bound last, [value] does not change, so the field is written here.
   */
  onCodeInput(event: Event, field: SMTInputComponent): void {
    this.codeInput.emit(event);
    const native = event.target as HTMLInputElement | null;
    if (!native) return;
    const cleaned = this.formRules.sanitizeCode(native.value);
    if (cleaned === native.value) return;
    field.value.set(cleaned);
    native.value = cleaned;
  }

  fieldTypeOptions(): SMTSelectOption<string>[] {
    return this.fieldTypeMemo([this.i18n.currentLang()], () =>
      FIELD_TYPES.map(([id, key]) => ({ id, label: this.i18n.translate(key) })),
    );
  }
}
