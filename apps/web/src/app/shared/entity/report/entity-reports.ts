import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import type { ApiSchema } from '@core/api/api-schema';
import {
  enumLabel,
  fieldLabel,
  type QueryCondition,
  type QueryFieldMeta,
  type QueryListMeta,
} from '@core/models/query-meta.models';
import { readFilterDsl } from '@core/services/query-meta.service';
import { ApiService } from '@core/services/api.service';
import type { ReportCompare, ReportParameter, ReportShare } from './report-analytics';

/** How a date or a moment is bucketed (ADR-0032 10.2); a moment in UTC. */
export type ReportTrunc = 'day' | 'week' | 'month' | 'quarter' | 'year';
export const REPORT_TRUNCS: readonly ReportTrunc[] = ['day', 'week', 'month', 'quarter', 'year'];

/** What a group answers: the count of its records or of a field's different values, or a figure of a number or of money. */
export type ReportOp = 'count' | 'count_distinct' | 'sum' | 'avg' | 'min' | 'max' | 'formula';
export const REPORT_OPS: readonly ReportOp[] = ['count', 'count_distinct', 'sum', 'avg', 'min', 'max', 'formula'];

/** How a report is drawn: a table, bars (it needs a grouping) or one figure (it has none). */
export type ReportChart = 'table' | 'bar' | 'kpi' | 'line' | 'horizontalBar';

/** A saved report is a report; a widget is a report shown on the person's dashboard. */
export type ReportKind = 'report' | 'widget';

export interface ReportGroup {
  field: string;
  trunc?: ReportTrunc;
}

export interface ReportMeasure {
  op: ReportOp;
  field?: string;
  /** The part of a total the measure shows (ADR-0044); only a count or a sum. */
  share?: ReportShare;
}

/**
 * The order of a report's groups: by their values (the default), by their words, or by a measure named by its
 * position; ascending unless `desc`.
 */
export interface ReportSort {
  by: 'group' | 'label' | 'measure';
  measure?: number;
  direction: 'asc' | 'desc';
}

/** The tops of groups the builder offers. */
export const REPORT_TOPS: readonly number[] = [5, 10, 20, 50, 100];

/** What a report asks for, as a saved view keeps it (ADR-0032 10.2). */
export interface ReportState {
  dataset?: string;
  groupBy: ReportGroup[];
  measures: ReportMeasure[];
  /** The list's filter DSL (ADR-0016). */
  filter: unknown[];
  chart: ReportChart;
  /** The order of the groups; none — by their values. */
  sort?: ReportSort;
  /** The top of the groups kept, in their order; none — every group up to the server's bound. */
  limit?: number;
  /** The first grouping makes the rows, the second the columns (ADR-0044). */
  pivot?: boolean;
  /** The totals of the groups. */
  totals?: boolean;
  /** Two periods compared. */
  compare?: ReportCompare;
  /** What a reader fills when opening the saved report. */
  parameters?: ReportParameter[];
}

/** The server's limits of one report. */
export const MAX_GROUPS = 2;
export const MAX_MEASURES = 4;

/** A report or widget the person saved on a list. */
export interface SavedReport {
  id: number;
  kind: ReportKind;
  name: string;
  state: ReportState;
  lockVersion: number;
  canEdit?: boolean;
  canShare?: boolean;
  shared?: boolean;
}

/** A widget of the person's dashboard: a report saved on an entity's list. */
export interface ReportWidget extends SavedReport {
  listCode: string;
  entity: string;
}

/** A row of a report's answer: the values of its groups and measures, and the server's words of the groups. */
export interface ReportRow {
  groups: (string | number | boolean | null)[];
  values: (number | null)[];
  /** The server's words for the group values (a choice, a list item, a user, a record), where it names them. */
  labels?: (string | null)[];
}

/** A measure column: a figure, its share of a total, or one of the periods of a comparison. */
export interface ReportMeasureColumn {
  op: ReportOp;
  field?: string;
  share?: ReportShare;
  period?: 'current' | 'previous' | 'change' | 'change_percent';
}

