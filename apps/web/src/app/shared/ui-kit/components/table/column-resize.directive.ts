/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/table/column-resize.directive.ts.
 * Per ADR-0015: behaviour changes belong upstream; only compatibility
 * edits are made here and each is marked. See NOTICE. */
import {
  booleanAttribute,
  Directive,
  effect,
  ElementRef,
  inject,
  input,
  OnDestroy,
  OnInit,
  output,
} from '@angular/core';

interface ResizeSession {
  startClientX: number;
  startWidthPx: number;
  containerWidthPx: number;
  minPx: number;
  maxPx: number;
}

const RESIZE_LINE_ACTIVE_COLOR = '#d0d5dd';
const RESIZE_LINE_PASSIVE_COLOR = 'rgba(208, 213, 221, 0.42)';
const RESIZE_LINE_WIDTH_PX = 1;

@Directive({
  selector: '[smtColumnResize]',
  standalone: true,
})
export class ColumnResizeDirective implements OnInit, OnDestroy {
  private el = inject(ElementRef<HTMLElement>);

  smtColumnResize = input(true, { transform: booleanAttribute });

  smtColumnResizeMin = input(60);

  smtColumnResizeMax = input(2000);

  smtColumnResizeContainerWidth = input(1);

  /**
   * Full hit-area width in px, centered on the column’s right edge
   * (half extends into this column, half into the next). Default 10 → 5px each side.
   */
  smtColumnResizeHandleWidth = input(10);

  /**
   * If true (default), resize won't create horizontal overflow when table
   * initially fits into its scroll container.
   */
  smtColumnResizePreventOverflow = input(true, { transform: booleanAttribute });

  /**
   * If true, width is expected to be applied continuously while dragging,
   * so the visual guide should stay on the current edge.
   */
  smtColumnResizeLive = input(false, { transform: booleanAttribute });

  resizeChange = output<{ widthPx: number; widthPercent: string }>();

  resizeEnd = output<{ widthPx: number; widthPercent: string }>();

  private static activeResizer: ColumnResizeDirective | null = null;

  private session: ResizeSession | null = null;

  private currentWidthPx = 0;

  private hasMoved = false;

  private boundMove: ((e: PointerEvent) => void) | null = null;

  private boundEnd: ((e: PointerEvent) => void) | null = null;

  private activePointerId: number | null = null;

  private handle: HTMLElement | null = null;

  private line: HTMLElement | null = null;

  private hostMouseEnter: (() => void) | null = null;

  private hostMouseLeave: (() => void) | null = null;

  private handleEventBlockers: { type: string; listener: EventListener }[] = [];

  private readonly onHandlePointerEnter = (): void => {
    this.refreshSiblingStacking();
  };

  constructor() {
    effect(() => {
      this.smtColumnResize();
      this.applyEnabledState();
    });
  }

  ngOnInit(): void {
    const handle = this.createResizeHandle();
    this.handle = handle;
    this.applyEnabledState();
    this.el.nativeElement.appendChild(handle);
    this.refreshSiblingStacking();
    this.attachHostHoverListeners();
  }

  ngOnDestroy(): void {
    if (ColumnResizeDirective.activeResizer === this) {
      ColumnResizeDirective.activeResizer = null;
    }
    this.cleanup();
    this.detachHandleEventBlockers();
    this.detachHostHoverListeners();
  }

  private createResizeHandle(): HTMLElement {
    const handleWidth = Math.max(2, Math.round(this.smtColumnResizeHandleWidth()));
    const handle = document.createElement('div');
    handle.className = 'smt-column-resize-handle';
    // Biruni `.tbl-cell-resizer`: `position:absolute; right:0; width:10px` (inside the cell).
    handle.style.cssText = `
      position: absolute;
      right: 0;
      top: 0;
      bottom: 0;
      width: ${handleWidth}px;
      cursor: col-resize;
      z-index: 20;
      touch-action: none;
      user-select: none;
    `;

    const line = document.createElement('span');
    line.style.cssText = `
      position: absolute;
      left: 50%;
      top: 0;
      bottom: 0;
      width: ${RESIZE_LINE_WIDTH_PX}px;
      margin-left: -${RESIZE_LINE_WIDTH_PX / 2}px;
      background: transparent;
      border-radius: 1px;
      opacity: 0;
      pointer-events: none;
      transition:
        background 120ms ease,
        opacity 120ms ease;
    `;

    this.line = line;
    handle.appendChild(line);
    handle.addEventListener('pointerdown', (e: PointerEvent) => this.onResizeStart(e));
    handle.addEventListener('click', (e: MouseEvent) => {
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
    });
    this.blockDragStartEvents(handle);
    return handle;
  }

