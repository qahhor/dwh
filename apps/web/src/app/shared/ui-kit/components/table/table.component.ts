/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/table/table.component.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE. */
import {
  afterNextRender,
  afterRenderEffect,
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  contentChild,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  model,
  NgZone,
  output,
  signal,
  TemplateRef,
  viewChild,
} from '@angular/core';
import { NgClass, NgTemplateOutlet } from '@angular/common';
import {
  ColumnContentType,
  ColumnHeaderType,
  OrderBy,
  TableColumnResizeEvent,
  TableConfig,
  TableRowAria,
  TableRowClass,
  TableRowKeydownEvent,
  TableRowReorderEvent,
  TableTabChangeEvent,
  TableTabItem,
} from './table.types';
import { SMTCellHeaderComponent } from './cell-header/cell-header.component';
import { SMTCellContentComponent } from './cell-content/cell-content.component';
import { SMTIconComponent } from '../icon/icon';
import { injectRegisterSMTIcons } from '../../providers/svg-icon.provider';
import { chevronUpIcon, emptyStateIcon, leftArrowIcon, menu01Icon, rightArrowIcon } from '../../svg-icons';
import { SMTSkeletonDirective } from '../../directives/skeleton/skeleton.directive';
import { CdkDrag, CdkDragDrop, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
// Compatibility: tabs are not vendored. The tab bar pulls in the kit's
// button, modal and alert-dialog trees — 42 further files — for a feature
// this application does not use yet. `hasTabs()` stays false and the
// template's tab block is removed; restoring tabs means vendoring the bar.
import { Tab } from '../tab-bar/types/tab';
import { ColumnResizeDirective } from './column-resize.directive';
import { SMTI18nService } from '../../i18n';
import {
  BIRUNI_GRID_MIN_WIDTH_PX,
  biruniPercentFromChipWidthPx,
  biruniSelectionColumnPercent,
  biruniUnitsToPercent,
  getBiruniTableSizingBasisWidth,
  resolveBiruniTrackWidthPx,
} from '../../utils/data-table-grid-sizing';

const DETAIL_CLOSE_FALLBACK_MS = 250;

/** Only skeletonize this many rows — enough for the viewport; rest stay put (avoids lag on pageSize 40–50+). */
const MAX_SKELETON_ROWS = 24;

/** Windowed (virtual) rendering kicks in above this row count; at or below it every row renders as before. */
const VIRTUAL_ROW_THRESHOLD = 50;

/** Extra rows rendered above/below the visible window — scroll headroom before blanking. */
const VIRTUAL_OVERSCAN_ROWS = 10;

/** Row-height estimate until a real row is measured (cell `min-h-9` → 36px). */
const VIRTUAL_DEFAULT_ROW_HEIGHT_PX = 36;

/** Viewport-height fallback for the first paint, before the scroll container is measured. */
const VIRTUAL_DEFAULT_VIEWPORT_PX = 800;

/** Windowed columns kick in above this count (order_list is ~80+). */
const VIRTUAL_COL_THRESHOLD = 20;

/** Extra columns rendered left/right of the visible window. */
const VIRTUAL_COL_OVERSCAN = 4;

interface TableColumnView<T> {
  key: string;
  index: number;
  align: 'left' | 'center' | 'right';
  content: ColumnContentType<T>;
  header: ColumnHeaderType;
  hasSorting: boolean;
  sortedBy: OrderBy | undefined;
  sortKey: string;
  hasInlineSelection: boolean;
  usesCellComponent: boolean;
}

@Component({
  selector: 'smt-table',
  standalone: true,
  imports: [
    CdkDropList,
    CdkDrag,
    SMTCellHeaderComponent,
    SMTCellContentComponent,
    SMTIconComponent,
    SMTSkeletonDirective,
    NgClass,
    NgTemplateOutlet,
    ColumnResizeDirective,
  ],
  templateUrl: './table.component.html',
  styleUrl: './table.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'flex flex-col w-full h-full min-h-0 text-md',
  },
})
export class SMTTableComponent<T> {
  readonly i18n = inject(SMTI18nService);

  private destroyRef = inject(DestroyRef);

  private ngZone = inject(NgZone);

  data = input.required<T[]>({ alias: 'smtData' });

  isLoading = input<boolean>(false, { alias: 'smtIsLoading' });

  /**
   * Skeleton row count when loading with no existing data (first fetch / empty table).
   * Defaults to 10; data-table should pass `pageSize`.
   */
  skeletonRowCount = input(10, { alias: 'smtSkeletonRowCount' });

  /** Explicit max-height override. When not set the table auto-sizes to the remaining viewport space. */
  maxHeight = input<string | null>(null, { alias: 'smtMaxHeight' });

  expandedRow = input<T | null>(null, { alias: 'smtExpandedRow' });

  detailClosing = input<boolean>(false, { alias: 'smtDetailClosing' });

  tabs = input<TableTabItem[]>([], { alias: 'smtTabs' });

  showTabs = input(true, { alias: 'smtShowTabs', transform: booleanAttribute });

  tabsPlacement = input<'top'>('top', { alias: 'smtTabsPlacement' });

  activeTabId = input<string | null>(null, { alias: 'smtActiveTabId' });

  defaultActiveTabId = input<string>('', { alias: 'smtDefaultActiveTabId' });

  rowDndEnabled = input(false, { alias: 'smtRowDndEnabled', transform: booleanAttribute });

  rowDndControlled = input(false, { alias: 'smtRowDndControlled', transform: booleanAttribute });

  columnResizeEnabled = input(true, { alias: 'smtColumnResizeEnabled', transform: booleanAttribute });

