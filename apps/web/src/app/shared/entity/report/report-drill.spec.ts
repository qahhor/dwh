import { describe, expect, it } from 'vitest';
import type { QueryFieldMeta, QueryListMeta } from '@core/models/query-meta.models';
import { drillFilter, groupText, reportParams, type ReportResult } from './entity-reports';
import { chartOptions, parseSort, reportOrder, sortOptions, sortValue, topOptions } from './report-order';

function field(key: string, type: QueryFieldMeta['type'], ops: QueryFieldMeta['ops']): QueryFieldMeta {
  return {
    key,
    labelKey: key,
    type,
    ops,
    sortable: false,
    nullable: true,
    defaultVisible: true,
    enumValues: [],
    enumLabelPrefix: null,
  };
}

const META: QueryListMeta = {
  code: 'studio.clients',
  fields: [
    field('region', 'enum', ['eq', 'in', 'empty']),
    field('visited', 'date', ['eq', 'gte', 'lt', 'empty']),
    field('createdAt', 'instant', ['gte', 'lt']),
    field('title', 'text', ['contains']),
  ],
} as QueryListMeta;

function result(groups: ReportResult['groups'], rows: ReportResult['rows']): ReportResult {
  return { groups, measures: [{ op: 'count' }], rows, truncated: false };
}

describe('drill-down of a report row', () => {
  it('adds the value of each group to the report filter, named by its label', () => {
    const answer = result(
      [{ field: 'region', implicit: false }],
      [
        { groups: ['north'], values: [3], labels: ['Север'] },
        { groups: [null], values: [1], labels: [null] },
      ],
    );
    const base = [{ field: 'title', op: 'contains', value: 'a' }];
    expect(drillFilter(answer, 0, META, base)).toEqual([
      { field: 'title', op: 'contains', value: 'a' },
      { field: 'region', op: 'eq', value: 'north', label: 'Север' },
    ]);
    expect(drillFilter(answer, 1, META, [])).toEqual([{ field: 'region', op: 'empty' }]);
    expect(drillFilter(answer, 5, META, [])).toBeNull();
  });

  it('turns a date bucket into its first day up to the next one, a moment in UTC', () => {
    const months = result(
      [{ field: 'visited', trunc: 'month', implicit: false }],
      [{ groups: ['2026-12-01'], values: [2] }],
    );
    expect(drillFilter(months, 0, META, [])).toEqual([
      { field: 'visited', op: 'gte', value: '2026-12-01' },
      { field: 'visited', op: 'lt', value: '2027-01-01' },
    ]);
    for (const [trunc, end] of [
      ['day', '2026-10-02'],
      ['week', '2026-10-08'],
      ['quarter', '2027-01-01'],
      ['year', '2027-10-01'],
    ] as const) {
      const bucket = result(
        [{ field: 'createdAt', trunc, implicit: false }],
        [{ groups: ['2026-10-01'], values: [1] }],
      );
      expect(drillFilter(bucket, 0, META, [])).toEqual([
        { field: 'createdAt', op: 'gte', value: '2026-10-01T00:00:00Z' },
        { field: 'createdAt', op: 'lt', value: end + 'T00:00:00Z' },
      ]);
    }
  });

  it('opens nothing when the list cannot say the group exactly, so a drill-down never shows more', () => {
    const regions = result([{ field: 'region', implicit: false }], [{ groups: ['north'], values: [3] }]);
    const any = [
      {
        any: [
          { field: 'title', op: 'contains', value: 'a' },
          { field: 'region', op: 'eq', value: 'x' },
        ],
      },
    ];
    expect(drillFilter(regions, 0, META, any)).toBeNull();
    expect(drillFilter(regions, 0, META, [{ any: [{ field: 'title', op: 'contains', value: 'a' }] }])).toEqual([
      { field: 'title', op: 'contains', value: 'a' },
      { field: 'region', op: 'eq', value: 'north' },
    ]);
    expect(drillFilter(regions, 0, META, [{ unknown: true }])).toBeNull();
    const titles = result([{ field: 'title', implicit: false }], [{ groups: ['x'], values: [1] }]);
    expect(drillFilter(titles, 0, META, [])).toBeNull();
    const missing = result([{ field: 'gone', implicit: false }], [{ groups: ['x'], values: [1] }]);
    expect(drillFilter(missing, 0, META, [])).toBeNull();
    const empty = result([{ field: 'createdAt', trunc: 'day', implicit: false }], [{ groups: [null], values: [1] }]);
    expect(drillFilter(empty, 0, META, [])).toBeNull();
  });

  it('asks the report with its order and top and names a group by the server label first', () => {
    expect(
      reportParams({
        groupBy: [{ field: 'region' }],
        measures: [{ op: 'count' }],
        filter: [],
        sort: { by: 'measure', measure: 0, direction: 'desc' },
        limit: 10,
      }),
    ).toEqual({
      dataset: undefined,
      groupBy: '[{"field":"region"}]',
      measures: '[{"op":"count"}]',
      filter: undefined,
      sort: '{"by":"measure","measure":0,"direction":"desc"}',
      limit: 10,
    });
    const words = { translate: (key: string) => key, date: (iso: string) => iso, refName: () => null };
    expect(groupText('north', META.fields[0], undefined, words, 'Север')).toBe('Север');
    expect(groupText(null, META.fields[0], undefined, words, 'Север')).toBe('ui.report.empty_group');
  });
});

