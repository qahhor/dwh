/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path utils/data-table-grid-sizing.ts.
 * Per ADR-0015: behaviour changes belong upstream; only compatibility
 * edits are made here and each is marked. See NOTICE. */
import { formatBiruniNumberDisplay } from './biruni-number';

/** Biruni b-grid / b-pg-grid shared 24-unit column model (`bConstants.CELL_SHARE`). */
export const BIRUNI_GRID_COLUMN_COUNT = 24;
/** `100 / 24` — matches Biruni `CELL_SHARE: 4.166666666666667`. */
export const BIRUNI_GRID_CELL_SHARE_PERCENT = 100 / BIRUNI_GRID_COLUMN_COUNT;
export const BIRUNI_GRID_MIN_WIDTH_PX = 880;
export const BIRUNI_GRID_MIN_RESIZE_PERCENT = 4;
export const BIRUNI_GRID_UNIT_TOLERANCE_PX = 1.5;
export const BIRUNI_DEFAULT_COL_SIZE = 1;

/** Default column-resize hit area (Biruni `.tbl-cell-resizer` width: 10px). */
export const BIRUNI_COLUMN_RESIZE_HANDLE_WIDTH_PX = 10;

/**
 * Half of the resize handle that sits past the last column edge.
 * Live table + settings content width both reserve this so a full 24/24 grid
 * does not force horizontal scroll.
 */
export const BIRUNI_GRID_RESIZE_HANDLE_OVERHANG_PX = BIRUNI_COLUMN_RESIZE_HANDLE_WIDTH_PX / 2;

/**
 * Biruni `grid_setting.html`: `content_width = wrapper.width() - 5`
 * (same 5px as {@link BIRUNI_GRID_RESIZE_HANDLE_OVERHANG_PX}).
 */
export const BIRUNI_GRID_SETTINGS_EXTRA_SPACE_PX = BIRUNI_GRID_RESIZE_HANDLE_OVERHANG_PX;

/**
 * @deprecated Checkbox is 1/24 of the table (CELL_SHARE %), not a fixed px track.
 * Kept only for consumers that still import the old constant.
 */
export const BIRUNI_SELECTION_COLUMN_WIDTH_PX = 48;

/**
 * Biruni unit → CSS % track (no `toFixed` — same as
 * `bConstants.CELL_SHARE * parseInt(c.size) + '%'`).
 */
export function biruniUnitsToPercent(units: number): string {
  const safeUnits = Number.isFinite(units) && units > 0 ? units : BIRUNI_DEFAULT_COL_SIZE;
  return `${BIRUNI_GRID_CELL_SHARE_PERCENT * safeUnits}%`;
}

/** Selection / checkbox column = 1 Biruni unit (`CELL_SHARE + '%'`). */
export function biruniSelectionColumnPercent(): string {
  return `${BIRUNI_GRID_CELL_SHARE_PERCENT}%`;
}

/** Biruni `b-col` / `b-pg-col` `align` attribute. */
export type BiruniColumnAlign = 'left' | 'center' | 'right';

/**
 * Resolve cell/header text alignment like Biruni.
 * Explicit `align` wins; otherwise `format`/`sortType` of `amount`|`number` → right
 * (common Biruni pairing: `format="amount" align="right"`).
 */
export function resolveBiruniColumnAlign(options: {
  align?: BiruniColumnAlign | null;
  format?: string | null;
  sortType?: string | null;
}): BiruniColumnAlign {
  const align = options.align;
  if (align === 'left' || align === 'center' || align === 'right') {
    return align;
  }

  const format = String(options.format ?? '')
    .trim()
    .toLowerCase();
  if (format === 'amount' || format === 'number') {
    return 'right';
  }

  const sortType = String(options.sortType ?? '')
    .trim()
    .toLowerCase();
  if (sortType === 'amount' || sortType === 'number') {
    return 'right';
  }

  return 'left';
}

/**
 * Biruni cell display for `format="amount"`: space-grouped thousands (`bNumber` / `| bNumber`).
 * `format="number"` is not displayed this way in legacy (numeric sort only).
 * Other formats / empty values are returned unchanged. Custom column `content` still wins.
 */