  /** Escape hatch: `false` restores full rendering of every row regardless of row count. */
  virtualRowsEnabled = input(true, { alias: 'smtVirtualRows', transform: booleanAttribute });

  /** Replaces the generic "nothing found" state, e.g. with advice to clear filters. */
  emptyTemplate = input<TemplateRef<unknown> | null>(null, { alias: 'smtEmptyTemplate' });

  rowClick = output<T>({ alias: 'smtRowClick' });

  /** Keyboard input on a focusable (`treegrid`) row; the owner decides what a key does. */
  rowKeydown = output<TableRowKeydownEvent<T>>({ alias: 'smtRowKeydown' });

  rowDblClick = output<T>({ alias: 'smtRowDblClick' });

  detailClosed = output<T>({ alias: 'smtDetailClosed' });

  sortChange = output<{ column: string; sortBy: OrderBy } | undefined>({ alias: 'smtSortChange' });

  tabChange = output<TableTabChangeEvent>({ alias: 'smtOnTabChange' });

  rowReorder = output<TableRowReorderEvent<T>>({ alias: 'smtRowReorder' });

  columnResize = output<TableColumnResizeEvent>({ alias: 'smtColumnResize' });

  config = model.required<TableConfig<T>>({ alias: 'smtConfig' });

  selectedItems = model<T[]>([], { alias: 'smtSelectedItems' });

  protected scrollContainer = viewChild<ElementRef<HTMLElement>>('scrollContainer');

  protected tableSurfaceRef = viewChild<ElementRef<HTMLElement>>('tableSurface');

  detailRowTemplate = contentChild<TemplateRef<{ $implicit: T }>>('smtDetailRow');

  protected showScrollToTop = signal(false);

  protected showHorizontalScrollLeft = signal(false);

  protected showHorizontalScrollRight = signal(false);

  /** Pixel height lock while collapsing the detail row (null when not closing). */
  protected detailCloseHeightPx = signal<number | null>(null);

  /** Hide heavy action content after height is locked so close doesn't reflow controls. */
  protected showDetailContent = signal(true);

  protected resizeContainerWidth = signal(1);

  protected liveResizedWidths = signal<Record<string, number>>({});

  private uncontrolledActiveTabId = signal<string>('');

  private dndData = signal<T[] | null>(null);

  private scrollContainerClientWidth = signal(BIRUNI_GRID_MIN_WIDTH_PX);

  private scrollContainerClientHeight = signal(0);

  private verticalScrollTop = signal(0);

  /** Horizontal pan position — drives column windowing; template must not read this. */
  private horizontalScrollLeft = signal(0);

  /** Average height of the currently rendered data rows (adapts the window to wrapped/multi-line rows). */
  private measuredRowHeightPx = signal<number | null>(null);

  /** Last measured height of the open detail block — spacer compensation while it's above the window. */
  private expandedDetailHeightPx = signal(0);

  selectionType = computed(() => {
    const dataLength = this.data().length;
    const selectedLength = this.selectedItems().length;

    if (dataLength === 0) return 'not-selected';
    if (selectedLength === dataLength && !this.isLoading()) return 'all-selected';
    if (selectedLength === 0) return 'not-selected';
    return 'partial-selected';
  });

  isEmptyData = computed(() => !this.isLoading() && this.data().length === 0);

  /** Placeholder rows only when loading an empty table (no previous data to skeletonize). */
  skeletons = computed(() => {
    const requested = Math.max(1, Math.round(this.skeletonRowCount()) || 10);
    return Array(Math.min(requested, MAX_SKELETON_ROWS)).fill(2);
  });

  /**
   * Row-level loading:
   * - empty + loading → up to {@link MAX_SKELETON_ROWS} sentinel rows
   * - has data + loading → only the first {@link MAX_SKELETON_ROWS} rows (skeletonized); rest hidden
   * - not loading → real data only
   */
  tableData = computed(() => {
    const rows = this.visualData();
    if (!this.isLoading()) return rows;
    if (rows.length === 0) return this.skeletons();
    return rows.length > MAX_SKELETON_ROWS ? rows.slice(0, MAX_SKELETON_ROWS) : rows;
  });

  /**
   * A container that scrolls sideways must be reachable by keyboard, or its
   * hidden columns are out of reach without a pointer. It becomes a named Tab
   * stop only while it actually overflows, so a table that fits adds none.
   */
  protected scrollsSideways = computed(() => this.showHorizontalScrollLeft() || this.showHorizontalScrollRight());

  /* Table semantics. The grid is drawn with divs, so without these roles a
     screen reader meets a stack of unrelated blocks instead of a table: no
     header association, no row count, no sort state. */
  protected tableRole = computed(() => this.config().ariaRole ?? 'table');

  protected cellRole = computed(() => (this.tableRole() === 'treegrid' ? 'gridcell' : 'cell'));

  /** Counts every row, not only the rendered window, so virtualization is invisible to the reader. */
  protected ariaRowCount = computed(() => this.headerRowCount() + this.tableData().length);

  /**
   * Windowed rendering guard. Falls back to full rendering (the pre-virtualization path,
   * bit-for-bit) when row DnD is on (drop indexes map onto the full list), on an explicit
   * `smtVirtualRows=false`, or for datasets small enough not to need it. An open detail
   * row does NOT disable windowing — see {@link expandedRowIndex} / spacer compensation;
   * close-while-offscreen is covered by the {@link DETAIL_CLOSE_FALLBACK_MS} timeout.
   */
  protected virtualScrollActive = computed(() => {
    if (!this.virtualRowsEnabled() || this.rowDndEnabled()) return false;
    return this.tableData().length > VIRTUAL_ROW_THRESHOLD;
  });

