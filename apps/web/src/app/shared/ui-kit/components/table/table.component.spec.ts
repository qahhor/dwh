/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/table/table.component.spec.ts.
 * Per ADR-0015 rule 6 the tests travel with the component. */
// @vitest-environment jsdom
import '@angular/compiler';
import { describe, expect, it, vi } from 'vitest';
import { SMTTableComponent } from './table.component';

describe('SMTTableComponent detail animation', () => {
  interface Row {
    id: number;
  }

  function createHarness(expandedRow: Row | null, closing = true) {
    const emit = vi.fn();
    const context = {
      detailClosing: () => closing,
      expandedRow: () => expandedRow,
      detailClosed: { emit },
      detailCloseHeightPx: { set: vi.fn() },
      showDetailContent: { set: vi.fn() },
      completeDetailClose: SMTTableComponent.prototype['completeDetailClose'],
    } as unknown as SMTTableComponent<Row>;

    return { context, emit };
  }

  it('completes close on height transition end', () => {
    const row = { id: 1 };
    const element = document.createElement('div');
    const event = {
      propertyName: 'height',
      currentTarget: element,
      target: element,
    } as unknown as TransitionEvent;
    const { context, emit } = createHarness(row);

    SMTTableComponent.prototype['onDetailCloseTransitionEnd'].call(context, row, event);

    expect(emit).toHaveBeenCalledWith(row);
  });

  it('ignores bubbled child transitions', () => {
    const row = { id: 1 };
    const element = document.createElement('div');
    const event = {
      propertyName: 'height',
      currentTarget: element,
      target: document.createElement('button'),
    } as unknown as TransitionEvent;
    const { context, emit } = createHarness(row);

    SMTTableComponent.prototype['onDetailCloseTransitionEnd'].call(context, row, event);

    expect(emit).not.toHaveBeenCalled();
  });

  it('ignores stale close completion for a different expanded row', () => {
    const closingRow = { id: 1 };
    const currentRow = { id: 2 };
    const { context, emit } = createHarness(currentRow);

    SMTTableComponent.prototype['completeDetailClose'].call(context, closingRow);

    expect(emit).not.toHaveBeenCalled();
  });
});

describe('SMTTableComponent virtual rows', () => {
  interface Row {
    id: number;
  }

  const makeRows = (count: number): Row[] => Array.from({ length: count }, (_, i) => ({ id: i + 1 }));

  const baseConfig = (rowHeight?: string) => ({
    columns: { id: { header: { type: 'primitive', value: 'ID' }, content: { type: 'primitive', value: () => '' } } },
    columnsOrder: ['id'],
    trackBy: (_index: number, item: Row) => item.id,
    rowHeight,
  });

  interface HarnessOverrides {
    rows?: Row[];
    rowHeight?: string;
    rowDndEnabled?: boolean;
    virtualRowsEnabled?: boolean;
    detailRowTemplate?: unknown;
    expandedRow?: Row | null;
  }

  async function createComponent(overrides: HarnessOverrides = {}) {
    const { TestBed } = await import('@angular/core/testing');
    const { provideZonelessChangeDetection, signal } = await import('@angular/core');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });

    const component = TestBed.runInInjectionContext(() => new SMTTableComponent<Row>());

    component.data = signal(overrides.rows ?? makeRows(1000)) as never;
    component.config = signal(baseConfig(overrides.rowHeight)) as never;
    component.isLoading = signal(false) as never;
    component.rowDndEnabled = signal(overrides.rowDndEnabled ?? false) as never;
    component.rowDndControlled = signal(false) as never;
    component.virtualRowsEnabled = signal(overrides.virtualRowsEnabled ?? true) as never;
    component.detailRowTemplate = signal(overrides.detailRowTemplate ?? undefined) as never;
    component.expandedRow = signal(overrides.expandedRow ?? null) as never;
    component.detailClosing = signal(false) as never;
    component.skeletonRowCount = signal(10) as never;

    return component;
  }

  it('renders every row below the threshold (legacy path untouched)', async () => {
    const rows = makeRows(50);
    const component = await createComponent({ rows });

    expect(component['virtualScrollActive']()).toBe(false);
    expect(component['virtualRows']()).toBe(component.tableData());
    expect(component['virtualTopSpacerPx']()).toBe(0);
    expect(component['virtualBottomSpacerPx']()).toBe(0);
  });

  it('windows large datasets from the top with a bottom spacer', async () => {
    const component = await createComponent();

    // viewport fallback 800px, row fallback 36px → ceil(800/36) + 10 overscan = 33 rows
    expect(component['virtualScrollActive']()).toBe(true);
    expect(component['virtualRange']()).toEqual({ start: 0, end: 33 });
    expect(component['virtualRows']().length).toBe(33);
    expect(component['virtualTopSpacerPx']()).toBe(0);
    expect(component['virtualBottomSpacerPx']()).toBe((1000 - 33) * 36);
  });

  it('keeps zebra parity by snapping the window start to an even index', async () => {
    const component = await createComponent();

    component['verticalScrollTop'].set(3636); // floor(3636/36) - 10 = 91 → snapped to 90
    const range = component['virtualRange']();
    expect(range.start).toBe(90);
    expect(range.start % 2).toBe(0);
    expect(component['virtualTopSpacerPx']()).toBe(90 * 36);
    expect(component['virtualRows']()[0]).toEqual({ id: 91 });
  });

  it('honors an explicit px rowHeight from config', async () => {
    const component = await createComponent({ rowHeight: '48px' });

    // ceil(800/48) + 10 = 27
    expect(component['virtualRange']()).toEqual({ start: 0, end: 27 });
    expect(component['virtualBottomSpacerPx']()).toBe((1000 - 27) * 48);
  });

  it('falls back to full rendering when row DnD is enabled', async () => {
    const component = await createComponent({ rowDndEnabled: true });

    expect(component['virtualScrollActive']()).toBe(false);
    expect(component['virtualRows']().length).toBe(1000);
  });

  it('stays windowed while a detail row is expanded', async () => {
    const rows = makeRows(1000);
    const component = await createComponent({ rows, detailRowTemplate: {}, expandedRow: rows[5] });

    expect(component['virtualScrollActive']()).toBe(true);
    const { start, end } = component['virtualRange']();
    expect(component['virtualRows']().length).toBe(end - start);
    expect(component['virtualRows']()).toContain(rows[5]);
  });

  it('compensates the top spacer for an open detail block above the window', async () => {
    const rows = makeRows(1000);
    const component = await createComponent({ rows, detailRowTemplate: {}, expandedRow: rows[10] });

    component['expandedDetailHeightPx'].set(150);
    component['verticalScrollTop'].set(3636);

    // effectiveTop = 3636 - 150 → start = floor(3486/36) - 10 = 86 (even)
    const range = component['virtualRange']();
    expect(range.start).toBe(86);
    expect(component['virtualTopSpacerPx']()).toBe(86 * 36 + 150);
  });

  it('does not compensate when the expanded row is inside or below the window', async () => {
    const rows = makeRows(1000);
    const component = await createComponent({ rows, detailRowTemplate: {}, expandedRow: rows[500] });

    component['expandedDetailHeightPx'].set(150);

    const range = component['virtualRange']();
    expect(range.start).toBe(0);
    expect(component['virtualTopSpacerPx']()).toBe(0);
  });

  it('can be disabled entirely via smtVirtualRows', async () => {
    const component = await createComponent({ virtualRowsEnabled: false });

    expect(component['virtualScrollActive']()).toBe(false);
    expect(component['virtualRows']().length).toBe(1000);
  });
});

