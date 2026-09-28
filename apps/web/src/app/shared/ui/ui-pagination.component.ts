import { ChangeDetectionStrategy, Component, OnChanges, SimpleChanges, input, model, output } from '@angular/core';

import { TranslatePipe } from '../../core/services/i18n.service';
import { SMTSelectComponent, SMTSelectOption } from '../ui-kit/components/forms/select';
import { optionsMemo } from '../ui-kit/components/forms/radio-group/radio-options';

@Component({
  selector: 'ui-pagination',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTSelectComponent],
  template: `
    @if (totalItems() > 0 || (cursorMode() && (currentPage() > 1 || hasNextPage()))) {
      <nav class="pagination-bar" [attr.aria-label]="'ui.pagination.paginaciya' | t">
        <!-- Left: Item Range & Total Counter -->
        @if (totalItems() > 0) {
          <div class="pagination-info" role="status" aria-live="polite" aria-atomic="true">
            <span class="range-text">
              {{ 'ui.pagination.pokazano' | t }}
              <strong class="highlight font-mono">{{ startItem }}–{{ endItem }}</strong>
              @if (!cursorMode() || !cursorItemsArePageLength()) {
                {{ 'files.iz' | t }} <strong class="highlight font-mono">{{ totalItems() }}</strong>
              }
            </span>
          </div>
        }

        <!-- Right: Page Size Selector & Navigation Buttons -->
        <div class="pagination-controls">
          <!-- Page Size Selector -->
          @if (showPageSize()) {
            <div class="page-size-picker">
              <label class="size-label" [for]="pageSizeSelectId">{{ 'ui.pagination.strok' | t }}</label>
              <smt-select
                class="size-select"
                [smtTriggerId]="pageSizeSelectId"
                [options]="pageSizeChoices()"
                [allowClear]="false"
                [disabled]="disabled()"
                [value]="pageSize()"
                (valueChange)="onPageSizeChange($event)"
              />
            </div>
          }

          <!-- Navigation Buttons & Page Numbers -->
          <div class="page-nav">
            <!-- First Page -->
            @if (!cursorMode()) {
              <button
                type="button"
                class="nav-btn"
                [attr.aria-label]="'ui.pagination.pervaya_stranica' | t"
                [title]="'ui.pagination.pervaya_stranica' | t"
                [disabled]="disabled() || currentPage() === 1"
                (click)="goToPage(1)"
              >
                <span class="material-symbols-outlined icon" aria-hidden="true">first_page</span>
              </button>
            }

            <!-- Prev Page -->
            <button
              type="button"
              class="nav-btn"
              [attr.aria-label]="'ui.pagination.predyduschaya_stranica' | t"
              [title]="'ui.pagination.predyduschaya_stranica' | t"
              [disabled]="disabled() || currentPage() === 1"
              (click)="goToPage(currentPage() - 1)"
            >
              <span class="material-symbols-outlined icon" aria-hidden="true">chevron_left</span>
            </button>

            <!-- Page Numbers -->
            @if (!cursorMode()) {
              <div class="page-numbers">
                @for (p of visiblePages; track p) {
                  @if (p === -1) {
                    <span class="ellipsis" aria-hidden="true">…</span>
                  }
                  @if (p !== -1) {
                    <button
                      type="button"
                      class="page-btn font-mono"
                      [class.active]="p === currentPage()"
                      [attr.aria-label]="'ui.pagination.page_number' | t: { page: p }"
                      [attr.aria-current]="p === currentPage() ? 'page' : null"
                      [disabled]="disabled()"
                      (click)="goToPage(p)"
                    >
                      {{ p }}
                    </button>
                  }
                }
              </div>
            }

            <!-- aria-label is ignored on a span without a role, so the name is real text. -->
            @if (cursorMode()) {
              <span class="current-page-indicator font-mono" aria-current="page">
                <span aria-hidden="true">{{ currentPage() }}</span>
                <span class="sr-only">{{ 'ui.pagination.page_number' | t: { page: currentPage() } }}</span>
              </span>
            }

            <!-- Next Page -->
            <button
              type="button"
              class="nav-btn"
              [attr.aria-label]="'ui.pagination.sleduyuschaya_stranica' | t"
              [title]="'ui.pagination.sleduyuschaya_stranica' | t"
              [disabled]="disabled() || (cursorMode() ? !hasNextPage() : currentPage() >= totalPages)"
              (click)="goToPage(currentPage() + 1)"
            >
              <span class="material-symbols-outlined icon" aria-hidden="true">chevron_right</span>
            </button>

            <!-- Last Page -->
            @if (!cursorMode()) {
              <button
                type="button"
                class="nav-btn"
                [attr.aria-label]="'ui.pagination.poslednyaya_stranica' | t"
                [title]="'ui.pagination.poslednyaya_stranica' | t"
                [disabled]="disabled() || currentPage() >= totalPages"
                (click)="goToPage(totalPages)"
              >
                <span class="material-symbols-outlined icon" aria-hidden="true">last_page</span>
              </button>
            }
          </div>
        </div>
      </nav>
    }
  `,
  styles: [
    `
      .pagination-bar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        padding: 10px 14px;
        background-color: var(--bg-surface);
        border-top: 1px solid var(--border-color);
        flex-wrap: wrap;
        font-size: 12px;
      }

      .pagination-info {
        color: var(--text-muted);
        font-size: 12px;
      }
      .highlight {
        color: var(--text-main);
        font-weight: 600;
      }

      .pagination-controls {
        display: flex;
        align-items: center;
        gap: 16px;
        flex-wrap: wrap;
      }

      .page-size-picker {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        color: var(--text-muted);
        font-size: 12px;
      }
      .size-select {
        width: 88px;
      }

      .page-nav {
        display: inline-flex;
        align-items: center;
        gap: 3px;
      }

      .nav-btn {
        width: 28px;
        height: 28px;
        border: 1px solid var(--border-color);
        background-color: var(--bg-hover);
        color: var(--text-muted);
        border-radius: var(--radius-xs);
        display: inline-flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        padding: 0;
        transition: all 0.12s ease;
      }
      .nav-btn:hover:not(:disabled) {
        background-color: var(--bg-surface);
        color: var(--text-main);
        border-color: var(--primary);
      }
      .nav-btn:disabled {
        opacity: 0.35;
        cursor: not-allowed;
      }
      .nav-btn .icon {
        font-size: 16px;
      }

      .page-numbers {
        display: inline-flex;
        align-items: center;
        gap: 3px;
        margin: 0 2px;
      }

      .page-btn {
        min-width: 28px;
        height: 28px;
        padding: 0 6px;
        border: 1px solid var(--border-color);
        background-color: var(--bg-hover);
        color: var(--text-muted);
        border-radius: var(--radius-xs);
        font-size: 12px;
        font-weight: 500;
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        transition: all 0.12s ease;
      }
      .page-btn:hover:not(.active) {
        color: var(--text-main);
        border-color: var(--primary);
      }
      .page-btn.active {
        background-color: var(--primary);
        color: var(--on-primary);
        border-color: var(--primary);
        font-weight: 600;
      }

      .ellipsis {
        padding: 0 4px;
        color: var(--text-muted);
        font-size: 12px;
        user-select: none;
      }

      .current-page-indicator {
        min-width: 28px;
        height: 28px;
        padding: 0 6px;
        border-radius: var(--radius-xs);
        background-color: var(--primary);
        color: var(--on-primary);
        display: inline-flex;
        align-items: center;
        justify-content: center;
        font-weight: 600;
      }

      .font-mono {
        font-family: monospace;
      }
    `,
  ],
})
export class UiPaginationComponent implements OnChanges {
  readonly pageSizeOptions = input<number[]>([10, 25, 50, 100]);
  readonly showPageSize = input<boolean>(true);
  readonly cursorItemsArePageLength = input<boolean>(false);
  readonly disabled = input<boolean>(false);

