import { describe, expect, it } from 'vitest';
import { registryTableConfig } from './registry-table-config';
import { describeCondition } from '../list-views/filter-conditions';
import { QueryListMeta } from '../../core/models/query-meta.models';
import { metaField } from '../../../testing/registry-meta';

type Row = { id: number; name: string; attributes: Record<string, unknown> };

/** A list with custom fields as the server adds them (ADR-0019 2.3, roadmap item 52). */
const META: QueryListMeta = {
  code: 'iam.users', defaultSort: 'name', defaultLimit: 20, maxLimit: 200, maxConditions: 20, maxInValues: 100,
  fields: [
    metaField('name', 'iam.users.col.name', 'text', { sortable: true }),
    metaField('cfRegion', '', 'text', { nullable: true, label: 'Регион', attribute: 'region' }),
    metaField('cfRemote', '', 'boolean', { nullable: true, label: 'Удалённо', attribute: 'remote' }),
    metaField('cfShift', '', 'enum', { nullable: true, label: 'Смена', attribute: 'shift', enumValues: ['day', 'night'] })
  ]
} as QueryListMeta;

const translate = (key: string) => ({ 'iam.users.col.name': 'Имя', 'common.yes': 'Да', 'common.no': 'Нет', 'ui.filter.op.eq': 'равно' } as Record<string, string>)[key] ?? key;

function cell(config: ReturnType<typeof registryTableConfig<Row>>, key: string, row: Row): unknown {
  const content = config.columns[key].content;
  return content.type === 'primitive' ? content.value(row) : undefined;
}

describe('registryTableConfig with custom fields', () => {
  const config = registryTableConfig<Row>(META, { translate, trackBy: (_i, row) => row.id, ariaLabel: 'Users', sort: null });
  const row: Row = { id: 1, name: 'Анна', attributes: { region: 'Tashkent', remote: 'false', shift: 'night' } };

  it('heads a custom field with its own name and a declared field with its translated key', () => {
    expect(config.columns['name'].header).toEqual({ type: 'primitive', value: 'Имя' });
    expect(config.columns['cfRegion'].header).toEqual({ type: 'primitive', value: 'Регион' });
    expect(config.columns['cfRegion'].hasSorting).toBe(false);
  });

  it('reads a custom value from the row attributes, a text "false" as no, and an option as it is', () => {
    expect(cell(config, 'cfRegion', row)).toBe('Tashkent');
    expect(cell(config, 'cfRemote', row)).toBe('Нет');
    expect(cell(config, 'cfShift', row)).toBe('night');
    expect(cell(config, 'cfRegion', { ...row, attributes: {} })).toBe('—');
    expect(cell(config, 'cfRemote', { ...row, attributes: {} })).toBe('—');
  });

  it('names a custom field by its own name in a filter chip', () => {
    expect(describeCondition({ field: 'cfRegion', op: 'eq', value: 'Tashkent' }, META, translate)).toBe('Регион: равно Tashkent');
  });
});
