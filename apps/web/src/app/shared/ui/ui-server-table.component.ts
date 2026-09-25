import { ChangeDetectionStrategy, Component, computed, effect, inject, input, linkedSignal, model, output, TemplateRef, untracked } from '@angular/core';
import { TranslatePipe } from '../../core/services/i18n.service';
import { SMTTableComponent } from '../ui-kit/components/table/table.component';
import { TableColumnResizeEvent, TableConfig, OrderBy } from '../ui-kit/components/table/table.types';
import {
  applyColumnState,
  EMPTY_COLUMN_STATE,
  isDefaultColumnState,
  normalizeColumnState,
  setColumnWidth,
  TableColumnState,
} from '../ui-kit/components/table/column-state';
import { SMTColumnOption, SMTColumnSettingsComponent } from '../ui-kit/components/column-settings';
import { TableColumnStateStore } from '../ui-kit/services/table-column-state.store';
import { ListViewState } from '../list-views/list-views';
import { UiListViewsComponent } from './ui-list-views.component';
import { UiFilterBarComponent } from './ui-filter-bar.component';
import { QueryListMeta } from '../../core/models/query-meta.models';
import { KeysetPager } from '../paging/keyset-pager';
import { UiButtonComponent } from './ui-button.component';
import { UiPaginationComponent } from './ui-pagination.component';

/**
 * A table over a keyset API: the vendored table for the rows, the
 * application's pagination for moving between pages, and a KeysetPager for
 * the state. Everything a list screen otherwise rebuilds by hand is here once:
 *
 * - loading is announced to assistive technology and shown as skeleton rows;
 * - a failed request keeps the rows already on screen and offers a retry of
 *   exactly that request;
 * - an empty result shows the screen's own empty state, but a failed first
 *   page shows only the error: "nothing found" would claim a result the
 *   server never gave;
 * - paging controls are disabled while a page is loading, and after a failed
 *   request until it is retried (its cursors may belong to an old query).
 *
 * With a `columnsId` the person can also show, hide, reorder and resize the
 * columns; the choice is remembered per table under that id. With `views`
 * the columns belong to the list's saved views instead (ADR-0016), and the
 * views menu sits next to the column settings. With `filterMeta` as well, the
 * filter builder and the chips of the active conditions come first.
 *
 * With `selectable`, rows get checkboxes and a bar above the table says how
 * many are chosen and holds the screen's bulk actions (`[bulkActions]`). The
 * choice belongs to the page on screen: moving to another page, reloading or
 * changing the filter clears it, so an action never reaches rows out of sight.
 */
@Component({
  selector: 'ui-server-table',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTTableComponent, UiPaginationComponent, UiButtonComponent, TranslatePipe, SMTColumnSettingsComponent, UiListViewsComponent, UiFilterBarComponent],
  template: `
    @if (columnsId() || views()) {
      <div class="server-table-tools">
        @if (views() && filterMeta(); as meta) {
          <ui-filter-bar [meta]="meta" [conditions]="views()!.filter()" (conditionsChange)="views()!.setFilter($event)" />
        }
        @if (views(); as views) {
          <ui-list-views [state]="views" />
        }
        <smt-column-settings [smtColumns]="columnOptions()" [smtState]="columnState()" (smtStateChange)="saveColumns($event)" />
      </div>
    }
    @if (selectable() && selected().length > 0) {
      <div class="bulk-bar" role="region" data-testid="bulk-bar" [attr.aria-label]="'ui.bulk.region' | t">
        <span class="bulk-count" role="status">{{ 'ui.bulk.selected' | t: { count: selected().length } }}</span>
        <ng-content select="[bulkActions]" />
        <span class="bulk-spacer"></span>
        <ui-button variant="ghost" size="sm" data-testid="bulk-clear" (onClick)="selected.set([])">{{ 'ui.bulk.clear' | t }}</ui-button>
      </div>
    }
    @if (pager().failed()) {
      <div class="inline-feedback" role="alert" [attr.id]="errorId() || null">
        <span class="material-symbols-outlined" aria-hidden="true">error</span>
        <span>{{ errorLabel() }}</span>
        <ui-button variant="secondary" size="sm" icon="refresh" (onClick)="pager().retry()">
          {{ 'ui.table.povtorit' | t }}
        </ui-button>
      </div>
    }
    @if (pager().loading()) {
      <p class="sr-only" role="status" data-server-table-status>{{ loadingLabel() }}</p>
    }
    @if (!failedWithoutRows()) {
      <smt-table
        [smtData]="pager().items()"
        [smtConfig]="shownConfig()"
        [(smtSelectedItems)]="selected"
        [smtIsLoading]="pager().loading()"
        [smtSkeletonRowCount]="pager().pageSize()"
        [smtEmptyTemplate]="emptyTemplate()"
        [smtColumnResizeEnabled]="!!columnsId() || !!views()"
        (smtColumnResize)="onColumnResize($event)"
        (smtSortChange)="sortChange.emit($event)"
        (smtRowClick)="rowClick.emit($event)" />
      <ui-pagination
        [totalItems]="countsPage() ? pager().items().length : pager().total()"
        [cursorItemsArePageLength]="countsPage()"
        [pageSize]="pager().pageSize()"
        [pageSizeOptions]="pageSizeOptions()"
        [currentPage]="pager().page()"
        [cursorMode]="true"
        [hasNextPage]="pager().canGoForward()"
        [disabled]="pager().loading() || pager().failed()"
        (pageChange)="pager().goTo($event)"
        (pageSizeChange)="pager().setPageSize($event)" />
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
    .bulk-bar {
      display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 8px 12px;
      border: 1px solid var(--primary-border); border-radius: var(--radius-md);
      background: var(--primary-subtle); color: var(--text-main);
    }
    .bulk-count { font-weight: 600; }
    .bulk-spacer { flex: 1; }
    .server-table-tools { display: flex; justify-content: flex-end; gap: 8px; flex-wrap: wrap; }
    .inline-feedback {
      display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
      padding: 10px 14px; border-radius: var(--radius-md, 8px);
      background: var(--danger-bg); color: var(--danger-text); border: 1px solid var(--danger-border);
    }
  `],
})
export class UiServerTableComponent<T> {
  readonly pager = input.required<KeysetPager<T>>();
  readonly config = input.required<TableConfig<T>>();
  /** Announced while a page loads, e.g. "Loading the change log". */
  readonly loadingLabel = input.required<string>();
  /** Shown with the retry button when a request fails. */
  readonly errorLabel = input.required<string>();
  readonly errorId = input<string>('');
  readonly emptyTemplate = input<TemplateRef<unknown> | null>(null);
  /**
   * True when the endpoint's `totalEstimated` counts only the rows of the page
   * it returned, not the whole result: the footer then shows the range on
   * screen without claiming a total ("21–40", not "21–40 of 20").
   */
  readonly countsPage = input(false);
  readonly rowClick = output<T>();
  /** A sortable header was clicked; `undefined` when sorting was switched off. */
  readonly sortChange = output<{ column: string; sortBy: OrderBy } | undefined>();
  /** Storage id of the column choice, e.g. `upl.sources`; empty — the columns are fixed. */
  readonly columnsId = input('');
  /** Saved views of the list; when set, they own the column choice instead of `columnsId`. */
  readonly views = input<ListViewState | null>(null);
  /** The list's field metadata; with `views`, it turns on the filter builder. */
  readonly filterMeta = input<QueryListMeta | null>(null);
  /** Columns that cannot be hidden, such as the one that names the row. */
  readonly lockedColumns = input<readonly string[]>([]);

