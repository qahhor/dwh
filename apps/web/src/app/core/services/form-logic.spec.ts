import { describe, expect, it } from 'vitest';
import type { FormulaNode } from '../formula/formula.models';
import type { FormFieldMeta, FormMeta } from '../models/form-meta.models';
import { formProblems, recordPayload, recordValues } from './form-meta.service';
import { fieldReadonly, fieldVisible } from './field-values';
import {
  hasLogic,
  logicReadonly,
  logicRequired,
  logicVisible,
  ruleProblems,
  viewerScope,
  withComputed,
  withDefaults,
} from './form-logic';

const num = (value: string): FormulaNode => ({ kind: 'literal', value, type: 'number' });
const text = (value: string): FormulaNode => ({ kind: 'literal', value, type: 'text' });
const ref = (name: string, type: FormulaNode['type'] = 'number'): FormulaNode => ({ kind: 'field', name, type });
const op = (name: string, type: FormulaNode['type'], ...args: FormulaNode[]): FormulaNode => ({
  kind: 'binary',
  name,
  type,
  args,
});
const call = (name: string, type: FormulaNode['type'], ...args: FormulaNode[]): FormulaNode => ({
  kind: 'call',
  name,
  type,
  args,
});

function field(key: string, extra: Partial<FormFieldMeta> = {}): FormFieldMeta {
  return { key, labelKey: `f.${key}`, type: 'number', required: false, readonly: false, ...extra };
}

/** The reference order: total = round(qty * price, 2), net = total - 1, reason shown and required above 10. */
const ORDER: FormMeta = {
  code: 'studio.orders',
  fields: [
    field('net', { readonly: true, computed: true, scale: 2, formula: op('-', 'number', ref('total'), num('1')) }),
    field('qty', { scale: 0 }),
    field('price', { scale: 2 }),
    field('discount', { scale: 2 }),
    field('total', {
      readonly: true,
      computed: true,
      scale: 2,
      formula: call('round', 'number', op('*', 'number', ref('qty'), ref('price')), num('2')),
    }),
    field('orderdate', { type: 'date', defaultFormula: call('today', 'date') }),
    field('label', { type: 'text', defaultFormula: op('&', 'text', text('#'), ref('qty')) }),
    field('reason', {
      type: 'text',
      visibleIf: op('>', 'bool', ref('discount'), num('10')),
      requiredIf: op('>', 'bool', ref('discount'), num('10')),
      readonlyIf: op('=', 'bool', ref('reason', 'text'), text('locked')),
    }),
    field('at', { type: 'datetime' }),
    field('late', {
      type: 'boolean',
      readonly: true,
      computed: true,
      formula: op('<', 'bool', ref('at', 'datetime'), call('now', 'datetime')),
    }),
  ],
  layout: [],
  actions: [],
  capabilities: [],
  rules: [
    {
      key: 'max',
      field: 'discount',
      messageKey: 'studio.live.orders.rule.max',
      formula: op('<=', 'bool', ref('discount'), num('30')),
    },
    {
      key: 'zero',
      messageKey: 'studio.live.orders.rule.zero',
      formula: op('>', 'bool', op('/', 'number', num('1'), ref('qty')), num('0')),
    },
  ],
};

const translate = (key: string) => key;

