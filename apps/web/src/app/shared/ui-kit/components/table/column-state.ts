/* Our code, after the idea of the kit's data-table settings (smartup-ui-kit@6472beb,
 * components/data-table/components/data-table-settings-modal and
 * services/data-table-settings.service). See ADR-0015 rule 2.
 *
 * What a person chose for a table's columns: their order, which are hidden and
 * the widths they dragged. The state names only what differs from the table's
 * own configuration, so a column added later appears in its default place
 * instead of being lost, and a column removed later is simply forgotten. */
import { TableConfig } from './table.types';

export interface TableColumnState {
  /** Column keys in the chosen order; keys missing here keep their default place at the end. */
  readonly order: readonly string[];
  readonly hidden: readonly string[];
  /** Widths the person set, as CSS pixel lengths (`"180px"`). */
  readonly widths: Readonly<Record<string, string>>;
}

export const EMPTY_COLUMN_STATE: TableColumnState = { order: [], hidden: [], widths: {} };

const WIDTH = /^\d{1,4}px$/;

/**
 * The state against the table's current columns: unknown keys dropped, new
 * columns appended in their default order, locked columns never hidden and at
 * least one column always visible.
 */
export function normalizeColumnState(
  state: TableColumnState | null | undefined,
  keys: readonly string[],
  locked: readonly string[] = [],
): TableColumnState {
  const known = new Set(keys);
  const chosen = (state?.order ?? []).filter((key, index, all) => known.has(key) && all.indexOf(key) === index);
  const order = [...chosen, ...keys.filter(key => !chosen.includes(key))];
  let hidden = (state?.hidden ?? []).filter(key => known.has(key) && !locked.includes(key));
  hidden = hidden.filter((key, index) => hidden.indexOf(key) === index);
  if (order.length > 0 && hidden.length >= order.length) {
    hidden = hidden.filter(key => key !== order[0]);
  }
  const widths: Record<string, string> = {};
  for (const [key, width] of Object.entries(state?.widths ?? {})) {
    if (known.has(key) && typeof width === 'string' && WIDTH.test(width)) widths[key] = width;
  }
  return { order, hidden, widths };
}

/** The table configuration with the person's order, visibility and widths applied. */
export function applyColumnState<T>(
  config: TableConfig<T>,
  state: TableColumnState | null | undefined,
  locked: readonly string[] = [],
): TableConfig<T> {
  if (!state) return config;
  const normalized = normalizeColumnState(state, config.columnsOrder, locked);
  const columns = { ...config.columns };
  for (const [key, width] of Object.entries(normalized.widths)) {
    columns[key] = { ...columns[key], width };
  }
  return {
    ...config,
    columns,
    columnsOrder: normalized.order.filter(key => !normalized.hidden.includes(key)),
  };
}

export function setColumnVisible(state: TableColumnState, key: string, visible: boolean): TableColumnState {
  const hidden = state.hidden.filter(item => item !== key);
  return { ...state, hidden: visible ? hidden : [...hidden, key] };
}

/** Moves a column by `delta` places within the full order (hidden columns included). */
export function moveColumn(state: TableColumnState, key: string, delta: number): TableColumnState {
  const order = [...state.order];
  const from = order.indexOf(key);
  const to = from + delta;
  if (from < 0 || to < 0 || to >= order.length) return state;
  order.splice(from, 1);
  order.splice(to, 0, key);
  return { ...state, order };
}

export function setColumnWidth(state: TableColumnState, key: string, widthPx: number): TableColumnState {
  const px = Math.max(24, Math.min(9999, Math.round(widthPx)));
  return { ...state, widths: { ...state.widths, [key]: `${px}px` } };
}

/** True when the state changes nothing, so nothing needs to be stored. */
export function isDefaultColumnState(state: TableColumnState, keys: readonly string[]): boolean {
  return state.hidden.length === 0
    && Object.keys(state.widths).length === 0
    && state.order.every((key, index) => key === keys[index]);
}
