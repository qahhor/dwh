import type { ReportMailing, ReportMailingSchedule } from './report-mailings.api';

/** The channels in their order on the screen. */
export const MAILING_CHANNELS = ['email', 'telegram', 'in_app'] as const;

/** The days of the week of a weekly mailing, Monday first; the server numbers them 1 to 7 (ISO). */
export const MAILING_WEEKDAYS = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'] as const;

/** The words of a weekday the server numbers 1 (Monday) to 7. */
export function weekdayKey(day: number): string {
  return `reports.mailings.weekday.${MAILING_WEEKDAYS[Math.min(Math.max(day, 1), 7) - 1]}`;
}

/** The conditions of a mailing and the comparisons of a threshold (ADR-0045). */
export const MAILING_CONDITIONS = ['always', 'has_rows', 'threshold'] as const;
export const MAILING_OPS = ['gt', 'gte', 'lt', 'lte', 'eq'] as const;

type Translate = (key: string, params?: Record<string, string | number>) => string;

/** When a mailing goes out, in words: "Monthly on day 1, 08:00 (Asia/Tashkent)". */
export function scheduleText(schedule: ReportMailingSchedule, translate: Translate): string {
  const time = schedule.time;
  let text: string;
  if (schedule.frequency === 'weekly') {
    const day = translate(weekdayKey(schedule.weekday ?? 1));
    text = translate('reports.mailings.summary.weekly', { day, time });
  } else if (schedule.frequency === 'monthly') {
    text = translate('reports.mailings.summary.monthly', { day: schedule.monthDay ?? 1, time });
  } else {
    text = translate('reports.mailings.summary.daily', { time });
  }
  return translate('reports.mailings.summary.zone', { schedule: text, zone: schedule.timeZone });
}

/** The channels of a mailing in words. */
export function channelsText(mailing: Pick<ReportMailing, 'channels'>, translate: Translate): string {
  return mailing.channels.map((channel) => translate(`reports.mailings.channel.${channel}`)).join(', ');
}

/** The tone of a run's status tag. */
export function runTone(status: string): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  switch (status) {
    case 'done':
      return 'success';
    case 'partial':
      return 'warning';
    case 'failed':
      return 'danger';
    case 'queued':
    case 'running':
      return 'info';
    default:
      return 'neutral';
  }
}

/** The zones of the time-zone database the browser knows, the given one first if it is missing. */
export function timeZones(current: string): string[] {
  const intl = Intl as unknown as { supportedValuesOf?: (key: string) => string[] };
  const zones = intl.supportedValuesOf ? intl.supportedValuesOf('timeZone') : [];
  return zones.includes(current) ? zones : [current, ...zones];
}

/** The time zone of the browser, the default of a new mailing. */
export function browserZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}
