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

/** Справочник «значение контракта → ключ i18n»: подписи живут в словарях, не в коде. */
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

/** Код ошибки контракта (`UPL_*`, `STALE_VERSION`, `FND_VERSION_*`, `VALIDATION_FAILED`, `PERMISSION_DENIED`) → ключ словаря. */
export function uplErrorKey(code: string): string {
  return 'upl.err.' + code;
}

/**
 * Ключи текстов ошибок UPL, на которые экраны отвечают особо (план 10/10, п. 3.1: ошибка несёт ключ своего текста
 * в `messageKey`, а коды остаются только у полей в `errors[].code`).
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

/** Перевод с параметрами: `I18nService.translate` либо его заглушка в тестах. */
export type UplTranslateFn = (key: string, params?: Record<string, string | number>) => string;

/**
 * Текст ошибки сервера: по ключу `messageKey` из словаря (язык мог смениться после запроса); без ключа или без
 * перевода — подкод (`detail`) → ключ `upl.err.*`; неизвестный код не прячем — `detail (code)`.
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
