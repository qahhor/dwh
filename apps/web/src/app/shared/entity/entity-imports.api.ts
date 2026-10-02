import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import type { ApiSchema } from '@core/api/api-schema';
import { ApiService } from '@core/services/api.service';

/** An import as its owner follows it (ADR-0032 10.1): state, counters, first problems by row, the report. */
export type EntityImport = ApiSchema<'ImportView'>;

/** A problem of a row of an import: `rows[17].qty`, or `rows[17]` for the whole row. */
export type EntityImportError = ApiSchema<'ImportRowError'>;

/** What an import does: `dry_run` checks every row and writes nothing, `apply` upserts by the entity's import key. */
export type EntityImportMode = 'dry_run' | 'apply';

/**
 * The import of an entity's records from an xlsx file (ADR-0032 10.1): the template, the start of an import of a file
 * uploaded to the files module, the import's journal row and its report. The server checks the rights and the scope
 * of every row, so the screen only shows what it answers.
 */
@Injectable({ providedIn: 'root' })
export class EntityImportsApi {
  private readonly api = inject(ApiService);

  /** Where the template of the entity is downloaded, in the viewer's language. */
  templateUrl(code: string, lang: string): string {
    return `/api/v1/entities/${encodeURIComponent(code)}/import-template?lang=${encodeURIComponent(lang)}`;
  }

  /** Queues an import of the uploaded file; a 409 names the imports still running, a 422 the bad request. */
  start(code: string, fileId: string, mode: EntityImportMode, lang: string): Observable<EntityImport> {
    return this.api.post<EntityImport>(
      `/entities/${encodeURIComponent(code)}/imports`,
      { fileId, mode, lang },
      { notifyError: false },
    );
  }

  /** The import as it is now. */
  get(id: string): Observable<EntityImport> {
    return this.api.get<EntityImport>(`/imports/${encodeURIComponent(id)}`, undefined, { notifyError: false });
  }

  /** Where the report of a finished import is downloaded: the file with a column of problems. */
  reportUrl(id: string): string {
    return `/api/v1/imports/${encodeURIComponent(id)}/report`;
  }
}

/** Whether the import's job is still to finish. */
export function importRunning(item: EntityImport | null): boolean {
  return item?.state === 'queued' || item?.state === 'running';
}