  /**
   * Rendered row window [start, end). Start is snapped to an even 0-based index so
   * `:nth-child(odd/even of .smt-data-row)` zebra striping keeps its global phase.
   * An open detail block above the window shifts scrollTop by its height — subtract
   * it so the scrollTop→row-index mapping stays aligned.
   */
  protected virtualRange = computed<{ start: number; end: number }>(() => {
    const total = this.tableData().length;
    if (!this.virtualScrollActive()) return { start: 0, end: total };

    const rowH = this.virtualRowHeightPx();
    const viewH = this.scrollContainerClientHeight() || VIRTUAL_DEFAULT_VIEWPORT_PX;
    let top = this.verticalScrollTop();

    const detailIdx = this.expandedRowIndex();
    if (detailIdx >= 0 && detailIdx * rowH < top) {
      top = Math.max(0, top - this.expandedDetailHeightPx());
    }

    let start = Math.max(0, Math.floor(top / rowH) - VIRTUAL_OVERSCAN_ROWS);
    start -= start % 2;
    const end = Math.min(total, Math.ceil((top + viewH) / rowH) + VIRTUAL_OVERSCAN_ROWS);
    return { start, end };
  });

  protected virtualRows = computed(() => {
    const rows = this.tableData();
    const { start, end } = this.virtualRange();
    if (start === 0 && end >= rows.length) return rows;
    return rows.slice(start, end);
  });

  protected virtualTopSpacerPx = computed(() => {
    if (!this.virtualScrollActive()) return 0;
    const { start } = this.virtualRange();
    // An unmounted open detail block above the window still occupies scroll height.
    const detailIdx = this.expandedRowIndex();
    const detailAbovePx = detailIdx >= 0 && detailIdx < start ? this.expandedDetailHeightPx() : 0;
    return Math.round(start * this.virtualRowHeightPx() + detailAbovePx);
  });

  protected virtualBottomSpacerPx = computed(() => {
    if (!this.virtualScrollActive()) return 0;
    const hidden = this.tableData().length - this.virtualRange().end;
    return Math.max(0, Math.round(hidden * this.virtualRowHeightPx()));
  });

  protected hasMultipleSelection = computed(() => this.config().hasMultipleSelection ?? false);

  protected biruniGridLayout = computed(() => this.config().biruniGridLayout ?? false);

  protected columnResizeBasisWidth = computed(() =>
    this.biruniGridLayout()
      ? getBiruniTableSizingBasisWidth(this.scrollContainerClientWidth())
      : this.resizeContainerWidth()
  );

  /** Biruni always appends trailing `1fr` after the percent tracks. */
  protected biruniShowsFillerColumn = computed(() => this.biruniGridLayout());

  /**
   * Biruni parity: `%` / units are shares of a **stable viewport basis**, then painted as `px`.
   * Table width = max(basis, sum of those px). Expanding one column grows only that track +
   * total width — siblings keep the same px (legacy does not re-% against an expanded grid).
   */
  protected biruniGridWidthPx = computed(() => {
    const containerW = Math.max(1, this.scrollContainerClientWidth());
    const basis = getBiruniTableSizingBasisWidth(containerW);
    if (!this.biruniGridLayout()) return basis;

    let sumPx = 0;
    if (this.hasMultipleSelection()) {
      sumPx += resolveBiruniTrackWidthPx(biruniSelectionColumnPercent(), basis);
    }
    for (const column of this.config().columnsOrder) {
      sumPx += this.getBiruniColumnTrackWidthPx(column, basis);
    }

    return Math.max(basis, Math.round(sumPx));
  });

  protected showCheckboxInDedicatedColumn = computed(() => this.biruniGridLayout() && this.hasMultipleSelection());

  protected canUseRowDnd = computed(() => this.rowDndEnabled() && !this.isLoading() && !this.hasDetailRow());

  protected selectedSet = computed(() => new Set<unknown>(this.selectedItems()));

  protected columnResizeMinPx = computed(() => Math.max(24, Math.round(this.columnResizeBasisWidth() * 0.04)));

  protected columnResizeMaxPx = computed(() => Math.max(1000, this.resizeContainerWidth() * 10));

  protected columnsView = computed((): TableColumnView<T>[] => {
    const cfg = this.config();
    const biruni = this.biruniGridLayout();
    const multi = this.hasMultipleSelection();
    return cfg.columnsOrder.map((key, index) => {
      const col = cfg.columns[key];
      const content = (col?.content ?? {
        type: 'primitive' as const,
        value: () => '',
      }) as ColumnContentType<T>;
      const hasInlineSelection = !biruni && index === 0 && multi;
      return {
        key,
        index,
        align: col?.align ?? 'left',
        content,
        header: col?.header ?? { type: 'primitive', value: '' },
        hasSorting: col?.hasSorting || false,
        sortedBy: col?.sortedBy,
        sortKey: col?.key || key,
        hasInlineSelection,
        usesCellComponent: hasInlineSelection || (content.type !== 'primitive' && content.type !== 'html'),
      };
    });
  });

  protected columnTrackWidthsPx = computed(() => {
    if (!this.biruniGridLayout()) return [];
    const basis = this.columnResizeBasisWidth();
    return this.config().columnsOrder.map(column => this.getBiruniColumnTrackWidthPx(column, basis));
  });

  protected dedicatedCheckboxWidthPx = computed(() => {
    if (!this.showCheckboxInDedicatedColumn()) return 0;
    return resolveBiruniTrackWidthPx(biruniSelectionColumnPercent(), this.columnResizeBasisWidth());
  });

