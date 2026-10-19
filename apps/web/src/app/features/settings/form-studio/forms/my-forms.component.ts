import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  Signal,
  TemplateRef,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTTagComponent } from '@shared/ui-kit/components/tag';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { MyFormsStore, type StudioFormSummary } from './my-forms.store';

/** «My forms»: the live forms with their status, versions and lifecycle actions (ADR-0041). */
@Component({
  selector: 'app-my-forms',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    RouterLink,
    TranslatePipe,
    SMTAlertComponent,
    SMTButtonComponent,
    SMTTagComponent,
    UiLocalTableComponent,
    UiPageHeaderComponent,
  ],
  providers: [MyFormsStore],
  templateUrl: './my-forms.component.html',
  styleUrl: './my-forms.component.css',
})
export class MyFormsComponent implements OnInit {
  readonly store = inject(MyFormsStore);
  private readonly i18n = inject(I18nService);
  private readonly permissions = inject(PermissionService);

  private readonly titleCell = viewChild.required<TemplateRef<unknown>>('titleCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('statusCell');
  private readonly versionCell = viewChild.required<TemplateRef<unknown>>('versionCell');
  private readonly recordsCell = viewChild.required<TemplateRef<unknown>>('recordsCell');
  private readonly modifiedCell = viewChild.required<TemplateRef<unknown>>('modifiedCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');

  /** The automations of a form open for whoever may read them (ADR-0046). */
  readonly mayAutomate = computed(() => this.permissions.hasPermission('studio.automations', 'view'));
  readonly config = computed<TableConfig<StudioFormSummary>>(() => {
    this.i18n.currentLang();
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, form) => form.entityCode,
      ariaLabel: this.i18n.translate('studio.forms.title'),
      layout: 'fit',
      columns: {
        title: { header: header('studio.forms.column.title'), content: cell(this.titleCell) },
        status: { header: header('studio.forms.column.status'), content: cell(this.statusCell) },
        version: { header: header('studio.forms.column.version'), content: cell(this.versionCell), width: '120px' },
        records: { header: header('studio.forms.column.records'), content: cell(this.recordsCell), width: '110px' },
        modifiedAt: { header: header('studio.forms.column.modified'), content: cell(this.modifiedCell) },
        actions: {
          header: header('common.actions'),
          content: cell(this.actionsCell),
          align: 'right',
        },
      },
      columnsOrder: ['title', 'status', 'version', 'records', 'modifiedAt', 'actions'],
    };
  });

  /** Every loaded form sorts on the page; more pages load on request. */
  readonly sortValues = {
    title: (form: StudioFormSummary) => this.store.title(form),
    status: (form: StudioFormSummary) => form.status,
    version: (form: StudioFormSummary) => form.activeVersion,
    records: (form: StudioFormSummary) => form.records,
    modifiedAt: (form: StudioFormSummary) => form.modifiedAt,
  };

  ngOnInit(): void {
    this.store.load();
  }

  /** The records of a served form open on the general screen; a quarantined or broken one is not served. */
  opens(form: StudioFormSummary): boolean {
    return !form.quarantined && !form.broken;
  }
}
