import { Injectable, inject } from '@angular/core';
import { disabled, maxLength, required, SchemaFn, validate } from '@angular/forms/signals';
import { I18nService } from '@core/services/i18n.service';
import { CustomField, CustomFieldFormData } from '../custom-fields.models';

export const RESERVED_CODES = new Set<string>([
  'id',
  'code',
  'name',
  'title',
  'state',
  'status',
  'created_at',
  'modified_at',
  'created_by',
  'modified_by',
  'login',
  'email',
  'password',
  'task_type',
  'priority',
  'description',
  'attributes',
  'options',
  'values',
]);

@Injectable({
  providedIn: 'root',
})
export class CustomFieldsFormService {
  private readonly uiI18n = inject(I18nService);

  createInitialFormData(selectedEntity: string, totalCount: number): CustomFieldFormData {
    return {
      entityType: selectedEntity !== 'ALL' ? selectedEntity : 'USER',
      code: '',
      name: '',
      fieldType: 'string',
      isRequired: false,
      defaultValue: '',
      orderNo: (totalCount + 1) * 10,
      optionsText: '',
    };
  }

  fromCustomField(field: CustomField): CustomFieldFormData {
    return {
      entityType: field.entityType,
      code: field.code,
      name: field.name,
      fieldType: field.fieldType,
      isRequired: field.isRequired,
      defaultValue: field.defaultValue || '',
      orderNo: field.orderNo || 0,
      optionsText: this.optionsToText(field.optionsJson),
    };
  }

  sanitizeCode(value: string): string {
    if (!value) return '';
    return value
      .toLowerCase()
      .replace(/[^a-z0-9_]/g, '_')
      .replace(/_+/g, '_');
  }

  /**
   * The rules of the field dialog (docs/guidelines/forms-ux-standard.md, section 4): name and code are required, a
   * new code is a slug outside the reserved words, a drop-down needs at least one value. The code of an edited field
   * cannot change, so it is not checked.
   */
  schema(isEditing: () => boolean): SchemaFn<CustomFieldFormData> {
    const t = (key: string) => () => this.uiI18n.translate(key);
    return (path) => {
      required(path.entityType);
      required(path.fieldType);
      required(path.name, { message: t('iam.custom_fields.editor.enter_field_name') });
      validate(path.name, ({ value }) =>
        !value() || value().trim()
          ? null
          : { kind: 'required', message: this.uiI18n.translate('iam.custom_fields.editor.enter_field_name') },
      );
      maxLength(path.name, 100);
      disabled(path.code, isEditing);
      required(path.code, { message: t('iam.custom_fields.editor.enter_field_code') });
      validate(path.code, ({ value }) => {
        const key = this.codeProblem(value());
        return key ? { kind: 'code', message: this.uiI18n.translate(key) } : null;
      });
      maxLength(path.code, 64);
      maxLength(path.defaultValue, 255);
      required(path.optionsText, {
        when: ({ valueOf }) => valueOf(path.fieldType) === 'select',
        message: t('iam.custom_fields.editor.options_required'),
      });
      validate(path.optionsText, ({ value, valueOf }) =>
        valueOf(path.fieldType) !== 'select' || !value() || this.parseOptionsText(value()).length > 0
          ? null
          : { kind: 'required', message: this.uiI18n.translate('iam.custom_fields.editor.options_required') },
      );
    };
  }

  /** The catalog key of what is wrong with a typed code, or null when it is a free slug (or empty). */
  codeProblem(value: string): string | null {
    const code = (value || '').trim();
    if (!code) return null;
    if (!/^[a-z][a-z0-9_]{1,63}$/.test(code)) return 'iam.invalid_code_slug';
    if (RESERVED_CODES.has(code.toLowerCase())) return 'iam.custom_fields.editor.code_reserved';
    return null;
  }

  parseOptionsText(value: string | undefined): Array<string | { value: string; label: string }> {
    return (value || '')
      .split(/\r?\n/)
      .map((option) => option.trim())
      .filter((option, index, all) => option.length > 0 && all.indexOf(option) === index)
      .map((option) => {
        const separatorIndex = option.indexOf('|');
        if (separatorIndex < 0) return option;

        const optionValue = option.slice(0, separatorIndex).trim();
        const optionLabel = option.slice(separatorIndex + 1).trim();
        return optionValue && optionLabel ? { value: optionValue, label: optionLabel } : option;
      });
  }

  optionsToText(optionsJson: string | undefined): string {
    if (!optionsJson) return '';
    try {
      const options: unknown = JSON.parse(optionsJson);
      if (!Array.isArray(options)) return '';
      return options
        .map((option) => {
          if (typeof option !== 'object' || option === null) return String(option);
          if (!('value' in option)) return '';

          const optionValue = String((option as { value: unknown }).value);
          const optionLabel = 'label' in option ? String((option as { label: unknown }).label) : optionValue;
          return optionValue === optionLabel ? optionValue : `${optionValue} | ${optionLabel}`;
        })
        .filter(Boolean)
        .join('\n');
    } catch {
      return '';
    }
  }
}
