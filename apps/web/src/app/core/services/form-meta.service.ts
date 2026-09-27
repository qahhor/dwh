import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import type { FieldErrorItem } from '../models/common.models';
import type { FormFieldMeta, FormMeta, FormProblems, FormValues } from '../models/form-meta.models';
import { ApiService } from './api.service';

type Translate = (key: string, params?: Record<string, string | number>) => string;
type Row = Record<string, unknown>;

/**
 * Entity forms (ADR-0019 2.2, roadmap item 55). Not cached: the actions follow the viewer's rights and the
 * custom fields an administrator adds appear at once, so a screen asks each time it opens.
 */
@Injectable({ providedIn: 'root' })
export class FormMetaService {
  private readonly api = inject(ApiService);

  get(code: string): Observable<FormMeta> {
    return this.api.get<FormMeta>(`/form-meta/${encodeURIComponent(code)}`, undefined, { notifyError: false });
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

/** A record's values by field key: declared fields from the row, custom ones from its `attributes`. */
export function recordValues(meta: FormMeta, row: Row | null | undefined): FormValues {
  const attributes = (row?.['attributes'] ?? {}) as Row;
  const values: FormValues = {};
  for (const field of meta.fields) {
    const value = field.attribute ? attributes[field.attribute] : row?.[field.key];
    values[field.key] = value ?? (field.type === 'boolean' && !field.attribute ? false : null);
  }
  return values;
}

/**
 * The body to save: declared fields by key, custom fields in `attributes`. Attributes the form does not show
 * are kept, so a field an administrator removed does not lose its value on the next save.
 */
export function recordPayload(meta: FormMeta, values: FormValues, row?: Row | null): Row {
  const attributes: Row = { ...((row?.['attributes'] ?? {}) as Row) };
  const payload: Row = {};
  for (const field of meta.fields) {
    const raw = values[field.key];
    const value = typeof raw === 'string' && field.type !== 'markdown' && field.type !== 'textarea' ? raw.trim() : raw;
    if (field.attribute) {
      attributes[field.attribute] = value === '' ? null : (value ?? null);
    } else {
      payload[field.key] = value;
    }
  }
  payload['attributes'] = attributes;
  return payload;
}

/** The problems the server would find in the declared fields, found before the request. */
export function formProblems(meta: FormMeta, values: FormValues, translate: Translate): FormProblems {
  const problems: FormProblems = {};
  for (const field of meta.fields) {
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

const KNOWN_CODES = new Set(['required', 'too_short', 'too_long', 'out_of_range', 'invalid']);

function fieldProblem(field: FormFieldMeta, value: unknown): string | null {
  if (value === null || value === undefined || (typeof value === 'string' && value.trim() === '')) {
    return field.required ? 'required' : null;
  }
  if (field.attribute) return null;
  switch (field.type) {
    case 'text':
    case 'textarea':
    case 'markdown': {
      const text = String(value);
      if (field.minLength != null && text.trim().length < field.minLength) return 'too_short';
      if (field.maxLength != null && text.length > field.maxLength) return 'too_long';
      if (field.pattern && !new RegExp(`^(?:${field.pattern})$`).test(text)) return 'invalid';
      return null;
    }
    case 'number': {
      const number = Number(value);
      if (!Number.isFinite(number)) return 'invalid';
      return (field.min != null && number < field.min) || (field.max != null && number > field.max)
        ? 'out_of_range'
        : null;
    }
    case 'select':
      return field.options?.includes(String(value)) ? null : 'invalid';
    default:
      return null;
  }
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
    default:
      return translate('ui.entity_form.required');
  }
}