  /**
   * Windowed columns: Biruni px tracks only, and never together with row DnD
   * (drop indexes / drag preview assume a full grid).
   */
  protected virtualColsActive = computed(() => {
    if (this.rowDndEnabled()) return false;
    if (!this.biruniGridLayout()) return false;
    return this.config().columnsOrder.length > VIRTUAL_COL_THRESHOLD;
  });

  protected virtualColRange = computed(() => {
    const total = this.config().columnsOrder.length;
    if (!this.virtualColsActive()) {
      return this.retainColRange(0, total);
    }

    const widths = this.columnTrackWidthsPx();
    const viewW = this.scrollContainerClientWidth() || 1;
    const left = this.horizontalScrollLeft();
    const checkboxW = this.dedicatedCheckboxWidthPx();
    const startX = left;
    const endX = left + viewW;

    let acc = checkboxW;
    let start = 0;
    for (let i = 0; i < total; i++) {
      const next = acc + (widths[i] ?? 0);
      if (next > startX) {
        start = i;
        break;
      }
      acc = next;
      start = i + 1;
    }

    acc = checkboxW;
    let end = total;
    for (let i = 0; i < total; i++) {
      acc += widths[i] ?? 0;
      if (acc >= endX) {
        end = i + 1;
        break;
      }
    }

    start = Math.max(0, start - VIRTUAL_COL_OVERSCAN);
    end = Math.min(total, end + VIRTUAL_COL_OVERSCAN);
    return this.retainColRange(start, end);
  });

  protected visibleColumnsView = computed(() => {
    const { start, end } = this.virtualColRange();
    return this.columnsView().slice(start, end);
  });

  protected virtualLeftSpacerPx = computed(() => {
    if (!this.virtualColsActive()) return 0;
    const { start } = this.virtualColRange();
    const widths = this.columnTrackWidthsPx();
    let sum = 0;
    for (let i = 0; i < start; i++) sum += widths[i] ?? 0;
    return Math.round(sum);
  });

  protected virtualRightSpacerPx = computed(() => {
    if (!this.virtualColsActive()) return 0;
    const { end } = this.virtualColRange();
    const widths = this.columnTrackWidthsPx();
    let sum = 0;
    for (let i = end; i < widths.length; i++) sum += widths[i] ?? 0;
    return Math.round(sum);
  });

  /**
   * Biruni `grid-template-columns: ${sizeArray.join(' ')} 1fr` —
   * tracks are px against the viewport basis (checkbox = CELL_SHARE) + trailing `1fr`.
   * Wide Biruni grids replace off-screen tracks with spacers (column window).
   */
  protected gridTemplateColumns = computed(() => {
    const parts: string[] = [];
    if (this.biruniGridLayout()) {
      const basis = this.columnResizeBasisWidth();
      if (this.hasMultipleSelection()) {
        parts.push(`${resolveBiruniTrackWidthPx(biruniSelectionColumnPercent(), basis)}px`);
      }
      if (this.virtualColsActive()) {
        const { start, end } = this.virtualColRange();
        const widths = this.columnTrackWidthsPx();
        const left = this.virtualLeftSpacerPx();
        if (left > 0) parts.push(`${left}px`);
        for (let i = start; i < end; i++) {
          parts.push(`${widths[i] ?? 0}px`);
        }
        const right = this.virtualRightSpacerPx();
        if (right > 0) parts.push(`${right}px`);
      } else {
        for (const column of this.config().columnsOrder) {
          parts.push(`${this.getBiruniColumnTrackWidthPx(column, basis)}px`);
        }
      }
      if (this.biruniShowsFillerColumn()) {
        parts.push('1fr');
      }
      return parts.join(' ');
    }

    for (const column of this.config().columnsOrder) {
      const width = this.getColumnWidthStyle(column) ?? this.getDefaultColumnWidth();
      parts.push(this.formatGridTrackSize(width));
    }
    return parts.join(' ');
  });

  /** Biruni: width locked to sizing basis (viewport − 5); non-Biruni grows with content. */
  protected tableSurfaceClass = computed(() => {
    if (this.biruniGridLayout()) return '';
    return this.config().layout === 'fit' ? 'w-full' : 'min-w-full w-max';
  });

  protected tableRefClass = computed(() =>
    this.biruniGridLayout() || this.config().layout === 'fit' ? 'w-full smt-grid-table' : 'min-w-full w-max smt-grid-table'
  );

  protected visibleTabs = computed(() => this.tabs().filter(tab => tab.id?.trim().length > 0));

  protected hasTabs = computed(
    () => this.showTabs() && this.visibleTabs().length > 0 && this.tabsPlacement() === 'top'
  );

  protected resolvedActiveTabId = computed(() => {
    const controlled = this.activeTabId();
    if (controlled && controlled.trim().length > 0) return controlled;
    return this.uncontrolledActiveTabId();
  });

  protected tabBarTabs = computed<Tab[]>(() =>
    this.visibleTabs().map(tab => ({
      label: tab.label,
      badgeValue: tab.badge,
      disabled: tab.disabled,
    }))
  );

  protected activeTabIndex = computed(() => {
    const tabs = this.visibleTabs();
    if (tabs.length === 0) return 0;
    const activeId = this.resolvedActiveTabId();
    const index = tabs.findIndex(tab => tab.id === activeId);
    return index >= 0 ? index : 0;
  });

  protected visualData = computed(() => {
    if (!this.rowDndEnabled() || this.rowDndControlled()) return this.data();
    return this.dndData() ?? this.data();
  });

  private headerRowCount = computed(() => (this.config().hideHeader ? 0 : 1));

  /** Index of the expanded (or closing) detail row within the current data; -1 when none. */
  private expandedRowIndex = computed(() => {
    if (!this.detailRowTemplate()) return -1;
    const row = this.expandedRow();
    if (row === null) return -1;
    return this.tableData().indexOf(row as never);
  });