  private onResizeStart(e: PointerEvent): void {
    if (!this.smtColumnResize()) return;
    if (!e.isPrimary) return;
    if (e.pointerType === 'mouse' && e.button !== 0) return;

    // Keep this column above the next so the right half of the handle stays hittable.
    this.refreshSiblingStacking();

    e.preventDefault();
    e.stopPropagation();
    e.stopImmediatePropagation();

    const startWidth = Math.round(this.el.nativeElement.getBoundingClientRect().width);
    const minPx = this.smtColumnResizeMin();
    const maxPx = this.getDragMaxWidth(startWidth);

    const clampedStartWidth = Math.max(minPx, Math.min(maxPx, startWidth));
    this.session = {
      startClientX: e.clientX,
      startWidthPx: clampedStartWidth,
      containerWidthPx: Math.max(1, Math.round(this.smtColumnResizeContainerWidth() || 1)),
      minPx,
      maxPx,
    };
    this.currentWidthPx = clampedStartWidth;
    this.hasMoved = false;
    this.activePointerId = e.pointerId;
    ColumnResizeDirective.activeResizer = this;

    try {
      this.handle?.setPointerCapture(e.pointerId);
    } catch {
      // Pointer capture can fail for synthetic events; document listeners still handle real drags.
    }

    this.setLineOffset(0);
    this.setLineVisible(true);

    this.boundMove = (moveEvent: PointerEvent) => this.onResizeMove(moveEvent);
    this.boundEnd = (endEvent: PointerEvent) => this.onResizeEnd(endEvent);
    document.addEventListener('pointermove', this.boundMove);
    document.addEventListener('pointerup', this.boundEnd);
    document.addEventListener('pointercancel', this.boundEnd);
  }

  private onResizeMove(e: PointerEvent): void {
    const session = this.session;
    if (!session) return;
    if (this.activePointerId !== null && e.pointerId !== this.activePointerId) return;

    e.preventDefault();

    const delta = e.clientX - session.startClientX;
    if (!this.hasMoved && Math.abs(delta) < 2) return;
    this.hasMoved = true;

    this.currentWidthPx = Math.round(session.startWidthPx + delta);
    this.currentWidthPx = Math.max(session.minPx, Math.min(session.maxPx, this.currentWidthPx));

    const lineDelta = this.currentWidthPx - session.startWidthPx;
    this.setLineOffset(this.smtColumnResizeLive() ? 0 : lineDelta);
    this.resizeChange.emit(this.toResizePayload(this.currentWidthPx));
  }

  private onResizeEnd(e?: PointerEvent): void {
    if (e && this.activePointerId !== null && e.pointerId !== this.activePointerId) return;

    const wasMoved = this.hasMoved;
    const widthPx = this.currentWidthPx;
    const pointerId = this.activePointerId;
    const containerWidthPx = this.session?.containerWidthPx;

    // Release drag state first.
    this.session = null;
    this.hasMoved = false;
    this.activePointerId = null;
    if (ColumnResizeDirective.activeResizer === this) {
      ColumnResizeDirective.activeResizer = null;
    }
    if (pointerId !== null) {
      try {
        this.handle?.releasePointerCapture(pointerId);
      } catch {
        // Ignore missing capture.
      }
    }
    this.cleanup();
    this.setLineVisible(this.el.nativeElement.matches(':hover'));

    if (!wasMoved) {
      this.setLineOffset(0);
      return;
    }

    this.setLineOffset(0);

    const payload = this.toResizePayload(widthPx, containerWidthPx);
    this.resizeEnd.emit(payload);
  }

  private cleanup(): void {
    if (this.boundMove) {
      document.removeEventListener('pointermove', this.boundMove);
      this.boundMove = null;
    }
    if (this.boundEnd) {
      document.removeEventListener('pointerup', this.boundEnd);
      document.removeEventListener('pointercancel', this.boundEnd);
      this.boundEnd = null;
    }
  }

  private blockDragStartEvents(handle: HTMLElement): void {
    const block = (e: Event) => {
      e.stopPropagation();
      e.stopImmediatePropagation();
    };

    for (const type of ['mousedown', 'touchstart', 'dragstart']) {
      handle.addEventListener(type, block, { capture: true, passive: false });
      this.handleEventBlockers.push({ type, listener: block });
    }
  }