describe('form logic on the screen (ADR-0042)', () => {
  it('computes each field after the fields it reads and keeps the object when nothing changes', () => {
    const values = withComputed(ORDER, { qty: '3', price: '19.99', at: '2000-01-01T10:00' });
    expect(values['total']).toBe('59.97');
    expect(values['net']).toBe('58.97');
    expect(values['late']).toBe(true);
    expect(withComputed(ORDER, values)).toBe(values);
    expect(withComputed({ fields: [field('qty')] }, values)).toBe(values);
  });

  it('starts a new record with its default formulas and leaves filled fields as they are', () => {
    const values = withDefaults(ORDER, { qty: '2', orderdate: null, label: '' });
    expect(values['orderdate']).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(values['label']).toBe('#2');
    expect(withDefaults(ORDER, { orderdate: '2026-01-01', label: 'x' })['orderdate']).toBe('2026-01-01');
    const created = recordValues(ORDER, null);
    expect(created['orderdate']).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('shows, requires and locks a field by its formulas and checks the rules before the request', () => {
    const reason = ORDER.fields.find((item) => item.key === 'reason') as FormFieldMeta;
    expect(logicVisible(reason, { discount: '15' })).toBe(true);
    expect(fieldVisible(reason, { discount: '5' })).toBe(false);
    expect(logicRequired(reason, { discount: '15' })).toBe(true);
    expect(logicRequired(reason, {})).toBe(false);
    expect(logicReadonly(reason, { reason: 'locked' })).toBe(true);
    expect(fieldReadonly(reason, { reason: 'locked' }, false)).toBe(true);
    expect(fieldReadonly(reason, { reason: 'locked' }, true)).toBe(false);
    const problems = formProblems(ORDER, { qty: '1', price: '1', discount: '40' }, translate, true);
    expect(problems['reason']).toBeDefined();
    expect(problems['discount']).toBe('studio.live.orders.rule.max');
    expect(ruleProblems(ORDER, { qty: '0', discount: '1' }, translate)['']).toBe('error.formula.division_by_zero');
    expect(ruleProblems(ORDER, { qty: '2' }, translate)).toEqual({});
    expect(hasLogic(ORDER)).toBe(true);
    expect(hasLogic({ fields: [field('qty')], rules: null })).toBe(false);
  });

  it('clears what a hidden field holds before it computes again, and sends no computed field', () => {
    const meta: FormMeta = {
      ...ORDER,
      fields: [
        field('extra', { visibleIf: op('>', 'bool', ref('qty'), num('1')) }),
        field('qty'),
        field('sum', { readonly: true, computed: true, formula: call('coalesce', 'number', ref('extra'), num('7')) }),
      ],
    };
    expect(withComputed(meta, { qty: '1', extra: '5' })['sum']).toBe('7');
    expect(withComputed(meta, { qty: '2', extra: '5' })['sum']).toBe('5');
    const payload = recordPayload(meta, { qty: '2', extra: '5', sum: '5' });
    expect(payload).not.toHaveProperty('sum');
  });

  it('reads the viewer zone and leaves a formula that fails to the server', () => {
    expect(viewerScope().zone).not.toBe('');
    const broken: FormFieldMeta = field('bad', {
      visibleIf: op('>', 'bool', op('/', 'number', num('1'), num('0')), num('0')),
      formula: op('/', 'number', num('1'), num('0')),
      readonly: true,
      computed: true,
    });
    expect(logicVisible(broken, {})).toBe(true);
    expect(withComputed({ fields: [broken] }, { bad: '3' })['bad']).toBe('3');
  });
});

describe('the rows of a document (ADR-0043)', () => {
  const amount = op(
    '*',
    'number',
    ref('qty'),
    op('-', 'number', num('1'), op('/', 'number', ref('doc.discount'), num('100'))),
  );
  const DOCUMENT: FormMeta = {
    code: 'studio.orders',
    fields: [
      field('discount', { scale: 2 }),
      field('total', {
        scale: 2,
        computed: true,
        readonly: true,
        formula: call('sum', 'number', ref('lines.items.amount', 'numbers')),
      }),
      field('lines', { formula: call('count', 'number', ref('lines.items', 'rows')), scale: 0 }),
    ],
    layout: [],
    actions: [],
    capabilities: [],
    collections: [
      {
        key: 'items',
        labelKey: 'items',
        maxRows: 10,
        fields: [
          field('qty', { scale: 0 }),
          field('amount', { scale: 2, computed: true, readonly: true, formula: amount }),
        ],
      },
    ],
    rules: [
      {
        key: 'some',
        messageKey: 'rule.some',
        formula: op('>', 'bool', call('count', 'number', ref('lines.items', 'rows')), num('0')),
      },
    ],
  };

  it('computes each row over the header, then the header over the rows', () => {
    const values = withComputed(DOCUMENT, { discount: '10', items: [{ qty: 2 }, { qty: 3, amount: '999' }] });
    expect(values['items']).toEqual([
      { qty: 2, amount: '1.80' },
      { qty: 3, amount: '2.70' },
    ]);
    expect(values['total']).toBe('4.50');
    expect(values['lines']).toBe('2');
    const same = withComputed(DOCUMENT, values);
    expect(same['items']).toBe(values['items']);
  });

  it('reads no rows as zero and checks the rules over the rows', () => {
    const empty = withComputed(DOCUMENT, { discount: null, items: [] });
    expect(empty['total']).toBe('0.00');
    expect(ruleProblems(DOCUMENT, empty, (key) => `t:${key}`)).toEqual({ '': 't:rule.some' });
    expect(ruleProblems(DOCUMENT, withComputed(DOCUMENT, { items: [{ qty: 1 }] }), (key) => key)).toEqual({});
    expect(withComputed(DOCUMENT, { discount: '5' })['total']).toBe('0.00');
  });
});