  readonly totalItems = input<number>(0);
  readonly cursorMode = input<boolean>(false);
  readonly hasNextPage = input<boolean>(false);

  readonly pageChange = output<number>();

  /** The page on screen; the bar moves it itself in numbered mode and reports it through pageChange. */
  readonly currentPage = model<number>(1);
  /** Rows per page; choosing another size sets it and emits pageSizeChange. */
  readonly pageSize = model<number>(10);

  private static nextId = 0;

  totalPages: number = 1;
  startItem: number = 0;
  endItem: number = 0;
  visiblePages: number[] = [];
  readonly pageSizeSelectId = `ui-pagination-size-${UiPaginationComponent.nextId++}`;

  private readonly pageSizeMemo = optionsMemo<SMTSelectOption<number>[]>();

  pageSizeChoices(): SMTSelectOption<number>[] {
    return this.pageSizeMemo([this.pageSizeOptions()], () =>
      this.pageSizeOptions().map((size) => ({ id: size, label: String(size) })),
    );
  }

  ngOnChanges(changes: SimpleChanges): void {
    this.calculatePagination();
  }

  calculatePagination() {
    if (this.totalItems() <= 0) {
      this.totalPages = 1;
      this.startItem = 0;
      this.endItem = 0;
      this.visiblePages = [];
      return;
    }

    if (this.cursorMode()) {
      this.currentPage.set(Math.max(1, this.currentPage()));
      this.totalPages = this.currentPage() + (this.hasNextPage() ? 1 : 0);
      this.startItem = (this.currentPage() - 1) * this.pageSize() + 1;
      this.endItem = this.cursorItemsArePageLength()
        ? this.startItem + this.totalItems() - 1
        : Math.min(this.currentPage() * this.pageSize(), this.totalItems());
      this.visiblePages = [];
      return;
    }

    this.totalPages = Math.max(1, Math.ceil(this.totalItems() / this.pageSize()));

    if (this.currentPage() > this.totalPages) {
      this.currentPage.set(this.totalPages);
    } else if (this.currentPage() < 1) {
      this.currentPage.set(1);
    }

    this.startItem = (this.currentPage() - 1) * this.pageSize() + 1;
    this.endItem = Math.min(this.currentPage() * this.pageSize(), this.totalItems());

    this.visiblePages = this.generatePageNumbers(this.currentPage(), this.totalPages);
  }

