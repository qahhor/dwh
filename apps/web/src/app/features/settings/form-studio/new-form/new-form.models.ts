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
/** Format 4: a tab of the record's card and form; it shows the sections that name it (ADR-0041). */
export interface SimpleFormTab {
  key: string;
  labels: StudioLabels;
}
/** Format 4: a titled group of shown fields, on a tab when the form has tabs. */
export interface SimpleFormSection {
  key: string;
  labels: StudioLabels;
  tab?: string;
  fields: string[];
}
/** Format 4: the sections of the form, each shown field on exactly one, and 2 to 6 tabs or none. */
export interface SimpleFormLayout {
  tabs?: SimpleFormTab[];
  sections: SimpleFormSection[];
}
export interface SimpleFormDeclaration {
  formatVersion: 1 | 2 | 3 | 4;
  kind: 'simple-form';
  module: { code: string; title: StudioLabels };
  entity: {
    name: string;
    title: StudioLabels;
    menu?: SimpleFormMenu;
    scope?: SimpleFormScope;
    /**
     * Format 4: what names a record — a required, listed text field, `name` for the system name and code fields, or
     * (in the editor only) `recordNumber` for the record's number, which the server writes as no title field.
     */
    titleField?: string;
  };
  fields: SimpleField[];
  layout?: SimpleFormLayout;
}

/** The title of a form named by the system name and code fields, as formats 1 to 3 are. */
export const SYSTEM_TITLE = 'name';
/** The editor's title of a form named by the record's number: format 4 without a title field. */
export const NUMBER_TITLE = 'recordNumber';
/** The bounds of a layout the server keeps. */
export const MAX_SECTIONS = 10;
export const MIN_TABS = 2;
export const MAX_TABS = 6;

/** A field that may name a record: a required, listed, shown text field. */
export function titleEligible(field: SimpleField): boolean {
  return field.type === 'text' && field.required && field.listed && !field.hidden;
}

/** What names the records of a declaration in the editor: a field, the system name or the record's number. */
export function titleOf(declaration: SimpleFormDeclaration): string {
  const title = declaration.entity.titleField;
  if (declaration.formatVersion === 4) return title ?? NUMBER_TITLE;
  return title ?? SYSTEM_TITLE;
}

/** A declaration as the editor keeps it: the record's number of a form of format 4 named explicitly. */
export function editorDeclaration(declaration: SimpleFormDeclaration): SimpleFormDeclaration {
  const copy = structuredClone(declaration);
  if (copy.formatVersion === 4 && copy.entity.titleField === undefined) copy.entity.titleField = NUMBER_TITLE;
  return copy;
}

/**
 * The layout as the server takes it: only shown fields, each on one section — a field placed nowhere goes to the
 * first section — and tabs only when there are at least two, each section on one of them.
 */
export function placedLayout(layout: SimpleFormLayout, fields: SimpleField[]): SimpleFormLayout {
  const shown = fields.filter((field) => !field.hidden).map((field) => field.id);
  const seen = new Set<string>();
  const sections = layout.sections.map((section) => {
    const own = section.fields.filter((id) => shown.includes(id) && !seen.has(id));
    own.forEach((id) => seen.add(id));
    return { ...section, fields: own };
  });
  const rest = shown.filter((id) => !seen.has(id));
  if (sections.length > 0) sections[0] = { ...sections[0], fields: [...sections[0].fields, ...rest] };
  const tabs = layout.tabs && layout.tabs.length >= MIN_TABS ? layout.tabs : undefined;
  const keys = new Set((tabs ?? []).map((tab) => tab.key));
  return {
    ...(tabs ? { tabs } : {}),
    sections: sections.map((section) => {
      const next: SimpleFormSection = { key: section.key, labels: section.labels, fields: section.fields };
      if (tabs) next.tab = section.tab && keys.has(section.tab) ? section.tab : tabs[0].key;
      return next;
    }),
  };
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
/** Whether a declaration says something only format 4 can: a layout, or a record named otherwise than by its name. */
export function usesFormatFour(declaration: SimpleFormDeclaration): boolean {
  const title = declaration.entity.titleField;
  return declaration.layout !== undefined || (title !== undefined && title !== SYSTEM_TITLE);
}
/** Whether a declaration says something only format 3 can: a select, a reference, a user or a narrower scope. */
export function usesFormatThree(declaration: SimpleFormDeclaration): boolean {
  return (
    (declaration.entity.scope !== undefined && declaration.entity.scope !== 'all') ||
    declaration.fields.some((field) => LIVE_FIELD_TYPES.includes(field.type))
  );
}
/**
 * The declaration in the lowest format that says it, never below `floor`: format 3 for its new kinds or a scope,
 * format 4 for a layout or a title other than the system name. The default scope `all` is left out of a form that
 * uses nothing else of format 3, so it keeps its earlier format; the record's number is written as no title field.
 */
export function withFormat(declaration: SimpleFormDeclaration, floor: 1 | 2 | 3 | 4 = 1): SimpleFormDeclaration {
  const copy = structuredClone(declaration);
  const kinds = copy.fields.some((field) => LIVE_FIELD_TYPES.includes(field.type));
  const four = floor === 4 || usesFormatFour(copy);
  if (copy.entity.scope === 'all' && !kinds && !four && floor < 3) delete copy.entity.scope;
  if (copy.layout) copy.layout = placedLayout(copy.layout, copy.fields);
  // A draft of an earlier format is named by the system name until it says otherwise.
  if (four && copy.entity.titleField === undefined && declaration.formatVersion < 4)
    copy.entity.titleField = SYSTEM_TITLE;
  if (!four || copy.entity.titleField === NUMBER_TITLE) delete copy.entity.titleField;
  copy.formatVersion = four ? 4 : kinds || copy.entity.scope !== undefined ? 3 : floor;
  return copy;
}
