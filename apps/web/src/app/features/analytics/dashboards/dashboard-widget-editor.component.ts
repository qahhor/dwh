import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  output,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { fieldLabel } from '@core/models/query-meta.models';
import { QueryMetaService } from '@core/services/query-meta.service';
import type { ReportViewItem } from '@shared/entity/report/report-catalog.api';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import type { DashboardWidgetWrite } from './dashboard.models';

@Component({
  selector: 'app-dashboard-widget-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTButtonComponent, SMTControlComponent, SMTSelectComponent, TranslatePipe],
  templateUrl: './dashboard-widget-editor.component.html',
  styleUrl: './dashboard-widget-editor.component.css',
})
export class DashboardWidgetEditorComponent {
  private readonly metadata = inject(QueryMetaService);
  private readonly i18n = inject(I18nService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly item = input.required<DashboardWidgetWrite>();

  readonly report = input<ReportViewItem>();
  readonly index = input(0);
  readonly count = input(1);
  readonly disabled = input(false);

  readonly changed = output<DashboardWidgetWrite>();
  readonly moved = output<number>();
  readonly removed = output<void>();

  readonly title = computed(() => this.report()?.name ?? this.i18n.translate('analytics.panels.unavailable'));
  readonly fields = computed<SMTSelectOption<string>[]>(() =>
    this.meta.hasValue()
      ? this.meta
          .value()
          .fields.filter((field) => field.type === 'date' || field.type === 'instant')
          .map((field) => ({ id: field.key, label: fieldLabel(field, (key) => this.i18n.translate(key)) }))
      : [],
  );
  readonly widths = computed<SMTSelectOption<1 | 2>[]>(() => [
    { id: 1, label: this.i18n.translate('analytics.panels.normal') },
    { id: 2, label: this.i18n.translate('analytics.panels.wide') },
  ]);

  readonly meta = rxResource({
    params: () => this.report(),
    stream: ({ params }) =>
      params.state.dataset
        ? this.metadata.report(params.entity, params.state.dataset)
        : this.metadata.get(params.listCode),
  });

  setField(periodField: string | null): void {
    const { viewId, width } = this.item();
    this.changed.emit({ viewId, width, ...(periodField ? { periodField } : {}) });
  }
  setWidth(width: 1 | 2 | null): void {
    if (width) this.changed.emit({ ...this.item(), width });
  }
  move(delta: number): void {
    const previous = this.host.nativeElement.ownerDocument.activeElement as HTMLElement | null;
    this.moved.emit(delta);
    afterNextRender(
      () => {
        if (previous && this.host.nativeElement.contains(previous) && !previous.matches(':disabled')) previous.focus();
        else this.host.nativeElement.querySelector<HTMLElement>('button:not([disabled])')?.focus();
      },
      { injector: this.injector },
    );
  }
}
