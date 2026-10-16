import type { FormMeta } from '@core/models/form-meta.models';

export const STUDIO_MAX_BYTES = 32 * 1024;
export const STUDIO_LANGUAGES = ['ru', 'uz', 'en'] as const;
export type StudioLanguage = (typeof STUDIO_LANGUAGES)[number];
export type StudioLabels = Record<StudioLanguage, string>;
export interface FormLayoutField {
  fieldId: string;
  labels?: StudioLabels;
}
export interface FormLayoutSection {
  id: string;
  labels?: StudioLabels;
  fields: FormLayoutField[];
}
export interface FormLayoutDeclaration {
  formatVersion: 1;
  kind: 'form-layout';
  entityCode: FormMeta['code'];
  sections: FormLayoutSection[];
}
export interface FormStudioPreview {
  declaration: FormLayoutDeclaration;
  meta: FormMeta;
}
