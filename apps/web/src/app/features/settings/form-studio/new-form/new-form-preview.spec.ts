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
