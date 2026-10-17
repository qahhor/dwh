import type { StudioLabels } from '../form-studio.models';
export type SimpleFieldType =
  | 'text'
  | 'textarea'
  | 'number'
  | 'date'
  | 'datetime'
  | 'time'
  | 'bool'
  | 'email'
  | 'phone'
  | 'url'
  | 'select'
  | 'ref'
  | 'user';
/** The field types of format 3, served only by live forms (ADR-0041). */
export const LIVE_FIELD_TYPES: readonly SimpleFieldType[] = ['select', 'ref', 'user'];
/** Format 3: a fixed choice of a select; its code is what the records keep. */
export interface SimpleFieldOption {
  code: string;
  labels: StudioLabels;
}
/** Format 3: what a reference names — an item of a reference list or a record of another live form. */
export interface SimpleFieldTarget {
  kind: 'list' | 'form';
  code: string;
}
/** Format 3: which records a viewer sees (ADR-0041, owner decision 1). */
export type SimpleFormScope = 'all' | 'org_unit' | 'owner';
export const SIMPLE_FORM_SCOPES: readonly SimpleFormScope[] = ['all', 'org_unit', 'owner'];
/** The name of each scope, by its catalog key. */
export const SIMPLE_FORM_SCOPE_KEYS: Readonly<Record<SimpleFormScope, string>> = {
  all: 'studio.scope.all',
  org_unit: 'studio.scope.org_unit',
  owner: 'studio.scope.owner',
};
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
  /** Format 3: the choices of a select. */
  options?: SimpleFieldOption[];
  /** Format 3: what a reference names. */
  target?: SimpleFieldTarget;
}
export interface SimpleFormMenu {
  icon: string;
  section: 'workspace' | 'administration';
  order: number;
}
export interface SimpleFormDeclaration {
  formatVersion: 1 | 2 | 3;
  kind: 'simple-form';
  module: { code: string; title: StudioLabels };
  entity: { name: string; title: StudioLabels; menu?: SimpleFormMenu; scope?: SimpleFormScope };
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
/** Whether a declaration says something only format 3 can: a select, a reference, a user or a narrower scope. */
export function usesFormatThree(declaration: SimpleFormDeclaration): boolean {
  return (
    (declaration.entity.scope !== undefined && declaration.entity.scope !== 'all') ||
    declaration.fields.some((field) => LIVE_FIELD_TYPES.includes(field.type))
  );
}
/**
 * The declaration in the lowest format that says it, never below `floor`: format 3 for its new kinds or a scope. The
 * default scope `all` is left out of a form that uses nothing else of format 3, so it keeps its earlier format.
 */
export function withFormat(declaration: SimpleFormDeclaration, floor: 1 | 2 | 3 = 1): SimpleFormDeclaration {
  const copy = structuredClone(declaration);
  const kinds = copy.fields.some((field) => LIVE_FIELD_TYPES.includes(field.type));
  if (copy.entity.scope === 'all' && !kinds && floor < 3) delete copy.entity.scope;
  copy.formatVersion = kinds || copy.entity.scope !== undefined ? 3 : floor;
  return copy;
}