  /** Config `rowHeight` when it's a plain px value; otherwise the measured average. */
  private virtualRowHeightPx = computed(() => {
    const raw = (this.config().rowHeight ?? '').trim();
    const px = /^(\d+(?:\.\d+)?)px$/.exec(raw);
    if (px) return Math.max(1, Number.parseFloat(px[1]));
    return this.measuredRowHeightPx() ?? VIRTUAL_DEFAULT_ROW_HEIGHT_PX;
  });

  private hasDetailRow = computed(() => !!this.detailRowTemplate() && this.expandedRow() != null);

  /** Dedicated checkbox column: selection UI is separate; cell text is empty. */
  protected readonly checkboxCellContent: ColumnContentType<unknown> = {
    type: 'primitive',
    value: () => '',
  };

  protected readonly checkboxHeaderContent: ColumnHeaderType = {
    type: 'primitive',
    value: '',
  };

  protected readonly trackByRow = (index: number, row: unknown): unknown => {
    return this.config().trackBy(index, row as T);
  };

  private lastVirtualColRange: { start: number; end: number } | null = null;

  private isTabSyncInitialized = false;

  private lastSyncedTabId: string | null = null;

  private horizontalScrollAnimation: number | null = null;

  constructor() {
    injectRegisterSMTIcons([emptyStateIcon, leftArrowIcon, rightArrowIcon, chevronUpIcon, menu01Icon]);

    afterNextRender(() => {
      this.updateResizeContainerWidth();
      const container = this.scrollContainer()?.nativeElement;
      const tableSurface = this.tableSurfaceRef()?.nativeElement;
      if (!container) return;

      this.syncHorizontalScrollPosition(container, true);

      // Absent in jsdom and some embedded webviews; sizing then stays at its first measurement.
      if (typeof ResizeObserver === 'undefined') return;

      this.ngZone.runOutsideAngular(() => {
        const observer = new ResizeObserver(() => {
          this.updateResizeContainerWidth();
          this.syncHorizontalScrollPosition(container, false);
          this.updateHorizontalHint(container);
        });
        observer.observe(container);
        if (tableSurface) observer.observe(tableSurface);

        const onScroll = (event: Event) => this.onScrollContainerScroll(event);
        container.addEventListener('scroll', onScroll, { passive: true });

        this.destroyRef.onDestroy(() => {
          observer.disconnect();
          container.removeEventListener('scroll', onScroll);
        });
      });
    });

    effect(() => {
      const el = this.scrollContainer()?.nativeElement ?? null;
      this.updateHorizontalHint(el);
    });

    // Measure real row heights after each window render — spacer math tracks the actual
    // average, so wrapped/multi-line rows don't drift the scrollbar.
    afterRenderEffect(() => {
      if (!this.virtualScrollActive()) return;
      this.virtualRows();
      const container = this.scrollContainer()?.nativeElement;
      const rows = container?.querySelectorAll<HTMLElement>('.smt-grid-body > .smt-data-row');
      if (!rows || rows.length === 0) return;

      let sum = 0;
      for (const row of rows) {
        sum += row.getBoundingClientRect().height;
      }
      const average = sum / rows.length;
      if (average > 0 && Math.abs(average - (this.measuredRowHeightPx() ?? 0)) > 0.5) {
        this.measuredRowHeightPx.set(average);
      }
    });

    // Remember the open detail block's height while it's rendered — once it scrolls out
    // of the window the top spacer keeps compensating for it so content doesn't jump.
    afterRenderEffect(() => {
      if (this.expandedRowIndex() < 0) {
        this.expandedDetailHeightPx.set(0);
        return;
      }
      if (!this.virtualScrollActive() || this.detailClosing()) return;
      const detailEl = this.scrollContainer()?.nativeElement?.querySelector<HTMLElement>(
        '.smt-grid-body > .smt-detail-row'
      );
      if (!detailEl) return;
      const height = detailEl.getBoundingClientRect().height;
      if (height > 0 && Math.abs(height - this.expandedDetailHeightPx()) > 0.5) {
        this.expandedDetailHeightPx.set(height);
      }
    });

    // Loading: jump to top so the capped skeleton rows fill the viewport.
    effect(() => {
      if (!this.isLoading()) return;
      const el = this.scrollContainer()?.nativeElement;
      if (!el) return;
      el.scrollTop = 0;
      if (this.verticalScrollTop() !== 0) this.verticalScrollTop.set(0);
      if (this.showScrollToTop()) this.showScrollToTop.set(false);
      this.updateHorizontalHint(el);
    });

    effect(() => {
      const source = this.data();
      if (!this.rowDndEnabled() || this.rowDndControlled()) {
        this.dndData.set(null);
        return;
      }
      this.dndData.set([...source]);
    });

    effect(onCleanup => {
      const row = this.expandedRow();
      const closing = this.detailClosing();

      if (!closing || row === null) {
        this.detailCloseHeightPx.set(null);
        this.showDetailContent.set(true);
        return;
      }

      // Lock current height, drop heavy content, then animate height → 0.
      // Avoids layout thrash from collapsing while action controls are still mounted.
      const container = this.scrollContainer()?.nativeElement;
      const detailEl = container?.querySelector('.smt-detail-row') as HTMLElement | null;
      const lockedHeight = detailEl ? Math.round(detailEl.getBoundingClientRect().height) : 0;
      this.detailCloseHeightPx.set(Math.max(0, lockedHeight));

      let raf2 = 0;
      const raf1 = requestAnimationFrame(() => {
        this.showDetailContent.set(false);
        raf2 = requestAnimationFrame(() => {
          this.detailCloseHeightPx.set(0);
        });
      });

      const timeoutId = setTimeout(() => this.completeDetailClose(row), DETAIL_CLOSE_FALLBACK_MS);
      onCleanup(() => {
        cancelAnimationFrame(raf1);
        cancelAnimationFrame(raf2);
        clearTimeout(timeoutId);
      });
    });

    effect(() => {
      const tabs = this.visibleTabs();
      const controlled = this.activeTabId();
      const defaultTabId = this.defaultActiveTabId();
      const current = this.uncontrolledActiveTabId();

      if (tabs.length === 0) {
        this.uncontrolledActiveTabId.set('');
        return;
      }

      if (controlled && tabs.some(tab => tab.id === controlled)) {
        return;
      }

      const fallback =
        (defaultTabId && tabs.some(tab => tab.id === defaultTabId) ? defaultTabId : '') ||
        (current && tabs.some(tab => tab.id === current) ? current : '') ||
        tabs.find(tab => !tab.disabled)?.id ||
        tabs[0].id;

      if (fallback && fallback !== current) {
        this.uncontrolledActiveTabId.set(fallback);
      }
    });

    effect(() => {
      const activeId = this.resolvedActiveTabId();
      const tabs = this.visibleTabs();
      if (!activeId || tabs.length === 0) return;
      const tab = tabs.find(item => item.id === activeId);
      if (!tab) return;

      if (!this.isTabSyncInitialized) {
        this.isTabSyncInitialized = true;
        this.lastSyncedTabId = activeId;
        return;
      }

      if (this.lastSyncedTabId === activeId) return;
      this.lastSyncedTabId = activeId;

      const hasControlledInput = !!this.activeTabId();
      if (!hasControlledInput) return;

      this.emitTabChange(tab, 'programmatic');
    });
  }