/** A total: of a pivot's row, of its column, or of every group (ADR-0044). */
export interface ReportTotal extends ReportRow {
  kind: 'row' | 'column' | 'grand';
}

/** The two periods of a comparison, their first and last days included. */
export interface ReportComparison {
  field: string;
  period: string;
  against: string;
  currentFrom: string;
  currentTo: string;
  previousFrom: string;
  previousTo: string;
}

/** A report's answer: its columns and its rows (groups, then measures, in the columns' order). */
export interface ReportResult {
  groups: { field: string; trunc?: ReportTrunc; implicit: boolean }[];
  measures: ReportMeasureColumn[];
  rows: ReportRow[];
  truncated: boolean;
  totals?: ReportTotal[];
  pivot?: boolean;
  comparison?: ReportComparison | null;
}

/** A list field a report can group by: a choice, a yes/no, a date, a moment or a reference (ADR-0032 10.2). */
export function groupable(field: QueryFieldMeta): boolean {
  if (field.type === 'enum' || field.type === 'boolean' || field.type === 'date' || field.type === 'instant') {
    return true;
  }
  return field.type === 'number' && !!field.ref;
}

/** A list field a report can sum: a number or the amount of money, never a reference's key. */
export function measurable(field: QueryFieldMeta): boolean {
  return field.type === 'number' && !field.ref && (!field.format || field.format === 'money');
}

/** Whether the field is bucketed by a date part. */
export function dated(field: QueryFieldMeta | undefined): boolean {
  return field?.type === 'date' || field?.type === 'instant';
}

/** A measure's heading: "Count", or "Sum: Total", with its share ("share of total, %") or its period. */
export function measureLabel(
  measure: ReportMeasureColumn,
  meta: QueryListMeta | null,
  translate: (key: string) => string,
): string {
  const op = translate(`ui.report.op.${measure.op}`);
  const field = measure.field ? meta?.fields.find((candidate) => candidate.key === measure.field) : undefined;
  const base = field ? `${op}: ${fieldLabel(field, translate)}` : op;
  const share = measure.share ? ` — ${translate(`ui.report.share.${measure.share}`)}` : '';
  const period = measure.period ? ` — ${translate(`ui.report.period.${measure.period}`)}` : '';
  return base + share + period;
}

/** A group's value in words: a choice by its label, yes/no, a bucket by its first day, a reference by its name. */
export function groupText(
  value: string | number | boolean | null,
  field: QueryFieldMeta | undefined,
  trunc: ReportTrunc | undefined,
  words: {
    translate: (key: string) => string;
    date: (iso: string, options: Intl.DateTimeFormatOptions) => string;
    refName: (field: QueryFieldMeta, key: unknown) => string | null;
  },
  label?: string | null,
): string {
  if (value === null) return words.translate('ui.report.empty_group');
  if (label) return label;
  if (typeof value === 'boolean') return words.translate(value ? 'common.yes' : 'common.no');
  if (trunc) return bucketText(String(value), trunc, words.date);
  if (field?.ref) return words.refName(field, value) ?? `#${value}`;
  if (field?.type === 'enum') return enumLabel(field, String(value), words.translate);
  return String(value);
}

