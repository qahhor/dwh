import { describe, expect, it } from 'vitest';
import type { FormMeta } from '../models/form-meta.models';
import { formField } from '@testing/form-meta';
import { translateTest } from '@testing/i18n-test.stub';
import { conditionHolds, fieldReadonly, fieldVisible, FIELD_VALUE_RULES, keyList, normalPhone } from './field-values';
import { formProblems, recordPayload, recordValues } from './form-meta.service';

/** Plan 10/10, item 5.2 (ADR-0032 4.1–4.4): the value rules of the new types and the form flags on the web. */
describe('field values', () => {
  const META: FormMeta = {
    code: 'test.orders',
    fields: [
      formField('title', 'text', { required: true }),
      formField('kind', 'select', {
        options: ['retail', 'wholesale'],
        defaultValue: { kind: 'fixed', value: 'retail' },
      }),
      formField('ownerId', 'ref', {
        required: true,
        ref: { path: '/iam/users', labelField: 'name', keyField: 'id', paged: true },
        visibleWhen: [{ field: 'kind', op: 'eq', values: ['wholesale'] }],
      }),
      formField('active', 'boolean', { defaultValue: { kind: 'fixed', value: 'true' } }),
      formField('due', 'date', { defaultValue: { kind: 'today' } }),
      formField('number', 'text', { readonly: 'always', defaultValue: { kind: 'sequence', value: 'T-{0000}' } }),
      formField('code', 'text', { readonly: 'on_update' }),
      formField('doubled', 'number', { readonly: 'always', computed: true }),
      formField('email', 'email'),
      formField('phone', 'phone'),
      formField('total', 'money', { currencies: ['UZS', 'USD'] }),
      formField('tagIds', 'multi_ref', { ref: { path: '/tags', labelField: 'name', keyField: 'id', paged: true } }),
      formField('photo', 'image'),
      formField('extra', 'json', { jsonRoot: 'object' }),
    ],
    layout: [],
    actions: [],
    capabilities: [],
  };

  it('starts a new record with the defaults the form can know', () => {
    const values = recordValues(META, null);

    expect(values['kind']).toBe('retail');
    expect(values['active']).toBe(true);
    expect(values['due']).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(values['number']).toBeNull();
    expect(values['total']).toEqual({ amount: null, currency: 'UZS' });
    expect(values['tagIds']).toEqual([]);
  });

  it('edits JSON as text and money as it is', () => {
    const values = recordValues(META, { extra: { a: 1 }, total: { amount: '10.00', currency: 'USD' }, tagIds: [3] });

    expect(values['extra']).toBe('{\n  "a": 1\n}');
    expect(values['total']).toEqual({ amount: '10.00', currency: 'USD' });
    expect(values['tagIds']).toEqual([3]);
  });

  it('saves each type as the server takes it, never a computed field, a hidden field empty', () => {
    const payload = recordPayload(META, {
      title: 'Order',
      kind: 'retail',
      ownerId: 5,
      email: ' Ann@Example.COM ',
      phone: '+998 (90) 123-45-67',
      total: { amount: '1250.50', currency: 'UZS' },
      tagIds: ['3', 9],
      photo: { id: 'f1', name: 'p.png' },
      extra: '{"a": 1}',
      doubled: 4,
    });

    expect(payload).toMatchObject({
      ownerId: null,
      email: 'ann@example.com',
      phone: '+998901234567',
      total: { amount: '1250.50', currency: 'UZS' },
      tagIds: [3, 9],
      photo: 'f1',
      extra: { a: 1 },
    });
    expect(payload).not.toHaveProperty('doubled');
    expect(recordPayload(META, { total: { amount: '', currency: 'UZS' }, tagIds: [], extra: '' })).toMatchObject({
      total: null,
      tagIds: [],
      extra: null,
    });
  });

  it('checks a shown, writable field only', () => {
    expect(formProblems(META, { title: 'x', kind: 'wholesale' }, translateTest)).toEqual({
      ownerId: translateTest('ui.entity_form.required'),
    });
    expect(formProblems(META, { title: 'x', kind: 'retail' }, translateTest)).toEqual({});
    expect(
      formProblems(
        META,
        { title: 'x', email: 'ann@', phone: '123', total: { amount: '1.555', currency: 'UZS' }, extra: '[1]' },
        translateTest,
      ),
    ).toEqual({
      email: translateTest('ui.entity_form.invalid'),
      phone: translateTest('ui.entity_form.invalid'),
      total: translateTest('ui.entity_form.invalid'),
      extra: translateTest('ui.entity_form.invalid'),
    });
    expect(
      formProblems(
        { ...META, fields: [formField('tags', 'multi_ref', { maxItems: 1 })] },
        { tags: [1, 2] },
        translateTest,
      ),
    ).toEqual({ tags: translateTest('ui.entity_form.too_many', { n: 1 }) });
  });

  it('tells when a field is shown and when it can be changed', () => {
    const owner = META.fields.find((field) => field.key === 'ownerId')!;
    const code = META.fields.find((field) => field.key === 'code')!;
    const doubled = META.fields.find((field) => field.key === 'doubled')!;
    const posted = formField('amount', 'number', {
      readonly: 'when',
      readonlyWhen: [{ field: 'status', op: 'eq', values: ['posted'] }],
    });

    expect(fieldVisible(owner, { kind: 'wholesale' })).toBe(true);
    expect(fieldVisible(owner, { kind: 'retail' })).toBe(false);
    expect(fieldReadonly(code, {}, true)).toBe(false);
    expect(fieldReadonly(code, {}, false)).toBe(true);
    expect(fieldReadonly(doubled, {}, true)).toBe(true);
    expect(fieldReadonly(posted, { status: 'posted' }, false)).toBe(true);
    expect(fieldReadonly(posted, { status: 'draft' }, false)).toBe(false);
    expect(fieldReadonly(posted, { status: 'posted' }, true)).toBe(false);
  });

  it('evaluates conditions as the server does', () => {
    const values = { kind: 'b', vip: true, owner: '' };
    expect(
      conditionHolds(
        [
          {
            any: [
              { field: 'kind', op: 'in', values: ['a', 'b'] },
              { field: 'x', op: 'eq', values: ['1'] },
            ],
          },
        ],
        values,
      ),
    ).toBe(true);
    expect(
      conditionHolds(
        [
          { field: 'vip', op: 'eq', values: ['true'] },
          { field: 'owner', op: 'empty', values: [] },
        ],
        values,
      ),
    ).toBe(true);
    expect(conditionHolds([{ field: 'kind', op: 'ne', values: ['b'] }], values)).toBe(false);
    expect(conditionHolds([{ field: 'owner', op: 'not_empty', values: [] }], values)).toBe(false);
    expect(conditionHolds(null, values)).toBe(true);
  });

  it('keeps values in their kept form', () => {
    expect(normalPhone('+1 (555) 010-99-88')).toBe('+15550109988');
    expect(keyList([1, null, '', 2])).toEqual([1, 2]);
    expect(FIELD_VALUE_RULES.url.problem(formField('site', 'url'), 'https://example.com/a?b=1')).toBeNull();
    expect(FIELD_VALUE_RULES.url.problem(formField('site', 'url'), 'javascript:alert(1)')).toBe('invalid');
    expect(
      FIELD_VALUE_RULES.money.problem(formField('t', 'money', { currencies: ['JPY'] }), {
        amount: '10.5',
        currency: 'JPY',
      }),
    ).toBe('invalid');
  });
});
