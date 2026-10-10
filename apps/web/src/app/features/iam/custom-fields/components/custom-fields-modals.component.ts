import { ChangeDetectionStrategy, Component, effect, inject, Injector, input, output } from '@angular/core';
import { FieldTree, FormField } from '@angular/forms/signals';

import { CustomField, CustomFieldFormData } from '../custom-fields.models';
import { CustomFieldsFormService } from '../services/custom-fields-form.service';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { SMTTextareaComponent } from '@shared/ui-kit/components/forms/textarea';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { focusFirstInvalid, UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { ProblemFieldErrors } from '@shared/ui/problem-fields';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFormErrorItem, UiFormErrorSummaryComponent } from '@shared/ui/ui-form-error-summary.component';

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
  ['datetime', 'iam.custom_fields.editor.type_datetime'],
  ['time', 'iam.custom_fields.editor.type_time'],
];

@Component({
  selector: 'app-custom-fields-modals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTSelectComponent,
    SMTTextareaComponent,
    SMTCheckboxComponent,
    SMTControlComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    FormField,
    UiFocusFirstInvalidDirective,
    UiFormActionsComponent,
    UiFormErrorSummaryComponent,
    TranslatePipe,
  ],
  templateUrl: './custom-fields-modals.component.html',
  styleUrl: './custom-fields-modals.component.css',
})
export class CustomFieldsModalsComponent {
  private readonly i18n = inject(I18nService);

  private readonly formRules = inject(CustomFieldsFormService);
  private readonly injector = inject(Injector);

  /** The page's form of the field (CustomFieldsFormService.schema). */
  readonly fieldForm = input.required<FieldTree<CustomFieldFormData>>();

  readonly editingField = input<CustomField | null>(null);
  readonly saving = input(false);

  readonly showModal = input(false);
  /** The server's messages of a refused save, shown under the fields and, for the others, in the summary. */
  readonly serverErrors = input<ProblemFieldErrors>({ fields: {}, other: [] });

  readonly closeModal = output<void>();
  readonly saveField = output<void>();
  readonly codeInput = output<Event>();

  private readonly entityMemo = optionsMemo<SMTSelectOption<string>[]>();

  constructor() {
    // A refused save marks fields invalid from the server's answer: focus goes to the first of them.
    effect(() => {
      if (Object.keys(this.serverErrors().fields).length === 0) return;
      const form = document.getElementById('customFieldForm');
      if (form) focusFirstInvalid(form, this.injector);
    });
  }

  serverError(field: string): string {
    return this.serverErrors().fields[field] ?? '';
  }

  /** The summary: field messages link to their field, messages of fields the dialog does not draw stand alone. */
  summary(): UiFormErrorItem[] {
    const ids: Record<string, string> = {
      entityType: 'custom-field-entity',
      code: 'custom-field-code',
      name: 'custom-field-name',
      fieldType: 'custom-field-type',
      defaultValue: 'custom-field-default',
      orderNo: 'custom-field-order',
      optionsText: 'custom-field-options',
    };
    const { fields, other } = this.serverErrors();
    return [
      ...Object.entries(fields).map(([field, message]) => ({ fieldId: ids[field], message })),
      ...other.map((message) => ({ message })),
    ];
  }
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