export function formatBiruniColumnCellValue(
  value: unknown,
  format?: string | null
): string | number | boolean | null | undefined {
  const kind = String(format ?? '')
    .trim()
    .toLowerCase();
  if (kind !== 'amount') {
    return value as string | number | boolean | null | undefined;
  }
  if (value === null || value === undefined || value === '') {
    return value as string | number | boolean | null | undefined;
  }
  return formatBiruniNumberDisplay(value as string | number);
}

/**
 * Live table sizing basis: `max(880, viewport) − 5`.
 * Subtracts the last-column resize-handle overhang, then that width is 100% for the
 * 24-unit split (checkbox stays one of the % tracks — no separate carve-out).
 */
export function getBiruniTableSizingBasisWidth(viewportWidthPx: number): number {
  return Math.max(
    1,
    Math.round(Math.max(BIRUNI_GRID_MIN_WIDTH_PX, viewportWidthPx) - BIRUNI_GRID_RESIZE_HANDLE_OVERHANG_PX)
  );
}

/**
 * Biruni settings modal content width: `parseInt(wrapper.width() - 5)`.
 */
export function getBiruniSettingsContentWidthPx(wrapperWidthPx: number): number {
  return Math.max(1, Math.round(wrapperWidthPx) - BIRUNI_GRID_SETTINGS_EXTRA_SPACE_PX);
}

/**
 * Biruni `getTableSizes(withCheckbox)` — percent tracks only (caller appends `1fr`).
 */
export function getBiruniTableSizeTracks(options: {
  columnSizes: (string | number | null | undefined)[];
  withCheckbox?: boolean;
}): string[] {
  const sizeArray = options.columnSizes.map(size => {
    const text = String(size ?? '').trim();
    if (text.endsWith('%')) {
      return `${Number.parseFloat(text) || 1}%`;
    }
    const units = Number.parseInt(text, 10);
    const safeUnits = Number.isFinite(units) && units > 0 ? units : BIRUNI_DEFAULT_COL_SIZE;
    return `${BIRUNI_GRID_CELL_SHARE_PERCENT * safeUnits}%`;
  });

  if (options.withCheckbox) {
    sizeArray.unshift(biruniSelectionColumnPercent());
  }

  return sizeArray;
}

export function resolveBiruniTrackWidthPx(width: string, basisWidthPx: number): number {
  const text = String(width ?? '').trim();
  const basis = Math.max(1, Math.round(basisWidthPx));

  if (text.endsWith('px')) {
    const px = Number.parseFloat(text.slice(0, -2));
    if (Number.isFinite(px) && px > 0) return Math.round(px);
  }

  if (text.endsWith('%')) {
    const percent = Number.parseFloat(text.slice(0, -1));
    if (Number.isFinite(percent) && percent > 0) {
      return Math.round((basis * percent) / 100);
    }
  }

  return Math.round(basis * (BIRUNI_GRID_CELL_SHARE_PERCENT / 100));
}

/**
 * Sum of Biruni column tracks in px against a stable basis, floored at `basis`
 * (so trailing `1fr` can fill when the set is under 100%). Growing one column
 * only changes that column’s px + the total — siblings stay put.
 */
export function calcBiruniTableContentWidthPx(options: {
  columnSizes: (string | number | null | undefined)[];
  withCheckbox?: boolean;
  basisWidthPx: number;
}): number {
  const basis = Math.max(1, Math.round(options.basisWidthPx));
  const tracks = getBiruniTableSizeTracks({
    columnSizes: options.columnSizes,
    withCheckbox: options.withCheckbox,
  });
  const sum = tracks.reduce((acc, track) => acc + resolveBiruniTrackWidthPx(track, basis), 0);
  return Math.max(basis, Math.round(sum));
}

export function parseBiruniPercentNumber(value: string): number {
  const text = String(value ?? '').trim();
  if (!text) return BIRUNI_GRID_CELL_SHARE_PERCENT;
  if (text.endsWith('%')) {
    const parsed = Number.parseFloat(text.slice(0, -1));
    if (Number.isFinite(parsed) && parsed > 0) return parsed;
  }
  return BIRUNI_GRID_CELL_SHARE_PERCENT;
}

export function clampBiruniResizePercent(percent: number): number {
  return Math.max(BIRUNI_GRID_MIN_RESIZE_PERCENT, percent);
}

