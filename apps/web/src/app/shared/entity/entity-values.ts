import type { FormFieldMeta, FormFieldType } from '@core/models/form-meta.models';
import { localMoment, optionLabel } from '@core/services/form-meta.service';
import type { RefLookups } from '../lookups/ref-lookup';

type Translate = (key: string) => string;
type Field = Pick<FormFieldMeta, 'type' | 'ref' | 'optionLabelPrefix'> & Partial<Pick<FormFieldMeta, 'optionLabels'>>;
type Text = (field: Field, value: unknown, translate: Translate, refs: Pick<RefLookups, 'name'>) => string;

/**
 * A field's value in words, as the card, the record history and the list show it (plan 10/10, items 5.0 and 5.2):
 * yes/no, an option by its label, an enumeration by its item's name, a referenced record by its name from its own
 * target (until the name has come, the key), a moment in the viewer's time, money in its currency, a file by its
 * name, JSON as compact text. A `Record` over every type: a new type without its words fails the typecheck.
 */
export const FIELD_TEXT: Record<FormFieldType, Text> = {
  text: plain,
  textarea: plain,
  markdown: plain,
  number: plain,
  date: (_field, value) => dateText(String(value)),
  datetime: (_field, value) => momentText(value),
  time: plain,
  boolean: (_field, value, translate) => translate(value === true || value === 'true' ? 'common.yes' : 'common.no'),
  select: (field, value, translate) => optionLabel(field as FormFieldMeta, String(value), translate),
  ref: (field, value, _translate, refs) => (field.ref ? refs.name(field.ref, value) : null) ?? String(value),
  email: plain,
  phone: plain,
  url: plain,
  money: (_field, value) => moneyText(value),
  enum: (field, value) => field.optionLabels?.[String(value)] ?? String(value),
  multi_ref: (field, value, _translate, refs) =>
    (Array.isArray(value) ? value : [value])
      .map((key) => (field.ref ? refs.name(field.ref, key) : null) ?? String(key))
      .join(', '),
  file: (_field, value) => fileName(value),
  image: (_field, value) => fileName(value),
  json: (_field, value) => (typeof value === 'string' ? value : JSON.stringify(value)),
};

export function fieldText(field: Field, value: unknown, translate: Translate, refs: Pick<RefLookups, 'name'>): string {
  return (FIELD_TEXT[field.type] ?? plain)(field, value, translate, refs);
}

/** A moment as `dd.MM.yyyy HH:mm` in the viewer's time; what is not a moment, as it is. */
export function momentText(value: unknown): string {
  const local = localMoment(value);
  if (!local) return String(value);
  return `${dateText(local.slice(0, 10))} ${local.slice(11, 16)}`;
}

/** Money in its currency's format (`1 250,50 UZS` in the viewer's language); what is not money, as it is. */
export function moneyText(value: unknown, locale?: string): string {
  const money = value as { amount?: unknown; currency?: unknown } | null;
  const amount = Number(money?.amount);
  const currency = typeof money?.currency === 'string' ? money.currency : '';
  if (!money || money.amount === null || money.amount === undefined || !Number.isFinite(amount)) return String(value);
  if (!/^[A-Z]{3}$/.test(currency)) return String(money.amount);
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency, currencyDisplay: 'code' }).format(amount);
  } catch {
    return `${money.amount} ${currency}`;
  }
}

/** A stored file by its name; an id that has no name yet, as it is. */
export function fileName(value: unknown): string {
  if (value && typeof value === 'object' && 'name' in value) return String((value as { name: unknown }).name);
  return String(value);
}

function plain(_field: Field, value: unknown): string {
  return typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value);
}

function dateText(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  return match ? `${match[3]}.${match[2]}.${match[1]}` : iso;
}
