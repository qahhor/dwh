import type { QueryListMeta } from '@core/models/query-meta.models';
import type { ReportResult, ReportTrunc } from './entity-reports';

export interface ReportPlotPoint {
  row: number;
  value: number | null;
  position: number;
  connect: boolean;
}

/** Projects a single measure without reaggregating its values (ADR-0039). */
export function reportChartProjection(
  result: ReportResult | null,
  meta: QueryListMeta | null,
  kind: 'line' | 'horizontalBar',
): ReportPlotPoint[] | null {
  if (!result || !meta || result.truncated || result.measures.length !== 1 || result.rows.length > 1000) return null;
  const groups = result.groups.map((group, index) => ({ ...group, index }));
  const chosen = groups.filter((group) => !group.implicit);
  if (chosen.length !== 1 || groups.length > 2) return null;
  const group = chosen[0];
  const field = meta.fields.find((item) => item.key === group.field);
  if (!field) return null;
  const temporal = field.type === 'date' || field.type === 'instant';
  if (kind === 'line' ? !temporal || !group.trunc : temporal || !['enum', 'boolean', 'number'].includes(field.type))
    return null;
  if (field.type === 'number' && !field.ref) return null;
  if (
    groups.some((item) => {
      const currency = item.implicit || meta.fields.find((entry) => entry.key === item.field)?.format === 'currency';
      return (
        currency &&
        (new Set(result.rows.map((row) => JSON.stringify(row.groups[item.index]))).size !== 1 ||
          result.rows.some((row) => row.groups[item.index] == null))
      );
    })
  )
    return null;
  if (result.rows.some((row) => row.values.length !== 1 || (row.values[0] !== null && !Number.isFinite(row.values[0]))))
    return null;
  const values = result.rows.flatMap((row) => (row.values[0] === null ? [] : [row.values[0]]));
  if (!Number.isFinite(Math.max(0, ...values) - Math.min(0, ...values))) return null;
  const keys = new Set(result.rows.map((row) => JSON.stringify(row.groups[group.index])));
  if (keys.size !== result.rows.length) return null;
  const points = result.rows.map((row, index) => ({
    row: index,
    value: row.values[0],
    position: kind === 'line' ? bucketTime(row.groups[group.index]) : index,
    connect: false,
  }));
  if (points.some((point) => !Number.isFinite(point.position))) return null;
  if (kind === 'line') {
    points.sort((left, right) => left.position - right.position);
    points.forEach((point, index) => {
      const previous = points[index - 1];
      point.connect =
        !!previous &&
        previous.value !== null &&
        point.value !== null &&
        nextBucket(previous.position, group.trunc!) === point.position;
    });
  }
  return points;
}

function bucketTime(value: unknown): number {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return NaN;
  const time = Date.parse(value + 'T00:00:00Z');
  return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === value ? time : NaN;
}

function nextBucket(time: number, trunc: ReportTrunc): number {
  const date = new Date(time);
  if (trunc === 'day' || trunc === 'week') date.setUTCDate(date.getUTCDate() + (trunc === 'week' ? 7 : 1));
  else date.setUTCMonth(date.getUTCMonth() + (trunc === 'year' ? 12 : trunc === 'quarter' ? 3 : 1));
  return date.getTime();
}
