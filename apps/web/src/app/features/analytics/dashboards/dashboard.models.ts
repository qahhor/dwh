import type { ApiSchema } from '@core/api/api-schema';
import type { ReportViewItem } from '@shared/entity/report/report-catalog.api';
export type DashboardWidgetWrite = Omit<ApiSchema<'DashboardWidgetRequest'>, 'width'> & { width: 1 | 2 };
export type DashboardWrite = Omit<ApiSchema<'DashboardRequest'>, 'widgets'> & { widgets: DashboardWidgetWrite[] };
export type DashboardSummary = ApiSchema<'DashboardSummary'>;
export type DashboardWidget = Omit<ApiSchema<'DashboardWidget'>, 'width' | 'report'> & {
  width: 1 | 2;
  report?: ReportViewItem;
};
export type DashboardDetail = Omit<ApiSchema<'DashboardDetail'>, 'widgets'> & { widgets: DashboardWidget[] };
export interface DashboardPeriod {
  from: string;
  to: string;
}
