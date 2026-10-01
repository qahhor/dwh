import { ProblemDetail } from '@core/models/common.models';
import { UPL_ERROR, UplTranslateFn, uplErrorKey, uplProblemText } from '../upl-labels';
import { UplPackageParams } from './packages-api';

/** All load error codes from the contract: each has the key `upl.err.<CODE>` in the dictionary. */
export const UPL_PACKAGE_CODES = [
  'UPL_PKG_SOURCE_REQUIRED',
  'UPL_PKG_PERIOD_REQUIRED',
  'UPL_PKG_PERIOD_ORDER',
  'UPL_PKG_FILE_REQUIRED',
  'UPL_PKG_FILE_EMPTY',
  'UPL_PKG_FILE_NOT_XLSX',
  'UPL_PKG_NO_FORMAT_AT_DATE',
  'UPL_PKG_FILE_TOO_LARGE',
  'UPL_PKG_NOT_FOUND',
  'UPL_PKG_STRUCTURE',
  'UPL_PKG_UNREADABLE',
  'UPL_PKG_TOO_MANY_CELLS',
  'UPL_PKG_INTERNAL',
  'UPL_PKG_NOT_VERIFIED',
  'UPL_PKG_NOTHING_TO_APPLY',
  'UPL_PKG_RECONCILIATION',
  'UPL_PKG_RAW_WRITE_FAILED',
  'UPL_PKG_APPLY_INTERRUPTED',
  'UPL_STRUCT_SHEET_MISSING',
  'UPL_STRUCT_COLUMN_MISSING',
  'UPL_STRUCT_COLUMN_UNKNOWN',
  'UPL_CELL_REQUIRED',
  'UPL_CELL_NOT_INTEGER',
  'UPL_CELL_NOT_NUMBER',
  'UPL_CELL_NOT_DATE',
  'UPL_CELL_KEY_MASK',
] as const;

/** Translation with parameters: `I18nService.translate` or its stub in tests. */
export type UplTranslate = UplTranslateFn;

/** The Russian text of an error code; without a translation the code itself is shown, not an empty string. */
export function uplPackageCodeText(
  code: string,
  params: UplPackageParams | null | undefined,
  translate: UplTranslate,
): string {
  const key = uplErrorKey(code);
  const text = translate(key, params ?? undefined);
  return text === key ? code : text;
}

/** The places of the "New load" form where server refusals land. */
export interface UplPackageFormErrors {
  source: string[];
  period: string[];
  file: string[];
  form: string[];
}

type UplFormPlace = keyof UplPackageFormErrors;

/** A server response field maps to a form place; an unknown field goes to the banner above the form. */
const FIELD_PLACE: Record<string, UplFormPlace> = {
  sourceId: 'source',
  periodFrom: 'period',
  periodTo: 'period',
  file: 'file',
};

/** Size refusals come with this code both from our check and from the framework. */
const FILE_SIZE_CODE = 'file_size_exceeded';

/** Error keys that relate to the selected source. */
const SOURCE_KEYS: readonly string[] = [UPL_ERROR.sourceNotFound, UPL_ERROR.noFormatAtDate];

function emptyErrors(): UplPackageFormErrors {
  return { source: [], period: [], file: [], form: [] };
}

function add(errors: UplPackageFormErrors, place: UplFormPlace, text: string): void {
  if (!errors[place].includes(text)) {
    errors[place].push(text);
  }
}

function fieldText(code: string, message: string, translate: UplTranslate): string {
  const key = uplErrorKey(code);
  const text = translate(key);
  return text === key ? `${message} (${code})` : text;
}

/** A server refusal maps to texts per form place; an unknown code is not hidden, it goes to the banner above. */
export function mapUplUploadProblem(
  problem: ProblemDetail | null | undefined,
  translate: UplTranslate,
): UplPackageFormErrors {
  const errors = emptyErrors();
  if (problem && (problem.code ?? '').toLowerCase() === FILE_SIZE_CODE) {
    add(errors, 'file', translate(uplErrorKey('UPL_PKG_FILE_TOO_LARGE')));
    return errors;
  }
  const fields = problem?.status === 422 ? (problem.errors ?? []) : [];
  if (fields.length > 0) {
    for (const field of fields) {
      add(errors, FIELD_PLACE[field.field] ?? 'form', fieldText(field.code, field.message, translate));
    }
    return errors;
  }
  if (SOURCE_KEYS.includes(problem?.messageKey ?? '')) {
    add(errors, 'source', uplProblemText(problem, translate));
    return errors;
  }
  add(errors, 'form', uplProblemText(problem, translate));
  return errors;
}

export function hasUplFormErrors(errors: UplPackageFormErrors): boolean {
  return errors.source.length > 0 || errors.period.length > 0 || errors.file.length > 0 || errors.form.length > 0;
}