  private detachHandleEventBlockers(): void {
    const handle = this.handle;
    if (!handle) {
      this.handleEventBlockers = [];
      return;
    }

    for (const { type, listener } of this.handleEventBlockers) {
      handle.removeEventListener(type, listener, { capture: true });
    }
    this.handleEventBlockers = [];
  }

  private applyEnabledState(): void {
    if (!this.handle) return;
    const enabled = this.smtColumnResize();
    this.handle.style.display = enabled ? 'block' : 'none';
    if (!enabled || !this.session) {
      this.setLineVisible(false);
    }
  }

  private toResizePayload(widthPx: number, containerWidthPx?: number): { widthPx: number; widthPercent: string } {
    const total = containerWidthPx || this.session?.containerWidthPx || this.smtColumnResizeContainerWidth() || 1;
    const widthPercent = `${((widthPx / total) * 100).toFixed(2)}%`;
    return { widthPx, widthPercent };
  }

  private getDragMaxWidth(startWidth: number): number {
    const configuredMax = this.smtColumnResizeMax();
    if (!this.smtColumnResizePreventOverflow()) return configuredMax;

    const container = this.el.nativeElement.closest('.smt-table-scroll') as HTMLElement | null;
    if (!container) return configuredMax;

    const containerWidth = container.clientWidth;
    const contentWidth = container.scrollWidth;
    const alreadyOverflowing = contentWidth > containerWidth + 1;
    if (alreadyOverflowing) return configuredMax;

    const slack = Math.max(0, containerWidth - contentWidth);
    return Math.min(configuredMax, startWidth + slack);
  }

  private attachHostHoverListeners(): void {
    const host = this.el.nativeElement;
    this.hostMouseEnter = () => {
      const activeResizer = ColumnResizeDirective.activeResizer;
      if (activeResizer && activeResizer !== this) {
        this.setLineVisible(false);
        return;
      }
      this.refreshSiblingStacking();
      this.setLineVisible(true);
    };
    this.hostMouseLeave = () => {
      if (this.session) return;
      this.setLineVisible(false);
    };
    host.addEventListener('mouseenter', this.hostMouseEnter);
    host.addEventListener('mouseleave', this.hostMouseLeave);

    this.handle?.addEventListener('pointerenter', this.onHandlePointerEnter);
  }

  /**
   * Earlier siblings stack above later ones so adjacent resize handles stay
   * hoverable when tracks are narrow.
   */
  private refreshSiblingStacking(): void {
    const host = this.el.nativeElement;
    const parent = host.parentElement;
    if (!parent) return;

    const siblings = Array.from(parent.children) as HTMLElement[];
    siblings.forEach((sibling, index) => {
      const style = sibling.style;
      if (!style.position || style.position === 'static') {
        const computed = typeof getComputedStyle === 'function' ? getComputedStyle(sibling).position : 'static';
        if (computed === 'static') {
          style.position = 'relative';
        }
      }
      style.zIndex = String(1000 - index);
    });
  }

  private detachHostHoverListeners(): void {
    const host = this.el.nativeElement;
    if (this.hostMouseEnter) {
      host.removeEventListener('mouseenter', this.hostMouseEnter);
      this.hostMouseEnter = null;
    }
    if (this.hostMouseLeave) {
      host.removeEventListener('mouseleave', this.hostMouseLeave);
      this.hostMouseLeave = null;
    }
    this.handle?.removeEventListener('pointerenter', this.onHandlePointerEnter);
  }

  private setLineVisible(visible: boolean): void {
    if (!this.line) return;
    const passiveVisible = this.shouldShowPassiveResizeLine();
    this.line.style.background = visible
      ? RESIZE_LINE_ACTIVE_COLOR
      : passiveVisible
        ? RESIZE_LINE_PASSIVE_COLOR
        : 'transparent';
    this.line.style.opacity = visible ? '1' : passiveVisible ? '1' : '0';
  }

  private setLineOffset(offsetPx: number): void {
    if (!this.line) return;
    this.line.style.transform = `translateX(${Math.round(offsetPx)}px)`;
  }

  private shouldShowPassiveResizeLine(): boolean {
    // Compatibility: guard matchMedia itself, not only window. It is absent in
    // jsdom and in some embedded webviews, where the unguarded call throws.
    // Browser behaviour is unchanged. Worth taking upstream.
    return (
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(pointer: coarse)').matches &&
      this.smtColumnResize()
    );
  }
}
