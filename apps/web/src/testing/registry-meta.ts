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

/** What `query-meta/audit.logs` answers (AuditQuery on the server). */
export const AUDIT_LOGS_META: QueryListMeta = {
  code: 'audit.logs', defaultSort: '-changedAt', defaultLimit: 50, maxLimit: 200, maxConditions: 20, maxInValues: 100,
  fields: [
    metaField('id', 'audit.col.id', 'number'),
    metaField('tableName', 'audit.col.table', 'text'),
    metaField('rowPk', 'audit.col.row', 'text'),
    metaField('event', 'audit.col.event', 'enum', { enumValues: ['I', 'U', 'D'], enumLabelPrefix: 'audit.event.' }),
    metaField('changedByName', 'audit.col.changed_by', 'text', { nullable: true }),
    metaField('isApi', 'audit.col.channel', 'boolean'),
    metaField('changedAt', 'audit.col.changed_at', 'instant', { sortable: true })
  ]
} as QueryListMeta;

/** What `query-meta/audit.security_events` answers. */
export const SECURITY_EVENTS_META: QueryListMeta = {
  code: 'audit.security_events', defaultSort: '-createdAt', defaultLimit: 50, maxLimit: 200, maxConditions: 20, maxInValues: 100,
  fields: [
    metaField('id', 'audit.col.id', 'number'),
    metaField('eventType', 'audit.col.event_type', 'text'),
    metaField('userName', 'audit.col.user', 'text', { nullable: true }),
    metaField('ip', 'audit.col.ip', 'text', { nullable: true }),
    metaField('userAgent', 'audit.col.user_agent', 'text', { nullable: true }),
    metaField('createdAt', 'audit.col.created_at', 'instant', { sortable: true })
  ]
} as QueryListMeta;

/** Providers that answer each list's metadata by its code and an empty set of saved views. */
export function registryProviders(...metas: QueryListMeta[]) {
  return [
    { provide: QueryMetaService, useValue: { get: (code: string) => of(metas.find(meta => meta.code === code) ?? metas[0]) } },
    { provide: ListViewsApi, useValue: { list: () => of([]), create: vi.fn(), update: vi.fn(), remove: vi.fn() } }
  ];
}
