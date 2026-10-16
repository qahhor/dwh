import type { FormMeta } from '@core/models/form-meta.models';
import type { FormStudioPreview, StudioLanguage } from './form-studio.models';

/** The preview owns its metadata; the shared cache and the entity's data contract stay unchanged. */
export function studioPreviewMeta(preview: FormStudioPreview, language: StudioLanguage): FormMeta {
  const meta = structuredClone(preview.meta);
  const labels = new Map(
    preview.declaration.sections
      .flatMap((section) => section.fields)
      .filter((field) => field.labels)
      .map((field) => [field.fieldId, field.labels![language]]),
  );
  meta.fields = meta.fields.map((field) =>
    labels.has(field.key) ? { ...field, labelKey: '', label: labels.get(field.key) } : field,
  );
  meta.layout = preview.declaration.sections.map((section) => ({
    key: section.id,
    labelKey: section.labels ? '' : (meta.layout.find((original) => original.key === section.id)?.labelKey ?? ''),
    ...(section.labels ? { label: section.labels[language] } : {}),
    fields: section.fields.map((field) => field.fieldId),
  }));
  return meta;
}
