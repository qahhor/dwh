import type { QueryFieldMeta } from '@core/models/query-meta.models';

/**
 * The analytics of a report (ADR-0044) as the web holds them: the share of a count or a sum in its total, a pivot of
 * the two groupings with totals, the totals of a flat report, a comparison of the current period with an earlier one,
 * and the parameters a saved report asks its reader for. The server computes every figure under the reader's scope;
 * the web lays them out.
 */

/** The part of a total a measure shows, in percent: of the whole, of its row or of its column in a pivot. */
export type ReportShare = 'total' | 'row' | 'column';
export const REPORT_SHARES: readonly ReportShare[] = ['total', 'row', 'column'];

/** The current period of a comparison: this month, quarter or year, the year to date, or a range the report names. */
export type ComparePeriod = 'month' | 'quarter' | 'year' | 'ytd' | 'custom';
export const COMPARE_PERIODS: readonly ComparePeriod[] = ['month', 'quarter', 'year', 'ytd', 'custom'];

/** What the current period is compared with: the period just before, or the same period a year earlier. */
export type CompareAgainst = 'previous' | 'year';
export const COMPARE_AGAINST: readonly CompareAgainst[] = ['previous', 'year'];

/** A comparison of two periods of a date or moment field, in the reader's time zone. */
export interface ReportCompare {
  field: string;
  period: ComparePeriod;
  against?: CompareAgainst;
  /** The first and last day of a custom period, ISO dates. */
  from?: string;
  to?: string;
}

/** The ready periods a date parameter may take as its default or its value. */
export type PeriodPreset =
  | 'today'
  | 'this_week'
  | 'this_month'
  | 'last_month'
  | 'this_quarter'
  | 'last_quarter'
  | 'this_year'
  | 'ytd'
  | 'last_7_days'
  | 'last_30_days';
export const PERIOD_PRESETS: readonly PeriodPreset[] = [
  'today',
  'this_week',
  'this_month',
  'last_month',
  'this_quarter',
  'last_quarter',
  'this_year',
  'ytd',
  'last_7_days',
  'last_30_days',
];

/** The value of a period: a ready period or its first and last day. */
export type PeriodValue = { preset: PeriodPreset } | { from: string; to: string };

/** The value of a parameter: a period, the values of a choice or the keys of a reference, or yes/no. */
export type ParameterValue = PeriodValue | (string | number)[] | boolean;

/** A parameter a saved report asks its reader for, bound to a filter of one field. */
export interface ReportParameter {
  key: string;
  field: string;
  required: boolean;
  /** The parameter's own name in each language; none — the field's name. */
  labels?: { ru: string; uz: string; en: string };
  default?: ParameterValue;
}

/** The parameters of a saved report at most. */
export const MAX_PARAMETERS = 8;

/** How a parameter of the field is filled: a period, values of a choice, rows of a reference, or yes/no. */
export type ParameterKind = 'period' | 'choice' | 'reference' | 'flag';

/** The kind of parameter a field takes, or null when it takes none. */
export function parameterKind(field: QueryFieldMeta | undefined): ParameterKind | null {
  if (!field || field.ops.length === 0) return null;
  if (field.type === 'date' || field.type === 'instant') return 'period';
  if (field.type === 'enum') return 'choice';
  if (field.type === 'boolean') return 'flag';
  if (field.type === 'number' && field.ref) return 'reference';
  return null;
}

/** A field whose different values can be counted: a plain value, a choice or a reference. */
export function countable(field: QueryFieldMeta): boolean {
  return (
    field.format !== 'formula' && ['enum', 'boolean', 'number', 'text', 'date', 'instant', 'time'].includes(field.type)
  );
}

/** A measure an administrator's dataset computes by a formula over aggregates (ADR-0044). */
export function computedMeasure(field: QueryFieldMeta): boolean {
  return field.format === 'formula';
}

/** A field a comparison may place records in periods by: a date or a moment it can filter by. */
export function comparable(field: QueryFieldMeta): boolean {
  return (field.type === 'date' || field.type === 'instant') && field.ops.length > 0;
}

/** Whether a value fills a parameter: a period with both days or a preset, at least one value, or yes/no. */
export function filled(value: ParameterValue | undefined | null): boolean {
  if (value === undefined || value === null) return false;
  if (typeof value === 'boolean') return true;
  if (Array.isArray(value)) return value.length > 0;
  return 'preset' in value ? !!value.preset : !!value.from && !!value.to && value.from <= value.to;
}

/** A key for a parameter of the field, unique among the keys taken. */
export function parameterKey(field: string, taken: readonly string[]): string {
  const base = field.replace(/[^a-zA-Z0-9]/g, '').replace(/^[^a-z]+/, '') || 'value';
  let key = base.slice(0, 32);
  for (let n = 2; taken.includes(key); n++) key = `${base.slice(0, 29)}${n}`;
  return key;
}

/** A comparison the server can run: a custom period needs both its days; null otherwise. */
export function completeCompare(compare: ReportCompare | null): ReportCompare | null {
  return compare && (compare.period !== 'custom' || (compare.from && compare.to)) ? compare : null;
}

/** Whether a required parameter has neither a value nor a default: the saved report waits for its reader. */
export function pendingParameters(
  parameters: readonly ReportParameter[],
  values: Record<string, ParameterValue> | null,
): boolean {
  return parameters.some((parameter) => parameter.required && !filled(values?.[parameter.key] ?? parameter.default));
}

/** The pivot, totals and comparison of a report, only the parts it has, as the server keeps them. */
export function reportShape(
  pivot: boolean,
  totals: boolean,
  compare: ReportCompare | null,
  groups: number,
): { pivot?: true; totals?: true; compare?: ReportCompare } {
  return {
    ...(pivot && groups === 2 ? { pivot: true as const } : {}),
    ...(totals && groups > 0 ? { totals: true as const } : {}),
    ...(compare ? { compare } : {}),
  };
}
