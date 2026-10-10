import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import { FieldErrorItem, ProblemDetail } from '@core/models/common.models';
import { UplPackageParams } from './packages.api';
import {
  UPL_PACKAGE_CODES,
  UplTranslate,
  hasUplFormErrors,
  mapUplUploadProblem,
  uplPackageCodeText,
} from './packages-errors';

/** The server's Russian catalog: texts of error keys (`error.upl.*`) the packaged dictionary may not have yet. */
const SERVER_RUSSIAN = JSON.parse(readFileSync('../server/src/main/resources/i18n/ru.json', 'utf8')) as Record<
  string,
  string
>;
const DICTIONARY: Record<string, string> = { ...PACKAGED_RUSSIAN, ...SERVER_RUSSIAN };

/** The real dictionary plus parameter substitution, as in `I18nService.translate`. */
const translate: UplTranslate = (key: string, params?: UplPackageParams) => {
  const template = DICTIONARY[key];
  if (template === undefined) return key;
  if (!params) return template;
  return template.replace(/\{([a-zA-Z0-9_]+)\}/g, (placeholder: string, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : placeholder,
  );
};

function text(code: string): string {
  return PACKAGED_RUSSIAN[`upl.err.${code}`];
}

function problem(status: number, code: string, detail: string, errors?: FieldErrorItem[]): ProblemDetail {
  return { title: 'TEST', status, code, detail, ...(errors ? { errors } : {}) };
}

function field(name: string, code: string, message = 'TEST message'): FieldErrorItem {
  return { field: name, code, message };
}

describe('upl package error codes', () => {
  it('has a Russian text for every code of the contract', () => {
    expect(UPL_PACKAGE_CODES.filter((code) => !(`upl.err.${code}` in PACKAGED_RUSSIAN))).toEqual([]);
  });

  it('lists exactly the twenty six codes of the contract', () => {
    expect(UPL_PACKAGE_CODES).toHaveLength(26);
    expect(new Set(UPL_PACKAGE_CODES).size).toBe(26);
  });

  it('puts the parameters of the code into its text', () => {
    expect(uplPackageCodeText('UPL_PKG_STRUCTURE', { count: 2 }, translate)).toBe(
      text('UPL_PKG_STRUCTURE').replace('{count}', '2'),
    );
    expect(uplPackageCodeText('UPL_PKG_STRUCTURE', { count: 2 }, translate)).toContain('2');
    expect(uplPackageCodeText('UPL_PKG_RECONCILIATION', { fileRows: 10, rawRows: 9 }, translate)).toBe(
      'Сверка не сошлась: строк в файле — 10, в базе — 9. Загрузите файл заново',
    );
  });

  it('shows the code itself when the dictionary has no text', () => {
    expect(uplPackageCodeText('UPL_NO_SUCH_CODE', null, translate)).toBe('UPL_NO_SUCH_CODE');
  });

  it('reads a code without parameters', () => {
    expect(uplPackageCodeText('UPL_CELL_REQUIRED', undefined, translate)).toBe(text('UPL_CELL_REQUIRED'));
  });
});

describe('mapUplUploadProblem', () => {
  it('puts a refusal by size of our check under the file', () => {
    const errors = mapUplUploadProblem(problem(413, 'file_size_exceeded', 'UPL_PKG_FILE_TOO_LARGE'), translate);
    expect(errors.file).toEqual([text('UPL_PKG_FILE_TOO_LARGE')]);
    expect(errors.form).toEqual([]);
  });

  it('puts a refusal by size of the platform under the file too', () => {
    const errors = mapUplUploadProblem(problem(413, 'FILE_SIZE_EXCEEDED', 'PAYLOAD_TOO_LARGE'), translate);
    expect(errors.file).toEqual([text('UPL_PKG_FILE_TOO_LARGE')]);
    expect(errors.source).toEqual([]);
  });

  it('spreads a validation refusal over three places at once', () => {
    const errors = mapUplUploadProblem(
      problem(422, 'validation_failed', 'UPL_PACKAGE_INVALID', [
        field('sourceId', 'UPL_PKG_SOURCE_REQUIRED'),
        field('periodFrom', 'UPL_PKG_PERIOD_ORDER'),
        field('file', 'UPL_PKG_FILE_NOT_XLSX'),
      ]),
      translate,
    );
    expect(errors.source).toEqual([text('UPL_PKG_SOURCE_REQUIRED')]);
    expect(errors.period).toEqual([text('UPL_PKG_PERIOD_ORDER')]);
    expect(errors.file).toEqual([text('UPL_PKG_FILE_NOT_XLSX')]);
    expect(errors.form).toEqual([]);
  });

  it('does not repeat the same text of two fields of one place', () => {
    const errors = mapUplUploadProblem(
      problem(422, 'validation_failed', 'UPL_PACKAGE_INVALID', [
        field('periodFrom', 'UPL_PKG_PERIOD_REQUIRED'),
        field('periodTo', 'UPL_PKG_PERIOD_REQUIRED'),
      ]),
      translate,
    );
    expect(errors.period).toEqual([text('UPL_PKG_PERIOD_REQUIRED')]);
  });

  it('shows a message and a code of a field error the dictionary does not know', () => {
    const errors = mapUplUploadProblem(
      problem(422, 'validation_failed', 'UPL_PACKAGE_INVALID', [field('sourceId', 'X_CODE', 'TEST text')]),
      translate,
    );
    expect(errors.source).toEqual(['TEST text (X_CODE)']);
  });

  it('puts an unknown field of a validation refusal over the form', () => {
    const errors = mapUplUploadProblem(
      problem(422, 'validation_failed', 'UPL_PACKAGE_INVALID', [field('other', 'UPL_PKG_INTERNAL')]),
      translate,
    );
    expect(errors.form).toEqual([text('UPL_PKG_INTERNAL')]);
  });

  it('puts a missing source and a missing format under the source, by the key of the error', () => {
    const keyed = (status: number, code: string, messageKey: string): ProblemDetail => ({
      ...problem(status, code, 'TEST detail'),
      messageKey,
    });
    const notFound = mapUplUploadProblem(keyed(404, 'not_found', 'error.upl.source_not_found'), translate);
    const noFormat = mapUplUploadProblem(keyed(409, 'conflict', 'error.upl.pkg_no_format_at_date'), translate);
    expect(notFound.source).toEqual([DICTIONARY['error.upl.source_not_found']]);
    expect(noFormat.source).toEqual([DICTIONARY['error.upl.pkg_no_format_at_date']]);
    expect(notFound.form).toEqual([]);
  });

  it('puts a refusal named by another key over the form with its text', () => {
    const errors = mapUplUploadProblem(
      { ...problem(409, 'conflict', 'TEST detail'), messageKey: 'error.upl.pkg_not_verified' },
      translate,
    );
    expect(errors.source).toEqual([]);
    expect(errors.form).toEqual([DICTIONARY['error.upl.pkg_not_verified']]);
  });

  it('puts an unknown refusal over the form with its code', () => {
    const errors = mapUplUploadProblem(problem(500, 'INTERNAL_ERROR', 'X_DETAIL'), translate);
    expect(errors.form).toEqual(['X_DETAIL (INTERNAL_ERROR)']);
    expect(errors.file).toEqual([]);
  });

  it('gives empty places without a refusal at hand', () => {
    const errors = mapUplUploadProblem(problem(422, 'validation_failed', 'UPL_PACKAGE_INVALID', []), translate);
    expect(errors.form).toHaveLength(1);
    expect(hasUplFormErrors(errors)).toBe(true);
    expect(hasUplFormErrors({ source: [], period: [], file: [], form: [] })).toBe(false);
  });
});