describe('SMTTableComponent scroll container', () => {
  function mockSignal<T>(initial: T) {
    let value = initial;
    const fn = (() => value) as (() => T) & { set: ReturnType<typeof vi.fn> };
    fn.set = vi.fn((next: T) => {
      if (!Object.is(value, next)) {
        value = next;
      }
    });
    return fn;
  }

  it('updates verticalScrollTop, scroll-to-top, and horizontal fade hints', () => {
    const verticalScrollTop = mockSignal(0);
    const showScrollToTop = mockSignal(false);
    const horizontalScrollLeft = mockSignal(0);
    const showHorizontalScrollLeft = mockSignal(false);
    const showHorizontalScrollRight = mockSignal(false);
    const context = {
      verticalScrollTop,
      showScrollToTop,
      horizontalScrollLeft,
      showHorizontalScrollLeft,
      showHorizontalScrollRight,
      updateHorizontalHint: SMTTableComponent.prototype['updateHorizontalHint'],
    } as unknown as SMTTableComponent<{ id: number }>;

    const el = {
      scrollTop: 500,
      scrollLeft: 40,
      scrollWidth: 1000,
      clientWidth: 400,
    };

    SMTTableComponent.prototype['onScrollContainerScroll'].call(context, { target: el } as unknown as Event);

    expect(verticalScrollTop.set).toHaveBeenCalledWith(500);
    expect(showScrollToTop.set).toHaveBeenCalledWith(true);
    expect(horizontalScrollLeft.set).toHaveBeenCalledWith(40);
    expect(showHorizontalScrollLeft.set).toHaveBeenCalledWith(true);
    expect(showHorizontalScrollRight.set).toHaveBeenCalledWith(true);
  });

  it('does not poke verticalScrollTop or showScrollToTop on horizontal-only scroll', () => {
    const verticalScrollTop = mockSignal(0);
    const showScrollToTop = mockSignal(false);
    const horizontalScrollLeft = mockSignal(0);
    const showHorizontalScrollLeft = mockSignal(false);
    const showHorizontalScrollRight = mockSignal(false);
    const context = {
      verticalScrollTop,
      showScrollToTop,
      horizontalScrollLeft,
      showHorizontalScrollLeft,
      showHorizontalScrollRight,
      updateHorizontalHint: SMTTableComponent.prototype['updateHorizontalHint'],
    } as unknown as SMTTableComponent<{ id: number }>;

    const el = {
      scrollTop: 0,
      scrollLeft: 80,
      scrollWidth: 1000,
      clientWidth: 400,
    };

    SMTTableComponent.prototype['onScrollContainerScroll'].call(context, { target: el } as unknown as Event);

    expect(verticalScrollTop.set).not.toHaveBeenCalled();
    expect(showScrollToTop.set).not.toHaveBeenCalled();
    expect(horizontalScrollLeft.set).toHaveBeenCalledWith(80);
    expect(showHorizontalScrollLeft.set).toHaveBeenCalledWith(true);
    expect(showHorizontalScrollRight.set).toHaveBeenCalledWith(true);
  });
});

