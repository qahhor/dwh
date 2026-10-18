import {
  editorDeclaration,
  SYSTEM_TITLE,
  titleOf,
  withFormat,
  type SimpleField,
  type SimpleFormDeclaration,
} from '../new-form/new-form.models';

/** A change the server refuses for a new version (ADR-0041, decision 2), named as the server names it. */
export type VersionChangeCode =
  | 'removed'
  | 'type'
  | 'required'
  | 'max_length'
  | 'scale'
  | 'default'
  | 'target'
  | 'option_removed'
  | 'scope'
  | 'computed'
  | 'lines_moved'
  | 'max_rows'
  | 'numbering'
  | 'workflow_removed'
  | 'state_removed'
  | 'initial';

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
 * target and the form its data scope (format 3). A form keeps or goes without the system name and code for good;
 * its layout and its title field change freely (format 4). Formulas, conditions and rules change freely, but a field
 * whose records hold entered values never becomes computed (format 5, ADR-0042).
 */
export function incompatibleChanges(active: SimpleFormDeclaration, draft: SimpleFormDeclaration): VersionChange[] {
  const next = new Map(draft.fields.map((field) => [field.id, field] as const));
  const changes: VersionChange[] = [];
  if ((active.entity.scope ?? 'all') !== (draft.entity.scope ?? 'all'))
    changes.push({ field: 'entity.scope', change: 'scope' });
  const named = titleOf(active) === SYSTEM_TITLE;
  if (named && titleOf(draft) !== SYSTEM_TITLE) changes.push({ field: SYSTEM_TITLE, change: 'removed' });
  if (!named && titleOf(draft) === SYSTEM_TITLE) changes.push({ field: SYSTEM_TITLE, change: 'required' });
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
    if (!before.formula && after.formula) changes.push({ field: before.id, change: 'computed' });
  }
  for (const added of next.values()) {
    if (added.required && !added.defaultValue) changes.push({ field: added.id, change: 'required' });
  }
  return [...changes, ...documentChanges(active, draft)];
}

/**
 * The changes of a document's parts a new version may not make (ADR-0043), as `StudioDocumentChangeRules`: a table of
 * lines keeps its place and takes no fewer rows, its columns keep their key, type and target, choices, decimals and
 * length and never become required or computed; the number keeps its pattern and period; the statuses stay, every
 * status records may be in is kept and the first one stays first.
 */
export function documentChanges(active: SimpleFormDeclaration, draft: SimpleFormDeclaration): VersionChange[] {
  const changes: VersionChange[] = [];
  (active.lines ?? []).forEach((table, index) => {
    const kept = draft.lines?.[index];
    const at = `lines.${table.key}`;
    if (!kept || kept.key !== table.key) {
      changes.push({ field: at, change: 'lines_moved' });
      return;
    }
    if (kept.maxRows < table.maxRows) changes.push({ field: at, change: 'max_rows' });
    const next = new Map(kept.fields.map((column) => [column.id, column] as const));
    for (const before of table.fields) {
      const after = next.get(before.id);
      next.delete(before.id);
      const path = `${at}.${before.id}`;
      if (!after) changes.push({ field: path, change: 'removed' });
      else if (after.type !== before.type) changes.push({ field: path, change: 'type' });
      else {
        if (JSON.stringify(after.target ?? null) !== JSON.stringify(before.target ?? null))
          changes.push({ field: path, change: 'target' });
        const codes = new Set((after.options ?? []).map((option) => option.code));
        if ((before.options ?? []).some((option) => !codes.has(option.code)))
          changes.push({ field: path, change: 'option_removed' });
        if (!before.required && after.required) changes.push({ field: path, change: 'required' });
        if (shorter(before.maxLength, after.maxLength)) changes.push({ field: path, change: 'max_length' });
        if (shorter(before.scale, after.scale)) changes.push({ field: path, change: 'scale' });
        if (!before.formula && after.formula) changes.push({ field: path, change: 'computed' });
      }
    }
    for (const added of next.values()) {
      if (added.required) changes.push({ field: `${at}.${added.id}`, change: 'required' });
    }
  });
  const numbering = active.entity.numbering;
  const kept = draft.entity.numbering;
  if (numbering && (!kept || kept.pattern !== numbering.pattern || kept.reset !== numbering.reset))
    changes.push({ field: 'entity.numbering', change: 'numbering' });
  const workflow = active.workflow;
  if (workflow && !draft.workflow) changes.push({ field: 'workflow', change: 'workflow_removed' });
  else if (workflow && draft.workflow) {
    if (draft.workflow.states[0]?.code !== workflow.states[0].code)
      changes.push({ field: `workflow.states.${workflow.states[0].code}`, change: 'initial' });
    const codes = new Set(draft.workflow.states.map((state) => state.code));
    workflow.states
      .filter((state) => !codes.has(state.code))
      .forEach((state) => changes.push({ field: `workflow.states.${state.code}`, change: 'state_removed' }));
  }
  return changes;
}

function shorter(before: number | null | undefined, after: number | null | undefined): boolean {
  return typeof before === 'number' && typeof after === 'number' && after < before;
}

/** The draft of a new version: the active declaration in format 2 or later, every field kept. */
export function versionDraft(active: SimpleFormDeclaration): SimpleFormDeclaration {
  const draft = editorDeclaration(active);
  draft.formatVersion = active.formatVersion >= 3 ? active.formatVersion : 2;
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
  const copy = withFormat(draft, draft.formatVersion >= 3 ? draft.formatVersion : 2);
  copy.fields = copy.fields.map((field) => {
    const next: SimpleField = { ...field };
    if (!next.hidden) delete next.hidden;
    if (next.defaultValue === undefined || next.defaultValue === '') delete next.defaultValue;
    return next;
  });
  return copy;
}
