import { describe, expect, it } from 'vitest';
import type { SimpleField, SimpleFormDeclaration } from '../new-form/new-form.models';
import { incompatibleChanges, normalizedDeclaration, shownFields, versionDraft } from './form-version.rules';

const labels = { ru: 'Поле', uz: 'Maydon', en: 'Field' };
const text: SimpleField = { id: 'title', type: 'text', labels, required: false, listed: true, maxLength: 100 };
const number: SimpleField = { id: 'amount', type: 'number', labels, required: false, listed: true, scale: 2 };
const kind: SimpleField = {
  id: 'kind',
  type: 'text',
  labels,
  required: true,
  listed: true,
  maxLength: 20,
  defaultValue: 'general',
};

function form(fields: SimpleField[]): SimpleFormDeclaration {
  return {
    formatVersion: 2,
    kind: 'simple-form',
    module: { code: 'studio', title: labels },
    entity: { name: 'visits', title: labels },
    fields,
  };
}

const active = form([text, number, kind]);

describe('form version rules (ADR-0041, the same as the server)', () => {
  it.each([
    ['labels', [{ ...text, labels: { ru: 'Название', uz: 'Nom', en: 'Title' } }, number, kind]],
    ['order', [number, text, kind]],
    ['optional', [text, number, { ...kind, required: false }]],
    ['longer text', [{ ...text, maxLength: 4000 }, number, kind]],
    ['more decimals', [text, { ...number, scale: 6 }, kind]],
    ['hidden', [{ ...text, hidden: true }, number, kind]],
    ['added optional', [text, number, kind, { ...text, id: 'region' }]],
    [
      'added required with default',
      [text, number, kind, { ...number, id: 'priority', required: true, defaultValue: '3' }],
    ],
  ])('allows %s', (_name, fields) => {
    expect(incompatibleChanges(active, form(fields as SimpleField[]))).toEqual([]);
  });

  it.each([
    ['type', [text, { ...number, type: 'text', scale: undefined, maxLength: 20 }, kind], 'amount', 'type'],
    ['removed', [text, kind], 'amount', 'removed'],
    ['renamed', [{ ...text, id: 'name' }, number, kind], 'title', 'removed'],
    ['required without default', [{ ...text, required: true }, number, kind], 'title', 'required'],
    [
      'added required without default',
      [text, number, kind, { ...text, id: 'region', required: true }],
      'region',
      'required',
    ],
    ['shorter text', [{ ...text, maxLength: 99 }, number, kind], 'title', 'max_length'],
    ['fewer decimals', [text, { ...number, scale: 1 }, kind], 'amount', 'scale'],
    ['default changed', [text, number, { ...kind, defaultValue: 'other' }], 'kind', 'default'],
    ['default added', [{ ...text, defaultValue: 'x' }, number, kind], 'title', 'default'],
  ])('refuses %s', (_name, fields, field, change) => {
    expect(incompatibleChanges(active, form(fields as SimpleField[]))).toEqual([{ field, change }]);
  });

  it('seeds a format 2 draft, counts shown fields and drops empty flags before sending', () => {
    const draft = versionDraft({ ...active, formatVersion: 1 });
    expect(draft.formatVersion).toBe(2);
    expect(draft).not.toBe(active);
    const edited = form([
      { ...text, hidden: false, defaultValue: '' },
      { ...number, hidden: true },
    ]);
    expect(shownFields(edited).map((field) => field.id)).toEqual(['title']);
    const sent = normalizedDeclaration(edited);
    expect(sent.fields[0]).not.toHaveProperty('hidden');
    expect(sent.fields[0]).not.toHaveProperty('defaultValue');
    expect(sent.fields[1].hidden).toBe(true);
  });
});
