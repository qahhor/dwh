import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import type { ApiSchema } from '@core/api/api-schema';
import type { FormMeta } from '@core/models/form-meta.models';
import type { EntityMenuItem } from '@core/models/navigation.models';
import type { QueryListMeta } from '@core/models/query-meta.models';
import { ApiService } from '@core/services/api.service';

/** A dataset as the server answers it (ADR-0044). */
export type ReportDataset = Required<Omit<ApiSchema<'ReportDataset'>, 'lines'>> & { lines?: string | null };

/** A measure a dataset computes by a formula over aggregates. */
export type ReportDatasetMeasure = ApiSchema<'ReportDatasetMeasure'>;

/** What a create or a change sends. */
export interface ReportDatasetBody {
  code?: string;
  name: { ru: string; uz: string; en: string };
  entity: string;
  lines: string | null;
  fields: string[];
  filter: unknown[];
  measures: ReportDatasetMeasure[];
}

/** A source an administrator may choose: an entity of the menu, and the tables of its lines. */
export interface DatasetSource {
  code: string;
  labelKey: string;
}

/**
 * The datasets administrators define for reports (ADR-0044): `/report-datasets` — the page, one dataset, a create, a
 * change and a delete at their revision — the fields a dataset over an entity may offer, and the entities and tables of
 * lines to choose from.
 */
@Injectable({ providedIn: 'root' })
export class ReportDatasetsApi {
  private readonly api = inject(ApiService);

  list(): Observable<ReportDataset[]> {
    return this.api
      .get<{ items?: ReportDataset[] }>('/report-datasets', { limit: 100 }, { notifyError: false })
      .pipe(map((page) => page.items ?? []));
  }

  create(body: ReportDatasetBody): Observable<ReportDataset> {
    return this.api.post<ReportDataset>('/report-datasets', body, { notifyError: false });
  }

  update(code: string, revision: number, body: ReportDatasetBody): Observable<ReportDataset> {
    return this.api.put<ReportDataset>(`/report-datasets/${encodeURIComponent(code)}`, body, {
      notifyError: false,
      ifMatch: revision,
    });
  }

  remove(code: string, revision: number): Observable<void> {
    return this.api.delete<void>(`/report-datasets/${encodeURIComponent(code)}`, {
      notifyError: false,
      ifMatch: revision,
    });
  }

  /** The fields a dataset over the entity may offer, with lines the fields of a line too. */
  fields(entity: string, lines: string | null): Observable<QueryListMeta> {
    return this.api.get<QueryListMeta>(
      '/report-datasets/fields',
      { entity, ...(lines ? { lines } : {}) },
      { notifyError: false },
    );
  }

  /** The entities of the menu the administrator sees. */
  entities(): Observable<DatasetSource[]> {
    return this.api
      .get<EntityMenuItem[]>('/entities/menu', undefined, { notifyError: false })
      .pipe(map((items) => items.map((item) => ({ code: item.code, labelKey: item.labelKey }))));
  }

  /** The tables of document lines of an entity. */
  lines(entity: string): Observable<DatasetSource[]> {
    return this.api
      .get<FormMeta>(`/form-meta/${encodeURIComponent(entity)}`, undefined, { notifyError: false })
      .pipe(map((meta) => (meta.collections ?? []).map((table) => ({ code: table.key, labelKey: table.labelKey }))));
  }
}