  goToPage(page: number) {
    if (this.disabled()) return;
    const currentPage = this.currentPage();
    const isCursorStep =
      this.cursorMode() && (page === currentPage - 1 || (page === currentPage + 1 && this.hasNextPage()));
    const isNumberedPage = !this.cursorMode() && page >= 1 && page <= this.totalPages;
    if (page >= 1 && page !== currentPage && (isCursorStep || isNumberedPage)) {
      if (this.cursorMode()) {
        this.pageChange.emit(page);
        return;
      }
      this.currentPage.set(page);
      this.calculatePagination();
      this.pageChange.emit(this.currentPage());
    }
  }

  onPageSizeChange(newSize: number | null) {
    if (newSize === null) return;
    this.pageSize.set(newSize);
    this.currentPage.set(1);
    this.calculatePagination();
  }

  private generatePageNumbers(current: number, total: number): number[] {
    if (total <= 7) {
      return Array.from({ length: total }, (_, i) => i + 1);
    }

    const pages: number[] = [];

    if (current <= 4) {
      for (let i = 1; i <= 5; i++) pages.push(i);
      pages.push(-1); // ellipsis
      pages.push(total);
    } else if (current >= total - 3) {
      pages.push(1);
      pages.push(-1); // ellipsis
      for (let i = total - 4; i <= total; i++) pages.push(i);
    } else {
      pages.push(1);
      pages.push(-1); // ellipsis
      pages.push(current - 1);
      pages.push(current);
      pages.push(current + 1);
      pages.push(-1); // ellipsis
      pages.push(total);
    }

    return pages;
  }
}