  emitRowClick(row: unknown, event?: MouseEvent): void {
    if (this.isLoading() || typeof row === 'number') return;
    // A click on a cell's own control (a select, a button, a link) belongs to
    // that control, as keys already do: a row that opens a record must not
    // open it when the user only changed a status inside it. Not in the source.
    if (event && isInsideCellControl(event)) return;
    this.rowClick.emit(row as T);
  }

  emitRowKeydown(row: unknown, event: KeyboardEvent): void {
    if (this.isLoading() || typeof row === 'number') return;
    // Keys pressed inside a cell's own control belong to that control.
    if (event.target !== event.currentTarget) return;
    this.rowKeydown.emit({ row: row as T, event });
  }

  emitRowDblClick(row: unknown): void {
    if (this.isLoading() || typeof row === 'number') return;
    this.rowDblClick.emit(row as T);
  }

  onSortingChange(key: string, sortBy: OrderBy | undefined, column: keyof TableConfig<T>['columns']) {
    // A new object, not a mutation: setting the same reference is not a change
    // to a signal, so `aria-sort` and every other reader of the column kept
    // the old state. It also leaves the owner's config object untouched.
    const config = this.config();
    const columns = Object.fromEntries(
      Object.entries(config.columns).map(([name, info]) => [name, { ...info, sortedBy: name === column ? sortBy : undefined }])
    );
    this.config.set({ ...config, columns });
    this.sortChange.emit(sortBy ? { column: key, sortBy } : undefined);
  }

  toggleSelection(row: T) {
    if (this.isLoading() || typeof (row as unknown) === 'number') return;
    const isSelected = this.selectedItems().some(r => r === row);
    if (isSelected) {
      this.selectedItems.set(this.selectedItems().filter(r => r !== row));
    } else {
      this.selectedItems.set([...this.selectedItems(), row]);
    }
  }

  selectAll() {
    this.selectedItems.set(this.visualData());
  }

  unselectAll() {
    this.selectedItems.set([]);
  }

  onHeaderCheckboxToggle(checked: boolean) {
    if (this.isLoading()) return;
    if (checked) {
      this.selectAll();
    } else {
      this.unselectAll();
    }
  }

  /** 1-based position among all rows, header included. */
  protected ariaRowIndex(windowIndex: number): number {
    return this.headerRowCount() + this.virtualRange().start + windowIndex + 1;
  }

  protected ariaSort(sortedBy: OrderBy | undefined, hasSorting: boolean): string | null {
    if (!hasSorting) return null;
    if (sortedBy === OrderBy.Asc) return 'ascending';
    if (sortedBy === OrderBy.Desc) return 'descending';
    return 'none';
  }

  protected rowAria(row: unknown): TableRowAria | null {
    if (row === 2 || this.tableRole() !== 'treegrid') return null;
    return this.config().rowAria?.(row as T) ?? null;
  }

  protected onScrollContainerScroll(e: Event) {
    const el = e.target as HTMLElement | null;
    if (!el) return;

    const top = el.scrollTop ?? 0;
    if (this.verticalScrollTop() !== top) {
      this.verticalScrollTop.set(top);
    }
    const showTop = top > 450;
    if (this.showScrollToTop() !== showTop) {
      this.showScrollToTop.set(showTop);
    }

    const left = el.scrollLeft ?? 0;
    if (this.horizontalScrollLeft() !== left) {
      this.horizontalScrollLeft.set(left);
    }

    this.updateHorizontalHint(el);
  }

  protected scrollContainerToTop(el: HTMLElement | null) {
    if (!el) return;
    el.scrollTo({ top: 0, behavior: 'smooth' });
  }

