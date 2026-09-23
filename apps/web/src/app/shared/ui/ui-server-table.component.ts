import { ChangeDetectionStrategy, Component, computed, input, output, TemplateRef } from '@angular/core';
import { TranslatePipe } from '../../core/services/i18n.service';
import { SMTTableComponent } from '../ui-kit/components/table/table.component';
import { TableConfig } from '../ui-kit/components/table/table.types';
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
 * - an empty result shows the screen's own empty state;
 * - paging controls are disabled while a page is loading.
 */
@Component({
  selector: 'ui-server-table',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTTableComponent, UiPaginationComponent, UiButtonComponent, TranslatePipe],
  template: `
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
    <smt-table
      [smtData]="pager().items()"
      [smtConfig]="config()"
      [smtIsLoading]="pager().loading()"
      [smtSkeletonRowCount]="pager().pageSize()"
      [smtEmptyTemplate]="emptyTemplate()"
      [smtColumnResizeEnabled]="false"
      (smtRowClick)="rowClick.emit($event)" />
    <ui-pagination
      [totalItems]="pager().total()"
      [pageSize]="pager().pageSize()"
      [pageSizeOptions]="pageSizeOptions()"
      [currentPage]="pager().page()"
      [cursorMode]="true"
      [hasNextPage]="pager().canGoForward()"
      [disabled]="pager().loading()"
      (pageChange)="pager().goTo($event)"
      (pageSizeChange)="pager().setPageSize($event)" />
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
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
  readonly rowClick = output<T>();

  /** The current size is always offered; otherwise the size picker shows blank. */
  protected readonly pageSizeOptions = computed(() =>
    [...new Set([10, 25, 50, 100, this.pager().pageSize()])].sort((a, b) => a - b));
}
