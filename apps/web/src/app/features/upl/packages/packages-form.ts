import { UplPackageFormErrors, UplTranslate } from './packages-errors';

export interface PackageUploadForm {
  sourceId: number | null;
  periodFrom: string;
  periodTo: string;
  file: File | null;
}

export function emptyForm(): PackageUploadForm {
  return { sourceId: null, periodFrom: '', periodTo: '', file: null };
}

export function emptyFormErrors(): UplPackageFormErrors {
  return { source: [], period: [], file: [], form: [] };
}

export function clearUploadFieldErrors(
  errors: UplPackageFormErrors,
  patch: Partial<PackageUploadForm>,
): UplPackageFormErrors {
  return {
    ...errors,
    ...('sourceId' in patch ? { source: [] } : {}),
    ...('periodFrom' in patch || 'periodTo' in patch ? { period: [] } : {}),
    ...('file' in patch ? { file: [] } : {}),
  };
}

export function touchUploadField(
  form: PackageUploadForm,
  errors: UplPackageFormErrors,
  field: 'sourceId' | 'periodFrom' | 'periodTo',
  translate: UplTranslate,
): UplPackageFormErrors {
  const place = field === 'sourceId' ? 'source' : 'period';
  const code = !form[field]
    ? field === 'sourceId'
      ? 'UPL_PKG_SOURCE_REQUIRED'
      : 'UPL_PKG_PERIOD_REQUIRED'
    : field !== 'sourceId' && form.periodFrom && form.periodTo && form.periodFrom > form.periodTo
      ? 'UPL_PKG_PERIOD_ORDER'
      : null;
  return code ? { ...errors, [place]: [translate('upl.err.' + code)] } : errors;
}