describe('SMTTableComponent primitive cells and column window', () => {
  interface Row {
    id: number;
  }

  const makeRows = (count: number): Row[] => Array.from({ length: count }, (_, i) => ({ id: i + 1 }));

  async function createWideTable(
    overrides: {
      columnCount?: number;
      rowCount?: number;
      rowDndEnabled?: boolean;
      hasMultipleSelection?: boolean;
      contentType?: 'primitive' | 'html';
    } = {}
  ) {
    const { TestBed } = await import('@angular/core/testing');
    const { provideZonelessChangeDetection, signal } = await import('@angular/core');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });

    const columnCount = overrides.columnCount ?? 80;
    const columns: Record<string, unknown> = {};
    const columnsOrder: string[] = [];
    for (let i = 0; i < columnCount; i++) {
      const key = `c${i}`;
      columnsOrder.push(key);
      columns[key] = {
        header: { type: 'primitive', value: key },
        content:
          overrides.contentType === 'html'
            ? { type: 'html', value: () => '<b>x</b>' }
            : { type: 'primitive', value: () => '' },
        width: '4.17%',
      };
    }

    const component = TestBed.runInInjectionContext(() => new SMTTableComponent<Row>());
    component.data = signal(makeRows(overrides.rowCount ?? 50)) as never;
    component.config = signal({
      columns,
      columnsOrder,
      trackBy: (_index: number, item: Row) => item.id,
      biruniGridLayout: true,
      hasMultipleSelection: overrides.hasMultipleSelection ?? true,
    }) as never;
    component.isLoading = signal(false) as never;
    component.rowDndEnabled = signal(overrides.rowDndEnabled ?? false) as never;
    component.rowDndControlled = signal(false) as never;
    component.virtualRowsEnabled = signal(true) as never;
    component.detailRowTemplate = signal(undefined) as never;
    component.expandedRow = signal(null) as never;
    component.detailClosing = signal(false) as never;
    component.skeletonRowCount = signal(10) as never;
    component['scrollContainerClientWidth'].set(400);
    return component;
  }

  it('marks primitive columns as inline (no smt-cell-content host)', async () => {
    const component = await createWideTable({ columnCount: 80, rowCount: 50 });
    const view = component['columnsView']();
    expect(view).toHaveLength(80);
    expect(view.every(col => !col.usesCellComponent && col.content.type === 'primitive')).toBe(true);
  });

  it('marks html columns as inline (no smt-cell-content host)', async () => {
    const component = await createWideTable({ columnCount: 8, contentType: 'html' });
    expect(component['columnsView']().every(col => !col.usesCellComponent)).toBe(true);
  });

  it('windows wide biruni grids so only visible columns plus overscan are in the view', async () => {
    const component = await createWideTable({ columnCount: 40, rowCount: 10 });

    expect(component['virtualColsActive']()).toBe(true);
    const visible = component['visibleColumnsView']();
    expect(visible.length).toBeGreaterThan(0);
    expect(visible.length).toBeLessThan(40);
    expect(component['virtualColRange']().start).toBe(0);
    expect(component['virtualLeftSpacerPx']()).toBe(0);
    expect(component['virtualRightSpacerPx']()).toBeGreaterThan(0);
  });

  it('shifts the column window when scrollLeft moves past rendered tracks', async () => {
    const component = await createWideTable({ columnCount: 40, rowCount: 10 });
    const firstWindow = component['virtualColRange']();

    component['horizontalScrollLeft'].set(2000);
    const shifted = component['virtualColRange']();

    expect(shifted.start).toBeGreaterThan(firstWindow.start);
    expect(shifted.end).toBeGreaterThan(firstWindow.end);
    expect(component['visibleColumnsView']().length).toBeLessThan(40);
    expect(component['virtualLeftSpacerPx']()).toBeGreaterThan(0);
  });

  it('disables column windowing when row DnD is on', async () => {
    const component = await createWideTable({ columnCount: 40, rowDndEnabled: true });

    expect(component['virtualColsActive']()).toBe(false);
    expect(component['visibleColumnsView']()).toHaveLength(40);
    expect(component['virtualLeftSpacerPx']()).toBe(0);
    expect(component['virtualRightSpacerPx']()).toBe(0);
  });
});
