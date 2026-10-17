import type { FormFieldMeta, FormMeta } from '@core/models/form-meta.models';
import type { StudioLanguage } from '../form-studio.models';
import type { SimpleField, SimpleFormDeclaration } from './new-form.models';

/**
 * Synthetic metadata for the existing renderer; the server and CLI validate the source independently. A hidden field
 * is served nowhere, so the preview leaves it out (ADR-0041). A select shows its own choices; a reference to a live
 * form and a user field pick from the same lists the record form uses; the items of a reference list are offered by
 * the activated form.
 */
export function simpleFormPreview(source: SimpleFormDeclaration, language: StudioLanguage): FormMeta {
  const draft = { ...source, fields: source.fields.filter((field) => !field.hidden) };
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
      required: field.required,
      ...kind(field, language),
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

function kind(
  field: SimpleField,
  language: StudioLanguage,
): Pick<FormFieldMeta, 'type' | 'options' | 'optionLabels' | 'ref'> {
  switch (field.type) {
    case 'bool':
      return { type: 'boolean' };
    case 'select':
      return {
        type: 'select',
        options: (field.options ?? []).map((option) => option.code),
        optionLabels: Object.fromEntries(
          (field.options ?? []).map((option) => [option.code, option.labels[language] || option.code]),
        ),
      };
    case 'user':
      return { type: 'ref', ref: { path: '/entities/md.users', labelField: 'name', keyField: 'id', paged: true } };
    case 'ref':
      return field.target?.kind === 'form' && field.target.code
        ? {
            type: 'ref',
            ref: { path: `/entities/${field.target.code}`, labelField: 'name', keyField: 'id', paged: true },
          }
        : { type: 'enum', options: [], optionLabels: {} };
    default:
      return { type: field.type };
  }
}
