import { UplColumn, UplFileKind, UplFormatDraftRequest, UplSheet } from '../upl-api';
import { UplFieldError } from './upl-format-errors';

/** The steps of the file format. */
export type UplFormatStep = 'file' | 'sheets' | 'publish';

/** Top-level fields edited on the "File" step. */
const FILE_STEP_FIELDS = new Set(['fileKind', 'encoding', 'delimiter', 'matchColumnsBy']);

/** An empty string in an optional field means "not filled in", not an empty value. */
export function trimToNull(value: string | null | undefined): string | null {
  const trimmed = (value ?? '').trim();
  return trimmed.length === 0 ? null : trimmed;
}

export function isFilled(value: string | number | null | undefined): boolean {
  return value !== null && value !== undefined && value !== '';
}

export function emptyModel(): UplFormatDraftRequest {
  return { lockVersion: 0, fileKind: 'xlsx', encoding: null, delimiter: null, matchColumnsBy: 'header', sheets: [] };
}

export function emptySheet(): UplSheet {
  return { id: null, ordinal: null, sheetName: null, headerRow: 1, totalRowMarker: null, columns: [] };
}

export function emptyColumn(): UplColumn {
  return {
    id: null,
    ordinal: null,
    filePosition: null,
    nameInFile: '',
    targetField: '',
    dataType: 'text',
    required: false,
    sourceUnit: null,
    baseUnit: null,
    keyMask: null,
    keyPadLength: null,
    keyPadMax: null,
    refBookCode: null,
    headerSynonyms: [],
  };
}

export function isNumericColumn(column: UplColumn): boolean {
  return column.dataType === 'integer' || column.dataType === 'number';
}

/** Clears the fields the current column type does not have; true if something was filled in. */
export function clearFieldsForType(column: UplColumn): boolean {
  let cleared = false;
  if (!isNumericColumn(column) && (isFilled(column.sourceUnit) || isFilled(column.baseUnit))) {
    column.sourceUnit = null;
    column.baseUnit = null;
    cleared = true;
  }
  if (
    column.dataType !== 'object_key' &&
    (isFilled(column.keyMask) || isFilled(column.keyPadLength) || isFilled(column.keyPadMax))
  ) {
    column.keyMask = null;
    column.keyPadLength = null;
    column.keyPadMax = null;
    cleared = true;
  }
  if (column.dataType !== 'ref_code' && isFilled(column.refBookCode)) {
    column.refBookCode = null;
    cleared = true;
  }
  return cleared;
}

/** The step that edits a field with an error: a sheet address goes to "Sheets and columns", file fields to "File". */
export function uplErrorStep(error: UplFieldError): UplFormatStep {
  return error.sheet === null && FILE_STEP_FIELDS.has(error.field) ? 'file' : 'sheets';
}

/**
 * The draft as the server takes it: ordinals follow the order on screen, empty
 * optional text becomes null, and settings of the other file kind are dropped.
 */
export function buildDraftRequest(model: UplFormatDraftRequest, lockVersion: number): UplFormatDraftRequest {
  const fileKind: UplFileKind = model.fileKind ?? 'xlsx';
  const csv = fileKind === 'csv';
  return {
    lockVersion,
    fileKind,
    encoding: csv ? model.encoding : null,
    delimiter: csv ? trimToNull(model.delimiter) : null,
    matchColumnsBy: model.matchColumnsBy ?? 'header',
    sheets: model.sheets.map((sheet, index) => ({
      id: sheet.id,
      ordinal: index + 1,
      sheetName: csv ? null : trimToNull(sheet.sheetName),
      headerRow: sheet.headerRow,
      totalRowMarker: trimToNull(sheet.totalRowMarker),
      columns: sheet.columns.map((column, columnIndex) => ({
        id: column.id,
        ordinal: columnIndex + 1,
        filePosition: column.filePosition,
        nameInFile: (column.nameInFile ?? '').trim(),
        targetField: (column.targetField ?? '').trim(),
        dataType: column.dataType,
        required: column.required,
        sourceUnit: trimToNull(column.sourceUnit),
        baseUnit: trimToNull(column.baseUnit),
        keyMask: trimToNull(column.keyMask),
        keyPadLength: column.keyPadLength,
        keyPadMax: column.keyPadMax,
        refBookCode: trimToNull(column.refBookCode),
        headerSynonyms: (column.headerSynonyms ?? []).map((name) => name.trim()).filter((name) => name.length > 0),
      })),
    })),
  };
}
