import { type EnvironmentProviders, type Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import type { ProblemDetail } from '../app/core/models/common.models';
import type { FormMeta } from '../app/core/models/form-meta.models';
import type { EntityMenuItem } from '../app/core/models/navigation.models';
import type { QueryFieldMeta, QueryListMeta } from '../app/core/models/query-meta.models';
import { ApiService } from '../app/core/services/api.service';
import { NavigationService } from '../app/core/services/navigation.service';
import { ToastService } from '../app/core/services/toast.service';
import { entityGuard } from '../app/shared/entity/page/entity.guard';
import type { EntityRecord } from '../app/shared/entity/entities.api';
import { SMTModalConfirmConfig, SMTModalService } from '../app/shared/ui-kit/components/modal';
import { inScreen } from './in-screen';
import { metaField } from './registry-meta';

/**
 * Test helpers for the general entity screen `/e/:code` (ADR-0032 7.3, 11.5): the `query-meta` of an entity from its
 * fields, a record of the runtime, and the screen rendered by the real router over an API that answers from these
 * fixtures alone — so a spec shows that an entity no web code knows gets its list, form and card.
 */

/** The `query-meta` of entity `code` with these fields, sorted by the first. */
export function queryMetaFixture(
  code: string,
  fields: QueryFieldMeta[],
  extra: Partial<QueryListMeta> = {},
): QueryListMeta {
  return {
    code,
    defaultSort: fields[0]?.key ?? 'id',
    defaultLimit: 50,
    maxLimit: 200,
    maxConditions: 20,
    maxInValues: 100,
    fields,
    ...extra,
  } as QueryListMeta;
}

/** A list field as `query-meta` answers it. */
export { metaField };

/** A record of the runtime: its id, revision 1 and every action allowed, unless the values say otherwise. */
export function entityRecord(id: number, values: Record<string, unknown> = {}): EntityRecord {
  return { id, revision: 1, archived: false, attributes: {}, actions: ['update', 'archive', 'delete'], ...values };
}

/** A problem as ApiService raises it. */
export function problem(status: number, extra: Partial<ProblemDetail> = {}): ProblemDetail {
  return { title: 'Error', status, code: status === 409 ? 'revision_conflict' : 'error', detail: '', ...extra };
}

type Answer = unknown | ((params?: unknown) => Observable<unknown>);

export interface EntityScreenOptions {
  meta: FormMeta;
  list?: QueryListMeta;
  /** The records of the list's first page. */
  records?: EntityRecord[];
  /** The menu items the shell has read; none — the screen names the entity by its code. */
  menu?: EntityMenuItem[];
  /** More answers by path: a value, or a function of the request's parameters. */
  answers?: Record<string, Answer>;
  /** Answers of POST, PATCH, PUT and DELETE by `METHOD path`; by default each returns the first record. */
  changes?: Record<string, (body: unknown) => Observable<unknown>>;
  providers?: (Provider | EnvironmentProviders)[];
}

/** The screen at `url` over the fixtures; `settle()` lets requests and the router finish. */
export async function renderEntityScreen(url: string, options: EntityScreenOptions) {
  const code = options.meta.code;
  const records = options.records ?? [];
  const answers: Record<string, Answer> = {
    [`/form-meta/${code}`]: options.meta,
    [`/query-meta/${options.meta.listCode ?? code}`]: options.list ?? queryMetaFixture(code, []),
    [`/list-views/${code}`]: [],
    [`/entities/${code}`]: { items: records, nextCursor: null, hasMore: false, totalEstimated: records.length },
    '/entities/menu': options.menu ?? [],
    '/modules/active': [],
    ...Object.fromEntries(records.map((record) => [`/entities/${code}/${record.id}`, record])),
    ...options.answers,
  };
  const answer = (path: string, params?: unknown): Observable<unknown> => {
    if (!(path in answers)) return throwError(() => problem(404));
    const found = answers[path];
    return typeof found === 'function' ? (found as (params?: unknown) => Observable<unknown>)(params) : of(found);
  };
  const change =
    (method: string) =>
    (path: string, body?: unknown): Observable<unknown> => {
      const handler = options.changes?.[`${method} ${path}`];
      return handler ? handler(body) : of(records[0] ?? entityRecord(1));
    };
  const api = {
    get: vi.fn((path: string, params?: unknown) => answer(path, params)),
    post: vi.fn((path: string, body?: unknown) => change('POST')(path, body)),
    patch: vi.fn((path: string, body?: unknown) => change('PATCH')(path, body)),
    put: vi.fn((path: string, body?: unknown) => change('PUT')(path, body)),
    delete: vi.fn((path: string) => change('DELETE')(path)),
  };
  const toast = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), show: vi.fn(), info: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        {
          path: 'e/:code',
          canActivate: [entityGuard],
          loadChildren: () => import('../app/shared/entity/page/entity.routes').then((m) => m.ENTITY_ROUTES),
        },
        { path: 'tasks', children: [] },
      ]),
      { provide: ApiService, useValue: api },
      { provide: ToastService, useValue: toast },
      ...(options.providers ?? []),
    ],
  });
  if (options.menu) TestBed.inject(NavigationService).entityItems.set(options.menu);
  /** Confirms at once and runs the confirmed work, as the dialog's Yes does. */
  const confirm = vi
    .spyOn(TestBed.inject(SMTModalService), 'confirm')
    .mockImplementation((config: SMTModalConfirmConfig) => {
      config.action?.().subscribe({ error: () => undefined });
      return of(true);
    });
  const harness = await RouterTestingHarness.create();
  const router = TestBed.inject(Router);
  async function settle(): Promise<void> {
    for (let round = 0; round < 4; round += 1) {
      harness.fixture.detectChanges();
      await harness.fixture.whenStable();
    }
    harness.fixture.detectChanges();
  }
  async function navigate(to: string): Promise<void> {
    await harness.navigateByUrl(to);
    await settle();
  }
  await navigate(url);
  const root = harness.fixture.nativeElement as HTMLElement;
  return { api, toast, confirm, harness, router, settle, navigate, screen: inScreen(root), root };
}