/** Biruni `grid_setting` calcWidth(percent) against the given content width (modal or table). */
export function calcBiruniSettingsChipWidthPx(sizePercent: string, contentWidthPx: number): number {
  const basis = Math.max(1, Math.round(contentWidthPx));
  const pct = parseBiruniPercentNumber(sizePercent);
  const minPx = Math.round(basis * (BIRUNI_GRID_MIN_RESIZE_PERCENT / 100));
  return Math.max(minPx, Math.round((basis * pct) / 100));
}

export function biruniPercentFromChipWidthPx(widthPx: number, contentWidthPx: number): string {
  const basis = Math.max(1, Math.round(contentWidthPx));
  const pct = clampBiruniResizePercent((widthPx / basis) * 100);
  // Biruni resize persists `parseFloat(calc).toFixed(2) + '%'`
  return `${pct.toFixed(2)}%`;
}

export function parseBiruniColSize(value: unknown): number | undefined {
  if (value == null || value === '') return undefined;
  const parsed = typeof value === 'number' ? value : Number.parseInt(String(value), 10);
  if (!Number.isFinite(parsed) || parsed <= 0 || parsed > BIRUNI_GRID_COLUMN_COUNT) return undefined;
  return parsed;
}

/**
 * Biruni `g.cols[].size` after JSON parse: string (`"33.33%"`, `"8"`) or number (units 1..24).
 */
export function coerceStoredColumnSize(raw: unknown): string | null {
  if (raw == null) return null;

  if (typeof raw === 'number') {
    if (!Number.isFinite(raw) || raw <= 0) return null;
    if (Number.isInteger(raw) && raw <= BIRUNI_GRID_COLUMN_COUNT) {
      return String(raw);
    }
    return String(raw);
  }

  if (typeof raw === 'string') {
    const text = raw.trim();
    return text.length > 0 ? text : null;
  }

  return null;
}

/**
 * Resolve runtime column width for Biruni-parity tables.
 * Priority: user override → colSize → width → default 1 unit.
 * Mirrors Biruni `getTableSizes` token rules.
 */
export function resolveBiruniColumnWidthPercent(options: {
  overrideWidth?: string | null;
  configuredWidth?: string | null;
  colSize?: number | null;
}): string {
  const override = String(options.overrideWidth ?? '').trim();
  if (override) {
    return normalizeBiruniWidthToken(override);
  }

  const colSize = parseBiruniColSize(options.colSize);
  if (colSize != null) {
    return biruniUnitsToPercent(colSize);
  }

  const configured = String(options.configuredWidth ?? '').trim();
  if (configured) {
    return normalizeBiruniWidthToken(configured);
  }

  return biruniUnitsToPercent(BIRUNI_DEFAULT_COL_SIZE);
}

/**
 * Same `%` token for table grid and settings modal chips (Biruni `d.initial` parity).
 * Converts legacy `px` tokens to `%` using basis width when needed.
 */
export function resolveBiruniSettingsColumnPercent(options: {
  overrideWidth?: string | null;
  configuredWidth?: string | null;
  colSize?: number | null;
  basisWidthPx?: number;
}): string {
  const raw = resolveBiruniColumnWidthPercent(options);
  if (raw.endsWith('%')) {
    return raw;
  }

  if (raw.endsWith('px')) {
    const px = Number.parseFloat(raw.slice(0, -2));
    const basis = options.basisWidthPx ?? getBiruniTableSizingBasisWidth(BIRUNI_GRID_MIN_WIDTH_PX);
    if (Number.isFinite(px) && px > 0) {
      return biruniPercentFromChipWidthPx(px, basis);
    }
  }

  return biruniUnitsToPercent(1);
}

function normalizeBiruniWidthToken(raw: string): string {
  const text = raw.trim();
  if (!text) return biruniUnitsToPercent(BIRUNI_DEFAULT_COL_SIZE);

  if (text.endsWith('%')) {
    // Biruni: `(parseFloat(c.size) || 1) + '%'`
    return `${Number.parseFloat(text) || 1}%`;
  }

  if (/^\d+(\.\d+)?$/.test(text)) {
    const parsed = Number.parseFloat(text);
    if (Number.isFinite(parsed) && parsed > 0 && parsed <= BIRUNI_GRID_COLUMN_COUNT) {
      return biruniUnitsToPercent(parsed);
    }
  }

  return text;
}
