// API answers for the accessibility gate. Invented but realistic: a
// distributor's structure, staff, audit history and tasks, enough rows to
// page. Nothing here is real data.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const catalog = code => JSON.parse(readFileSync(
  fileURLToPath(new URL(`../../apps/server/src/main/resources/i18n/${code}.json`, import.meta.url)), 'utf8'));

const at = day => `2026-09-${String(day).padStart(2, '0')}T09:00:00Z`;

function user(id) {
  return {
    id, name: `Сотрудник ${id}`, login: `user${id}`, email: `user${id}@example.test`, state: id % 7 === 0 ? 'P' : 'A',
    language: 'ru', timezone: 'Asia/Tashkent', attributes: {}, is2faEnabled: id % 3 === 0, forcePasswordChange: false,
    // Every tenth reports to someone far down the list, whose name the screen must look up.
    managerId: id === 1 ? undefined : id % 10 === 0 ? 80 : 1 + (id % 5),
    createdAt: at(1), modifiedAt: at(2),
  };
}

function orgUnits() {
  const units = [];
  let id = 0;
  const add = (parentId, code, name, kind, state = 'A') => {
    id += 1;
    units.push({ id, parentId, code, name, kind, state, orderNo: id, createdAt: at(1), modifiedAt: at(1) });
    return id;
  };
  const company = add(null, 'SMT', 'Smartup Distribution', 'company');
  for (const [code, city, branches] of [['TAS', 'Ташкент', 2], ['SAM', 'Самарканд', 2], ['FER', 'Фергана', 1]]) {
    const region = add(company, code, `Регион ${city}`, 'region');
    for (let b = 1; b <= branches; b += 1) {
      const branch = add(region, `${code}-${b}`, `Филиал ${city} ${b}`, 'branch', code === 'SAM' && b === 2 ? 'P' : 'A');
      for (const [dept, name] of [['SAL', 'Продажи'], ['WH', 'Склад'], ['MER', 'Мерчандайзинг']]) add(branch, `${code}-${b}-${dept}`, name, 'department');
    }
  }
  return units;
}

const auditRecord = id => ({
  id, tableName: ['md_users', 'ms_tasks', 'md_roles'][id % 3], rowPk: String(id), event: ['I', 'U', 'D'][id % 3],
  isApi: id % 5 === 0, changedAt: at((id % 27) + 1), changedColumns: ['name'],
  ...(id % 2 ? { changedByName: 'Иван Петров', changedByLogin: 'ipetrov' } : {}),
});
const securityEvent = id => ({
  id, eventType: ['LOGIN_SUCCESS', 'LOGIN_FAILED', 'LOGIN_LOCKED', 'PASSWORD_CHANGED', 'API_TOKEN_CREATED'][id % 5],
  ip: `10.0.0.${id}`, userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0', details: { login: 'guest' },
  createdAt: at((id % 27) + 1), ...(id % 2 ? { userName: 'Иван Петров', userLogin: 'ipetrov' } : {}),
});
const task = id => ({
  id, title: `Проверить выкладку в торговой точке ${id}`, projectId: null, typeId: null, statusId: 1, priority: ['low', 'medium', 'high'][id % 3],
  parentTaskId: null, endTime: at((id % 27) + 1), createdAt: at(1), modifiedAt: at(1),
});
const page = (items, nextCursor = null, totalEstimated = items.length) => ({ items, nextCursor, hasMore: nextCursor !== null, totalEstimated });
const metaField = (key, labelKey, type, extra = {}) =>
  ({ key, labelKey, type, ops: ['eq'], sortable: false, nullable: false, defaultVisible: true, searchable: false, enumValues: [], enumLabelPrefix: null, ...extra });
const range = (from, to) => Array.from({ length: Math.abs(to - from) + 1 }, (_, i) => from < to ? from + i : from - i);

export const me = {
  user: user(1),
  permissions: ['*.*'],
  permissionsVersion: 1,
};

