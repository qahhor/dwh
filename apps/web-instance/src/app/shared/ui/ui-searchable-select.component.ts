import { Component, Input, Output, EventEmitter, ElementRef, HostListener, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

export interface SelectOption {
  id: any;
  label: string;
  subLabel?: string;
  icon?: string;
  color?: string;
}

@Component({
  selector: 'ui-searchable-select',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="searchable-select-container" [class.disabled]="disabled">
      <!-- Select Button / Trigger -->
      <button
        type="button"
        class="select-trigger"
        [class.is-open]="isOpen()"
        [class.has-value]="selectedOption() !== null"
        [disabled]="disabled"
        (click)="toggleDropdown()"
      >
        <div class="trigger-content">
          <span *ngIf="selectedOption()?.icon" class="material-symbols-outlined trigger-icon" [style.color]="selectedOption()?.color">
            {{ selectedOption()?.icon }}
          </span>
          <span class="trigger-label">
            {{ selectedOption() ? selectedOption()?.label : placeholder }}
          </span>
          <span *ngIf="selectedOption()?.subLabel" class="trigger-sublabel text-muted">
            {{ selectedOption()?.subLabel }}
          </span>
        </div>

        <div class="trigger-actions">
          <button
            *ngIf="allowClear && selectedOption() !== null && !disabled"
            type="button"
            class="clear-btn"
            title="Очистить выбор"
            (click)="clearSelection($event)"
          >
            <span class="material-symbols-outlined">close</span>
          </button>
          <span class="material-symbols-outlined arrow-icon">
            {{ isOpen() ? 'expand_less' : 'expand_more' }}
          </span>
        </div>
      </button>

      <!-- Dropdown Popover -->
      <div class="dropdown-popover" *ngIf="isOpen()">
        <!-- Search Input -->
        <div class="search-box">
          <span class="material-symbols-outlined search-ico">search</span>
          <input
            #searchInput
            type="text"
            class="search-input"
            [placeholder]="searchPlaceholder"
            [(ngModel)]="searchQuery"
            (click)="$event.stopPropagation()"
          />
          <button *ngIf="searchQuery" type="button" class="mini-clear-btn" (click)="searchQuery = ''">
            <span class="material-symbols-outlined">close</span>
          </button>
        </div>

        <!-- Options List -->
        <div class="options-list">
          <!-- Clear / None Option -->
          <div
            *ngIf="allowClear"
            class="option-item none-option"
            [class.selected]="selectedId === null || selectedId === undefined"
            (click)="selectOption(null)"
          >
            <span class="option-label text-muted">{{ emptyLabel }}</span>
          </div>

          <!-- Filtered Options -->
          <div
            *ngFor="let opt of filteredOptions()"
            class="option-item"
            [class.selected]="opt.id === selectedId"
            (click)="selectOption(opt.id)"
          >
            <div class="opt-left">
              <span *ngIf="opt.icon" class="material-symbols-outlined opt-icon" [style.color]="opt.color">
                {{ opt.icon }}
              </span>
              <span class="opt-avatar" *ngIf="!opt.icon">
                {{ getInitials(opt.label) }}
              </span>
              <span class="opt-label">{{ opt.label }}</span>
              <span *ngIf="opt.subLabel" class="opt-sublabel text-muted">{{ opt.subLabel }}</span>
            </div>
            <span *ngIf="opt.id === selectedId" class="material-symbols-outlined check-ico">check</span>
          </div>

          <!-- Empty Result Hint -->
          <div *ngIf="filteredOptions().length === 0" class="no-results-hint">
            Ничего не найдено
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .searchable-select-container {
      position: relative;
      width: 100%;
    }
    .searchable-select-container.disabled {
      opacity: 0.6;
      pointer-events: none;
    }

    .select-trigger {
      display: flex;
      align-items: center;
      justify-content: space-between;
      width: 100%;
      height: 34px;
      padding: 0 10px;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      color: var(--text-main);
      font-size: 13px;
      cursor: pointer;
      outline: none;
      transition: all 0.15s ease;
      text-align: left;
    }
    .select-trigger:hover:not(:disabled) {
      border-color: var(--text-muted);
    }
    .select-trigger.is-open {
      border-color: var(--primary);
      box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.15);
    }

    .trigger-content {
      display: flex;
      align-items: center;
      gap: 6px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      flex: 1;
    }
    .trigger-icon { font-size: 16px; flex-shrink: 0; }
    .trigger-label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-weight: 400;
    }
    .select-trigger.has-value .trigger-label {
      font-weight: 500;
    }
    .trigger-sublabel { font-size: 11px; flex-shrink: 0; }

    .trigger-actions {
      display: flex;
      align-items: center;
      gap: 4px;
      margin-left: 6px;
      flex-shrink: 0;
    }
    .clear-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      padding: 2px;
      display: flex;
      border-radius: 3px;
    }
    .clear-btn:hover { color: var(--danger); background-color: var(--danger-bg); }
    .clear-btn .material-symbols-outlined { font-size: 14px; }
    .arrow-icon { font-size: 18px; color: var(--text-muted); }

    /* Popover */
    .dropdown-popover {
      position: absolute;
      top: calc(100% + 4px);
      left: 0;
      right: 0;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      box-shadow: var(--shadow-lg);
      z-index: 9999;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      animation: fadeIn 0.12s ease-out;
    }

    @keyframes fadeIn {
      from { opacity: 0; transform: translateY(-4px); }
      to { opacity: 1; transform: translateY(0); }
    }

    .search-box {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 6px 8px;
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
    }
    .search-ico { font-size: 16px; color: var(--text-muted); }
    .search-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 12px;
      color: var(--text-main);
      width: 100%;
      padding: 2px 0;
    }
    .mini-clear-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      padding: 0;
      display: flex;
    }
    .mini-clear-btn .material-symbols-outlined { font-size: 13px; }

    .options-list {
      max-height: 200px;
      overflow-y: auto;
      padding: 4px;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .option-item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 6px 8px;
      border-radius: var(--radius-xs);
      cursor: pointer;
      font-size: 12px;
      transition: background 0.1s ease;
    }
    .option-item:hover {
      background-color: var(--bg-hover);
    }
    .option-item.selected {
      background-color: rgba(99, 102, 241, 0.1);
      color: var(--primary);
      font-weight: 500;
    }
    .option-item.none-option {
      border-bottom: 1px dashed var(--border-color);
      margin-bottom: 2px;
    }

    .opt-left {
      display: flex;
      align-items: center;
      gap: 6px;
      overflow: hidden;
    }
    .opt-icon { font-size: 16px; flex-shrink: 0; }
    .opt-avatar {
      width: 20px;
      height: 20px;
      border-radius: 50%;
      background-color: var(--border-color);
      color: var(--text-main);
      font-size: 10px;
      font-weight: 600;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }
    .opt-label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .opt-sublabel { font-size: 11px; flex-shrink: 0; }
    .check-ico { font-size: 16px; color: var(--primary); }

    .no-results-hint {
      padding: 16px 8px;
      text-align: center;
      color: var(--text-muted);
      font-size: 12px;
    }

    .text-muted { color: var(--text-muted); }
  `]
})
export class UiSearchableSelectComponent {
  @Input() options: SelectOption[] = [];
  @Input() selectedId: any = null;
  @Input() placeholder: string = 'Выберите из списка...';
  @Input() searchPlaceholder: string = 'Поиск...';
  @Input() emptyLabel: string = 'Не выбрано / Снять выбор';
  @Input() allowClear: boolean = true;
  @Input() disabled: boolean = false;

  @Output() selectedIdChange = new EventEmitter<any>();

  readonly isOpen = signal<boolean>(false);
  searchQuery = '';

  constructor(private elementRef: ElementRef) {}

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    if (!this.elementRef.nativeElement.contains(event.target)) {
      this.isOpen.set(false);
    }
  }

  toggleDropdown() {
    if (this.disabled) return;
    this.isOpen.update(v => !v);
    if (this.isOpen()) {
      this.searchQuery = '';
    }
  }

  selectedOption(): SelectOption | null {
    if (this.selectedId === null || this.selectedId === undefined) return null;
    return this.options.find(o => String(o.id) === String(this.selectedId)) || null;
  }

  filteredOptions(): SelectOption[] {
    if (!this.searchQuery.trim()) {
      return this.options;
    }
    const q = this.searchQuery.trim().toLowerCase();
    return this.options.filter(o =>
      o.label.toLowerCase().includes(q) ||
      (o.subLabel && o.subLabel.toLowerCase().includes(q))
    );
  }

  selectOption(id: any) {
    this.selectedId = id;
    this.selectedIdChange.emit(id);
    this.isOpen.set(false);
  }

  clearSelection(event: MouseEvent) {
    event.stopPropagation();
    this.selectOption(null);
  }

  getInitials(name: string): string {
    if (!name) return '?';
    const parts = name.trim().split(' ');
    if (parts.length >= 2) {
      return (parts[0][0] + parts[1][0]).toUpperCase();
    }
    return name.substring(0, 2).toUpperCase();
  }
}
