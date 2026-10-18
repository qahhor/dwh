import type { FormFieldMeta, FormMeta } from '@core/models/form-meta.models';
import type { StudioLanguage } from '../form-studio.models';
import {
  NUMBER_TITLE,
  placedLayout,
  SYSTEM_TITLE,
  titleOf,
  type SimpleField,
  type SimpleFormDeclaration,
} from './new-form.models';

/**
 * Synthetic metadata for the existing renderer; the server and CLI validate the source independently. A hidden field
 * is served nowhere, so the preview leaves it out (ADR-0041). A select shows its own choices; a reference to a live
 * form and a user field pick from the same lists the record form uses; the items of a reference list are offered by
 * the activated form. The record's title and the sections and tabs of format 4 are shown as the form will draw them.
 */
export function simpleFormPreview(source: SimpleFormDeclaration, language: StudioLanguage): FormMeta {
  const draft = { ...source, fields: source.fields.filter((field) => !field.hidden) };
  const title = titleOf(source);
  const system: FormFieldMeta[] =
    title === SYSTEM_TITLE
      ? [
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
        ]
      : title === NUMBER_TITLE
        ? [{ key: NUMBER_TITLE, labelKey: 'studio.record.number', type: 'number', required: false, readonly: true }]
        : [];
  const fields: FormFieldMeta[] = [
    ...system,
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
  const systemKeys = system.map((field) => field.key);
  const layout = source.layout ? placedLayout(source.layout, source.fields) : null;
  return {
    code: `${draft.module.code}.${draft.entity.name}`,
    fields,
    layout: layout
      ? layout.sections.map((section, index) => ({
          key: section.key,
          labelKey: '',
          label: section.labels[language] || section.key,
          fields: index === 0 ? [...systemKeys, ...section.fields] : section.fields,
        }))
      : [{ key: 'main', labelKey: 'entity.section.main', fields: fields.map((field) => field.key) }],
    ...(layout?.tabs
      ? {
          tabs: layout.tabs.map((tab) => ({
            key: tab.key,
            labelKey: '',
            label: tab.labels[language] || tab.key,
            kind: 'sections' as const,
            sections: layout.sections.filter((section) => section.tab === tab.key).map((section) => section.key),
          })),
        }
      : {}),
    actions: [],
    capabilities: [],
    titleField: title === SYSTEM_TITLE ? 'name' : title,
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
