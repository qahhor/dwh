import type { ReportMailing } from '../app/features/reports/mailings/report-mailings.api';

/** A stored mailing of a saved report for the specs of the mailings screens (ADR-0045). */
export const MAILING: ReportMailing = {
  id: 3,
  viewId: 7,
  entity: 'studio.orders',
  name: 'Итоги месяца',
  recipients: [{ id: 5, name: 'Продавец', login: 'seller', state: 'A' }],
  channels: ['email', 'in_app'],
  attachXlsx: true,
  includeLink: true,
  schedule: { frequency: 'monthly', time: '08:00', timeZone: 'Asia/Tashkent', monthDay: 1 },
  parameters: { period: { preset: 'last_month' } },
  condition: { kind: 'has_rows' },
  active: true,
  nextRunAt: '2026-11-01T03:00:00Z',
  ownerId: 1,
  lastRun: { id: 9, status: 'done', createdAt: '2026-10-19T03:00:00Z' },
  revision: 4,
  createdAt: '2026-10-18T00:00:00Z',
  modifiedAt: '2026-10-18T00:00:00Z',
};
