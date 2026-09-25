/* Our code, after the idea of the kit's DataTableSettingsService (smartup-ui-kit@6472beb,
 * services/data-table-settings.service.ts). See ADR-0015 rule 2.
 *
 * Remembers each table's column state for this person in this browser. The
 * browser may refuse storage (private window, blocked site data), so every
 * read and write is guarded and a table simply falls back to its defaults.
 * Saved views on the server (roadmap item 14) replace this store behind the
 * same three calls. */
import { Injectable } from '@angular/core';
import { TableColumnState } from '../components/table/column-state';

const PREFIX = 'dwh.table-columns.v1.';

@Injectable({ providedIn: 'root' })
export class TableColumnStateStore {
  load(tableId: string): TableColumnState | null {
    try {
      const raw = localStorage.getItem(PREFIX + tableId);
      return raw === null ? null : parseState(JSON.parse(raw));
    } catch {
      return null;
    }
  }

  save(tableId: string, state: TableColumnState): void {
    try {
      localStorage.setItem(PREFIX + tableId, JSON.stringify(state));
    } catch {
      // Storage refused: the choice lasts until the page is left.
    }
  }

  clear(tableId: string): void {
    try {
      localStorage.removeItem(PREFIX + tableId);
    } catch {
      // Nothing stored that could be removed.
    }
  }
}

/** Only a well-formed state is trusted; anything else counts as "nothing stored". */
function parseState(value: unknown): TableColumnState | null {
  if (!value || typeof value !== 'object') return null;
  const { order, hidden, widths } = value as Record<string, unknown>;
  const strings = (list: unknown) => Array.isArray(list) && list.every(item => typeof item === 'string');
  if (!strings(order) || !strings(hidden) || !widths || typeof widths !== 'object' || Array.isArray(widths)) {
    return null;
  }
  return { order: order as string[], hidden: hidden as string[], widths: widths as Record<string, string> };
}
