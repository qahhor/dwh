import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import type { FieldErrorItem } from '../models/common.models';
import type { FormFieldMeta, FormMeta, FormProblems, FormValues } from '../models/form-meta.models';
import { ApiService } from './api.service';
import { FIELD_VALUE_RULES, fieldReadonly, fieldVisible, localMoment, utcMoment } from './field-values';
import { MetaCache, MetaCacheState } from './meta-cache';

export { fieldReadonly, fieldVisible, localMoment, utcMoment };

type Translate = (key: string, params?: Record<string, string | number>) => string;
type Row = Record<string, unknown>;

/**
 * Entity forms (ADR-0019 2.2, roadmap item 55), cached like the list descriptions (plan 10/10, item 5.0): the
 * actions follow the viewer's rights and the custom fields can change, so a form is read again when either
 * changes here and after `META_TTL_MS`.
 */
@Injectable({ providedIn: 'root' })
export class FormMetaService {
  private readonly api = inject(ApiService);
  private readonly cache = new MetaCache<FormMeta>(inject(MetaCacheState));

  get(code: string): Observable<FormMeta> {
    return this.cache.get(code, () =>
      this.api.get<FormMeta>(`/form-meta/${encodeURIComponent(code)}`, undefined, { notifyError: false }),
    );
  }
}

/** Whether the viewer may take the action; nothing is allowed before the form has come. */
export function canDo(meta: FormMeta | null | undefined, action: string): boolean {
  return !!meta?.actions.includes(action);
}

/** Whether the entity declares the capability (`history`, `export`, `bulk`, `saved_views`, `custom_fields`). */
export function hasCapability(meta: FormMeta | null | undefined, capability: string): boolean {
  return !!meta?.capabilities.includes(capability);
}

/** The field's label: its catalog key, or the name a custom field carries. */
export function fieldLabel(field: FormFieldMeta, translate: Translate): string {
  return field.labelKey ? translate(field.labelKey) : (field.label ?? field.key);
}

/** An option's label: from the catalog by the field's prefix, or the option itself. */
export function optionLabel(field: FormFieldMeta, option: string, translate: Translate): string {
  return field.optionLabelPrefix ? translate(field.optionLabelPrefix + option) : option;
}

/**
 * A record's values by field key: declared fields from the row, custom ones from its `attributes`, each as its
 * control edits it (a moment in the viewer's time, JSON as text; plan 10/10, item 5.2). A new record (no row) starts
 * with the fields' defaults the form can know: a fixed value, today, now (ADR-0032 4.3); the server fills the rest.
 */
export function recordValues(meta: FormMeta, row: Row | null | undefined): FormValues {
  const attributes = (row?.['attributes'] ?? {}) as Row;
  const values: FormValues = {};
  for (const field of meta.fields) {
    const raw = field.attribute ? attributes[field.attribute] : row?.[field.key];
    const value = raw ?? (row ? null : defaultOf(field));
    if (field.attribute) {
      // A custom moment is edited in the viewer's time too (plan 10/10, item 5.0).
      values[field.key] = field.type === 'datetime' ? (localMoment(value) ?? value ?? null) : (value ?? null);
      continue;
    }
    const edited = FIELD_VALUE_RULES[field.type].formValue(field, value);
    values[field.key] = edited ?? (field.type === 'boolean' ? false : null);
  }
  return values;
}

/** The default a new record's field starts with on the form, or null when the server gives it. */
function defaultOf(field: FormFieldMeta): unknown {
  const value = field.defaultValue;
  switch (value?.kind) {
    case 'fixed':
      return field.type === 'boolean' ? value.value === 'true' : (value.value ?? null);
    case 'today':
      return localMoment(new Date().toISOString())?.slice(0, 10) ?? null;
    case 'now':
      return new Date().toISOString();
    default:
      return null;
  }
}

/**
 * The body to save: declared fields by key, custom fields in `attributes`. Attributes the form does not show
 * are kept, so a field an administrator removed does not lose its value on the next save. A computed field is never
 * sent, nor is a new record's field that is read-only whatever the state — the server gives it its default, and
 * refuses a value the viewer may not set (ADR-0032 4.4, 5.2); a field its condition hides is sent empty, as the
 * server keeps it.
 */
