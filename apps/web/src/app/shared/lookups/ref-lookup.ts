import { Observable, catchError, forkJoin, map, of, shareReplay } from 'rxjs';
import type { SMTLookupKey, SMTLookupSource } from '../ui-kit/components/forms/data-select/lookup-source';
import type { QueryRefMeta } from '@core/models/query-meta.models';
import type { KeysetPage } from '@core/models/common.models';
import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from '@core/services/api.service';
import { LookupSources } from './lookup-sources';

type Row = Record<string, unknown>;

/**
 * The rows a reference field is picked from (ADR-0019 2.4, roadmap item 53): the endpoint the server names
 * in `query-meta`, whose own rights and data scope decide what is offered. A paged endpoint is searched on
 * the server; a whole (short) list is loaded once and searched here. Failures stay inside the field.
 */
export function refLookup(api: ApiService, ref: QueryRefMeta): SMTLookupSource<Row, SMTLookupKey> {
  const quiet = { notifyError: false };
  const key = (row: Row) => row[ref.keyField] as SMTLookupKey;
  const option = (row: Row) => ({ label: String(row[ref.labelField] ?? row[ref.keyField] ?? '') });
  if (ref.paged) {
    return {
      page: (search, cursor, limit) =>
        api.get<KeysetPage<Row>>(
          ref.path,
          {
            limit,
            cursor: cursor ?? undefined,
            q: search || undefined,
          },
          quiet,
        ),
      key,
      option,
      // A chosen key is named by its own read, as the reference lists do (lookup-sources.ts); a list paged
      // under `/page` names where that read lives.
      resolve: (keys) =>
        forkJoin(
          keys.map((one) =>
            api
              .get<Row>(`${ref.readPath ?? ref.path}/${encodeURIComponent(String(one))}`, undefined, quiet)
              .pipe(catchError(() => of(null))),
          ),
        ).pipe(map((found) => found.filter((row): row is Row => row != null))),
    };
  }
  let whole: Observable<Row[]> | null = null;
  const rows = () =>
    (whole ??= api.get<Row[] | KeysetPage<Row>>(ref.path, undefined, quiet).pipe(
      map((body) => (Array.isArray(body) ? body : (body?.items ?? []))),
      shareReplay({ bufferSize: 1, refCount: false }),
    ));
  return {
    page: (search) =>
      rows().pipe(
        map((items) => {
          const text = search.trim().toLowerCase();
          const found = text ? items.filter((row) => option(row).label.toLowerCase().includes(text)) : items;
          return { items: found, nextCursor: null, hasMore: false };
        }),
      ),
    key,
    option,
    resolve: (keys) =>
      rows().pipe(
        map((items) => items.filter((row) => keys.includes(key(row)))),
        catchError(() => of([])),
      ),
  };
}

/**
 * Reference lookups by their target (plan 10/10, item 5.0): a component asks for a field's source without holding
 * ApiService. A target the app knows keeps its own look — a person as "Name" with "@login" (active people only),
 * a task as "#12 Title", a project by its name — and any other list is read as the server names it. The same
 * source names a chosen row everywhere: in the form, the card, a list cell and the history.
 */
@Injectable({ providedIn: 'root' })
export class RefLookups {
  private readonly api = inject(ApiService);
  private readonly lookups = inject(LookupSources);
  private readonly names = signal<ReadonlyMap<string, string>>(new Map());
  private readonly sources = new Map<string, SMTLookupSource<Row, SMTLookupKey>>();
  private readonly asked = new Set<string>();

  source(ref: QueryRefMeta): SMTLookupSource<Row, SMTLookupKey> {
    const id = targetOf(ref);
    let source = this.sources.get(id);
    if (!source) {
      source = this.known(ref) ?? refLookup(this.api, ref);
      this.sources.set(id, source);
    }
    return source;
  }

  /**
   * The name of the referenced row, read once and kept; null until it has come. A signal is read, so a view that
   * shows it is drawn again when the name arrives; the read itself starts after the current render.
   */
  name(ref: QueryRefMeta, key: unknown): string | null {
    if (key === null || key === undefined || key === '') return null;
    const id = `${targetOf(ref)}#${String(key)}`;
    const known = this.names().get(id);
    if (known !== undefined) return known;
    if (!this.asked.has(id)) {
      this.asked.add(id);
      queueMicrotask(() => this.read(ref, key as SMTLookupKey, id));
    }
    return null;
  }

  private read(ref: QueryRefMeta, key: SMTLookupKey, id: string): void {
    const source = this.source(ref);
    source.resolve?.([key]).subscribe({
      next: (rows) => {
        const row = rows.find((candidate) => String(source.key(candidate)) === String(key));
        if (row) this.names.update((names) => new Map(names).set(id, source.option(row).label));
      },
      // The value stays shown as it is; a failed read is not a reason to bother the person.
      error: () => this.asked.delete(id),
    });
  }

  private known(ref: QueryRefMeta): SMTLookupSource<Row, SMTLookupKey> | null {
    // Each of these is keyed by the row's numeric id, as the server's reference to it is.
    const sources: Record<string, unknown> = {
      '/iam/users': this.lookups.activeUsers,
      '/tasks': this.lookups.tasks,
      '/tasks/projects/page': this.lookups.projects,
    };
    const source = ref.keyField === 'id' ? sources[ref.path] : undefined;
    return (source as SMTLookupSource<Row, SMTLookupKey> | undefined) ?? null;
  }
}

/** One source per target list and key: two fields that point to the same list share their names. */
function targetOf(ref: QueryRefMeta): string {
  return `${ref.path}|${ref.keyField}|${ref.readPath ?? ''}`;
}
