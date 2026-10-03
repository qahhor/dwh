import { FieldErrorItem, ProblemDetail } from '@core/models/common.models';
import { UplFormatDraftRequest } from '../upl.api';
import { uplErrorKey } from '../upl-labels';

/**
 * A server error with an address: sheet/column are zero-based indexes from `sheets[i].columns[j].<field>`;
 * null means a level up.
 */
export interface UplFieldError {
  sheet: number | null;
  column: number | null;
  field: string;
  code: string;
  key: string;
  message: string;
}

const ADDRESS_PATTERN = /^sheets\[(\d+)\](?:\.columns\[(\d+)\])?(?:\.(.+))?$/;

const DEFAULT_CODE = 'VALIDATION_FAILED';

interface UplErrorAddress {
  sheet: number | null;
  column: number | null;
  field: string;
}

function parseAddress(address: string): UplErrorAddress {
  const match = ADDRESS_PATTERN.exec(address);
  if (!match) {
    return { sheet: null, column: null, field: address };
  }
  return {
    sheet: Number(match[1]),
    column: match[2] === undefined ? null : Number(match[2]),
    field: match[3] ?? '',
  };
}

function toFieldError(address: string, code: string, message: string): UplFieldError {
  const normalizedCode = code || DEFAULT_CODE;
  return {
    ...parseAddress(address),
    code: normalizedCode,
    key: uplErrorKey(normalizedCode),
    message: message ?? '',
  };
}

function dedupeKey(error: UplFieldError): string {
  return `${error.sheet}|${error.column}|${error.field}|${error.code}`;
}

/** The field errors from `errors[]` of problem+json, in address form. */
export function parseUplFieldErrors(errors: FieldErrorItem[] | null | undefined): UplFieldError[] {
  if (!Array.isArray(errors)) {
    return [];
  }
  return errors.map((item) => toFieldError(item.field ?? '', item.code ?? '', item.message ?? ''));
}

/** All address errors of a response: `errors[]` plus `invalid_params`, without "address + code" duplicates. */
export function parseUplProblem(problem: ProblemDetail): UplFieldError[] {
  const fromErrors = parseUplFieldErrors(problem?.errors);
  const fromParams = Array.isArray(problem?.invalid_params)
    ? problem.invalid_params!.map((param) => toFieldError(param.name ?? '', param.code ?? '', param.reason ?? ''))
    : [];

  const seen = new Set<string>();
  const result: UplFieldError[] = [];
  for (const error of [...fromErrors, ...fromParams]) {
    const key = dedupeKey(error);
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    result.push(error);
  }
  return result;
}

/**
 * The text of a field error: the dictionary key, and if there is none, the server message and the code in brackets
 * (a raw key never reaches the UI); without a server message, only the code.
 */
export function uplFieldErrorText(error: UplFieldError, translate: (key: string) => string): string {
  const translated = translate(error.key);
  if (translated !== error.key) {
    return translated;
  }
  return error.message ? `${error.message} (${error.code})` : error.code;
}

/** The error of one column cell. */
export function uplCellError(
  list: UplFieldError[],
  sheet: number,
  column: number,
  field: string,
): UplFieldError | null {
  return list.find((error) => error.sheet === sheet && error.column === column && error.field === field) ?? null;
}

/** An error of a field of the sheet itself (not of a column). */
export function uplSheetError(list: UplFieldError[], sheet: number, field: string): UplFieldError | null {
  return list.find((error) => error.sheet === sheet && error.column === null && error.field === field) ?? null;
}

/** Whether the sheet has at least one error, its own or in a column. */
export function uplSheetHasErrors(list: UplFieldError[], sheet: number): boolean {
  return list.some((error) => error.sheet === sheet);
}

const TARGET_FIELD_PATTERN = /^[a-z][a-z0-9_]{0,62}$/;

/**
 * Checks made before sending: what the server would refuse with Bean
 * Validation codes. The codes are the same, so the texts come from the same dictionary.
 */
export function localFormatErrors(model: UplFormatDraftRequest): UplFieldError[] {
  const found: FieldErrorItem[] = [];
  model.sheets.forEach((sheet, s) => {
    const row = Number(sheet.headerRow);
    if (sheet.headerRow === null || !Number.isInteger(row) || row < 1) {
      found.push({ field: `sheets[${s}].headerRow`, code: 'Min', message: '' });
    }
    sheet.columns.forEach((column, c) => {
      if ((column.nameInFile ?? '').trim().length === 0) {
        found.push({ field: `sheets[${s}].columns[${c}].nameInFile`, code: 'NotBlank', message: '' });
      }
      const target = (column.targetField ?? '').trim();
      if (target.length === 0) {
        found.push({ field: `sheets[${s}].columns[${c}].targetField`, code: 'NotBlank', message: '' });
      } else if (!TARGET_FIELD_PATTERN.test(target)) {
        found.push({ field: `sheets[${s}].columns[${c}].targetField`, code: 'Pattern', message: '' });
      }
    });
  });
  return parseUplFieldErrors(found);
}

/** Field of a column error (the tail after `columns[n].`) → key of that column's table header (M-21). */
const COLUMN_FIELD_LABEL_KEY: Record<string, string> = {
  nameInFile: 'upl.format.col.name_in_file',
  targetField: 'upl.format.col.target_field',
  dataType: 'upl.format.col.type',
  required: 'upl.format.col.required',
  sourceUnit: 'upl.format.col.source_unit',
  baseUnit: 'upl.format.col.base_unit',
  keyMask: 'upl.format.col.key_mask',
  keyPadLength: 'upl.format.col.key_pad_length',
  keyPadMax: 'upl.format.col.key_pad_max',
  refBookCode: 'upl.format.col.ref_book',
  filePosition: 'upl.format.col.file_position',
};

/** Where an error is, for the summary: with the field's name when the table header knows it (M-21). */
export function uplErrorAddress(
  problem: UplFieldError,
  translate: (key: string, params?: Record<string, string>) => string,
): string {
  if (problem.sheet === null) return '';
  const sheet = (problem.sheet + 1).toString();
  if (problem.column === null) return translate('upl.format.err_at_sheet', { sheet });
  const column = (problem.column + 1).toString();
  const labelKey = COLUMN_FIELD_LABEL_KEY[problem.field];
  return labelKey
    ? translate('upl.format.err_at_field', { sheet, column, field: translate(labelKey) })
    : translate('upl.format.err_at_column', { sheet, column });
}
