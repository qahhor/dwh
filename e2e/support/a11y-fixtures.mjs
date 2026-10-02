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

/** A user as the general runtime reads it (ADR-0032 6.2): the record's properties and the viewer's actions. */
function userRecord(id) {
  const { forcePasswordChange, ...fields } = user(id);
  return { ...fields, credentialChangeRequired: forcePasswordChange, revision: 1, roleIds: [], actions: ['update'] };
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
// A record of ms.tasks on the general runtime (ADR-0032 8): its status and type are codes.
const task = id => ({
  id, title: `Проверить выкладку в торговой точке ${id}`, projectId: null, typeCode: 'task', statusCode: 'open', priority: ['low', 'medium', 'high'][id % 3],
  parentTaskId: null, endTime: at((id % 27) + 1), createdAt: at(1), modifiedAt: at(1), attributes: {}, revision: 1,
});
const page = (items, nextCursor = null, totalEstimated = items.length) => ({ items, nextCursor, hasMore: nextCursor !== null, totalEstimated });
const metaField = (key, labelKey, type, extra = {}) =>
  ({ key, labelKey, type, ops: ['eq'], sortable: false, nullable: false, defaultVisible: true, searchable: false, enumValues: [], enumLabelPrefix: null, ...extra });
const note = id => ({
  id, title: `Планёрка филиала ${id}`, contentMd: id % 2 ? `Итоги недели: выкладка, возвраты, **план на ${id} точек**` : 'Проверить остатки на складе',
  color: ['default', 'blue', 'green', 'yellow', 'purple', 'red'][id % 6], isPinned: id <= 2, attributes: {},
  createdBy: 1, createdAt: at(id), modifiedAt: at(id + 1),
});
const formField = (key, labelKey, type, extra = {}) => ({ key, labelKey, label: null, type, required: false, ...extra });
// The reference document (ADR-0032 9.4): an order with lines and statuses on the general screen, no web code of its own.
const money = (amount, currency = 'UZS') => ({ amount, currency });
const orderLine = (id, product, qty, price) => ({
  id, position: id, product, qty, price: money(price), amount: money((qty * Number(price)).toFixed(2)),
});
const order = id => ({
  id, number: `ORD-${String(id).padStart(6, '0')}`, orderDate: `2026-09-${String(id).padStart(2, '0')}`,
  customer: `Магазин «Ассорти» ${id}`, currency: 'UZS', status: id === 1 ? 'draft' : 'posted', total: money('41.70'),
  attributes: {}, createdAt: at(id), modifiedAt: at(id), revision: 1,
});
const MONEY_FIELD = { currencies: ['UZS', 'USD', 'EUR'], currencyFrom: 'currency' };
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
  '/settings/system': {
    values: { 'system.company_name': 'Smartup Distribution', 'system.default_language': 'ru' },
    revision: 1,
  },
  '/settings/user': {},
  '/modules/active': [
    { code: 'notes', name: 'Заметки', version: '1.0.0', route: '/notes', icon: 'description', isSystem: false, status: 'ACTIVE', isActive: true },
    { code: 'example', name: 'Эталон', version: '1.0.0', route: '/e/example.orders', icon: 'receipt_long', isSystem: false, status: 'ACTIVE', isActive: true },
  ],
  '/navigation/items/active': [],
  '/notifications/unread-count': { unread_count: 0 },
  '/announcements/active': [],
  '/iam/org-units': orgUnits(),
  '/iam/org-units/users/1': { userId: 1, orgUnitIds: [2, 5], legacyOrgUnitId: null, revision: 1 },
  '/iam/org-units/users/1/scope': { rule: 'UNITS', visibleOrgUnitIds: [2, 5] },
  // The users are an entity of the general runtime (ADR-0032 8): their form and list come from the server, the
  // records from /entities/md.users, and the screen is the general one with the tabs of the accounts.
  '/form-meta/md.users': {
    code: 'md.users', listCode: 'md.users',
    fields: [
      formField('name', 'iam.users.col.name', 'text', { required: true, minLength: 1, maxLength: 255 }),
      formField('login', 'iam.users.col.login', 'text', { required: true, minLength: 3, maxLength: 50, readonlyOnUpdate: true }),
      formField('email', 'iam.users.col.email', 'email', { required: true, readonlyOnUpdate: true }),
      formField('phone', 'iam.users.col.phone', 'phone'),
      formField('state', 'iam.users.col.state', 'select', { options: ['A', 'P'], optionLabelPrefix: 'iam.users.state.', readonly: true }),
      formField('orgUnitId', 'iam.users.col.org_unit', 'ref', { ref: { path: '/iam/org-units', labelField: 'name', keyField: 'id', paged: false } }),
      formField('managerId', 'iam.users.col.manager', 'ref', { ref: { path: '/entities/md.users', labelField: 'name', keyField: 'id', paged: true } }),
      formField('language', 'iam.common.language', 'text', { defaultValue: { kind: 'fixed', value: 'ru' } }),
      formField('timezone', 'iam.common.time_zone', 'text', { defaultValue: { kind: 'fixed', value: 'UTC' } }),
      formField('is2faEnabled', 'iam.users.col.two_factor', 'boolean', { readonly: true }),
      formField('credentialChangeRequired', 'iam.common.force_password_change', 'boolean', { readonly: true }),
    ],
    layout: [
      { key: 'profile', labelKey: 'iam.users.section.profile', fields: ['name', 'login', 'email', 'phone'] },
      { key: 'work', labelKey: 'iam.users.section.work', fields: ['orgUnitId', 'managerId', 'language', 'timezone'] },
      { key: 'security', labelKey: 'iam.users.section.security', fields: ['state', 'is2faEnabled', 'credentialChangeRequired'] },
    ],
    actions: ['create', 'update', 'block', 'unblock', 'reset_2fa', 'enable_2fa', 'force_password_change', 'anonymize'],
    capabilities: ['custom_fields', 'export', 'history', 'saved_views'],
  },
  '/query-meta/md.users': {
    code: 'md.users', defaultSort: 'name', defaultLimit: 20, maxLimit: 200, maxConditions: 20, maxInValues: 100,
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
  '/list-views/md.users': [],
  '/entities/md.users': page(range(1, 50).map(userRecord), 'u2', 60),
  '/entities/md.users#u2': page(range(51, 60).map(userRecord), null, 60),
  '/entities/md.users/1': userRecord(1),
  '/entities/md.users/2': {
    ...userRecord(2), managerId: 1, orgUnitId: 5,
    actions: ['update', 'block', 'reset_2fa', 'force_password_change', 'anonymize'],
  },
  '/entities/md.users/80': userRecord(80),
  '/iam/users/2/security': {
    userId: 2, login: 'user2', is2faEnabled: false, forcePasswordChange: false, createdAt: at(1), authVersion: 3,
    activeSessionsCount: 1,
    activeSessions: [{ id: 7, userId: 2, ip: '10.0.0.2', userAgent: 'Mozilla/5.0 Chrome/140.0', deviceInfo: 'browser', createdAt: at(3), lastSeenAt: at(4) }],
    recentLoginAttempts: [{ id: 9, login: 'user2', ip: '10.0.0.2', isSuccess: true, attemptAt: at(3) }],
  },
  '/iam/users/2/effective-permissions': { items: [{ form: 'tasks.items', action: 'view', source: 'role' }] },
  '/iam/users/2/permissions': { grants: [] },
  '/iam/forms': [],
  '/iam/org-units/users/2': { userId: 2, orgUnitIds: [5], legacyOrgUnitId: 5, revision: 4 },
  '/iam/org-units/users/2/scope': { rule: 'UNITS', visibleOrgUnitIds: [5] },
  '/iam/roles': [],
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
      metaField('statusCode', 'tasks.col.status', 'enum'),
      metaField('endTime', 'tasks.col.due', 'instant', { nullable: true })
    ]
  },
  '/list-views/ms.tasks': [],
  '/entities/ms.tasks': page(range(100, 51).map(task), 't2', 60),
  '/entities/ms.tasks#t2': page(range(50, 41).map(task), null, 60),
  // The project screen pages the runtime list ms.projects (ADR-0032 8); the progress of the page comes beside it.
  '/query-meta/ms.projects': {
    code: 'ms.projects', defaultSort: 'name', defaultLimit: 50, maxLimit: 200, maxConditions: 20, maxInValues: 100,
    fields: [
      metaField('id', 'projects.col.id', 'number', { sortable: true }),
      metaField('name', 'projects.col.name', 'text', { sortable: true, searchable: true }),
      metaField('archived', 'entity.col.archived', 'boolean'),
      metaField('createdAt', 'projects.col.created_at', 'instant', { sortable: true }),
      metaField('progress', 'projects.col.progress', 'number', { sortable: true })
    ]
  },
  '/list-views/ms.projects': [],
  '/entities/ms.projects': page(range(1, 10).map(id => ({
    id, name: `Выкладка в сети ${id}`, description: id % 2 ? `Регион ${id}: контроль полки и POSM` : undefined,
    archived: id % 5 === 0, attributes: {}, createdAt: at(id), revision: 1,
  })), 'p2', 14),
  '/entities/ms.projects#p2': page(range(11, 14).map(id => ({
    id, name: `Выкладка в сети ${id}`, archived: false, attributes: {}, createdAt: at(id), revision: 1,
  })), null, 14),
  // One answer for every page: the screen keeps the entries of the projects it shows.
  '/tasks/projects/progress': range(1, 14).map(id => ({
    projectId: id, totalTasks: id > 10 ? 0 : id + 2, doneTasks: id > 10 ? 0 : id, progress: id > 10 ? 0 : Math.round(id * 100 / (id + 2)),
  })),
  // The notes screen is the reference entity screen (plan 10/10, item 2.1): its form and list come from the server.
  '/form-meta/ms.notes': {
    code: 'ms.notes', listCode: 'ms.notes',
    fields: [
      formField('title', 'notes.col.title', 'text', { required: true, minLength: 1, maxLength: 255 }),
      formField('contentMd', 'notes.col.content', 'markdown', { maxLength: 100000 }),
      formField('color', 'notes.col.color', 'select', { options: ['default', 'blue', 'green', 'yellow', 'purple', 'red'], optionLabelPrefix: 'notes.color_' }),
      formField('isPinned', 'notes.col.pinned', 'boolean'),
    ],
    layout: [
      { key: 'main', labelKey: 'entity.section.main', fields: ['title', 'contentMd'] },
      { key: 'settings', labelKey: 'entity.section.settings', fields: ['color', 'isPinned'] },
    ],
    actions: ['create', 'update', 'archive', 'delete'],
    capabilities: ['bulk', 'custom_fields', 'export', 'history', 'saved_views'],
  },
  '/query-meta/ms.notes': {
    code: 'ms.notes', defaultSort: '-isPinned', defaultLimit: 50, maxLimit: 200, maxConditions: 20, maxInValues: 100,
    fields: [
      metaField('title', 'notes.col.title', 'text', { sortable: true, searchable: true }),
      metaField('isPinned', 'notes.col.pinned', 'boolean'),
      metaField('modifiedAt', 'notes.col.modified_at', 'instant', { sortable: true }),
    ],
  },
  '/list-views/ms.notes': [],
  '/entities/ms.notes': page(range(1, 6).map(note), null, 6),
  // The general entity screen (ADR-0032 7.1) reads a record of the runtime with the viewer's actions, and names the
  // entity by its menu item.
  '/entities/ms.notes/1': { ...note(1), revision: 2, archived: false, actions: ['update', 'archive', 'delete'] },
  '/entities/menu': [
    { code: 'ms.notes', form: 'notes', route: '/notes', labelKey: 'nav.notes', icon: 'description', section: 'workspace', order: 30, module: 'notes' },
    { code: 'md.users', form: 'md.users', route: '/e/md.users', labelKey: 'nav.users', icon: 'people', section: 'iam', order: 10, module: null },
    { code: 'example.orders', form: 'example.orders', route: '/e/example.orders', labelKey: 'nav.example_orders', icon: 'receipt_long', section: 'workspace', order: 90, module: 'example' },
  ],
  '/history/ms.notes/1': page([
    { id: 2, event: 'U', changedAt: at(2), changedByName: 'Иван Петров', changedByLogin: 'ipetrov', isApi: false, changes: [{ field: 'title', labelKey: 'notes.col.title', oldValue: 'Планёрка', newValue: 'Планёрка филиала 1' }] },
    { id: 1, event: 'I', changedAt: at(1), changedByName: 'Иван Петров', changedByLogin: 'ipetrov', isApi: false, changes: [] },
  ]),
  '/entities/ms.task_statuses': page([{ id: 1, code: 'open', name: 'Открыта', color: '#3b82f6', sortOrder: 1, terminal: false, system: true }]),
  '/entities/ms.task_types': page([]),
  '/form-meta/example.orders': {
    code: 'example.orders', listCode: 'example.orders',
    fields: [
      formField('number', 'example.orders.col.number', 'text', { readonly: true, defaultValue: { kind: 'sequence', value: 'ORD-{000000}' } }),
      formField('orderDate', 'example.orders.col.order_date', 'date', { required: true, defaultValue: { kind: 'today' } }),
      formField('customer', 'example.orders.col.customer', 'text', { required: true, minLength: 1, maxLength: 255 }),
      formField('currency', 'example.orders.col.currency', 'select', { required: true, options: ['UZS', 'USD', 'EUR'], defaultValue: { kind: 'fixed', value: 'UZS' } }),
      formField('status', 'example.orders.col.status', 'select', { readonly: true, options: ['draft', 'posted', 'cancelled'], optionLabelPrefix: 'example.orders.status.', defaultValue: { kind: 'fixed', value: 'draft' } }),
      formField('total', 'example.orders.col.total', 'money', { readonly: true, computed: true, ...MONEY_FIELD }),
      formField('comment', 'example.orders.col.comment', 'textarea', { maxLength: 2000 }),
    ],
    layout: [
      { key: 'main', labelKey: 'entity.section.main', fields: ['number', 'orderDate', 'customer', 'currency', 'status', 'total'] },
      { key: 'settings', labelKey: 'entity.section.settings', fields: ['comment'] },
    ],
    actions: ['create', 'update', 'post', 'unpost', 'cancel', 'import'],
    capabilities: ['bulk', 'export', 'history', 'import', 'saved_views'],
    collections: [{
      key: 'lines', labelKey: 'example.orders.lines', maxRows: 500,
      fields: [
        formField('product', 'example.orders.line.product', 'text', { required: true, minLength: 1, maxLength: 255 }),
        formField('qty', 'example.orders.line.qty', 'number', { required: true, scale: 3 }),
        formField('price', 'example.orders.line.price', 'money', { required: true, ...MONEY_FIELD }),
        formField('amount', 'example.orders.line.amount', 'money', { readonly: true, computed: true, ...MONEY_FIELD }),
      ],
    }],
    workflow: {
      field: 'status',
      states: [
        { code: 'draft', labelKey: 'example.orders.status.draft', initial: true, terminal: false, locks: [] },
        { code: 'posted', labelKey: 'example.orders.status.posted', initial: false, terminal: false, locks: ['currency', 'customer', 'lines', 'orderDate'] },
        { code: 'cancelled', labelKey: 'example.orders.status.cancelled', initial: false, terminal: true, locks: [] },
      ],
      transitions: [
        { code: 'post', from: ['draft'], to: 'posted', permission: 'post' },
        { code: 'unpost', from: ['posted'], to: 'draft', permission: 'unpost' },
        { code: 'cancel', from: ['draft'], to: 'cancelled', permission: 'cancel', confirmKey: 'example.orders.cancel_confirm' },
      ],
    },
    tabs: [
      { key: 'main', labelKey: 'ui.entity_page.tab_fields', kind: 'sections', sections: ['main', 'settings'] },
      { key: 'lines', labelKey: 'example.orders.lines', kind: 'collection', collection: 'lines' },
      { key: 'history', labelKey: 'ui.entity_page.tab_history', kind: 'history' },
    ],
  },
  '/query-meta/example.orders': {
    code: 'example.orders', defaultSort: '-number', defaultLimit: 50, maxLimit: 200, maxConditions: 20, maxInValues: 100,
    fields: [
      metaField('number', 'example.orders.col.number', 'text', { sortable: true, searchable: true }),
      metaField('orderDate', 'example.orders.col.order_date', 'date', { sortable: true }),
      metaField('customer', 'example.orders.col.customer', 'text', { sortable: true, searchable: true }),
      metaField('status', 'example.orders.col.status', 'enum', { enumValues: ['draft', 'posted', 'cancelled'], enumLabelPrefix: 'example.orders.status.' }),
      metaField('total', 'example.orders.col.total', 'number', { sortable: true, format: 'money' }),
    ],
  },
  '/list-views/example.orders': [],
  '/entities/example.orders': page(range(6, 1).map(order), null, 6),
  '/entities/example.orders/1': {
    ...order(1), actions: ['create', 'update', 'post', 'cancel'],
    lines: [orderLine(1, 'Мука пшеничная, 50 кг', 3, '10.00'), orderLine(2, 'Сахар, 25 кг', 1.5, '4.20'), orderLine(3, 'Соль, 1 кг', 10, '0.54')],
  },
  '/history/example.orders/1': page([
    { id: 2, event: 'U', changedAt: at(2), changedByName: 'Иван Петров', changedByLogin: 'ipetrov', isApi: false, changes: [{ field: 'lines', labelKey: 'example.orders.lines', oldValue: { count: 2 }, newValue: { count: 3, added: [{}] } }] },
    { id: 1, event: 'I', changedAt: at(1), changedByName: 'Иван Петров', changedByLogin: 'ipetrov', isApi: false, changes: [] },
  ]),
};
