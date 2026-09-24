import { UplColumn, UplFormatDraftRequest, UplSheet } from '../upl-api';
import { UplFieldError } from './upl-format-errors';

/** Шаги анкеты формата. */
export type UplFormatStep = 'file' | 'sheets' | 'publish';

/** Поля верхнего уровня, которые правятся на шаге «Файл». */
const FILE_STEP_FIELDS = new Set(['fileKind', 'encoding', 'delimiter', 'matchColumnsBy']);

/** Пустая строка в необязательном поле означает «не заполнено», а не пустое значение. */
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
    refBookCode: null
  };
}

export function isNumericColumn(column: UplColumn): boolean {
  return column.dataType === 'integer' || column.dataType === 'number';
}

/** Очищает поля, которых у текущего типа колонки нет; true — если что-то было заполнено. */
export function clearFieldsForType(column: UplColumn): boolean {
  let cleared = false;
  if (!isNumericColumn(column) && (isFilled(column.sourceUnit) || isFilled(column.baseUnit))) {
    column.sourceUnit = null;
    column.baseUnit = null;
    cleared = true;
  }
  if (column.dataType !== 'object_key'
    && (isFilled(column.keyMask) || isFilled(column.keyPadLength) || isFilled(column.keyPadMax))) {
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

/** На каком шаге правится поле с ошибкой: адрес листа — «Листы и колонки», поля файла — «Файл». */
export function uplErrorStep(error: UplFieldError): UplFormatStep {
  return error.sheet === null && FILE_STEP_FIELDS.has(error.field) ? 'file' : 'sheets';
}
