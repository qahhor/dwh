import { describe, expect, it } from 'vitest';
import { featureI18nProblems } from '@testing/feature-i18n';
import { MAILING_CHANNELS, MAILING_CONDITIONS, MAILING_OPS, MAILING_WEEKDAYS } from './mailings/mailing-words';

/** The words of a mailing built from its values at run time (ADR-0045). */
const MAILING_KEYS = [
  ...MAILING_CHANNELS.map((channel) => `reports.mailings.channel.${channel}`),
  ...MAILING_CONDITIONS.map((condition) => `reports.mailings.condition.${condition}`),
  ...MAILING_OPS.map((op) => `reports.mailings.op.${op}`),
  ...MAILING_WEEKDAYS.map((day) => `reports.mailings.weekday.${day}`),
  ...['daily', 'weekly', 'monthly'].map((frequency) => `reports.mailings.frequency.${frequency}`),
  ...['scheduled', 'manual', 'test'].map((kind) => `reports.mailings.runs.kind.${kind}`),
  ...['queued', 'running', 'done', 'partial', 'failed', 'skipped'].map(
    (status) => `reports.mailings.runs.status.${status}`,
  ),
  ...['sent', 'queued', 'skipped', 'failed'].map((status) => `reports.mailings.runs.delivery.${status}`),
  ...['pending', 'processing', 'sent', 'failed', 'dead_letter'].map((state) => `reports.mailings.runs.outbox.${state}`),
  ...[
    'inactive',
    'no_access',
    'no_address',
    'channel_unavailable',
    'opted_out',
    'condition',
    'condition_ignored',
    'parameters',
    'report_failed',
    'attachment_too_large',
    'paused',
  ].map((reason) => `reports.mailings.runs.reason.${reason}`),
];

describe('i18n: reports', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    expect(
      featureI18nProblems({
        dir: 'src/app/features/reports',
        owns: ['reports.'],
        english: true,
        dynamic: MAILING_KEYS,
      }),
    ).toEqual([]);
  });
});
