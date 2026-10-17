import { withFormat, type SimpleField, type SimpleFormDeclaration } from '../new-form/new-form.models';

/** A change the server refuses for a new version (ADR-0041, decision 2), named as the server names it. */
export type VersionChangeCode =
  'removed' | 'type' | 'required' | 'max_length' | 'scale' | 'default' | 'target' | 'option_removed' | 'scope';

export interface VersionChange {
  field: string;
  change: VersionChangeCode;
}

/** Server limits of a version: fields kept with hidden ones, and fields served. */
export const VERSION_MAX_FIELDS = 30;
export const VERSION_MAX_SHOWN_FIELDS = 20;

export const MENU_ICONS = [
  'box',
  'description',
  'assignment',
  'event',
  'storefront',
  'local_shipping',
  'inventory_2',
  'people',
  'support_agent',
  'receipt_long',
] as const;

/**
 * The changes from the active declaration to the draft that a new version may not make — the same rules
 * as `StudioChangeRules` on the server: a key and its type stay for good, a field may only be hidden, a
 * required field needs a default, a text may only grow longer and a number only take more decimals, and a
 * default never changes once the field exists. A select keeps every choice records may hold, a reference its
 * target and the form its data scope (format 3).
 */
export function incompatibleChanges(active: SimpleFormDeclaration, draft: SimpleFormDeclaration): VersionChange[] {
  const next = new Map(draft.fields.map((field) => [field.id, field] as const));
  const changes: VersionChange[] = [];
  if ((active.entity.scope ?? 'all') !== (draft.entity.scope ?? 'all'))
    changes.push({ field: 'entity.scope', change: 'scope' });
  for (const before of active.fields) {
    const after = next.get(before.id);
    next.delete(before.id);
    if (!after) {
      changes.push({ field: before.id, change: 'removed' });
      continue;
    }
    if (after.type !== before.type) {
      changes.push({ field: before.id, change: 'type' });
      continue;
    }
    if (JSON.stringify(after.target ?? null) !== JSON.stringify(before.target ?? null))
      changes.push({ field: before.id, change: 'target' });
    const kept = new Set((after.options ?? []).map((option) => option.code));
    if ((before.options ?? []).some((option) => !kept.has(option.code)))
      changes.push({ field: before.id, change: 'option_removed' });
    if ((after.defaultValue ?? null) !== (before.defaultValue ?? null))
      changes.push({ field: before.id, change: 'default' });
    if (!before.required && after.required && !after.defaultValue)
      changes.push({ field: before.id, change: 'required' });
    if (shorter(before.maxLength, after.maxLength)) changes.push({ field: before.id, change: 'max_length' });
    if (shorter(before.scale, after.scale)) changes.push({ field: before.id, change: 'scale' });
  }
  for (const added of next.values()) {
    if (added.required && !added.defaultValue) changes.push({ field: added.id, change: 'required' });
  }
  return changes;
}

function shorter(before: number | null | undefined, after: number | null | undefined): boolean {
  return typeof before === 'number' && typeof after === 'number' && after < before;
}

/** The draft of a new version: the active declaration in format 2 or later, every field kept. */
export function versionDraft(active: SimpleFormDeclaration): SimpleFormDeclaration {
  const draft = structuredClone(active);
  draft.formatVersion = active.formatVersion === 3 ? 3 : 2;
  return draft;
}

/** The fields the form will serve: every field that is not hidden. */
export function shownFields(declaration: SimpleFormDeclaration): SimpleField[] {
  return declaration.fields.filter((field) => !field.hidden);
}

/**
 * The declaration as the server takes it: a `hidden` flag only when true and a default only when given,
 * so an unchanged field keeps the checksum of its stored form; format 3 once it says something only that
 * format can.
 */
export function normalizedDeclaration(draft: SimpleFormDeclaration): SimpleFormDeclaration {
  const copy = withFormat(draft, draft.formatVersion === 3 ? 3 : 2);
  copy.fields = copy.fields.map((field) => {
    const next: SimpleField = { ...field };
    if (!next.hidden) delete next.hidden;
    if (next.defaultValue === undefined || next.defaultValue === '') delete next.defaultValue;
    return next;
  });
  return copy;
}
