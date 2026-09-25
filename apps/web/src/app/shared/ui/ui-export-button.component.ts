import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { QueryListMeta } from '../../core/models/query-meta.models';
import { ExportsService } from '../../core/services/exports.service';
import { I18nService, TranslatePipe } from '../../core/services/i18n.service';
import { toQueryParams } from '../../core/services/query-meta.service';
import { ToastService } from '../../core/services/toast.service';
import { ListViewState } from '../list-views/list-views';
import { normalizeColumnState } from '../ui-kit/components/table/column-state';
import { problemText } from './problem-text';
import { UiButtonComponent } from './ui-button.component';

/**
 * "Export to Excel" for a registry list (ADR-0018): the list exactly as on
 * screen — its filter, sort, search and the columns shown, in their order —
 * is queued for export, and the file appears in the person's exports journal.
 * The server re-checks everything, so the button only gathers the state.
 */
@Component({
  selector: 'ui-export-button',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, UiButtonComponent],
  template: `
    <ui-button
      variant="secondary"
      size="sm"
      icon="download"
      data-testid="export-button"
      [loading]="busy()"
      [disabled]="!meta()"
      [ariaLabel]="'exports.button_label' | t"
      (onClick)="export()">
      {{ 'exports.button' | t }}
    </ui-button>
  `,
})
export class UiExportButtonComponent {
  private readonly exports = inject(ExportsService);
  private readonly i18n = inject(I18nService);
  private readonly toast = inject(ToastService);

  readonly views = input.required<ListViewState>();

  readonly meta = input<QueryListMeta | null>(null);
  /** The search box's text, when the screen has one. */
  readonly search = input<string | null>(null);
  /** List options the export keeps, e.g. the files list's scope. */
  readonly options = input<Record<string, string> | null>(null);

  readonly busy = signal(false);

  export(): void {
    const meta = this.meta();
    if (!meta || this.busy()) return;
    const views = this.views();
    const params = toQueryParams({ sort: views.sort(), conditions: views.filter(), search: this.search() ?? '' });
    this.busy.set(true);
    this.exports.request({
      list: meta.code,
      ...params,
      columns: this.shownColumns(meta, views),
      ...(this.options() ? { options: this.options()! } : {}),
      lang: this.i18n.currentLang()
    }).subscribe({
      next: () => {
        this.busy.set(false);
        this.toast.success(this.i18n.translate('exports.queued'));
      },
      error: problem => {
        this.busy.set(false);
        if ((problem as { detail?: string })?.detail === 'EXPORT_BUSY') {
          this.toast.warning(this.i18n.translate('exports.busy'));
        } else {
          this.toast.error(problemText(problem) || this.i18n.translate('exports.request_failed'));
        }
      }
    });
  }

  /** The columns on screen in their order: the list's default columns with the person's order and hiding. */
  private shownColumns(meta: QueryListMeta, views: ListViewState): string[] {
    const keys = meta.fields.filter(field => field.defaultVisible).map(field => field.key);
    const state = normalizeColumnState(views.columns(), keys);
    return state.order.filter(key => !state.hidden.includes(key));
  }
}
