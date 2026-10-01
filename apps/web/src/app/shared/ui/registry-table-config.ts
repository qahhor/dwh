import { TrackByFunction } from '@angular/core';
import {
  enumLabel,
  fieldLabel,
  fieldValue,
  QueryFieldMeta,
  QueryListMeta,
  QueryRefMeta,
  QuerySort,
} from '@core/models/query-meta.models';
import { ColumnContentType, ColumnInfo, OrderBy, TableConfig } from '../ui-kit/components/table/table.types';
import { fileName, moneyText } from '../entity/entity-values';

export interface RegistryTableOptions<T> {
  translate: (key: string) => string;
  trackBy: TrackByFunction<T>;
  ariaLabel: string;
  /** The sort the list is loaded with; its column shows the direction. */
  sort: QuerySort | null;
  /** Cells a screen renders itself (a link, a badge); every other field gets a plain cell by its type. */
  cells?: Partial<Record<string, ColumnContentType<T>>>;
  widths?: Partial<Record<string, string>>;
  align?: Partial<Record<string, ColumnInfo<T>['align']>>;
  /**
   * The name of a referenced row (`RefLookups.name`, plan 10/10, item 5.0): a reference column shows it instead of
   * the key, once it has come. Without it a reference shows its key.
   */
  refName?: (ref: QueryRefMeta, key: unknown) => string | null;
}

/**
 * A table configuration from a list's server metadata (ADR-0016): the columns,
 * their order, headers and which of them sort all come from `query-meta`, so a
 * field added on the server shows up without touching the screen. A screen
 * only supplies cells that need more than the value. A field the server marks
 * `defaultVisible: false` is offered in the filter but gets no column.
 */
export function registryTableConfig<T>(meta: QueryListMeta, options: RegistryTableOptions<T>): TableConfig<T> {
  const columns: Record<string, ColumnInfo<T>> = {};
  const shown = meta.fields.filter((field) => field.defaultVisible !== false);
  for (const field of shown) {
    columns[field.key] = {
      key: field.key,
      header: { type: 'primitive', value: fieldLabel(field, options.translate) },
      content: options.cells?.[field.key] ?? defaultCell<T>(field, options.translate, options.refName),
      hasSorting: field.sortable,
      sortedBy: options.sort?.field === field.key ? (options.sort.descending ? OrderBy.Desc : OrderBy.Asc) : undefined,
      width: options.widths?.[field.key],
      align:
        options.align?.[field.key] ?? (field.type === 'number' && field.format !== 'currency' ? 'right' : undefined),
    };
  }
  return {
    trackBy: options.trackBy,
    ariaLabel: options.ariaLabel,
    columns,
    columnsOrder: shown.map((field) => field.key),
  };
}

/** The sort a header click asks for, or the list's default when sorting is switched off. */
export function sortFromHeader(event: { column: string; sortBy: OrderBy } | undefined): QuerySort | null {
  return event ? { field: event.column, descending: event.sortBy === OrderBy.Desc } : null;
}

function defaultCell<T>(
  field: QueryFieldMeta,
  translate: (key: string) => string,
  refName?: RegistryTableOptions<T>['refName'],
): ColumnContentType<T> {
  const value = (row: T) => fieldValue(field, row);
  const formatted = formatCell<T>(field, value, refName);
  if (formatted) return formatted;
  const ref = field.ref;
  if (ref && refName) {
    // A reference by the name of its row, from its own target (a person, a project, any list).
    return {
      type: 'primitive',
      value: (row) => {
        const raw = value(row);
        return raw == null || raw === '' ? '—' : (refName(ref, raw) ?? String(raw));
      },
    };
  }
  switch (field.type) {
    case 'date':
      return { type: 'date', value: (row) => (value(row) as string | null) ?? '' };
    case 'instant':
      return { type: 'date-time', value: (row) => (value(row) as string | null) ?? '' };
    case 'enum':
      return {
        type: 'primitive',
        value: (row) => {
          const raw = value(row);
          return raw == null ? '—' : enumLabel(field, String(raw), translate);
        },
      };
    case 'boolean':
      // A custom field may hold the text "true" or "false", and may be empty.
      return {
        type: 'primitive',
        value: (row) => {
          const raw = value(row);
          if (raw === true || raw === 'true') return translate('common.yes');
          if (raw === false || raw === 'false') return translate('common.no');
          return raw == null || raw === '' ? '—' : String(raw);
        },
      };
    default:
      return { type: 'primitive', value: (row) => (value(row) as string | number | null) ?? '—' };
  }
}

/**
 * The cell of a field whose list type alone does not say how to show it (the field's `format`, ADR-0032 4.1): money in
 * its currency, several references by their names, a file by its name, JSON as compact text. Null for a field without
 * a format of its own, or one its list type shows (an e-mail, a phone, an address, an enumeration).
 */
function formatCell<T>(
  field: QueryFieldMeta,
  value: (row: T) => unknown,
  refName?: RegistryTableOptions<T>['refName'],
): ColumnContentType<T> | null {
  const shown = (text: (raw: unknown) => string): ColumnContentType<T> => ({
    type: 'primitive',
    value: (row) => {
      const raw = value(row);
      return raw == null || raw === '' || (Array.isArray(raw) && raw.length === 0) ? '—' : text(raw);
    },
  });
  switch (field.format) {
    case 'money':
      return shown((raw) => moneyText(raw));
    case 'multi_ref':
      return shown((raw) =>
        (Array.isArray(raw) ? raw : [raw])
          .map((key) => (field.ref && refName ? refName(field.ref, key) : null) ?? String(key))
          .join(', '),
      );
    case 'file':
    case 'image':
      return shown((raw) => fileName(raw));
    case 'json':
      return shown((raw) => (typeof raw === 'string' ? raw : JSON.stringify(raw)));
    default:
      return null;
  }
}
