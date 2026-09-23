/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/table/table.types.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE. */
import { Signal, TemplateRef, TrackByFunction, Type } from '@angular/core';

type Primitive = string | number | boolean | null | undefined;

export type ColumnContentType<T> =
  | {
      type: 'primitive';
      value: (data: T) => Primitive;
    }
  | {
      type: 'html';
      value: (data: T) => string;
    }
  | {
      type: 'date' | 'date-time';
      value: (data: T) => Date | string;
      format?: string;
    }
  | {
      type: 'templateRef';
      value: Signal<TemplateRef<any>>;
    }
  | {
      type: 'component';
      value: {
        component: Type<any>;
        inputs: (data: T) => Record<string, unknown>;
      };
    };

export type ColumnHeaderType =
  | {
      type: 'primitive';
      value: Primitive;
    }
  | {
      type: 'html';
      value: string;
    }
  | {
      type: 'date' | 'date-time';
      value: Date | string;
      format?: string;
    }
  | {
      type: 'templateRef';
      value: Signal<TemplateRef<never>>;
    }
  | {
      type: 'component';
      value: {
        component: Type<any>;
        inputs: Record<string, unknown>;
      };
    };

export interface ColumnInfo<T> {
  header: ColumnHeaderType;
  content: ColumnContentType<T>;
  hasSorting?: boolean;
  sortedBy?: OrderBy;
  key?: string;
  width?: string;
  /** Biruni `align` — applied to header and body cells. */
  align?: 'left' | 'center' | 'right';
}

export type TableRowClass = string | Record<string, boolean> | (string | Record<string, boolean>)[];

export interface TableConfig<T> {
  rowHeight?: string;
  trackBy: TrackByFunction<T>;
  hasMultipleSelection?: boolean;
  hideHeader?: boolean;
  /** Biruni b-grid layout: checkbox track + % widths + trailing 1fr filler. */
  biruniGridLayout?: boolean;
  columns: Record<string, ColumnInfo<T>>;
  columnsOrder: string[];
  rowClass?: (row: T) => TableRowClass | null | undefined;
  /**
   * `content` (default) grows the table to its content and scrolls sideways;
   * `fit` holds it to the container, so percentage tracks resolve against the
   * container instead of against content widths.
   */
  layout?: 'content' | 'fit';
  /** Accessible name. Without one a screen reader announces an unnamed table. */
  ariaLabel?: string;
  /** ARIA pattern the rows follow. `treegrid` makes rows focusable and carries `rowAria`. */
  ariaRole?: 'table' | 'treegrid';
  /** A treegrid whose rows can be selected several at a time; `rowAria.selected` then means "checked". */
  ariaMultiselectable?: boolean;
  /** Per-row ARIA state for the `treegrid` pattern. */
  rowAria?: (row: T) => TableRowAria | null | undefined;
}

/** Row state a `treegrid` exposes to assistive technology (WAI-ARIA APG treegrid). */
export interface TableRowAria {
  /** Stable key, rendered as `data-smt-row-id` so keyboard focus can find the row again. */
  id: string;
  level: number;
  /** `null` for a leaf, which has no expanded state. */
  expanded: boolean | null;
  setSize: number;
  posInSet: number;
  selected?: boolean;
  /** Roving tabindex: exactly one row is 0, every other row is -1. */
  tabindex: 0 | -1;
}

export interface TableRowKeydownEvent<T> {
  row: T;
  event: KeyboardEvent;
}

export interface TableTabItem {
  id: string;
  label: string;
  disabled?: boolean;
  badge?: string | number;
  meta?: Record<string, unknown>;
}

export interface TableTabChangeEvent {
  tabId: string;
  index: number;
  tab: TableTabItem;
  source: 'click' | 'programmatic';
}

export interface TableRowReorderEvent<T> {
  item: T;
  previousIndex: number;
  currentIndex: number;
  data: T[];
}

export interface TableColumnResizeEvent {
  key: string;
  widthPx: number;
  widthPercent: string;
}

export enum OrderBy {
  Asc = 'ASC',
  Desc = 'DESC',
}
