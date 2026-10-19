import { describe, expect, it } from 'vitest';
import { translateTest } from '@testing/i18n-test.stub';
import { ORDER_META } from '@testing/automations';
import {
  browserZone,
  fieldLabel,
  isUserField,
  readableNames,
  runTone,
  stateLabel,
  timeZones,
  transitionLabel,
  triggerText,
  writableFields,
} from './automation-words';

/* The words of automations (ADR-0046): what starts one, which fields it writes and reads, the tone of a status. */
describe('automation words', () => {
  const t = translateTest;

  it('names what starts an automation', () => {
    expect(triggerText({ kind: 'created' }, ORDER_META, t)).toBe(t('studio.automations.trigger.created'));
    expect(triggerText({ kind: 'transition', to: ['pending'] }, ORDER_META, t)).toContain('test.state.pending');
    expect(triggerText({ kind: 'transition' }, ORDER_META, t)).toBe(t('studio.automations.trigger.transition'));
    expect(triggerText({ kind: 'saved', fields: ['discount', 'gone'] }, ORDER_META, t)).toContain('gone');
    expect(triggerText({ kind: 'scheduled', schedule: { frequency: 'daily', time: '09:00' } }, null, t)).toContain(
      '09:00',
    );
    expect(
      triggerText({ kind: 'scheduled', schedule: { frequency: 'weekly', time: '08:00', weekday: 3 } }, null, t),
    ).toContain(t('studio.automations.weekday.wednesday'));
    expect(
      triggerText({ kind: 'scheduled', schedule: { frequency: 'monthly', time: '07:00', monthDay: 31 } }, null, t),
    ).toContain('31');
  });

  it('writes only the fields a person enters and reads the record without its system parts', () => {
    expect(writableFields(ORDER_META).map((field) => field.key)).toEqual(['client', 'discount', 'manager', 'review']);
    expect(writableFields(null)).toEqual([]);
    expect(readableNames(ORDER_META)).toEqual([
      'client',
      'discount',
      'total',
      'manager',
      'review',
      'docNumber',
      'docStatus',
    ]);
    expect(isUserField(ORDER_META.fields[3])).toBe(true);
    expect(isUserField(ORDER_META.fields[0])).toBe(false);
  });

  it('labels fields, states and transitions, a code when unknown', () => {
    expect(fieldLabel(ORDER_META.fields[1], t)).toBe('test.discount');
    expect(fieldLabel({ ...ORDER_META.fields[1], labelKey: '', label: 'Скидка' }, t)).toBe('Скидка');
    expect(stateLabel(ORDER_META, 'pending', t)).toBe('test.state.pending');
    expect(stateLabel(ORDER_META, 'nowhere', t)).toBe('nowhere');
    expect(transitionLabel(ORDER_META, 'approve', t)).toBe('test.move.approve');
    expect(transitionLabel(null, 'approve', t)).toBe('approve');
  });

  it('gives each status a tone and the browser its zone', () => {
    expect(runTone('done')).toBe('success');
    expect(runTone('would_apply')).toBe('success');
    expect(runTone('failed')).toBe('danger');
    expect(runTone('queued')).toBe('info');
    expect(runTone('skipped')).toBe('warning');
    expect(runTone('other')).toBe('neutral');
    expect(browserZone()).toBeTruthy();
    expect(timeZones('Mars/Olympus')[0]).toBe('Mars/Olympus');
  });
});
