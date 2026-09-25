import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../../core/services/api.service';
import { ListQuery } from '../../../core/models/query-meta.models';
import { toQueryParams } from '../../../core/services/query-meta.service';
import { KeysetPage } from '../../../core/models/common.models';
import { UplApiService, UplSource, UplSourceItem } from '../upl-api';

export type UplPackageStatus = 'received' | 'verified' | 'rejected' | 'applied';

/** Параметры русского текста ошибки: подставляются в фигурные скобки ключа `upl.err.*`. */
export type UplPackageParams = Record<string, string | number>;

export interface UplPackageItem {
  id: string;
  sourceId: number;
  sourceCode: string;
  sourceName: string;
  formatVersion: number;
  periodFrom: string;
  periodTo: string;
  fileName: string;
  fileSizeBytes: number;
  /** Absent when the viewer may not see who uploaded (a field right on the server, ADR-0016 2.9). */
  uploadedBy?: string;
  uploadedAt: string;
  status: UplPackageStatus;
  rowsTotal: number | null;
  rowsAccepted: number | null;
  rowsRejected: number | null;
  errorsTotal: number | null;
  rejectCode: string | null;
  rejectParams: UplPackageParams | null;
  loadId: number | null;
  rawRows: number | null;
}

export interface UplPackageErrorItem {
  sheet: string | null;
  rowNo: number | null;
  columnName: string | null;
  value: string | null;
  code: string;
  params: UplPackageParams | null;
}

export interface UplPackageErrors {
  total: number;
  shown: number;
  items: UplPackageErrorItem[];
}

export interface UplPackageUpload {
  sourceId: number;
  periodFrom: string;
  periodTo: string;
  file: File;
}

const PACKAGES = '/upl/packages';
/** Источники для формы читаются порциями: список И3 отдаёт не больше 200 записей за раз. */

@Injectable({ providedIn: 'root' })
export class UplPackagesApiService {
  private readonly api = inject(ApiService);
  private readonly upl = inject(UplApiService);

  /** Страница загрузок; `query` — фильтр, сортировка и поиск реестра полей (`query-meta/upl.packages`). */
  list(limit = 50, cursor?: string | null, query?: ListQuery | null): Observable<KeysetPage<UplPackageItem>> {
    const params = { limit, ...(cursor ? { cursor } : {}), ...toQueryParams(query) };
    return this.api.get<KeysetPage<UplPackageItem>>(PACKAGES, params, { notifyError: false });
  }

  /** `multipart/form-data`: заголовок ставит браузер сам — руками его не задаём, иначе теряется граница частей. */
  upload(request: UplPackageUpload): Observable<UplPackageItem> {
    const form = new FormData();
    form.append('sourceId', String(request.sourceId));
    form.append('periodFrom', request.periodFrom);
    form.append('periodTo', request.periodTo);
    form.append('file', request.file, request.file.name);
    return this.api.post<UplPackageItem>(PACKAGES, form, { notifyError: false });
  }

  /** One upload by its id, for a card opened from a link. */
  get(id: string): Observable<UplPackageItem> {
    return this.api.get<UplPackageItem>(`${PACKAGES}/${encodeURIComponent(id)}`, undefined, { notifyError: false });
  }

  errors(id: string): Observable<UplPackageErrors> {
    return this.api.get<UplPackageErrors>(`${PACKAGES}/${encodeURIComponent(id)}/errors`, undefined, { notifyError: false });
  }

  /** Применяет проверенную загрузку: ответ — пакет «применён» или «отклонён системой» с причиной сверки. */
  apply(id: string): Observable<UplPackageItem> {
    return this.api.post<UplPackageItem>(`${PACKAGES}/${id}/apply`, null, { notifyError: false });
  }

  /** Страница источников для поиска в форме: подстрока в коде или названии (`q`). */
  searchSources(query: string, cursor: string | null, pageSize: number): Observable<KeysetPage<UplSourceItem>> {
    return this.upl.listSources(pageSize, cursor, { search: query });
  }

  /** Один источник — чтобы показать выбранным тот, что создан из формы. */
  source(id: string): Observable<UplSource> {
    return this.upl.getSource(id);
  }
}
