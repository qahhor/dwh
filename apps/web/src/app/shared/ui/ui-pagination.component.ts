import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'ui-pagination',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <nav class="pagination-bar" *ngIf="totalItems > 0" aria-label="Пагинация">
      <!-- Left: Item Range & Total Counter -->
      <div class="pagination-info" role="status" aria-live="polite" aria-atomic="true">
        <span class="range-text">
          Показано <strong class="highlight font-mono">{{ startItem }}–{{ endItem }}</strong> из <strong class="highlight font-mono">{{ totalItems }}</strong>
        </span>
      </div>

      <!-- Right: Page Size Selector & Navigation Buttons -->
      <div class="pagination-controls">
        <!-- Page Size Selector -->
        <div class="page-size-picker" *ngIf="showPageSize">
          <label class="size-label" [for]="pageSizeSelectId">Строк:</label>
          <select
            [id]="pageSizeSelectId"
            class="size-select"
            [ngModel]="pageSize"
            (ngModelChange)="onPageSizeChange($event)"
          >
            <option *ngFor="let opt of pageSizeOptions" [ngValue]="opt">{{ opt }}</option>
          </select>
        </div>

        <!-- Navigation Buttons & Page Numbers -->
        <div class="page-nav">
          <!-- First Page -->
          <button
            type="button"
            class="nav-btn"
            aria-label="Первая страница"
            title="Первая страница"
            [disabled]="currentPage === 1"
            (click)="goToPage(1)"
          >
            <span class="material-symbols-outlined icon" aria-hidden="true">first_page</span>
          </button>

          <!-- Prev Page -->
          <button
            type="button"
            class="nav-btn"
            aria-label="Предыдущая страница"
            title="Предыдущая страница"
            [disabled]="currentPage === 1"
            (click)="goToPage(currentPage - 1)"
          >
            <span class="material-symbols-outlined icon" aria-hidden="true">chevron_left</span>
          </button>

          <!-- Page Numbers -->
          <div class="page-numbers">
            <ng-container *ngFor="let p of visiblePages">
              <span *ngIf="p === -1" class="ellipsis" aria-hidden="true">…</span>
              <button
                *ngIf="p !== -1"
                type="button"
                class="page-btn font-mono"
                [class.active]="p === currentPage"
                [attr.aria-label]="'Страница ' + p"
                [attr.aria-current]="p === currentPage ? 'page' : null"
                (click)="goToPage(p)"
              >
                {{ p }}
              </button>
            </ng-container>
          </div>

          <!-- Next Page -->
          <button
            type="button"
            class="nav-btn"
            aria-label="Следующая страница"
            title="Следующая страница"
            [disabled]="currentPage >= totalPages"
            (click)="goToPage(currentPage + 1)"
          >
            <span class="material-symbols-outlined icon" aria-hidden="true">chevron_right</span>
          </button>

          <!-- Last Page -->
          <button
            type="button"
            class="nav-btn"
            aria-label="Последняя страница"
            title="Последняя страница"
            [disabled]="currentPage >= totalPages"
            (click)="goToPage(totalPages)"
          >
            <span class="material-symbols-outlined icon" aria-hidden="true">last_page</span>
          </button>
        </div>
      </div>
    </nav>
  `,
  styles: [`
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
      height: 28px;
      padding: 2px 6px;
      border-radius: var(--radius-xs);
      border: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      color: var(--text-main);
      font-size: 12px;
      cursor: pointer;
    }
    .size-select:focus {
      border-color: var(--primary);
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
      color: #ffffff;
      border-color: var(--primary);
      font-weight: 600;
    }

    .ellipsis {
      padding: 0 4px;
      color: var(--text-muted);
      font-size: 12px;
      user-select: none;
    }

    .font-mono {
      font-family: monospace;
    }
  `]
})
export class UiPaginationComponent implements OnChanges {
  private static nextId = 0;

  @Input() totalItems: number = 0;
  @Input() currentPage: number = 1;
  @Input() pageSize: number = 10;
  @Input() pageSizeOptions: number[] = [10, 25, 50, 100];
  @Input() showPageSize: boolean = true;

  @Output() pageChange = new EventEmitter<number>();
  @Output() pageSizeChange = new EventEmitter<number>();

  totalPages: number = 1;
  startItem: number = 0;
  endItem: number = 0;
  visiblePages: number[] = [];
  readonly pageSizeSelectId = `ui-pagination-size-${UiPaginationComponent.nextId++}`;

  ngOnChanges(changes: SimpleChanges): void {
    this.calculatePagination();
  }

  calculatePagination() {
    if (this.totalItems <= 0) {
      this.totalPages = 1;
      this.startItem = 0;
      this.endItem = 0;
      this.visiblePages = [];
      return;
    }

    this.totalPages = Math.max(1, Math.ceil(this.totalItems / this.pageSize));

    if (this.currentPage > this.totalPages) {
      this.currentPage = this.totalPages;
    } else if (this.currentPage < 1) {
      this.currentPage = 1;
    }

    this.startItem = (this.currentPage - 1) * this.pageSize + 1;
    this.endItem = Math.min(this.currentPage * this.pageSize, this.totalItems);

    this.visiblePages = this.generatePageNumbers(this.currentPage, this.totalPages);
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

  goToPage(page: number) {
    if (page >= 1 && page <= this.totalPages && page !== this.currentPage) {
      this.currentPage = page;
      this.calculatePagination();
      this.pageChange.emit(this.currentPage);
    }
  }

  onPageSizeChange(newSize: number) {
    this.pageSize = newSize;
    this.currentPage = 1;
    this.calculatePagination();
    this.pageSizeChange.emit(this.pageSize);
  }
}