  protected startHorizontalAutoScroll(direction: 'left' | 'right') {
    const el = this.scrollContainer()?.nativeElement ?? null;
    if (!el) return;
    this.stopHorizontalAutoScroll();
    const step = direction === 'right' ? 8 : -8;

    const tick = () => {
      const maxScroll = (el.scrollWidth ?? 0) - (el.clientWidth ?? 0);
      if (maxScroll <= 0) {
        this.stopHorizontalAutoScroll();
        this.updateHorizontalHint(el);
        return;
      }

      const next = Math.min(Math.max(0, (el.scrollLeft ?? 0) + step), maxScroll);
      if (next === el.scrollLeft) {
        this.stopHorizontalAutoScroll();
        this.updateHorizontalHint(el);
        return;
      }

      el.scrollLeft = next;
      this.updateHorizontalHint(el);
      this.horizontalScrollAnimation = window.requestAnimationFrame(tick);
    };

    this.horizontalScrollAnimation = window.requestAnimationFrame(tick);
  }

  protected stopHorizontalAutoScroll() {
    if (this.horizontalScrollAnimation !== null) {
      cancelAnimationFrame(this.horizontalScrollAnimation);
      this.horizontalScrollAnimation = null;
    }
  }

  protected onTabIndexChange(index: number): void {
    const tab = this.visibleTabs()[index];
    if (!tab) return;
    if (tab.disabled) return;

    const controlled = this.activeTabId();
    if (!controlled) {
      this.uncontrolledActiveTabId.set(tab.id);
    }

    this.lastSyncedTabId = tab.id;
    this.tabChange.emit({
      tabId: tab.id,
      index,
      tab,
      source: 'click',
    });
  }

  protected onRowDrop(event: CdkDragDrop<T[]>): void {
    if (!this.canUseRowDnd() || event.previousIndex === event.currentIndex) return;

    const current = [...this.visualData()];
    if (event.previousIndex < 0 || event.currentIndex < 0) return;
    if (event.previousIndex >= current.length || event.currentIndex >= current.length) return;
    moveItemInArray(current, event.previousIndex, event.currentIndex);

    if (!this.rowDndControlled()) {
      this.dndData.set(current);
    }

    const moved = current[event.currentIndex];
    if (!moved) return;

    this.rowReorder.emit({
      item: moved,
      previousIndex: event.previousIndex,
      currentIndex: event.currentIndex,
      data: current,
    });
  }

  protected onColumnResizeEnd(columnKey: string, result: { widthPx: number; widthPercent: string }): void {
    this.setLiveResizedWidth(columnKey, null);
    const widthPercent = this.biruniGridLayout()
      ? biruniPercentFromChipWidthPx(result.widthPx, this.columnResizeBasisWidth())
      : result.widthPercent;

    if (this.biruniGridLayout()) {
      this.applyColumnWidthPercent(columnKey, widthPercent);
    } else {
      this.applyColumnWidthPx(columnKey, result.widthPx);
    }

    const column = this.config().columns[columnKey];
    if (!column) return;

    this.columnResize.emit({
      key: column.key ?? columnKey,
      widthPx: result.widthPx,
      widthPercent,
    });
  }

  protected onColumnResizePreview(columnKey: string, result: { widthPx: number }): void {
    this.setLiveResizedWidth(columnKey, result.widthPx);
    this.syncHorizontalHintsAfterResize();
  }

  protected getColumnWidthStyle(columnKey: string): string | undefined {
    const live = this.liveResizedWidths()[columnKey];
    if (Number.isFinite(live)) {
      return this.biruniGridLayout() ? this.toLivePercentWidth(live) : `${live}px`;
    }
    return this.config().columns[columnKey]?.width ?? this.getDefaultColumnWidth();
  }

  protected showDetailForRow(row: unknown): boolean {
    if (row === 2 || !this.detailRowTemplate()) return false;
    const expanded = this.expandedRow();
    return expanded != null && expanded === row;
  }

  protected onDetailAnimationEnd(row: unknown, event: AnimationEvent): void {
    // Open uses keyframe animation; close uses height transition instead.
    if (row === 2 || this.detailClosing() || event.target !== event.currentTarget) return;
  }

  protected onDetailCloseTransitionEnd(row: unknown, event: TransitionEvent): void {
    if (row === 2 || !this.detailClosing() || event.target !== event.currentTarget) return;
    if (event.propertyName !== 'height') return;
    this.completeDetailClose(row as T);
  }

  protected resolveRowClass(row: unknown): TableRowClass | null {
    if (row === 2) return null;
    const rowClass = this.config().rowClass;
    if (!rowClass) return null;
    return rowClass(row as T) ?? null;
  }

  private retainColRange(start: number, end: number): { start: number; end: number } {
    const prev = this.lastVirtualColRange;
    if (prev && prev.start === start && prev.end === end) return prev;
    const next = { start, end };
    this.lastVirtualColRange = next;
    return next;
  }

  /** px track for one Biruni column against the stable viewport basis (live drag uses raw px). */
  private getBiruniColumnTrackWidthPx(columnKey: string, basis: number): number {
    const live = this.liveResizedWidths()[columnKey];
    if (Number.isFinite(live)) {
      return Math.max(1, Math.round(live));
    }
    const width = this.getColumnWidthStyle(columnKey) ?? this.getDefaultColumnWidth();
    return resolveBiruniTrackWidthPx(width, basis);
  }

  private getDefaultColumnWidth(): string {
    if (this.biruniGridLayout()) {
      return biruniUnitsToPercent(1);
    }
    const count = Math.max(1, this.config().columnsOrder.length);
    return `${(100 / count).toFixed(2)}%`;
  }

  private applyColumnWidthPercent(columnKey: string, widthPercent: string): void {
    const current = this.config();
    const column = current.columns[columnKey];
    if (!column) return;

    const nextColumns = {
      ...current.columns,
      [columnKey]: {
        ...column,
        width: widthPercent,
      },
    };

    this.config.set({
      ...current,
      columns: nextColumns,
    });
  }

