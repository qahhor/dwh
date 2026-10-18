import { describe, expect, it } from 'vitest';
import { initialSimpleForm } from './new-form.models';
import { simpleFormPreview } from './new-form-preview';
describe('simpleFormPreview', () => {
  it('uses standard implicit fields and preserves order and constraints of additional fields', () => {
    const value = initialSimpleForm();
    value.module.code = 'inventory';
    value.entity.name = 'locations';
    value.fields = [
      {
        id: 'capacity',
        type: 'number',
        labels: { ru: 'Объём', uz: 'Hajm', en: 'Capacity' },
        required: true,
        listed: false,
        scale: 3,
      },
      {
        id: 'active',
        type: 'bool',
        labels: { ru: 'Активен', uz: 'Faol', en: 'Active' },
        required: false,
        listed: true,
      },
    ];
    const meta = simpleFormPreview(value, 'en');
    expect(meta.code).toBe('inventory.locations');
    expect(meta.fields.map((field) => field.key)).toEqual(['name', 'code', 'capacity', 'active']);
    expect(meta.fields[1]).toMatchObject({ required: true, maxLength: 64, pattern: '[a-z0-9_-]+' });
    expect(meta.fields[2]).toMatchObject({ label: 'Capacity', scale: 3, required: true, type: 'number' });
    expect(meta.fields[3].type).toBe('boolean');
    expect(meta.layout[0].fields).toEqual(['name', 'code', 'capacity', 'active']);
  });
  it('keeps labels literal and matches text length validation', () => {
    const value = initialSimpleForm();
    value.fields = [
      {
        id: 'note',
        type: 'textarea',
        labels: { ru: '<img>', uz: 'Izoh', en: 'Note' },
        required: true,
        listed: true,
        maxLength: 700,
      },
    ];
    expect(simpleFormPreview(value, 'ru').fields[2]).toMatchObject({ label: '<img>', minLength: 1, maxLength: 700 });
  });
});

describe('simpleFormPreview of format 3', () => {
  it('shows a select by its own choices and references by the lists they pick from', () => {
    const labels = { ru: 'Поле', uz: 'Maydon', en: 'Field' };
    const meta = simpleFormPreview(
      {
        formatVersion: 3,
        kind: 'simple-form',
        module: { code: 'studio', title: labels },
        entity: { name: 'visits', title: labels, scope: 'org_unit' },
        fields: [
          {
            id: 'result',
            type: 'select',
            labels,
            required: true,
            listed: true,
            options: [{ code: 'done', labels: { ru: 'Готово', uz: 'Tayyor', en: 'Done' } }],
          },
          {
            id: 'client',
            type: 'ref',
            labels,
            required: false,
            listed: true,
            target: { kind: 'form', code: 'studio.clients' },
          },
          { id: 'kind', type: 'ref', labels, required: false, listed: true, target: { kind: 'list', code: 'kinds' } },
          { id: 'owner', type: 'user', labels, required: false, listed: true },
        ],
      },
      'en',
    );
    const byKey = Object.fromEntries(meta.fields.map((field) => [field.key, field]));
    expect(byKey['result']).toMatchObject({ type: 'select', options: ['done'], optionLabels: { done: 'Done' } });
    expect(byKey['client']).toMatchObject({ type: 'ref', ref: { path: '/entities/studio.clients' } });
    expect(byKey['kind']).toMatchObject({ type: 'enum', options: [] });
    expect(byKey['owner']).toMatchObject({ type: 'ref', ref: { path: '/entities/md.users' } });
  });

  it('shows a computed field read-only', () => {
    const value = initialSimpleForm();
    value.fields = [
      {
        id: 'total',
        type: 'number',
        labels: { ru: 'Сумма', uz: 'Summa', en: 'Total' },
        required: false,
        listed: true,
        scale: 2,
        formula: 'qty * 2',
      },
    ];
    expect(simpleFormPreview(value, 'ru').fields.find((field) => field.key === 'total')).toMatchObject({
      readonly: true,
      computed: true,
    });
  });
});
