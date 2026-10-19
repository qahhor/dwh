import type { FormMeta } from '../app/core/models/form-meta.models';
import type { Automation, AutomationRun } from '../app/features/settings/form-studio/automations/automations.api';
import { formField } from './form-meta';

/** The reference order of ADR-0043 with a manager, a responsible person and a review flag, as form-meta gives it. */
export const ORDER_META: FormMeta = {
  code: 'studio.orders',
  listCode: 'studio.orders',
  fields: [
    formField('client', 'ref', {
      required: true,
      ref: { path: '/entities/studio.clients', labelField: 'name', keyField: 'id', paged: true },
    }),
    formField('discount', 'number'),
    formField('total', 'number', { computed: true }),
    formField('manager', 'ref', {
      ref: { path: '/entities/md.users', labelField: 'name', keyField: 'id', paged: true },
    }),
    formField('review', 'boolean'),
    formField('docNumber', 'text', { readonly: true }),
    formField('docStatus', 'select', { readonly: true, options: ['draft', 'pending', 'approved'] }),
    formField('orgUnit', 'ref'),
  ],
  layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['client', 'discount', 'total'] }],
  actions: ['create', 'update'],
  capabilities: [],
  workflow: {
    field: 'docStatus',
    states: [
      { code: 'draft', labelKey: 'test.state.draft', initial: true, terminal: false, locks: [] },
      { code: 'pending', labelKey: 'test.state.pending', initial: false, terminal: false, locks: [] },
      { code: 'approved', labelKey: 'test.state.approved', initial: false, terminal: true, locks: [] },
    ],
    transitions: [
      { code: 'submit', from: ['draft'], to: 'pending', permission: 'submit', labelKey: 'test.move.submit' },
      { code: 'approve', from: ['pending'], to: 'approved', permission: 'approve', labelKey: 'test.move.approve' },
    ],
  },
};

/** A switched-on automation that notifies the manager when an order goes for approval. */
export const AUTOMATION: Automation = {
  id: 5,
  form: 'studio.orders',
  name: 'Уведомить менеджера',
  active: true,
  formArchived: false,
  trigger: { kind: 'transition', to: ['pending'] },
  condition: 'total > 0',
  actions: [
    {
      type: 'notify',
      channels: ['in_app'],
      recipients: { fields: ['manager'] },
      title: { ru: 'Заказ {docNumber}' },
      body: { ru: 'Сумма {total}' },
    },
  ],
  timeZone: 'Asia/Tashkent',
  author: { id: 1, name: 'Администратор' },
  lastRun: { id: 9, status: 'done', createdAt: '2026-10-19T08:00:00Z', finishedAt: '2026-10-19T08:00:01Z' },
  revision: 3,
  createdAt: '2026-10-19T07:00:00Z',
  modifiedAt: '2026-10-19T07:30:00Z',
};

/** A scheduled automation that reminds of waiting orders every day. */
export const SCHEDULED: Automation = {
  ...AUTOMATION,
  id: 6,
  name: 'Напоминание',
  trigger: { kind: 'scheduled', schedule: { frequency: 'daily', time: '09:00' } },
  nextRunAt: '2026-10-20T04:00:00Z',
  lastRun: undefined,
};

/** A run that told the manager, and one whose action the form refused. */
export const RUNS: AutomationRun[] = [
  {
    id: 11,
    kind: 'event',
    trigger: 'transition',
    recordId: 42,
    depth: 1,
    status: 'done',
    results: [
      {
        index: 0,
        type: 'notify',
        status: 'done',
        recipients: [{ id: 2, name: 'Менеджер', channels: ['in_app'] }],
        message: { title: 'Заказ ЗК-1', body: 'Сумма 15' },
      },
    ],
    attempts: 1,
    createdAt: '2026-10-19T08:00:00Z',
    finishedAt: '2026-10-19T08:00:01Z',
  },
  {
    id: 12,
    kind: 'event',
    trigger: 'saved',
    recordId: 43,
    depth: 1,
    status: 'failed',
    reason: 'action_failed',
    error: {
      key: 'error.common.record_fields_invalid',
      params: {},
      fields: [{ field: 'review', key: 'error.field.readonly', params: {} }],
    },
    results: [
      {
        index: 0,
        type: 'set_field',
        status: 'failed',
        reason: 'refused',
        values: { review: 'true' },
        error: { key: 'error.common.record_fields_invalid', params: {}, fields: [] },
      },
    ],
    attempts: 1,
    createdAt: '2026-10-19T08:05:00Z',
  },
];
