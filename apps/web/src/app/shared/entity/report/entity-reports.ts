import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import type { ApiSchema } from '@core/api/api-schema';
import { enumLabel, fieldLabel, type QueryFieldMeta, type QueryListMeta } from '@core/models/query-meta.models';
import { ApiService } from '@core/services/api.service';

/** How a date or a moment is bucketed (ADR-0032 10.2); a moment in UTC. */
export type ReportTrunc = 'day' | 'week' | 'month' | 'quarter' | 'year';
export const REPORT_TRUNCS: readonly ReportTrunc[] = ['day', 'week', 'month', 'quarter', 'year'];

/** What a group answers: the count of its records, or a figure of a number or of money. */
export type ReportOp = 'count' | 'sum' | 'avg' | 'min' | 'max';
export const REPORT_OPS: readonly ReportOp[] = ['count', 'sum', 'avg', 'min', 'max'];

/** How a report is drawn: a table, bars (it needs a grouping) or one figure (it has none). */
export type ReportChart = 'table' | 'bar' | 'kpi';

/** A saved report is a report; a widget is a report shown on the person's dashboard. */
export type ReportKind = 'report' | 'widget';

export interface ReportGroup {
  field: string;
  trunc?: ReportTrunc;
}

export interface ReportMeasure {
  op: ReportOp;
  field?: string;
}

/** What a report asks for, as a saved view keeps it (ADR-0032 10.2). */
export interface ReportState {
  groupBy: ReportGroup[];
  measures: ReportMeasure[];
  /** The list's filter DSL (ADR-0016). */
  filter: unknown[];
  chart: ReportChart;
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
}

/** A widget of the person's dashboard: a report saved on an entity's list. */
export interface ReportWidget extends SavedReport {
  listCode: string;
  entity: string;
}

/** A report's answer: its columns and its rows (groups, then measures, in the columns' order). */
export interface ReportResult {
  groups: { field: string; trunc?: ReportTrunc; implicit: boolean }[];
  measures: { op: ReportOp; field?: string }[];
  rows: { groups: (string | number | boolean | null)[]; values: (number | null)[] }[];
  truncated: boolean;
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

/** A measure's heading: "Count", or "Sum: Total". */
export function measureLabel(
  measure: { op: ReportOp; field?: string },
  meta: QueryListMeta | null,
  translate: (key: string) => string,
): string {
  const op = translate(`ui.report.op.${measure.op}`);
  const field = measure.field ? meta?.fields.find((candidate) => candidate.key === measure.field) : undefined;
  return field ? `${op}: ${fieldLabel(field, translate)}` : op;
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
): string {
  if (value === null) return words.translate('ui.report.empty_group');
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
  run(code: string, state: Pick<ReportState, 'groupBy' | 'measures' | 'filter'>): Observable<ReportResult> {
    const params = {
      groupBy: JSON.stringify(state.groupBy),
      measures: JSON.stringify(state.measures),
      filter: state.filter.length > 0 ? JSON.stringify(state.filter) : undefined,
    };
    return this.api
      .get<ApiSchema<'QueryAggregateResult'>>(`${entityPath(code)}/report`, params, { notifyError: false })
      .pipe(map(result));
  }

  /** A report or widget the person saved, run now. */
  saved(code: string, id: number): Observable<ReportResult> {
    return this.api
      .get<ApiSchema<'QueryAggregateResult'>>(`${entityPath(code)}/reports/${id}`, undefined, { notifyError: false })
      .pipe(map(result));
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
      { name: report.name, kind: report.kind, state: report.state, isDefault: false, lockVersion: report.lockVersion },
      { notifyError: false },
    );
  }

  remove(listCode: string, id: number): Observable<void> {
    return this.api.delete<void>(`${viewsPath(listCode)}/${id}`, { notifyError: false });
  }
}

function result(raw: ApiSchema<'QueryAggregateResult'>): ReportResult {
  return {
    groups: (raw.groups ?? []).map((group) => ({
      field: group.field ?? '',
      trunc: group.trunc as ReportTrunc | undefined,
      implicit: group.implicit === true,
    })),
    measures: (raw.measures ?? []).map((measure) => ({
      op: (measure.op ?? 'count') as ReportOp,
      field: measure.field,
    })),
    rows: (raw.rows ?? []).map((row) => ({
      groups: (row.groups ?? []) as (string | number | boolean | null)[],
      values: (row.values ?? []) as (number | null)[],
    })),
    truncated: raw.truncated === true,
  };
}

function entityPath(code: string): string {
  return `/entities/${encodeURIComponent(code)}`;
}

function viewsPath(listCode: string): string {
  return `/list-views/${encodeURIComponent(listCode)}`;
}