  /** Rows can be chosen for bulk actions. */
  readonly selectable = input(false);
  /** The chosen rows of the page on screen. */
  readonly selected = model<T[]>([]);

  private readonly columnStore = inject(TableColumnStateStore);

  constructor() {
    // A new page of rows (paging, reload, a new filter) ends the old choice.
    effect(() => {
      this.pager().items();
      untracked(() => {
        if (this.selected().length > 0) this.selected.set([]);
      });
    });
  }

  /** The stored choice for this table, reloaded when the id changes. */
  private readonly storedColumns = linkedSignal<TableColumnState>(() => {
    const id = this.columnsId();
    return (id && this.columnStore.load(id)) || EMPTY_COLUMN_STATE;
  });

  /** The choice on screen: the saved views' when the list has them, otherwise the one stored for this table. */
  protected readonly columnState = computed(() => this.views()?.columns() ?? this.storedColumns());

  private readonly customizable = computed(() => Boolean(this.columnsId() || this.views()));

  protected readonly shownConfig = computed(() => {
    const config = this.customizable() ? applyColumnState(this.config(), this.columnState(), this.lockedColumns()) : this.config();
    return this.selectable() ? { ...config, hasMultipleSelection: true } : config;
  });

  /** Labels for the settings panel, from each column's plain header; otherwise its key. */
  protected readonly columnOptions = computed<SMTColumnOption[]>(() => {
    const config = this.config();
    return config.columnsOrder.map(key => {
      const header = config.columns[key]?.header;
      const label = header && header.type === 'primitive' && header.value != null ? String(header.value) : key;
      return { key, label, locked: this.lockedColumns().includes(key) };
    });
  });

  protected saveColumns(state: TableColumnState): void {
    const keys = this.config().columnsOrder;
    const normalized = normalizeColumnState(state, keys, this.lockedColumns());
    const views = this.views();
    if (views) {
      views.setColumns(normalized);
      return;
    }
    this.storedColumns.set(normalized);
    if (isDefaultColumnState(normalized, keys)) {
      this.columnStore.clear(this.columnsId());
    } else {
      this.columnStore.save(this.columnsId(), normalized);
    }
  }

  protected onColumnResize(event: TableColumnResizeEvent): void {
    if (!this.customizable()) return;
    const current = normalizeColumnState(this.columnState(), this.config().columnsOrder, this.lockedColumns());
    this.saveColumns(setColumnWidth(current, event.key, event.widthPx));
  }

  /** The request failed and there is nothing earlier to keep on screen. */
  protected readonly failedWithoutRows = computed(() => this.pager().failed() && this.pager().items().length === 0);

  /** The current size is always offered; otherwise the size picker shows blank. */
  protected readonly pageSizeOptions = computed(() =>
    [...new Set([10, 25, 50, 100, this.pager().pageSize()])].sort((a, b) => a - b));
}
