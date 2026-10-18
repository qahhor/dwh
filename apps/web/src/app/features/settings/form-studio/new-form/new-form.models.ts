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
  /** Format 5: the formula of a computed field — read-only, recomputed on every save (ADR-0042). */
  formula?: string;
  /** Format 5: the formula of the value a new record takes when it has none. */
  defaultFormula?: string;
  /** Format 5: the field is shown only while this yes/no formula is true. */
  visibleIf?: string;
  /** Format 5: an optional field is required while this yes/no formula is true. */
  requiredIf?: string;
  /** Format 5: a saved record's field cannot change while this yes/no formula is true. */
  readonlyIf?: string;
}

/** The keys of a field's formulas (format 5, ADR-0042). */
export const FIELD_FORMULA_KEYS = ['formula', 'defaultFormula', 'visibleIf', 'requiredIf', 'readonlyIf'] as const;
export type FieldFormulaKey = (typeof FIELD_FORMULA_KEYS)[number];

/** The field types whose value a formula may compute. */
export const COMPUTED_TYPES: readonly SimpleFieldType[] = [
  'text',
  'textarea',
  'number',
  'date',
  'datetime',
  'time',
  'bool',
];

/** The field types whose default a formula may give. */
export const DEFAULTED_TYPES: readonly SimpleFieldType[] = [...COMPUTED_TYPES, 'select', 'email', 'phone', 'url'];

/** Format 5: the message of a rule in each language, up to 200 characters. */
export interface SimpleFormRuleMessage {
  ru: string;
  uz: string;
  en: string;
}

/** Format 5: a validation rule — a yes/no formula every save must not make false, its message and its field. */
export interface SimpleFormRule {
  key: string;
  formula: string;
  message: SimpleFormRuleMessage;
  field?: string;
}

/** Rules of one form (server limit). */
export const MAX_RULES = 20;
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
/** Format 6: a column of a table of lines — a value of one of the field types, or a formula (ADR-0043). */
export interface SimpleLineField {
  id: string;
  type: SimpleFieldType;
  labels: StudioLabels;
  required: boolean;
  maxLength?: number | null;
  scale?: number | null;
  options?: SimpleFieldOption[];
  target?: SimpleFieldTarget;
  /** A computed column: its formula over the row and the header's entered fields (`doc.<field>`). */
  formula?: string;
}

/** Format 6: a table of lines of a document, kept apart from the record by its place in the form. */
export interface SimpleFormLines {
  key: string;
  labels: StudioLabels;
  maxRows: number;
  fields: SimpleLineField[];
}

/** Format 6: the number of a document — a pattern, the period its counter starts again in, the status that gives it. */
export interface SimpleFormNumbering {
  pattern: string;
  reset: 'never' | 'year' | 'month';
  assignOn?: string;
  labels?: StudioLabels;
}

/** The colors of a status's badge (ADR-0043). */
export const STATE_COLORS = ['gray', 'blue', 'green', 'orange', 'red', 'purple'] as const;
export type StateColor = (typeof STATE_COLORS)[number];

/** Format 6: a status of a document; the first one is where a record starts. */
export interface SimpleFormState {
  code: string;
  labels: StudioLabels;
  color: StateColor;
  terminal?: true;
  lockAll?: true;
  locks?: string[];
}

/** Format 6: a transition — a record action with a right of its own. */
export interface SimpleFormTransition {
  code: string;
  labels: StudioLabels;
  from: string[];
  to: string;
  condition?: string;
  comment: 'none' | 'optional' | 'required';
  notAuthor?: true;
  notify?: true;
}

/** Format 6: the statuses of a document and the transitions between them. */
export interface SimpleFormWorkflow {
  labels?: StudioLabels;
  states: SimpleFormState[];
  transitions: SimpleFormTransition[];
}

/** Bounds of a document the server keeps (ADR-0043). */
export const MAX_LINE_TABLES = 3;
export const MAX_LINE_COLUMNS = 20;
export const MAX_LINE_ROWS = 500;
export const MAX_STATES = 20;
export const MAX_TRANSITIONS = 30;

/** The editor's title of a document named by its number: format 6 with a number and no title field. */
export const DOCUMENT_NUMBER_TITLE = 'docNumber';

