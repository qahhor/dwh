import type { FormFieldMeta } from '@core/models/form-meta.models';
import { localMoment, optionLabel } from '@core/services/form-meta.service';
import type { RefLookups } from '../lookups/ref-lookup';

type Translate = (key: string) => string;

/**
 * A field's value in words, as the card and the record history show it (plan 10/10, item 5.0): yes/no, an option
 * by its label, a referenced record by its name from its own target (until the name has come, the key), a moment
 * in the viewer's time and a time of day as it is.
 */
export function fieldText(
  field: Pick<FormFieldMeta, 'type' | 'ref' | 'optionLabelPrefix'>,
  value: unknown,
  translate: Translate,
  refs: Pick<RefLookups, 'name'>,
): string {
  switch (field.type) {
    case 'boolean':
      return translate(value === true || value === 'true' ? 'common.yes' : 'common.no');
    case 'select':
      return optionLabel(field as FormFieldMeta, String(value), translate);
    case 'ref':
      return (field.ref ? refs.name(field.ref, value) : null) ?? String(value);
    case 'datetime':
      return momentText(value);
    case 'date':
      return dateText(String(value));
    default:
      return typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value);
  }
}

/** A moment as `dd.MM.yyyy HH:mm` in the viewer's time; what is not a moment, as it is. */
export function momentText(value: unknown): string {
  const local = localMoment(value);
  if (!local) return String(value);
  return `${dateText(local.slice(0, 10))} ${local.slice(11, 16)}`;
}

function dateText(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  return match ? `${match[3]}.${match[2]}.${match[1]}` : iso;
}
