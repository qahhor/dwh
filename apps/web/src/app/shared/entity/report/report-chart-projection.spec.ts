import { describe, expect, it } from 'vitest';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import type { ReportResult, ReportTrunc } from './entity-reports';
import { reportChartProjection } from './report-chart-projection';

const meta = queryMetaFixture('test.series', [
  metaField('at', '', 'date', { label: 'Date' }),
  metaField('category', '', 'boolean', { label: 'Category' }),
  metaField('currency', '', 'enum', { format: 'currency' }),
]);
function result(dates: (string | null)[], values: (number | null)[], trunc: ReportTrunc = 'month'): ReportResult {
  return {
    groups: [{ field: 'at', implicit: false, trunc }],
    measures: [{ op: 'avg', field: 'value' }],
    rows: dates.map((date, index) => ({ groups: [date], values: [values[index]] })),
    truncated: false,
  };
}
describe('calendar chart projection', () => {
  it.each([
    ['day', '2026-03-28', '2026-03-29'],
    ['week', '2026-03-23', '2026-03-30'],
    ['month', '2026-12-01', '2027-01-01'],
    ['quarter', '2026-10-01', '2027-01-01'],
    ['year', '2026-01-01', '2027-01-01'],
  ] as const)('connects adjacent UTC %s buckets independent of local offsets', (trunc, start, end) => {
    const points = reportChartProjection(result([end, start], [2, 1], trunc), meta, 'line')!;
    expect(points.map((point) => [point.row, point.value, point.connect])).toEqual([
      [1, 1, false],
      [0, 2, true],
    ]);
    expect(points[0].position).toBe(Date.parse(start + 'T00:00:00Z'));
  });
  it('retains NULL and does not interpolate an omitted period', () => {
    const points = reportChartProjection(
      result(['2026-01-01', '2026-02-01', '2026-04-01'], [null, 0, 3]),
      meta,
      'line',
    )!;
    expect(points.map((point) => [point.value, point.connect])).toEqual([
      [null, false],
      [0, false],
      [3, false],
    ]);
  });
  it.each([null, '2026-02-31', 'not a date', '2026-01-01T00:00:00Z'])('rejects an invalid bucket %s', (value) => {
    expect(reportChartProjection(result([value], [1]), meta, 'line')).toBeNull();
  });
  it('requires one unambiguous dimension and one measure', () => {
    expect(reportChartProjection(null, meta, 'line')).toBeNull();
    expect(reportChartProjection(result([], []), null, 'line')).toBeNull();
    expect(reportChartProjection(result(['2026-01-01', '2026-01-01'], [1, 2]), meta, 'line')).toBeNull();
    const value = result(['2026-01-01'], [1]);
    value.groups.push({ field: 'category', implicit: false });
    expect(reportChartProjection(value, meta, 'line')).toBeNull();
  });
  it('allows a single known currency, while a missing currency is ambiguous', () => {
    const value = result(['2026-01-01'], [1]);
    value.groups.push({ field: 'currency', implicit: true });
    value.rows[0].groups.push('USD');
    expect(reportChartProjection(value, meta, 'line')).not.toBeNull();
    value.rows[0].groups[1] = null;
    expect(reportChartProjection(value, meta, 'line')).toBeNull();
  });
  it('does not produce an infinite scale from two finite extreme values', () => {
    expect(reportChartProjection(result(['2026-01-01', '2026-02-01'], [-1e308, 1e308]), meta, 'line')).toBeNull();
  });
});
