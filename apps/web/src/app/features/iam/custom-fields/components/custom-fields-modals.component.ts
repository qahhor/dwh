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
  styleUrl: './custom-fields-modals.component.css',
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
