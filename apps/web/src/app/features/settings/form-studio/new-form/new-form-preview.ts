import type { FormFieldMeta, FormMeta } from '@core/models/form-meta.models';
import type { StudioLanguage } from '../form-studio.models';
import type { SimpleFormDeclaration } from './new-form.models';

/** Synthetic metadata for the existing renderer; the server and CLI validate the source independently. */
export function simpleFormPreview(draft: SimpleFormDeclaration, language: StudioLanguage): FormMeta {
  const fields: FormFieldMeta[] = [
    {
      key: 'name',
      labelKey: 'settings.simple_form.name_field',
      type: 'text',
      required: true,
      minLength: 1,
      maxLength: 255,
    },
    {
      key: 'code',
      labelKey: 'settings.simple_form.code_field',
      type: 'text',
      required: true,
      minLength: 1,
      maxLength: 64,
      pattern: '[a-z0-9_-]+',
    },
    ...draft.fields.map((field): FormFieldMeta => ({
      key: field.id,
      labelKey: '',
      label: field.labels[language],
      type: field.type === 'bool' ? 'boolean' : field.type,
      required: field.required,
      ...(field.maxLength != null ? { maxLength: field.maxLength, ...(field.required ? { minLength: 1 } : {}) } : {}),
      ...(field.scale != null ? { scale: field.scale } : {}),
    })),
  ];
  return {
    code: `${draft.module.code}.${draft.entity.name}`,
    fields,
    layout: [{ key: 'main', labelKey: 'entity.section.main', fields: fields.map((field) => field.key) }],
    actions: [],
    capabilities: [],
  };
}
