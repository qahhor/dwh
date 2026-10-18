import type { QueryListMeta } from '@core/models/query-meta.models';
import type { SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import type { SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import {
  REPORT_TOPS,
  measureLabel,
  type ReportChart,
  type ReportMeasure,
  type ReportSort,
  type ReportState,
} from './entity-reports';

/**
 * The order of a report's groups and the top it keeps, as the builder offers them (plan 10/10, the form and report
 * builder): by the groups' values (the default), by their words either way, or by each measure either way; the top is
 * one of a few counts. The server checks both again and orders by words once it has read them.
 */

/** The order and the top a run asks for: only for a grouped report, and an order by a measure it has. */
export function reportOrder(
  groups: number,
  measures: readonly ReportMeasure[],
  sort: ReportSort | null,
  limit: number | null,
): Pick<ReportState, 'sort' | 'limit'> {
  if (groups === 0) return {};
  const kept = sort && (sort.by !== 'measure' || (sort.measure ?? 0) < measures.length) ? sort : null;
  return { ...(kept ? { sort: kept } : {}), ...(limit ? { limit } : {}) };
}

/** The orders offered, by option id: `group`, `label:asc`, `measure:0:desc`. */
export function sortOptions(
  measures: readonly ReportMeasure[],
  meta: QueryListMeta | null,
  translate: (key: string, params?: Record<string, string | number>) => string,
): SMTSelectOption<string>[] {
  return [
    { id: 'group', label: translate('ui.report.sort_group') },
    { id: 'label:asc', label: translate('ui.report.sort_label_asc') },
    { id: 'label:desc', label: translate('ui.report.sort_label_desc') },
    ...measures.flatMap((measure, index) => {
      const name = measureLabel(measure, meta, (key) => translate(key));
      return [
        { id: 'measure:' + index + ':desc', label: translate('ui.report.sort_measure_desc', { measure: name }) },
        { id: 'measure:' + index + ':asc', label: translate('ui.report.sort_measure_asc', { measure: name }) },
      ];
    }),
  ];
}

/** The option id of an order. */
export function sortValue(sort: ReportSort | undefined): string {
  if (!sort || sort.by === 'group') return 'group';
  return sort.by === 'label' ? 'label:' + sort.direction : 'measure:' + (sort.measure ?? 0) + ':' + sort.direction;
}

/** The order an option id names; null for the groups' values. */
export function parseSort(value: string | null): ReportSort | null {
  const [by, first, second] = (value ?? 'group').split(':');
  if (by === 'label') return { by: 'label', direction: first === 'desc' ? 'desc' : 'asc' };
  if (by === 'measure') return { by: 'measure', measure: Number(first), direction: second === 'asc' ? 'asc' : 'desc' };
  return null;
}

/** The tops offered. */
export function topOptions(
  translate: (key: string, params?: Record<string, number>) => string,
): SMTSelectOption<number>[] {
  return REPORT_TOPS.map((top) => ({ id: top, label: translate('ui.report.top_n', { n: top }) }));
}

/**
 * The charts offered for a report: a table always, bars with a grouping, a line for one dated grouping and one
 * measure, horizontal bars for one other grouping and one measure, one figure without a grouping (ADR-0039).
 */
export function chartOptions(
  groups: number,
  firstDated: boolean,
  measures: number,
  translate: (key: string) => string,
): SMTRadioOption<ReportChart>[] {
  const single = groups === 1 && measures === 1;
  return [
    { value: 'table', label: translate('ui.report.chart_table'), icon: 'table' },
    { value: 'bar', label: translate('ui.report.chart_bar'), icon: 'bar_chart', disabled: groups === 0 },
    { value: 'line', label: translate('ui.report.chart_line'), icon: 'show_chart', disabled: !single || !firstDated },
    {
      value: 'horizontalBar',
      label: translate('ui.report.chart_horizontal_bar'),
      icon: 'bar_chart',
      disabled: !single || firstDated,
    },
    { value: 'kpi', label: translate('ui.report.chart_kpi'), icon: 'pin', disabled: groups > 0 },
  ];
}