function bucketText(
  iso: string,
  trunc: ReportTrunc,
  date: (iso: string, options: Intl.DateTimeFormatOptions) => string,
): string {
  switch (trunc) {
    case 'year':
      return iso.slice(0, 4);
    case 'quarter':
      return `${iso.slice(0, 4)} Q${Math.floor((Number(iso.slice(5, 7)) - 1) / 3) + 1}`;
    case 'month':
      return date(iso, { year: 'numeric', month: 'long', timeZone: 'UTC' });
    default:
      return date(iso, { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' });
  }
}

/** A report with its parts in the canonical order the server keeps. */
export function emptyReport(): ReportState {
  return { groupBy: [], measures: [{ op: 'count' }], filter: [], chart: 'table' };
}

/**
 * Reports over entity lists (ADR-0032 10.2): the builder's request `/entities/{code}/report`, a saved report
 * `/entities/{code}/reports/{id}`, the person's reports and widgets as views of the list `/list-views/{list}` and the
 * dashboard's widgets `/report-widgets`. A screen shows its own message for each failure.
 */
@Injectable({ providedIn: 'root' })
export class EntityReportsApi {
  private readonly api = inject(ApiService);

  /** The report as asked, under the entity's scope and field rights. */
  run(code: string, state: ReportAsked): Observable<ReportResult> {
    const params = reportParams(state);
    return this.api
      .get<ApiSchema<'QueryAggregateResult'>>(`${entityPath(code)}/report`, params, { notifyError: false })
      .pipe(map(reportResult));
  }

  /** A report or widget the person saved, run now with the values of its parameters (ADR-0044). */
  saved(code: string, id: number, parameters?: Record<string, unknown>): Observable<ReportResult> {
    const params =
      parameters && Object.keys(parameters).length ? { parameters: JSON.stringify(parameters) } : undefined;
    return this.api
      .get<ApiSchema<'QueryAggregateResult'>>(`${entityPath(code)}/reports/${id}`, params, { notifyError: false })
      .pipe(map(reportResult));
  }

  /** The person's widgets, by name. */
  widgets(): Observable<ReportWidget[]> {
    return this.api.get<ReportWidget[]>('/report-widgets', undefined, { notifyError: false });
  }

  /** The person's reports and widgets of a list, by name. */
  list(listCode: string): Observable<SavedReport[]> {
    return this.api.get<SavedReport[]>(viewsPath(listCode), { kind: 'report,widget' }, { notifyError: false });
  }

  save(listCode: string, name: string, kind: ReportKind, state: ReportState): Observable<SavedReport> {
    return this.api.post<SavedReport>(
      viewsPath(listCode),
      { name, kind, state, isDefault: false },
      { notifyError: false },
    );
  }

  /** Writes a saved report again: a widget becomes a report when it leaves the dashboard, and back. */
  update(listCode: string, report: SavedReport): Observable<SavedReport> {
    return this.api.put<SavedReport>(
      `${viewsPath(listCode)}/${report.id}`,
      { name: report.name, kind: report.kind, state: report.state, isDefault: false },
      { notifyError: false, ifMatch: report.lockVersion },
    );
  }

  remove(listCode: string, id: number, revision: number): Observable<void> {
    return this.api.delete<void>(`${viewsPath(listCode)}/${id}`, { notifyError: false, ifMatch: revision });
  }
}

/** What a report run asks for. */
export type ReportAsked = Pick<
  ReportState,
  'groupBy' | 'measures' | 'filter' | 'dataset' | 'sort' | 'limit' | 'pivot' | 'totals' | 'compare'
>;

/** The request parameters of a report as asked: the JSON parts, the order and top, and its analytic shape. */
export function reportParams(state: ReportAsked): Record<string, string | number | boolean | undefined> {
  return {
    dataset: state.dataset,
    groupBy: JSON.stringify(state.groupBy),
    measures: JSON.stringify(state.measures),
    filter: state.filter.length > 0 ? JSON.stringify(state.filter) : undefined,
    sort: state.sort ? JSON.stringify(state.sort) : undefined,
    limit: state.limit,
    pivot: state.pivot || undefined,
    totals: state.totals || undefined,
    compare: state.compare ? JSON.stringify(state.compare) : undefined,
  };
}

/**
 * The filter of the records behind one row of a report (drill-down): the report's own filter and, for each group, its
 * value — a date bucket as its first day up to the next one, an empty group as an empty field. Null when the list
 * cannot say it exactly — a filter whose conditions hold when any of them does, a group the list does not filter by —
 * so a drill-down never shows more records than the row counts; the server still applies the reader's scope.
 */
export function drillFilter(
  result: ReportResult,
  row: number | ReportRow,
  meta: QueryListMeta,
  filter: readonly unknown[],
): QueryCondition[] | null {
  const read = readFilterDsl(filter);
  const exact = read.match === 'any' ? read.conditions.length <= 1 : read.conditions.length === filter.length;
  const values = typeof row === 'number' ? result.rows[row] : row;
  if (!exact || !values || result.comparison) return null;
  const total = (values as Partial<ReportTotal>).kind;
  const conditions: QueryCondition[] = read.conditions.map((condition) => ({ ...condition }));
  const explicit = result.groups.map((group, index) => (group.implicit ? -1 : index)).filter((index) => index >= 0);
  for (let index = 0; index < result.groups.length; index++) {
    const group = result.groups[index];
    // A total keeps the groups it adds up empty: they narrow nothing (ADR-0044).
    if (total === 'grand' && !group.implicit) continue;
    if (total === 'row' && index === explicit[1]) continue;
    if (total === 'column' && index === explicit[0]) continue;
    const field = meta.fields.find((candidate) => candidate.key === group.field);
    const condition = field ? groupConditions(field, group.trunc, values.groups[index], values.labels?.[index]) : null;
    if (!condition) return null;
    conditions.push(...condition);
  }
  return conditions;
}

/** The conditions of one group value, or null when the field does not take them. */
function groupConditions(
  field: QueryFieldMeta,
  trunc: ReportTrunc | undefined,
  value: string | number | boolean | null,
  label: string | null | undefined,
): QueryCondition[] | null {
  if (value === null) return field.ops.includes('empty') ? [{ field: field.key, op: 'empty' }] : null;
  if (trunc) {
    if (!field.ops.includes('gte') || !field.ops.includes('lt')) return null;
    const [from, to] = bucketRange(String(value), trunc);
    const moment = (day: string) => (field.type === 'instant' ? day + 'T00:00:00Z' : day);
    return [
      { field: field.key, op: 'gte', value: moment(from) },
      { field: field.key, op: 'lt', value: moment(to) },
    ];
  }
  if (!field.ops.includes('eq')) return null;
  return [{ field: field.key, op: 'eq', value, ...(label ? { label } : {}) }];
}

/** The first day of a date bucket and the first day of the next one, as ISO dates. */
function bucketRange(iso: string, trunc: ReportTrunc): [string, string] {
  const start = new Date(iso.slice(0, 10) + 'T00:00:00Z');
  const end = new Date(start);
  switch (trunc) {
    case 'day':
      end.setUTCDate(end.getUTCDate() + 1);
      break;
    case 'week':
      end.setUTCDate(end.getUTCDate() + 7);
      break;
    case 'month':
      end.setUTCMonth(end.getUTCMonth() + 1);
      break;
    case 'quarter':
      end.setUTCMonth(end.getUTCMonth() + 3);
      break;
    default:
      end.setUTCFullYear(end.getUTCFullYear() + 1);
  }
  return [start.toISOString().slice(0, 10), end.toISOString().slice(0, 10)];
}

export function reportResult(raw: ApiSchema<'QueryAggregateResult'>): ReportResult {
  return {
    groups: (raw.groups ?? []).map((group) => ({
      field: group.field ?? '',
      trunc: group.trunc as ReportTrunc | undefined,
      implicit: group.implicit === true,
    })),
    measures: (raw.measures ?? []).map((measure) => ({
      op: (measure.op ?? 'count') as ReportOp,
      field: measure.field,
      ...(measure.share ? { share: measure.share as ReportShare } : {}),
      ...(measure.period ? { period: measure.period as ReportMeasureColumn['period'] } : {}),
    })),
    rows: (raw.rows ?? []).map(reportRow),
    truncated: raw.truncated === true,
    totals: (raw.totals ?? []).map((total) => ({ ...reportRow(total), kind: total.kind ?? 'grand' })),
    pivot: raw.pivot === true,
    comparison: (raw.comparison as ReportComparison | undefined) ?? null,
  };
}

function reportRow(row: { groups?: unknown[]; values?: unknown[]; labels?: unknown[] }): ReportRow {
  return {
    groups: (row.groups ?? []) as (string | number | boolean | null)[],
    values: (row.values ?? []) as (number | null)[],
    labels: (row.labels ?? []) as (string | null)[],
  };
}

function entityPath(code: string): string {
  return `/entities/${encodeURIComponent(code)}`;
}

function viewsPath(listCode: string): string {
  return `/list-views/${encodeURIComponent(listCode)}`;
}
