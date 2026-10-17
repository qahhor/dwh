import type { StudioLabels } from '../form-studio.models';
export type SimpleFieldType =
  'text' | 'textarea' | 'number' | 'date' | 'datetime' | 'time' | 'bool' | 'email' | 'phone' | 'url';
export interface SimpleField {
  id: string;
  type: SimpleFieldType;
  labels: StudioLabels;
  required: boolean;
  listed: boolean;
  maxLength?: number | null;
  scale?: number | null;
  /** Format 2: kept with its data, served nowhere (ADR-0041). */
  hidden?: boolean;
  /** Format 2: the fixed value a new record takes. */
  defaultValue?: string;
}
export interface SimpleFormMenu {
  icon: string;
  section: 'workspace' | 'administration';
  order: number;
}
export interface SimpleFormDeclaration {
  formatVersion: 1 | 2;
  kind: 'simple-form';
  module: { code: string; title: StudioLabels };
  entity: { name: string; title: StudioLabels; menu?: SimpleFormMenu };
  fields: SimpleField[];
}
export interface SimpleFormPackage {
  formatVersion: 1;
  kind: 'simple-form-package';
  declaration: SimpleFormDeclaration;
  checksum: string;
}
export const SIMPLE_FORM_MAX_BYTES = 32 * 1024;
export function initialSimpleForm(): SimpleFormDeclaration {
  return {
    formatVersion: 1,
    kind: 'simple-form',
    module: { code: '', title: { ru: '', uz: '', en: '' } },
    entity: { name: '', title: { ru: '', uz: '', en: '' } },
    fields: [],
  };
}
