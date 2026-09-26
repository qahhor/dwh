import { of } from 'rxjs';
import { vi } from 'vitest';
import { QueryFieldMeta, QueryListMeta } from '../app/core/models/query-meta.models';
import { QueryMetaService } from '../app/core/services/query-meta.service';
import { ListViewsApi } from '../app/shared/list-views/list-views';

/** A registry field as `query-meta` answers it; tests override only what they check. */
export function metaField(key: string, labelKey: string, type: QueryFieldMeta['type'], extra: Partial<QueryFieldMeta> = {}): QueryFieldMeta {
  return { key, labelKey, type, ops: ['eq'], sortable: false, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null, ...extra } as QueryFieldMeta;
}

/** What `query-meta/ms.tasks` answers (MsTaskQuery on the server). */
export const TASKS_META: QueryListMeta = {
  code: 'ms.tasks', defaultSort: 'id', defaultLimit: 50, maxLimit: 200, maxConditions: 20, maxInValues: 100,
  fields: [
    metaField('id', 'tasks.col.id', 'number', { sortable: true }),
    metaField('title', 'tasks.col.title', 'text', { sortable: true }),
    metaField('descriptionMarkdown', 'tasks.col.description', 'text', { defaultVisible: false }),
    metaField('projectId', 'tasks.col.project', 'number', { nullable: true }),
    metaField('priority', 'tasks.col.priority', 'enum', { enumValues: ['low', 'medium', 'high', 'critical'], enumLabelPrefix: 'tasks.priority.' }),
    metaField('statusId', 'tasks.col.status', 'number'),
    metaField('endTime', 'tasks.col.due', 'instant', { nullable: true })
  ]
} as QueryListMeta;

/** Providers that answer a list's metadata and an empty set of saved views. */
export function registryProviders(meta: QueryListMeta) {
  return [
    { provide: QueryMetaService, useValue: { get: () => of(meta) } },
    { provide: ListViewsApi, useValue: { list: () => of([]), create: vi.fn(), update: vi.fn(), remove: vi.fn() } }
  ];
}
