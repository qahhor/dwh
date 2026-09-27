import { describe, expect, it } from 'vitest';
import { NOTES_FORM_META, formField, withCustomField } from '../../../testing/form-meta';
import { translateTest } from '../../../testing/i18n-test.stub';
import { canDo, formProblems, optionLabel, recordPayload, recordValues, serverProblems } from './form-meta.service';

const BUDGET = formField('cfBudget', 'number', { labelKey: '', label: 'Бюджет', attribute: 'budget', required: true });
const META = withCustomField(NOTES_FORM_META, BUDGET);

describe('form-meta helpers', () => {
  it('reads declared fields from the row and custom ones from its attributes', () => {
    const values = recordValues(META, {
      title: 'T',
      contentMd: 'x',
      color: 'blue',
      attributes: { budget: 5, other: 1 },
    });

    expect(values).toEqual({ title: 'T', contentMd: 'x', color: 'blue', isPinned: false, cfBudget: 5 });
  });

  it('saves declared fields by key and custom ones in attributes, keeping attributes the form does not show', () => {
    const payload = recordPayload(
      META,
      { title: '  T  ', contentMd: ' text ', color: 'red', isPinned: true, cfBudget: '' },
      { attributes: { budget: 5, other: 1 } },
    );

    expect(payload).toEqual({
      title: 'T',
      contentMd: ' text ',
      color: 'red',
      isPinned: true,
      attributes: { budget: null, other: 1 },
    });
  });

  it('finds what the server would reject, by field', () => {
    const problems = formProblems(META, { title: 'x'.repeat(256), color: 'orange', cfBudget: null }, translateTest);

    expect(problems).toEqual({
      title: translateTest('ui.entity_form.too_long', { n: 255 }),
      color: translateTest('ui.entity_form.invalid'),
      cfBudget: translateTest('ui.entity_form.required'),
    });
    expect(formProblems(META, { title: 'ok', color: 'blue', cfBudget: 3 }, translateTest)).toEqual({});
    expect(formProblems(META, { title: '   ', cfBudget: 3 }, translateTest)).toEqual({
      title: translateTest('ui.entity_form.required'),
    });
  });

  it('puts the server problems on their fields, a custom field by its attribute', () => {
    const problems = serverProblems(
      META,
      [
        { field: 'title', code: 'too_long', message: 'server words' },
        { field: 'attributes.budget', code: 'invalid_number', message: 'Поле Бюджет должно быть числом' },
        { field: 'unknown', code: 'required', message: 'ignored' },
      ],
      translateTest,
    );

    expect(problems).toEqual({
      title: translateTest('ui.entity_form.too_long', { n: 255 }),
      cfBudget: 'Поле Бюджет должно быть числом',
    });
  });

  it('allows only the actions the form lists, and none before it comes', () => {
    expect(canDo(NOTES_FORM_META, 'pin')).toBe(true);
    expect(canDo({ ...NOTES_FORM_META, actions: ['update'] }, 'delete')).toBe(false);
    expect(canDo(null, 'create')).toBe(false);
  });

  it('names an option by the field prefix, or by itself', () => {
    expect(optionLabel(NOTES_FORM_META.fields[2], 'blue', translateTest)).toBe(translateTest('notes.color_blue'));
    expect(optionLabel(formField('stage', 'select', { options: ['a'] }), 'a', translateTest)).toBe('a');
  });
});
