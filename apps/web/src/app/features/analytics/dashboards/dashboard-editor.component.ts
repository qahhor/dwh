import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { ReportCatalogApi, type ReportViewItem } from '@shared/entity/report/report-catalog.api';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { DashboardWidgetEditorComponent } from './dashboard-widget-editor.component';
import { DashboardStore, DASHBOARD_WIDGET_LIMIT } from './dashboard.store';
import type { DashboardWidgetWrite } from './dashboard.models';

@Component({
  selector: 'app-dashboard-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    SMTControlComponent,
    SMTInputComponent,
    SMTSelectComponent,
    DashboardWidgetEditorComponent,
  ],
  templateUrl: './dashboard-editor.component.html',
  styleUrl: './dashboard-editor.component.css',
})
export class DashboardEditorComponent {
  readonly store = inject(DashboardStore);
  private readonly catalog = inject(ReportCatalogApi);
  private readonly i18n = inject(I18nService);

  readonly selected = signal<number | null>(null);

  readonly options = computed(() =>
    this.reports
      .items()
      .filter((report) => !this.store.draft()?.widgets.some((widget) => widget.viewId === report.id))
      .map((report) => ({
        id: report.id,
        label: report.name,
        subLabel: report.shared ? this.i18n.translate('ui.report.shared') : undefined,
      })),
  );

  readonly limit = DASHBOARD_WIDGET_LIMIT;
  readonly reports = new KeysetPager<ReportViewItem>((cursor, limit) => this.catalog.list(undefined, cursor, limit), {
    destroyRef: inject(DestroyRef),
  });

  constructor() {
    this.reports.first();
  }

  report(viewId: number): ReportViewItem | undefined {
    return (
      this.reports.items().find((report) => report.id === viewId) ??
      this.store.selected()?.widgets.find((widget) => widget.viewId === viewId)?.report
    );
  }
  add(): void {
    const id = this.selected();
    if (id !== null) {
      this.store.add(id);
      this.selected.set(null);
    }
  }
  configure(index: number, item: DashboardWidgetWrite): void {
    this.store.configure(index, item.periodField ?? null, item.width);
  }
}