  private toLivePercentWidth(widthPx: number): string {
    const total = Math.max(1, this.columnResizeBasisWidth());
    return `${((widthPx / total) * 100).toFixed(2)}%`;
  }

  private applyColumnWidthPx(columnKey: string, widthPx: number): void {
    const current = this.config();
    const column = current.columns[columnKey];
    if (!column) return;

    const nextColumns = {
      ...current.columns,
      [columnKey]: {
        ...column,
        width: `${widthPx}px`,
      },
    };

    this.config.set({
      ...current,
      columns: nextColumns,
    });
  }

  private syncHorizontalHintsAfterResize(): void {
    const container = this.scrollContainer()?.nativeElement ?? null;
    this.updateResizeContainerWidth();
    this.updateHorizontalHint(container);
  }

  private setLiveResizedWidth(columnKey: string, widthPx: number | null): void {
    const current = this.liveResizedWidths();
    if (widthPx == null) {
      if (!(columnKey in current)) return;
      const next = { ...current };
      delete next[columnKey];
      this.liveResizedWidths.set(next);
      return;
    }

    const rounded = Math.round(widthPx);
    if (current[columnKey] === rounded) return;
    this.liveResizedWidths.set({ ...current, [columnKey]: rounded });
  }

  private emitTabChange(tab: TableTabItem, source: 'click' | 'programmatic'): void {
    const tabs = this.visibleTabs();
    const index = tabs.findIndex(item => item.id === tab.id);
    if (index < 0) return;
    this.tabChange.emit({
      tabId: tab.id,
      index,
      tab,
      source,
    });
  }

  private completeDetailClose(row: T): void {
    if (!this.detailClosing() || this.expandedRow() !== row) return;
    this.detailCloseHeightPx.set(null);
    this.showDetailContent.set(true);
    this.detailClosed.emit(row);
  }

  private syncHorizontalScrollPosition(container: HTMLElement, resetToStart: boolean): void {
    const maxScroll = Math.max(0, (container.scrollWidth ?? 0) - (container.clientWidth ?? 0));
    if (resetToStart || maxScroll <= 0) {
      container.scrollLeft = 0;
      return;
    }

    if ((container.scrollLeft ?? 0) > maxScroll) {
      container.scrollLeft = maxScroll;
    }
  }

  private updateHorizontalHint(el: HTMLElement | null) {
    if (!el) {
      if (this.showHorizontalScrollLeft()) this.showHorizontalScrollLeft.set(false);
      if (this.showHorizontalScrollRight()) this.showHorizontalScrollRight.set(false);
      return;
    }

    const scrollWidth = el.scrollWidth ?? 0;
    const clientWidth = el.clientWidth ?? 0;
    const maxScroll = scrollWidth - clientWidth;

    if (scrollWidth <= clientWidth + 4 || maxScroll <= 0) {
      if (this.showHorizontalScrollLeft()) this.showHorizontalScrollLeft.set(false);
      if (this.showHorizontalScrollRight()) this.showHorizontalScrollRight.set(false);
      return;
    }

    const left = el.scrollLeft ?? 0;
    const showLeft = left > 4;
    const showRight = left < maxScroll - 4;
    if (this.showHorizontalScrollLeft() !== showLeft) this.showHorizontalScrollLeft.set(showLeft);
    if (this.showHorizontalScrollRight() !== showRight) this.showHorizontalScrollRight.set(showRight);
  }

  private updateResizeContainerWidth() {
    const container = this.scrollContainer()?.nativeElement;
    if (!container) return;

    const clientW = Math.max(1, Math.round(container.clientWidth || 0));
    if (this.scrollContainerClientWidth() !== clientW) this.scrollContainerClientWidth.set(clientW);

    const clientH = Math.max(0, Math.round(container.clientHeight || 0));
    if (this.scrollContainerClientHeight() !== clientH) this.scrollContainerClientHeight.set(clientH);

    const left = container.scrollLeft ?? 0;
    if (this.horizontalScrollLeft() !== left) this.horizontalScrollLeft.set(left);

    if (this.biruniGridLayout()) {
      const next = this.biruniGridWidthPx();
      if (this.resizeContainerWidth() !== next) this.resizeContainerWidth.set(next);
      return;
    }

    const tableSurface = this.tableSurfaceRef()?.nativeElement;
    const width = Math.max(
      clientW,
      tableSurface?.scrollWidth ?? 0,
      tableSurface?.getBoundingClientRect().width ?? 0,
      container.scrollWidth ?? 0
    );
    const rounded = Math.round(width);
    if (this.resizeContainerWidth() !== rounded) this.resizeContainerWidth.set(rounded);
  }

  private formatGridTrackSize(width: string): string {
    // Biruni uses bare `%` / `1fr` tracks (no minmax wrapper).
    if (this.biruniGridLayout()) return String(width ?? '').trim();
    return width;
  }
}

const CELL_CONTROL = 'a[href], button, input, select, textarea, label, summary, [contenteditable]:not([contenteditable="false"]), '
  + '[role="button"], [role="link"], [role="checkbox"], [role="switch"], [role="option"], [role="combobox"], [role="menuitem"]';

/** True when the click started inside an interactive element of the row, not on the row itself. */
function isInsideCellControl(event: MouseEvent): boolean {
  const row = event.currentTarget;
  const target = event.target;
  if (!(row instanceof Element) || !(target instanceof Element)) return false;
  const control = target.closest(CELL_CONTROL);
  return !!control && control !== row && row.contains(control);
}
