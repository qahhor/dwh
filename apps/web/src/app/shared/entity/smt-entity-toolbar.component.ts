import { ChangeDetectionStrategy, Component, computed, inject, input, model, output, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import type { FormMeta } from '../../core/models/form-meta.models';
import type { QueryListMeta } from '../../core/models/query-meta.models';
import { EntitiesApi } from './entities.api';
import { canDo, hasCapability } from '../../core/services/form-meta.service';
import { I18nService, TranslatePipe } from '../../core/services/i18n.service';
import { BULK_MAX_IDS, BulkResult } from '../bulk/bulk';
import type { ListViewState } from '../list-views/list-views';
import { UiBulkResultComponent } from '../ui/ui-bulk-result.component';
import { UiExportButtonComponent } from '../ui/ui-export-button.component';
import { UiListViewsComponent } from '../ui/ui-list-views.component';
import { SMTButtonComponent } from '../ui-kit/components/button';
import { SMTModalService } from '../ui-kit/components/modal';

/**
 * What an entity's declaration promises its list screen (ADR-0019, roadmap item 56), drawn from `form-meta`
 * with no wiring per screen: saved views, the export of the list as it stands, and deleting the chosen records
 * at once — each only when the entity declares it and, for the delete, when the viewer holds its right. The
 * screen keeps its own list; it hands over its view state, list metadata and search, and the chosen ids.
 */
@Component({
  selector: 'smt-entity-toolbar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTButtonComponent, UiListViewsComponent, UiExportButtonComponent, UiBulkResultComponent],
  host: { class: 'smt-entity-toolbar' },
  template: `
    @if (views(); as state) {
      @if (showViews()) {
        <ui-list-views [state]="state" />
      }
      @if (showExport()) {
        <ui-export-button [views]="state" [meta]="listMeta()" [search]="search()" />
      }
    }
    @if (canBulkDelete() && selected().length > 0) {
      <button
        smt-button
        type="button"
        smtVariant="danger"
        smtSize="sm"
        smtIcon="delete"
        data-testid="entity-bulk-delete"
        [smtLoading]="busy()"
        (click)="deleteSelected()"
      >
        {{ 'ui.entity_toolbar.delete_selected' | t: { n: selected().length } }}
      </button>
      <button smt-button type="button" smtVariant="ghost" smtSize="sm" [disabled]="busy()" (click)="selected.set([])">
        {{ 'ui.entity_toolbar.clear_selection' | t }}
      </button>
    }
    <ui-bulk-result [result]="result()" (closed)="result.set(null)" />
  `,
  styles: [':host { display: inline-flex; flex-wrap: wrap; align-items: center; gap: 8px; }'],
})
export class SMTEntityToolbarComponent {
  private readonly entities = inject(EntitiesApi);

  private readonly i18n = inject(I18nService);

  private readonly modal = inject(SMTModalService);

  readonly meta = input<FormMeta | null>(null);

  /** The list's view state, when the screen keeps one. */
  readonly views = input<ListViewState | null>(null);

  /** The list's field metadata (`query-meta`), which the export needs for its columns. */
  readonly listMeta = input<QueryListMeta | null>(null);

  /** The screen's search text, kept by the export. */
  readonly search = input<string | null>(null);

  /** Emitted after a bulk action ran, so the screen reloads its list. */
  readonly bulkDone = output<BulkResult>();

  /** The chosen records' ids. */
  readonly selected = model<number[]>([]);

  readonly busy = signal(false);

  /** The last bulk action's result, when some records failed. */
  readonly result = signal<BulkResult | null>(null);

  readonly showViews = computed(() => hasCapability(this.meta(), 'saved_views'));

  readonly showExport = computed(() => hasCapability(this.meta(), 'export') && !!this.listMeta());

  /** Whether the screen should offer choosing records: the entity has bulk actions the viewer may take. */
  readonly canBulkDelete = computed(() => hasCapability(this.meta(), 'bulk') && canDo(this.meta(), 'delete'));

  deleteSelected(): void {
    const meta = this.meta();
    const ids = this.selected().slice(0, BULK_MAX_IDS);
    if (!meta || ids.length === 0 || this.busy()) return;
    const t = (key: string, params?: Record<string, string | number>) => this.i18n.translate(key, params);
    this.modal
      .confirm({
        title: t('ui.entity_toolbar.delete_title'),
        message: t('ui.entity_toolbar.delete_confirm', { n: ids.length }),
        yesLabel: t('common.delete'),
        noLabel: t('common.cancel'),
        destructive: true,
        action: () => this.runDelete(meta.code, ids),
      })
      .subscribe();
  }

  private runDelete(code: string, ids: number[]): Observable<BulkResult> {
    this.busy.set(true);
    return this.entities.bulkDelete(code, ids).pipe(
      tap({
        next: (result) => {
          this.busy.set(false);
          this.selected.set([]);
          this.result.set(result.failed > 0 ? result : null);
          this.bulkDone.emit(result);
        },
        error: () => this.busy.set(false),
      }),
    );
  }
}