export interface SimpleFormDeclaration {
  formatVersion: 1 | 2 | 3 | 4 | 5 | 6;
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
    /** Format 6: the number of a document. */
    numbering?: SimpleFormNumbering;
  };
  fields: SimpleField[];
  layout?: SimpleFormLayout;
  /** Format 5: the validation rules of the form. */
  rules?: SimpleFormRule[];
  /** Format 6: the tables of lines of a document. */
  lines?: SimpleFormLines[];
  /** Format 6: the statuses and transitions of a document. */
  workflow?: SimpleFormWorkflow;
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
  if (declaration.formatVersion >= 4)
    return title ?? (declaration.entity.numbering ? DOCUMENT_NUMBER_TITLE : NUMBER_TITLE);
  return title ?? SYSTEM_TITLE;
}

/** A declaration as the editor keeps it: the record's number of a form of format 4 named explicitly. */
export function editorDeclaration(declaration: SimpleFormDeclaration): SimpleFormDeclaration {
  const copy = structuredClone(declaration);
  if (copy.formatVersion >= 4 && copy.entity.titleField === undefined)
    copy.entity.titleField = copy.entity.numbering ? DOCUMENT_NUMBER_TITLE : NUMBER_TITLE;
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
/** Whether a declaration says something only format 5 can: a formula of a field or a rule (ADR-0042). */
export function usesFormatFive(declaration: SimpleFormDeclaration): boolean {
  return (
    !!declaration.rules?.length || declaration.fields.some((field) => FIELD_FORMULA_KEYS.some((key) => !!field[key]))
  );
}
/** Whether a declaration says something only format 6 can: lines, a number or statuses (ADR-0043). */
export function usesFormatSix(declaration: SimpleFormDeclaration): boolean {
  return !!declaration.lines?.length || !!declaration.workflow || !!declaration.entity.numbering;
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
export function withFormat(
  declaration: SimpleFormDeclaration,
  floor: 1 | 2 | 3 | 4 | 5 | 6 = 1,
): SimpleFormDeclaration {
  const copy = structuredClone(declaration);
  const kinds = copy.fields.some((field) => LIVE_FIELD_TYPES.includes(field.type));
  copy.fields = copy.fields.map((field) => withoutEmptyFormulas(field));
  if (copy.rules && copy.rules.length === 0) delete copy.rules;
  if (copy.lines && copy.lines.length === 0) delete copy.lines;
  copy.lines?.forEach((table) => (table.fields = table.fields.map((column) => withoutEmptyFormula(column))));
  copy.workflow?.transitions.forEach((transition) => {
    if (!transition.condition?.trim()) delete transition.condition;
  });
  const six = floor === 6 || usesFormatSix(copy);
  const five = six || floor === 5 || usesFormatFive(copy);
  const four = five || floor === 4 || usesFormatFour(copy);
  if (copy.entity.scope === 'all' && !kinds && !four && floor < 3) delete copy.entity.scope;
  if (copy.layout) copy.layout = placedLayout(copy.layout, copy.fields);
  // A draft of an earlier format is named by the system name until it says otherwise; a document with its number is
  // named by it (ADR-0043).
  if (four && copy.entity.titleField === undefined && declaration.formatVersion < 4 && !copy.entity.numbering)
    copy.entity.titleField = SYSTEM_TITLE;
  if (!four || copy.entity.titleField === NUMBER_TITLE || copy.entity.titleField === DOCUMENT_NUMBER_TITLE)
    delete copy.entity.titleField;
  copy.formatVersion = six ? 6 : five ? 5 : four ? 4 : kinds || copy.entity.scope !== undefined ? 3 : floor;
  return copy;
}

/** A column without a formula left empty in the editor. */
function withoutEmptyFormula(column: SimpleLineField): SimpleLineField {
  const next = { ...column };
  if (!next.formula?.trim()) delete next.formula;
  return next;
}

/** A field without the formulas left empty in the editor. */
function withoutEmptyFormulas(field: SimpleField): SimpleField {
  const next = { ...field };
  for (const key of FIELD_FORMULA_KEYS) {
    if (!next[key]?.trim()) delete next[key];
  }
  return next;
}