describe('order and top of a report', () => {
  const translate = (key: string, params?: Record<string, string | number>) =>
    params ? key + ':' + JSON.stringify(params) : key;

  it('asks an order and a top only for a grouped report and an order by a measure it has', () => {
    const measures = [{ op: 'count' as const }];
    expect(reportOrder(0, measures, { by: 'label', direction: 'asc' }, 5)).toEqual({});
    expect(reportOrder(1, measures, { by: 'measure', measure: 3, direction: 'desc' }, 5)).toEqual({ limit: 5 });
    expect(reportOrder(1, measures, { by: 'label', direction: 'desc' }, null)).toEqual({
      sort: { by: 'label', direction: 'desc' },
    });
  });

  it('names the orders by option ids and reads them back', () => {
    const ids = sortOptions([{ op: 'count' }], null, translate).map((option) => option.id);
    expect(ids).toEqual(['group', 'label:asc', 'label:desc', 'measure:0:desc', 'measure:0:asc']);
    for (const id of ids) expect(sortValue(parseSort(id) ?? undefined)).toBe(id);
    expect(parseSort(null)).toBeNull();
    expect(topOptions(translate).map((option) => option.id)).toEqual([5, 10, 20, 50, 100]);
  });

  it('offers the charts that fit the groups and measures', () => {
    const disabled = (groups: number, dated: boolean, measures: number) =>
      chartOptions(groups, dated, measures, translate)
        .filter((option) => option.disabled)
        .map((option) => option.value);
    expect(disabled(0, false, 1)).toEqual(['bar', 'line', 'horizontalBar']);
    expect(disabled(1, true, 1)).toEqual(['horizontalBar', 'kpi']);
    expect(disabled(1, false, 1)).toEqual(['line', 'kpi']);
    expect(disabled(2, false, 2)).toEqual(['line', 'horizontalBar', 'kpi']);
  });
});

describe('drill-down of a pivot total and a comparison (ADR-0044)', () => {
  const pivot: ReportResult = {
    ...result(
      [
        { field: 'region', implicit: false },
        { field: 'visited', trunc: 'month', implicit: false },
      ],
      [],
    ),
    pivot: true,
  };

  it('narrows a row total by its row, a column total by its column and the grand total by nothing', () => {
    expect(drillFilter(pivot, { kind: 'row', groups: ['north', null], values: [1] } as never, META, [])).toEqual([
      { field: 'region', op: 'eq', value: 'north' },
    ]);
    expect(
      drillFilter(pivot, { kind: 'column', groups: [null, '2026-10-01'], values: [1] } as never, META, []),
    ).toEqual([
      { field: 'visited', op: 'gte', value: '2026-10-01' },
      { field: 'visited', op: 'lt', value: '2026-11-01' },
    ]);
    expect(drillFilter(pivot, { kind: 'grand', groups: [null, null], values: [1] } as never, META, [])).toEqual([]);
  });

  it('opens nothing for a comparison and sends the analytic shape as parameters', () => {
    const compared = { ...result([], [{ groups: [], values: [1, 0, 1, null] }]), comparison: {} as never };
    expect(drillFilter(compared, 0, META, [])).toBeNull();
    expect(
      reportParams({
        groupBy: [],
        measures: [{ op: 'count' }],
        filter: [],
        pivot: true,
        totals: true,
        compare: { field: 'visited', period: 'ytd' },
      }),
    ).toEqual(expect.objectContaining({ pivot: true, totals: true, compare: '{"field":"visited","period":"ytd"}' }));
  });
});
