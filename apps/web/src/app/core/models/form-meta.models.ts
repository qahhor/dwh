import type { QueryRefMeta } from './query-meta.models';

/**
 * Every field type of `form-meta` (ADR-0019 2.2, ADR-0032 4.1), in the server's order (`FieldType`): the server's
 * `FieldTypeMatrixTest` reads this list, so a type added on one side fails the other's build.
 */
export const FORM_FIELD_TYPES = [
  'text',
  'textarea',
  'markdown',
  'number',
  'date',
  'datetime',
  'time',
  'boolean',
  'select',
  'ref',
  'email',
  'phone',
  'url',
  'money',
  'enum',
  'multi_ref',
  'file',
  'image',
  'json',
] as const;

/** A field's type in `form-meta`: the control the form draws for it. */
export type FormFieldType = (typeof FORM_FIELD_TYPES)[number];

/** One test of a condition, the filter DSL's shape: `eq`, `ne`, `in`, `empty`, `not_empty` over values as text. */
export interface ConditionClauseMeta {
  field: string;
  op: 'eq' | 'ne' | 'in' | 'empty' | 'not_empty';
  values: string[];
}

/** One item of a condition: a clause, or `any` — clauses of which one must hold. Every item must hold. */
export interface ConditionItemMeta {
  field?: string | null;
  op?: ConditionClauseMeta['op'] | null;
  values?: string[] | null;
  any?: ConditionClauseMeta[] | null;
}

/** The value a new record takes without the field (ADR-0032 4.3). */
export interface DefaultValueMeta {
  kind: 'fixed' | 'now' | 'today' | 'current_user' | 'sequence';
  value?: string | null;
}

/** One field of an entity's form and the rules the server checks on save. */
export interface FormFieldMeta {
  key: string;
  /** Catalog key of the label; empty for a custom field, which carries its own `label`. */
  labelKey: string;
  label?: string | null;
  type: FormFieldType;
  required: boolean;
  minLength?: number | null;
  maxLength?: number | null;
  min?: number | null;
  max?: number | null;
  pattern?: string | null;
  /** A select's options; an enumeration's item codes. */
  options?: string[] | null;
  /** Catalog prefix of the options' labels (`notes.color_` + `blue`); none — the option is its own label. */
  optionLabelPrefix?: string | null;
  /** An enumeration's item names by code, read from its reference entity (ADR-0032 4.5). */
  optionLabels?: Record<string, string> | null;
  ref?: QueryRefMeta | null;
  /** Set on a custom field: its code in the record's `attributes`. */
  attribute?: string | null;
  /** When a save cannot change it: always, once the record exists, or while `readonlyWhen` holds (ADR-0032 4.4). */
  readonly?: 'always' | 'on_update' | 'when' | null;
  readonlyWhen?: ConditionItemMeta[] | null;
  /** The server computes it: shown read-only, never sent. */
  computed?: boolean | null;
  defaultValue?: DefaultValueMeta | null;
  /** The form shows the field only while this holds; hidden, it is not required and keeps no value. */
  visibleWhen?: ConditionItemMeta[] | null;
  /** Most digits after the point. */
  scale?: number | null;
  /** Most keys of a multiple reference (100 without it). */
  maxItems?: number | null;
  /** Largest file, in bytes. */
  maxBytes?: number | null;
  /** The content types a file may have; none — any. */
  contentTypes?: string[] | null;
  /** The ISO 4217 currencies money may be in, the first offered first. */
  currencies?: string[] | null;
  /** The root JSON must have; none — an object or an array. */
  jsonRoot?: 'object' | 'array' | null;
}

export interface FormSectionMeta {
  key: string;
  labelKey: string;
  fields: string[];
}

/** `GET /api/v1/form-meta/{code}`: the form, and the actions the viewer may take on the entity. */
export interface FormMeta {
  code: string;
  listCode?: string | null;
  fields: FormFieldMeta[];
  layout: FormSectionMeta[];
  actions: string[];
  capabilities: string[];
}

/** A record's values in the form, by field key. */
export type FormValues = Record<string, unknown>;

/** Problems by field key: the words to show under the field. */
export type FormProblems = Record<string, string>;
