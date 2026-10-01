import type { FormFieldMeta, FormMeta } from '../app/core/models/form-meta.models';

/** A form-meta field with the defaults the server sends for an unconstrained field. */
export function formField(key: string, type: FormFieldMeta['type'], extra: Partial<FormFieldMeta> = {}): FormFieldMeta {
  return { key, labelKey: `test.${key}`, label: null, type, required: false, ...extra };
}

/** The note form as MsNoteEntity declares it, with every action allowed. */
export const NOTES_FORM_META: FormMeta = {
  code: 'ms.notes',
  listCode: 'ms.notes',
  fields: [
    formField('title', 'text', { labelKey: 'notes.col.title', required: true, minLength: 1, maxLength: 255 }),
    formField('contentMd', 'markdown', { labelKey: 'notes.col.content', maxLength: 100000 }),
    formField('color', 'select', {
      labelKey: 'notes.col.color',
      options: ['default', 'blue', 'green', 'yellow', 'purple', 'red'],
      optionLabelPrefix: 'notes.color_',
    }),
    formField('isPinned', 'boolean', { labelKey: 'notes.col.pinned' }),
  ],
  layout: [
    { key: 'main', labelKey: 'entity.section.main', fields: ['title', 'contentMd'] },
    { key: 'settings', labelKey: 'entity.section.settings', fields: ['color', 'isPinned'] },
  ],
  actions: ['create', 'update', 'pin', 'archive', 'delete'],
  capabilities: ['archive', 'bulk', 'custom_fields', 'export', 'history', 'saved_views'],
};

/** The note form with one custom field, as the registry adds it. */
export function withCustomField(meta: FormMeta, field: FormFieldMeta): FormMeta {
  return {
    ...meta,
    fields: [...meta.fields, field],
    layout: [...meta.layout, { key: 'custom', labelKey: 'entity.section.custom', fields: [field.key] }],
  };
}
