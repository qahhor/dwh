import { ProblemDetail } from '@core/models/common.models';
import {
  UplDataType,
  UplEncoding,
  UplFileKind,
  UplMatchBy,
  UplPeriodicity,
  UplStrictness,
  UplVersionStatus,
} from './upl-api';

/** A map from a contract value to an i18n key: the labels live in the dictionaries, not in code. */
export const UPL_PERIODICITY_KEY: Record<UplPeriodicity, string> = {
  month: 'upl.periodicity.month',
  quarter: 'upl.periodicity.quarter',
  year: 'upl.periodicity.year',
  adhoc: 'upl.periodicity.adhoc',
};

export const UPL_STRICTNESS_KEY: Record<UplStrictness, string> = {
  error: 'upl.strictness.error',
  warning: 'upl.strictness.warning',
};

export const UPL_VERSION_STATUS_KEY: Record<UplVersionStatus, string> = {
  draft: 'upl.version.status.draft',
  published: 'upl.version.status.published',
  superseded: 'upl.version.status.superseded',
};

export const UPL_DATA_TYPE_KEY: Record<UplDataType, string> = {
  text: 'upl.format.type.text',
  integer: 'upl.format.type.integer',
  number: 'upl.format.type.number',
  date: 'upl.format.type.date',
  object_key: 'upl.format.type.object_key',
  ref_code: 'upl.format.type.ref_code',
};

export const UPL_FILE_KIND_KEY: Record<UplFileKind, string> = {
  xlsx: 'upl.format.file_kind.xlsx',
  csv: 'upl.format.file_kind.csv',
};

export const UPL_ENCODING_KEY: Record<UplEncoding, string> = {
  'utf-8': 'upl.format.encoding.utf_8',
  'windows-1251': 'upl.format.encoding.windows_1251',
};

export const UPL_MATCH_BY_KEY: Record<UplMatchBy, string> = {
  header: 'upl.format.match_by.header',
  position: 'upl.format.match_by.position',
};

/**
 * A contract error code (`UPL_*`, `STALE_VERSION`, `FND_VERSION_*`, `VALIDATION_FAILED`, `PERMISSION_DENIED`) to a
 * dictionary key.
 */
export function uplErrorKey(code: string): string {
  return 'upl.err.' + code;
}

/**
 * Text keys of UPL errors the screens handle specially (plan 10/10, item 3.1: an error carries the key of its text
 * in `messageKey`, and codes remain only on fields in `errors[].code`).
 */
export const UPL_ERROR = {
  staleVersion: 'error.upl.stale_version',
  formatNotDraft: 'error.upl.format_not_draft',
  draftExists: 'error.upl.fnd_version_draft_exists',
  notAfterPrevious: 'error.upl.fnd_version_not_after_previous',
  sourceNotFound: 'error.upl.source_not_found',
  sourceCodeTaken: 'error.upl.source_code_taken',
  packageNotFound: 'error.upl.pkg_not_found',
  noFormatAtDate: 'error.upl.pkg_no_format_at_date',
} as const;

/** Translation with parameters: `I18nService.translate` or its stub in tests. */
export type UplTranslateFn = (key: string, params?: Record<string, string | number>) => string;

/**
 * The text of a server error: by the `messageKey` key from the dictionary (the language may have changed after the
 * request); without a key or a translation, the subcode (`detail`) to the key `upl.err.*`; an unknown code is not
 * hidden: `detail (code)`.
 */
export function uplProblemText(problem: ProblemDetail | null | undefined, translate: UplTranslateFn): string {
  const messageKey = problem?.messageKey;
  if (messageKey) {
    const text = translate(messageKey, problem?.params);
    if (text !== messageKey) {
      return text;
    }
  }
  const detail = problem?.detail ?? '';
  const key = uplErrorKey(detail);
  const text = translate(key);
  return text === key ? `${detail} (${problem?.code ?? ''})` : text;
}