/** Keyed by API path; `path#cursor` answers a follow-up page. */
export const fixtures = {
  '/i18n/ru': catalog('ru'),
  '/i18n/languages': [
    { code: 'ru', name: 'Русский', builtin: true, active: true, revision: 1, translated: 1929, total: 1929, coverage: 100 },
    { code: 'en', name: 'English', builtin: true, active: true, revision: 1, translated: 1751, total: 1929, coverage: 91 },
  ],
  '/settings/system': { company_name: 'Smartup Distribution', default_language: 'ru' },
  '/settings/user': {},
  '/modules/active': [],
  '/navigation/items/active': [],
  '/notifications/unread-count': { unread_count: 0 },
  '/announcements/active': [],
  '/iam/org-units': orgUnits(),
  '/iam/org-units/users/1': { userId: 1, orgUnitIds: [2, 5], legacyOrgUnitId: null },
  '/iam/org-units/users/1/scope': { rule: 'UNITS', visibleOrgUnitIds: [2, 5] },
  // The user list is a registry list (roadmap item 48): its fields come from query-meta, the total is real.
  '/query-meta/iam.users': {
    code: 'iam.users', defaultSort: 'name', defaultLimit: 20, maxLimit: 200, maxConditions: 20, maxInValues: 100,
    fields: [
      metaField('name', 'iam.users.col.name', 'text', { sortable: true, searchable: true }),
      metaField('login', 'iam.users.col.login', 'text', { sortable: true, searchable: true, defaultVisible: false }),
      metaField('email', 'iam.users.col.email', 'text', { sortable: true, searchable: true }),
      metaField('phone', 'iam.users.col.phone', 'text', { nullable: true, searchable: true, defaultVisible: false }),
      metaField('state', 'iam.users.col.state', 'enum', { enumValues: ['A', 'P'], enumLabelPrefix: 'iam.users.state.' }),
      metaField('is2faEnabled', 'iam.users.col.two_factor', 'boolean'),
      metaField('createdAt', 'iam.users.col.created_at', 'instant', { sortable: true })
    ]
  },
  '/list-views/iam.users': [],
  '/iam/users': page(range(1, 50).map(user), 'u2', 60),
  '/iam/users#u2': page(range(51, 60).map(user), null, 60),
  '/iam/users/80': { ...user(80), managerId: 1 },
  '/rbac/roles': [],
  '/custom-fields': [],
  '/audit/stats': { totalAuditLogs: 30, totalSecurityEvents: 12, securityEventsLast24h: 3, failedLoginsLast24h: 1 },
  // Both audit lists are registry lists (roadmap item 50).
  '/query-meta/audit.logs': {
    code: 'audit.logs', defaultSort: '-changedAt', defaultLimit: 50, maxLimit: 200, maxConditions: 20, maxInValues: 100,
    fields: [
      metaField('id', 'audit.col.id', 'number'),
      metaField('tableName', 'audit.col.table', 'text'),
      metaField('rowPk', 'audit.col.row', 'text'),
      metaField('event', 'audit.col.event', 'enum', { enumValues: ['I', 'U', 'D'], enumLabelPrefix: 'audit.event.' }),
      metaField('changedByName', 'audit.col.changed_by', 'text', { nullable: true, searchable: true }),
      metaField('isApi', 'audit.col.channel', 'boolean'),
      metaField('changedAt', 'audit.col.changed_at', 'instant', { sortable: true })
    ]
  },
  '/query-meta/audit.security_events': {
    code: 'audit.security_events', defaultSort: '-createdAt', defaultLimit: 50, maxLimit: 200, maxConditions: 20, maxInValues: 100,
    fields: [
      metaField('id', 'audit.col.id', 'number'),
      metaField('eventType', 'audit.col.event_type', 'text'),
      metaField('userName', 'audit.col.user', 'text', { nullable: true, searchable: true }),
      metaField('ip', 'audit.col.ip', 'text', { nullable: true, searchable: true }),
      metaField('userAgent', 'audit.col.user_agent', 'text', { nullable: true, searchable: true }),
      metaField('createdAt', 'audit.col.created_at', 'instant', { sortable: true })
    ]
  },
  '/list-views/audit.logs': [],
  '/list-views/audit.security_events': [],
  '/audit/logs': page(range(30, 11).map(auditRecord), 'a2', 30),
  '/audit/logs#a2': page(range(10, 1).map(auditRecord), null, 30),
  '/audit/security-events': page(range(12, 1).map(securityEvent), null, 12),
  // The task list is a registry list (roadmap item 49).
  '/query-meta/ms.tasks': {
    code: 'ms.tasks', defaultSort: 'id', defaultLimit: 50, maxLimit: 200, maxConditions: 20, maxInValues: 100,
    fields: [
      metaField('id', 'tasks.col.id', 'number', { sortable: true }),
      metaField('title', 'tasks.col.title', 'text', { sortable: true, searchable: true }),
      metaField('descriptionMarkdown', 'tasks.col.description', 'text', { searchable: true, defaultVisible: false }),
      metaField('projectId', 'tasks.col.project', 'number', { nullable: true }),
      metaField('priority', 'tasks.col.priority', 'enum', { enumValues: ['low', 'medium', 'high', 'critical'], enumLabelPrefix: 'tasks.priority.' }),
      metaField('statusId', 'tasks.col.status', 'number'),
      metaField('endTime', 'tasks.col.due', 'instant', { nullable: true })
    ]
  },
  '/list-views/ms.tasks': [],
  '/tasks': page(range(100, 51).map(task), 't2', 60),
  '/tasks#t2': page(range(50, 41).map(task), null, 60),
  '/tasks/projects': range(1, 14).map(id => ({
    id, name: `Выкладка в сети ${id}`, description: id % 2 ? `Регион ${id}: контроль полки и POSM` : undefined,
    state: id % 5 ? 'A' : 'P', attributes: {}, createdAt: at(id), createdBy: 1,
  })),
  '/tasks/projects/stats': range(1, 12).map(id => ({ projectId: id, totalTasks: id + 2, activeTasks: 2, doneTasks: id })),
  '/tasks/statuses': [{ id: 1, name: 'Открыта', color: '#3b82f6', orderNo: 1, isFinal: false }],
  '/tasks/types': [],
};
