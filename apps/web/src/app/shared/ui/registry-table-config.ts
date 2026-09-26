import { TrackByFunction } from '@angular/core';
import { fieldLabel, fieldValue, QueryFieldMeta, QueryListMeta, QuerySort } from '../../core/models/query-meta.models';
import { ColumnContentType, ColumnInfo, OrderBy, TableConfig } from '../ui-kit/components/table/table.types';

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
  const shown = meta.fields.filter(field => field.defaultVisible !== false);
  for (const field of shown) {
    columns[field.key] = {
      key: field.key,
      header: { type: 'primitive', value: fieldLabel(field, options.translate) },
      content: options.cells?.[field.key] ?? defaultCell<T>(field, options.translate),
      hasSorting: field.sortable,
      sortedBy: options.sort?.field === field.key ? (options.sort.descending ? OrderBy.Desc : OrderBy.Asc) : undefined,
      width: options.widths?.[field.key],
      align: options.align?.[field.key] ?? (field.type === 'number' ? 'right' : undefined),
    };
  }
  return {
    trackBy: options.trackBy,
    ariaLabel: options.ariaLabel,
    columns,
    columnsOrder: shown.map(field => field.key),
  };
}

/** The sort a header click asks for, or the list's default when sorting is switched off. */
export function sortFromHeader(event: { column: string; sortBy: OrderBy } | undefined): QuerySort | null {
  return event ? { field: event.column, descending: event.sortBy === OrderBy.Desc } : null;
}

function defaultCell<T>(field: QueryFieldMeta, translate: (key: string) => string): ColumnContentType<T> {
  const value = (row: T) => fieldValue(field, row);
  switch (field.type) {
    case 'date':
      return { type: 'date', value: row => (value(row) as string | null) ?? '' };
    case 'instant':
      return { type: 'date-time', value: row => (value(row) as string | null) ?? '' };
    case 'enum':
      return {
        type: 'primitive',
        value: row => {
          const raw = value(row);
          return raw == null ? '—' : field.enumLabelPrefix ? translate(`${field.enumLabelPrefix}${raw}`) : String(raw);
        },
      };
    case 'boolean':
      // A custom field may hold the text "true" or "false", and may be empty.
      return {
        type: 'primitive',
        value: row => {
          const raw = value(row);
          if (raw === true || raw === 'true') return translate('common.yes');
          if (raw === false || raw === 'false') return translate('common.no');
          return raw == null || raw === '' ? '—' : String(raw);
        },
      };
    default:
      return { type: 'primitive', value: row => (value(row) as string | number | null) ?? '—' };
  }
}
