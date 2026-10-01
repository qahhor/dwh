import { ChangeDetectionStrategy, Component, computed, inject, input, model, output, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import type { FormMeta } from '@core/models/form-meta.models';
import type { QueryCondition, QueryListMeta } from '@core/models/query-meta.models';
import { EntitiesApi } from './entities.api';
import { canDo, hasCapability } from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { BULK_MAX_IDS, BulkResult } from '../bulk/bulk';
import type { ListViewState } from '../list-views/list-views';
import { UiBulkResultComponent } from '../ui/ui-bulk-result.component';
import { UiExportButtonComponent } from '../ui/ui-export-button.component';
import { UiListViewsComponent } from '../ui/ui-list-views.component';
import { SMTButtonComponent } from '../ui-kit/components/button';
import { SMTModalService } from '../ui-kit/components/modal';

/**
 * What an entity's declaration promises its list screen (ADR-0019, roadmap item 56), drawn from `form-meta`
 * with no wiring per screen: saved views, the export of the list as it stands, the archive switch and archiving the
 * chosen records (ADR-0032 5.4), and deleting the chosen records at once — each only when the entity declares it
 * and, for the archive and the delete, when the viewer holds its right. The
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
      @if (showArchive()) {
        <button
          smt-button
          type="button"
          smtSize="sm"
          smtIcon="inventory_2"
          data-testid="entity-archive-toggle"
          [smtVariant]="showingArchive() ? 'secondary' : 'ghost'"
          [attr.aria-pressed]="showingArchive()"
          (click)="toggleArchive()"
        >
          {{ 'ui.entity_toolbar.archive_toggle' | t }}
        </button>
      }
    }
    @if (canBulkArchive() && selected().length > 0) {
      <button
        smt-button
        type="button"
        smtVariant="secondary"
        smtSize="sm"
        smtIcon="inventory_2"
        data-testid="entity-bulk-archive"
        [smtLoading]="busy()"
        (click)="archiveSelected()"
      >
        {{ 'ui.entity_toolbar.archive_selected' | t: { n: selected().length } }}
      </button>
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

  /** The archive switch of an archivable entity's list (ADR-0032 5.4): the list shows archived records instead. */
  readonly showArchive = computed(() => hasCapability(this.meta(), 'archive'));

  readonly showingArchive = computed(() => (this.views()?.filter() ?? []).some(isArchiveFilter));

  /** Archiving the chosen records, offered while the list shows the records in use. */
  readonly canBulkArchive = computed(
    () =>
      hasCapability(this.meta(), 'bulk') &&
      hasCapability(this.meta(), 'archive') &&
      canDo(this.meta(), 'archive') &&
      !this.showingArchive(),
  );

  /** Shows the archive, or the records in use again; the filter travels with a saved view. */
  toggleArchive(): void {
    const state = this.views();
    if (!state) return;
    const others = state.filter().filter((condition) => !isArchiveFilter(condition));
    state.filter.set(this.showingArchive() ? others : [...others, ARCHIVED]);
  }

  /** Archives the chosen records; an archived record can be restored, so nothing is asked first. */
  archiveSelected(): void {
    const meta = this.meta();
    const ids = this.selected().slice(0, BULK_MAX_IDS);
    if (!meta || ids.length === 0 || this.busy()) return;
    this.track(this.entities.bulkArchive(meta.code, ids)).subscribe();
  }

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
    return this.track(this.entities.bulkDelete(code, ids));
  }

  /** A bulk action's run: busy while it goes, then the choice is cleared and the failures are reported. */
  private track(run: Observable<BulkResult>): Observable<BulkResult> {
    this.busy.set(true);
    return run.pipe(
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

/** The filter of the archive switch: the list shows archived records only (ADR-0032 5.4). */
const ARCHIVED: QueryCondition = { field: 'archived', op: 'eq', value: true };

function isArchiveFilter(condition: QueryCondition): boolean {
  return condition.field === ARCHIVED.field && condition.op === ARCHIVED.op && condition.value === true;
}
