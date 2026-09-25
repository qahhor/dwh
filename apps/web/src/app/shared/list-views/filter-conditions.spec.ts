import { describe, expect, it } from 'vitest';
import { QueryListMeta } from '../../core/models/query-meta.models';
import { changeField, describeCondition, draftError, fromCondition, newDraft, toCondition } from './filter-conditions';

const META: QueryListMeta = {
  code: 'upl.sources',
  defaultSort: 'code',
  defaultLimit: 50,
  maxLimit: 200,
  maxConditions: 20,
  maxInValues: 2,
  fields: [
    { key: 'code', labelKey: 'Code', type: 'text', ops: ['eq', 'in', 'contains'], sortable: true, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
    { key: 'period', labelKey: 'Period', type: 'enum', ops: ['eq', 'in'], sortable: false, nullable: false, defaultVisible: true, enumValues: ['month', 'year'], enumLabelPrefix: 'p.' },
    { key: 'version', labelKey: 'Version', type: 'number', ops: ['eq', 'between', 'empty'], sortable: false, nullable: true, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
    { key: 'due', labelKey: 'Due', type: 'date', ops: ['gte'], sortable: false, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
    { key: 'draft', labelKey: 'Draft', type: 'boolean', ops: ['eq'], sortable: false, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
    { key: 'secret', labelKey: 'Secret', type: 'text', ops: [], sortable: false, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null }
  ]
};

const draft = (field: string, op: string, value = '', valueTo = '', values: string[] = []) =>
  ({ field, op, value, valueTo, values }) as ReturnType<typeof fromCondition>;

describe('filter conditions', () => {
  it('starts a row on the first filterable field and keeps the operation when the field has it', () => {
    expect(newDraft(META)).toEqual(draft('code', 'eq'));
    expect(changeField(draft('code', 'eq', 'x'), META.fields[1])).toEqual(draft('period', 'eq'));
    expect(changeField(draft('code', 'contains', 'x'), META.fields[2]).op).toBe('eq');
  });

  it('turns complete rows into typed DSL conditions', () => {
    expect(toCondition(draft('code', 'in', ' a, b ,,'), META)).toEqual({ field: 'code', op: 'in', value: ['a', 'b'] });
    expect(toCondition(draft('period', 'in', '', '', ['year', 'bogus']), META)).toEqual({ field: 'period', op: 'in', value: ['year'] });
    expect(toCondition(draft('version', 'between', '1', '2.5'), META)).toEqual({ field: 'version', op: 'between', value: [1, 2.5] });
    expect(toCondition(draft('version', 'empty', 'ignored'), META)).toEqual({ field: 'version', op: 'empty' });
    expect(toCondition(draft('draft', 'eq', 'false'), META)).toEqual({ field: 'draft', op: 'eq', value: false });
  });

  it('says what a row is missing', () => {
    expect(draftError(draft('code', 'eq'), META)).toBe('ui.filter.err.required');
    expect(draftError(draft('version', 'eq', 'ten'), META)).toBe('ui.filter.err.number');
    expect(draftError(draft('version', 'between', '1'), META)).toBe('ui.filter.err.required');
    expect(draftError(draft('due', 'gte', '31.01.2026'), META)).toBe('ui.filter.err.date');
    expect(draftError(draft('code', 'in', 'a, b, c'), META)).toBe('ui.filter.err.too_many');
    expect(draftError(draft('period', 'in'), META)).toBe('ui.filter.err.required');
    expect(draftError(draft('secret', 'eq', 'x'), META)).toBe('ui.filter.err.field');
    expect(draftError(draft('code', 'gt', 'x'), META)).toBe('ui.filter.err.field');
    expect(draftError(draft('version', 'empty'), META)).toBeNull();
    expect(draftError(draft('due', 'gte', '2026-01-31'), META)).toBeNull();
  });

  it('reads a saved condition back into a row', () => {
    expect(fromCondition({ field: 'period', op: 'in', value: ['month'] })).toEqual(draft('period', 'in', 'month', '', ['month']));
    expect(fromCondition({ field: 'version', op: 'between', value: [1, 3] })).toEqual(draft('version', 'between', '1', '3'));
    expect(fromCondition({ field: 'version', op: 'empty' })).toEqual(draft('version', 'empty'));
  });

  it('describes a condition for its chip with labels and translated values', () => {
    const translate = (key: string) => ({ 'ui.filter.op.in': 'one of', 'ui.filter.op.empty': 'is empty', 'p.month': 'Monthly', 'common.no': 'No', 'ui.filter.op.eq': 'equals' } as Record<string, string>)[key] ?? key;

    expect(describeCondition({ field: 'period', op: 'in', value: ['month'] }, META, translate)).toBe('Period: one of Monthly');
    expect(describeCondition({ field: 'version', op: 'between', value: [1, 3] }, META, translate)).toBe('Version: 1 — 3');
    expect(describeCondition({ field: 'version', op: 'empty' }, META, translate)).toBe('Version: is empty');
    expect(describeCondition({ field: 'draft', op: 'eq', value: false }, META, translate)).toBe('Draft: equals No');
  });
});
