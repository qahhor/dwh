import type { FormFieldMeta, FormMeta } from '@core/models/form-meta.models';
import { USERS_PATH } from '@shared/lookups/lookup-sources';
import type { ActionType, AutomationTrigger, TriggerKind } from './automations.api';

/** What starts an automation, in the order the editor offers it (ADR-0046). */
export const TRIGGER_KINDS: readonly TriggerKind[] = [
  'created',
  'updated',
  'saved',
  'transition',
  'deleted',
  'scheduled',
] as const;

/** The actions an automation may take, in the order the editor offers them. */
export const ACTION_TYPES: readonly ActionType[] = ['set_field', 'create_record', 'transition', 'notify'] as const;

/** The channels of a notification in their order on the screen. */
export const NOTIFY_CHANNELS = ['in_app', 'email', 'telegram'] as const;

/** The days of a weekly schedule, Monday first; the server numbers them 1 to 7 (ISO). */
export const WEEKDAYS = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'] as const;

/** The fields of a live form the platform keeps itself: an automation does not write them. */
const SYSTEM_FIELDS = new Set(['orgUnit', 'recordNumber', 'modifiedAt', 'docNumber', 'docStatus', 'docComment']);

type Translate = (key: string, params?: Record<string, string | number>) => string;

/** A field's label in the viewer's language: its catalog text, else its own name, else its key. */
export function fieldLabel(field: FormFieldMeta, translate: Translate): string {
  if (field.labelKey) return translate(field.labelKey);
  return field.label || field.key;
}

/** Whether a field names a user of the system: a notification may go to that user. */
export function isUserField(field: FormFieldMeta): boolean {
  return field.type === 'ref' && field.ref?.path === USERS_PATH;
}

/** The fields an action may write: entered by a person — neither computed nor kept by the platform, nor a file. */
export function writableFields(meta: FormMeta | null): FormFieldMeta[] {
  return (meta?.fields ?? []).filter(
    (field) =>
      !field.computed &&
      !field.attribute &&
      !SYSTEM_FIELDS.has(field.key) &&
      field.type !== 'file' &&
      field.type !== 'image' &&
      field.key !== 'name' &&
      field.key !== 'code',
  );
}

/** The names a formula or a message of the form may read: its fields, its number and its status. */
export function readableNames(meta: FormMeta | null): string[] {
  const names = (meta?.fields ?? [])
    .filter((field) => !field.attribute && field.type !== 'file' && field.type !== 'image')
    .map((field) => field.key)
    .filter((key) => key !== 'orgUnit' && key !== 'docComment' && key !== 'name' && key !== 'code');
  return [...new Set(names)];
}

/** The label of a status of the form's process, or its code. */
export function stateLabel(meta: FormMeta | null, code: string, translate: Translate): string {
  const state = meta?.workflow?.states.find((candidate) => candidate.code === code);
  return state ? translate(state.labelKey) : code;
}

/** The label of a transition of the form's process, or its code. */
export function transitionLabel(meta: FormMeta | null, code: string, translate: Translate): string {
  const transition = meta?.workflow?.transitions.find((candidate) => candidate.code === code);
  return transition?.labelKey ? translate(transition.labelKey) : code;
}

/** What starts an automation, in words: "On a transition to «Approved»", "Daily at 09:00". */
export function triggerText(trigger: AutomationTrigger, meta: FormMeta | null, translate: Translate): string {
  const base = translate(`studio.automations.trigger.${trigger.kind}`);
  if (trigger.kind === 'transition') {
    const to = (trigger.to ?? []).map((code) => stateLabel(meta, code, translate));
    return to.length ? translate('studio.automations.summary.to', { trigger: base, states: to.join(', ') }) : base;
  }
  if ((trigger.kind === 'updated' || trigger.kind === 'saved') && trigger.fields?.length) {
    const fields = trigger.fields.map((key) => {
      const field = meta?.fields.find((candidate) => candidate.key === key);
      return field ? fieldLabel(field, translate) : key;
    });
    return translate('studio.automations.summary.fields', { trigger: base, fields: fields.join(', ') });
  }
  const schedule = trigger.schedule;
  if (trigger.kind === 'scheduled' && schedule) {
    let when = translate('studio.automations.summary.daily', { time: schedule.time });
    if (schedule.frequency === 'weekly') {
      const day = translate(
        `studio.automations.weekday.${WEEKDAYS[Math.min(Math.max(schedule.weekday ?? 1, 1), 7) - 1]}`,
      );
      when = translate('studio.automations.summary.weekly', { day, time: schedule.time });
    } else if (schedule.frequency === 'monthly') {
      when = translate('studio.automations.summary.monthly', { day: schedule.monthDay ?? 1, time: schedule.time });
    }
    return when;
  }
  return base;
}

/** The tone of a run's status tag. */
export function runTone(status: string): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  switch (status) {
    case 'done':
    case 'would_apply':
      return 'success';
    case 'failed':
      return 'danger';
    case 'queued':
      return 'info';
    case 'skipped':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** The time zone of the browser, the default of a new automation. */
export function browserZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

/** The zones of the time-zone database the browser knows, the given one first if it is missing. */
export function timeZones(current: string): string[] {
  const intl = Intl as unknown as { supportedValuesOf?: (key: string) => string[] };
  const zones = intl.supportedValuesOf ? intl.supportedValuesOf('timeZone') : [];
  return zones.includes(current) ? zones : [current, ...zones];
}
