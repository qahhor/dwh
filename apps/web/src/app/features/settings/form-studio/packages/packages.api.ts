import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import type { ApiSchema } from '@core/api/api-schema';
import type { KeysetPage } from '@core/models/common.models';
import { ApiService } from '@core/services/api.service';

/** A template the product ships (ADR-0047). */
export type SolutionTemplate = ApiSchema<'SolutionTemplate'>;
/** An uploaded package or a template being checked or applied. */
export type SolutionImport = ApiSchema<'SolutionImport'>;
export type SolutionImportItem = ApiSchema<'SolutionImportItem'>;
export type SolutionImportSummary = ApiSchema<'SolutionImportSummary'>;
export type SolutionPlaceholder = ApiSchema<'SolutionPackagePlaceholder'>;
export type SolutionExportRequest = ApiSchema<'SolutionExportRequest'>;
export type SolutionExportPlan = ApiSchema<'SolutionExportPlan'>;
export type SolutionExportItem = ApiSchema<'SolutionExportItem'>;
export type SolutionLabels = ApiSchema<'SimpleFormLabels'>;

/** A live form as the Studio list names it. */
export interface PackageFormSummary {
  entityCode: string;
  title: SolutionLabels;
  status: string;
}

/** A role a role placeholder may be mapped to. */
export interface PackageRole {
  id: number;
  name: string;
  state: string;
}

/** The mapping of placeholders: a role placeholder takes one role id, a placeholder of users user ids. */
export type PackageMapping = Record<string, number[]>;

const QUIET = { notifyError: false };

/** The suffix of a second copy as a query parameter, or nothing. */
function withSuffix(path: string, suffix: string | null | undefined): string {
  const value = suffix?.trim();
  return value ? `${path}?suffix=${encodeURIComponent(value)}` : path;
}

/**
 * Solution packages and templates (ADR-0047): `/studio/templates` — the gallery and the creation of a solution from a
 * template; `/studio/packages` — the plan and the download of a package of chosen forms, the upload and check of a
 * package, its application with the mapping of its placeholders and the history of imports.
 */
@Injectable({ providedIn: 'root' })
export class PackagesApi {
  private readonly api = inject(ApiService);

  templates(): Observable<SolutionTemplate[]> {
    return this.api.get<SolutionTemplate[]>('/studio/templates', undefined, QUIET);
  }

  fromTemplate(code: string, suffix?: string | null): Observable<SolutionImport> {
    return this.api.post<SolutionImport>(withSuffix(`/studio/templates/${code}/imports`, suffix), {}, QUIET);
  }

  /** Uploads the package as it was read from its file; the server checks it and keeps the import. */
  upload(pack: unknown, suffix?: string | null): Observable<SolutionImport> {
    return this.api.post<SolutionImport>(withSuffix('/studio/packages/imports', suffix), pack, QUIET);
  }

  imports(cursor?: string | null): Observable<KeysetPage<SolutionImportSummary>> {
    return this.api.get<KeysetPage<SolutionImportSummary>>(
      '/studio/packages/imports',
      { limit: 20, ...(cursor ? { cursor } : {}) },
      QUIET,
    );
  }

  get(id: number): Observable<SolutionImport> {
    return this.api.get<SolutionImport>(`/studio/packages/imports/${id}`, undefined, QUIET);
  }

  apply(id: number, mapping: PackageMapping, dryRun: boolean): Observable<SolutionImport> {
    return this.api.post<SolutionImport>(`/studio/packages/imports/${id}/apply`, { mapping, dryRun }, QUIET);
  }

  plan(request: SolutionExportRequest): Observable<SolutionExportPlan> {
    return this.api.post<SolutionExportPlan>('/studio/packages/export/preview', request, QUIET);
  }

  /** The package of the selection, as the server sealed it. */
  export(request: SolutionExportRequest): Observable<unknown> {
    return this.api.post<unknown>('/studio/packages/export', request, QUIET);
  }

  forms(): Observable<KeysetPage<PackageFormSummary>> {
    return this.api.get<KeysetPage<PackageFormSummary>>('/studio/forms', { limit: 100 }, QUIET);
  }

  roles(): Observable<PackageRole[]> {
    return this.api.get<PackageRole[]>('/iam/roles', undefined, QUIET);
  }
}