export function recordPayload(meta: FormMeta, values: FormValues, row?: Row | null): Row {
  const attributes: Row = { ...((row?.['attributes'] ?? {}) as Row) };
  const payload: Row = {};
  for (const field of meta.fields) {
    if (field.computed || (field.readonly && !row)) continue;
    const raw = values[field.key];
    if (field.attribute) {
      const trimmed = typeof raw === 'string' ? raw.trim() : raw;
      const value = field.type === 'datetime' ? (utcMoment(trimmed) ?? trimmed) : trimmed;
      attributes[field.attribute] = value === '' ? null : (value ?? null);
      continue;
    }
    const rules = FIELD_VALUE_RULES[field.type];
    if (!fieldVisible(field, values)) {
      payload[field.key] = field.type === 'multi_ref' ? [] : null;
      continue;
    }
    payload[field.key] = rules.empty(raw) ? emptyOf(field, raw) : rules.payload(field, raw);
  }
  payload['attributes'] = attributes;
  return payload;
}

/** The types of plan 10/10, item 5.2 send an empty value as null (several references as an empty list). */
const SENT_AS_NULL = new Set<FormFieldMeta['type']>([
  'email',
  'phone',
  'url',
  'money',
  'enum',
  'file',
  'image',
  'json',
]);

/** What an empty field is sent as: a list of keys an empty list, a new type null, an older one as typed. */
function emptyOf(field: FormFieldMeta, raw: unknown): unknown {
  if (field.type === 'multi_ref') return [];
  if (SENT_AS_NULL.has(field.type)) return null;
  return typeof raw === 'string' && field.type !== 'markdown' && field.type !== 'textarea' ? raw.trim() : raw;
}

/** The problems the server would find in the declared fields, found before the request. */
export function formProblems(meta: FormMeta, values: FormValues, translate: Translate, creating = false): FormProblems {
  const problems: FormProblems = {};
  for (const field of meta.fields) {
    if (!fieldVisible(field, values) || fieldReadonly(field, values, creating)) continue;
    const problem = fieldProblem(field, values[field.key]);
    if (problem) problems[field.key] = problemText(problem, field, translate);
  }
  return problems;
}

/**
 * A 422's field errors by field key. A custom field's error names it `attributes.<code>`; an error of a
 * field the form does not show is left for the caller's message.
 */
export function serverProblems(
  meta: FormMeta,
  errors: readonly FieldErrorItem[] | undefined,
  translate: Translate,
): FormProblems {
  const problems: FormProblems = {};
  for (const error of errors ?? []) {
    const field = meta.fields.find((candidate) =>
      candidate.attribute ? `attributes.${candidate.attribute}` === error.field : candidate.key === error.field,
    );
    if (field && !problems[field.key]) {
      problems[field.key] = KNOWN_CODES.has(error.code) ? problemText(error.code, field, translate) : error.message;
    }
  }
  return problems;
}

const KNOWN_CODES = new Set(['required', 'too_short', 'too_long', 'out_of_range', 'invalid', 'too_many', 'readonly']);

function fieldProblem(field: FormFieldMeta, value: unknown): string | null {
  const rules = FIELD_VALUE_RULES[field.type];
  if (field.attribute ? FIELD_VALUE_RULES.text.empty(value) : rules.empty(value)) {
    return field.required ? 'required' : null;
  }
  if (field.attribute) return null;
  return rules.problem(field, value);
}

function problemText(code: string, field: FormFieldMeta, translate: Translate): string {
  switch (code) {
    case 'too_short':
      return translate('ui.entity_form.too_short', { n: field.minLength ?? 0 });
    case 'too_long':
      return translate('ui.entity_form.too_long', { n: field.maxLength ?? 0 });
    case 'out_of_range':
      return translate('ui.entity_form.out_of_range');
    case 'invalid':
      return translate('ui.entity_form.invalid');
    case 'too_many':
      return translate('ui.entity_form.too_many', { n: field.maxItems ?? 100 });
    case 'readonly':
      return translate('ui.entity_form.readonly');
    default:
      return translate('ui.entity_form.required');
  }
}
