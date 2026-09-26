import type { QueryRefMeta } from './query-meta.models';

/** A field's type in `form-meta` (ADR-0019 2.2): the control the form draws for it. */
export type FormFieldType = 'text' | 'textarea' | 'markdown' | 'number' | 'date' | 'boolean' | 'select' | 'ref';

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
  options?: string[] | null;
  /** Catalog prefix of the options' labels (`notes.color_` + `blue`); none — the option is its own label. */
  optionLabelPrefix?: string | null;
  ref?: QueryRefMeta | null;
  /** Set on a custom field: its code in the record's `attributes`. */
  attribute?: string | null;
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
