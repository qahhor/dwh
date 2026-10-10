import { describe, expect, it } from 'vitest';
import type { FormMeta } from '../models/form-meta.models';
import { formField } from '@testing/form-meta';
import { translateTest } from '@testing/i18n-test.stub';
import {
  formProblems,
  lockedKeys,
  recordPayload,
  recordValues,
  rowMoney,
  serverProblems,
  withLocks,
} from './form-meta.service';

/*
 * The rows and the process of a document on the form (ADR-0032 9.1–9.2, plan 10/10, item 5.7): the rows as the form
 * edits them, the body that sends them, their problems under the addresses the server gives (`lines[1].qty`), and what
 * a state of the process locks.
 */
const DOC: FormMeta = {
  code: 'test.docs',
  listCode: 'test.docs',
  fields: [
    formField('customer', 'text', { required: true }),
    formField('currency', 'select', { options: ['UZS', 'USD'] }),
    formField('status', 'select', { options: ['draft', 'posted', 'cancelled'], readonly: true }),
  ],
  layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['customer', 'currency', 'status'] }],
  actions: ['create', 'update', 'post'],
  capabilities: [],
  collections: [
    {
      key: 'lines',
      labelKey: 'test.lines',
      maxRows: 2,
      fields: [
        formField('product', 'text', { required: true, maxLength: 10 }),
        formField('qty', 'number', { required: true }),
        formField('price', 'money', { currencies: ['UZS', 'USD'], currencyFrom: 'currency' }),
        formField('amount', 'money', { currencies: ['UZS', 'USD'], currencyFrom: 'currency', computed: true }),
      ],
    },
  ],
  workflow: {
    field: 'status',
    states: [
      { code: 'draft', labelKey: 'd', initial: true, terminal: false, locks: [] },
      { code: 'posted', labelKey: 'p', initial: false, terminal: false, locks: ['customer', 'lines'] },
      { code: 'cancelled', labelKey: 'c', initial: false, terminal: true, locks: [] },
    ],
    transitions: [{ code: 'post', from: ['draft'], to: 'posted', permission: 'post', confirmKey: null }],
  },
};

const RECORD = {
  id: 1,
  customer: 'Shop',
  currency: 'UZS',
  status: 'draft',
  lines: [
    {
      id: 11,
      position: 1,
      product: 'Flour',
      qty: 3,
      price: { amount: '10.00', currency: 'UZS' },
      amount: { amount: '30.00', currency: 'UZS' },
    },
  ],
};

describe('the rows of a document on the form', () => {
  it('reads the rows with their ids and sends them in their order, money in the document currency', () => {
    const values = recordValues(DOC, RECORD);
    expect(values['lines']).toEqual([
      expect.objectContaining({ id: 11, product: 'Flour', qty: 3, price: { amount: '10.00', currency: 'UZS' } }),
    ]);

    const edited = {
      ...values,
      currency: 'USD',
      lines: [
        { id: null, product: 'Tea', qty: '1', price: { amount: '2', currency: 'UZS' } },
        ...(values['lines'] as []),
      ],
    };
    const payload = recordPayload(DOC, edited, RECORD);
    expect(payload['lines']).toEqual([
      { product: 'Tea', qty: '1', price: { amount: '2', currency: 'USD' } },
      { id: 11, product: 'Flour', qty: 3, price: { amount: '10.00', currency: 'USD' } },
    ]);
    expect(recordValues(DOC, null)['lines']).toEqual([]);
  });

  it('finds the problems of a row under its address and of the collection under its key', () => {
    const values = {
      ...recordValues(DOC, null),
      customer: 'Shop',
      currency: 'UZS',
      lines: [
        { id: null, product: 'Flour', qty: '1', price: null },
        { id: null, product: 'A very long name', qty: null, price: null },
        { id: null, product: 'Salt', qty: '1', price: null },
      ],
    };
    const problems = formProblems(DOC, values, translateTest, true);
    expect(problems['lines[1].product']).toBe(translateTest('ui.entity_form.too_long', { count: 10 }));
    expect(problems['lines[1].qty']).toBe(translateTest('ui.entity_form.required'));
    expect(problems['lines']).toBe(translateTest('ui.entity_lines.too_many', { count: 2 }));
    expect(problems['lines[0].product']).toBeUndefined();
  });

  it('puts the server problems of a row and of the collection under their addresses', () => {
    const problems = serverProblems(
      DOC,
      [
        { field: 'lines[0].qty', code: 'out_of_range', message: '' },
        { field: 'lines', code: 'required', message: '' },
        { field: 'lines[2]', code: 'invalid', message: 'Bad row' },
        { field: 'unknown[0].x', code: 'invalid', message: 'ignored' },
      ],
      translateTest,
    );
    expect(problems).toEqual({
      'lines[0].qty': translateTest('ui.entity_form.out_of_range'),
      lines: translateTest('ui.entity_lines.required'),
      'lines[2]': 'Bad row',
    });
  });

  it('locks what a state names, everything in a terminal state, and leaves locked rows out of the body', () => {
    expect([...lockedKeys(DOC, { status: 'posted' })]).toEqual(['customer', 'lines']);
    expect(lockedKeys(DOC, { status: 'cancelled' })).toEqual(new Set(['customer', 'currency', 'status', 'lines']));
    expect(lockedKeys(DOC, { status: 'draft' }).size).toBe(0);
    expect(withLocks(DOC, { status: 'posted' }).fields.find((field) => field.key === 'customer')?.readonly).toBe(true);

    const posted = { ...RECORD, status: 'posted' };
    expect('lines' in recordPayload(DOC, recordValues(DOC, posted), posted)).toBe(false);
  });

  it('takes the currency of a row money from the document', () => {
    const price = DOC.collections![0].fields[2];
    expect(rowMoney(price, '7.5', { currency: 'USD' })).toEqual({ amount: '7.5', currency: 'USD' });
    expect(rowMoney(DOC.fields[0], 'x', { currency: 'USD' })).toBe('x');
  });
});
