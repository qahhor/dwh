interface ColumnResizeKeyboardOptions {
  enabled: () => boolean;
  label: () => string;
  current: () => number;
  minimum: () => number;
  maximum: (current: number) => number;
  resize: (width: number) => void;
}

/** The handle is a focusable separator; arrows move it and Home/End reach its bounds. */
export function installColumnResizeKeyboard(handle: HTMLElement, options: ColumnResizeKeyboardOptions): () => void {
  handle.setAttribute('role', 'separator');
  handle.setAttribute('aria-orientation', 'vertical');
  const refresh = (current = options.current()) => {
    handle.tabIndex = options.enabled() ? 0 : -1;
    handle.setAttribute('aria-label', options.label());
    handle.setAttribute('aria-valuemin', String(options.minimum()));
    handle.setAttribute('aria-valuemax', String(Math.max(options.minimum(), options.maximum(current))));
    handle.setAttribute('aria-valuenow', String(Math.round(current)));
  };
  handle.addEventListener('focus', () => refresh());
  handle.addEventListener('keydown', (event) => {
    if (!options.enabled() || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    event.stopPropagation();
    const current = options.current();
    const minimum = options.minimum();
    const maximum = Math.max(minimum, options.maximum(current));
    const step = event.shiftKey ? 50 : 10;
    const requested =
      event.key === 'Home'
        ? minimum
        : event.key === 'End'
          ? maximum
          : current + (event.key === 'ArrowLeft' ? -step : step);
    const next = Math.round(Math.max(minimum, Math.min(maximum, requested)));
    if (next !== current) options.resize(next);
    refresh(next);
  });
  return () => refresh();
}

/** Pointer and keyboard interactions share the same visible resize handle. */
export function createColumnResizeHandle(width: number): { handle: HTMLElement; line: HTMLElement } {
  const handleWidth = Math.max(2, Math.round(width));
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
      width: 1px;
      margin-left: -0.5px;
      background: transparent;
      border-radius: 1px;
      opacity: 0;
      pointer-events: none;
      transition:
        background 120ms ease,
        opacity 120ms ease;
    `;

  return { handle, line };
}
