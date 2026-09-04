# Shared UI components

Angular 22 standalone primitives used by the SmartupCMS application. Source is copied verbatim for SuperDesign context.

## ui-button.component.ts

- Source: `apps/web/src/app/shared/ui/ui-button.component.ts`
- Description: Button primitive with size, variant, loading, disabled, and icon behavior.

```typescript
import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'ui-button',
  standalone: true,
  imports: [CommonModule],
  template: `
    <button
      [type]="type"
      [disabled]="disabled || loading"
      [class]="'btn btn-' + variant + ' btn-' + size"
      [class.btn-full-width]="fullWidth"
      [attr.aria-busy]="loading ? 'true' : null"
      [attr.aria-label]="ariaLabel || null"
      (click)="onClick.emit($event)"
    >
      <span *ngIf="loading" class="spinner" aria-hidden="true"></span>
      <span *ngIf="loading" class="sr-only">Выполняется…</span>
      <span *ngIf="icon && !loading" class="material-symbols-outlined icon" aria-hidden="true">{{ icon }}</span>
      <ng-content></ng-content>
    </button>
  `,
  styles: [`
    button {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      font-family: inherit;
      font-weight: 500;
      border-radius: var(--radius-sm);
      border: 1px solid transparent;
      cursor: pointer;
      transition: all 0.15s ease-in-out;
      white-space: nowrap;
      user-select: none;
    }

    button:focus-visible {
      outline: 2px solid var(--focus-ring, var(--primary));
      outline-offset: 2px;
    }

    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    /* Sizes */
    .btn-sm {
      padding: 4px 8px;
      font-size: 12px;
      height: 28px;
    }

    .btn-md {
      padding: 6px 14px;
      font-size: 13px;
      height: 34px;
    }

    .btn-lg {
      padding: 8px 18px;
      font-size: 14px;
      height: 40px;
    }

    .btn-full-width {
      width: 100%;
    }

    /* Variants */
    .btn-primary {
      background-color: var(--primary);
      color: var(--text-inverse);
    }
    .btn-primary:hover:not(:disabled) {
      background-color: var(--primary-hover);
    }

    .btn-secondary {
      background-color: var(--bg-surface);
      border-color: var(--border-color);
      color: var(--text-main);
    }
    .btn-secondary:hover:not(:disabled) {
      background-color: var(--bg-hover);
    }

    .btn-danger {
      background-color: var(--danger);
      color: var(--text-inverse);
    }
    .btn-danger:hover:not(:disabled) {
      background-color: var(--danger-hover, #b91c1c);
    }

    .btn-ghost {
      background-color: transparent;
      color: var(--text-muted);
    }
    .btn-ghost:hover:not(:disabled) {
      background-color: var(--bg-hover);
      color: var(--text-main);
    }

    .icon {
      font-size: 18px;
    }

    .spinner {
      width: 14px;
      height: 14px;
      border: 2px solid rgba(255, 255, 255, 0.3);
      border-top-color: currentColor;
      border-radius: 50%;
      animation: spin 0.6s linear infinite;
    }

    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      margin: -1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      white-space: nowrap;
      border: 0;
    }

    @keyframes spin {
      to { transform: rotate(360deg); }
    }
  `]
})
export class UiButtonComponent {
  @Input() variant: 'primary' | 'secondary' | 'danger' | 'ghost' = 'primary';
  @Input() size: 'sm' | 'md' | 'lg' = 'md';
  @Input() type: 'button' | 'submit' | 'reset' = 'button';
  @Input() disabled: boolean = false;
  @Input() loading: boolean = false;
  @Input() icon?: string;
  @Input() fullWidth: boolean = false;
  @Input() ariaLabel?: string;

  @Output() onClick = new EventEmitter<MouseEvent>();
}
```

## ui-modal.component.ts

- Source: `apps/web/src/app/shared/ui/ui-modal.component.ts`
- Description: Accessible modal primitive with overlay, focus management, header/body/footer slots.

```typescript
import {
  Component,
  Input,
  Output,
  EventEmitter,
  HostListener,
  OnChanges,
  OnDestroy,
  SimpleChanges
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { A11yModule } from '@angular/cdk/a11y';

@Component({
  selector: 'ui-modal',
  standalone: true,
  imports: [CommonModule, A11yModule],
  template: `
    <div *ngIf="isOpen" class="modal-backdrop" (click)="onBackdropClick($event)">
      <div
        [class]="'modal-dialog modal-' + size"
        role="dialog"
        aria-modal="true"
        [attr.aria-labelledby]="titleId"
        cdkTrapFocus
        [cdkTrapFocusAutoCapture]="true"
      >
        <div class="modal-header">
          <h3 class="modal-title" [id]="titleId">{{ title }}</h3>
          <button
            *ngIf="dismissible"
            type="button"
            class="modal-close"
            (click)="close.emit()"
            [attr.aria-label]="'Закрыть диалог «' + title + '»'"
          >
            <span class="material-symbols-outlined" aria-hidden="true">close</span>
          </button>
        </div>
        <div class="modal-body">
          <ng-content select="[body]"></ng-content>
          <ng-content></ng-content>
        </div>
        <div class="modal-footer" *ngIf="hasFooter">
          <ng-content select="[footer]"></ng-content>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .modal-backdrop {
      position: fixed;
      top: 0;
      left: 0;
      width: 100vw;
      height: 100vh;
      background-color: rgba(15, 23, 42, 0.6);
      backdrop-filter: blur(2px);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 1000;
      padding: 16px;
    }

    .modal-dialog {
      background-color: var(--bg-surface);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow-overlay);
      border: 1px solid var(--border-color);
      width: 100%;
      max-height: 90vh;
      display: flex;
      flex-direction: column;
    }

    .modal-sm { max-width: 400px; }
    .modal-md { max-width: 580px; }
    .modal-lg { max-width: 800px; }
    .modal-xl { max-width: 1100px; }

    .modal-header {
      padding: 14px 18px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid var(--border-color);
    }

    .modal-title {
      font-size: 15px;
      font-weight: 600;
      color: var(--text-main);
    }

    .modal-close {
      background: transparent;
      border: none;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 4px;
      border-radius: var(--radius-sm);
    }
    .modal-close:hover {
      background-color: var(--bg-hover);
      color: var(--text-main);
    }

    .modal-body {
      padding: 18px;
      overflow-y: auto;
      flex: 1;
    }

    .modal-footer {
      padding: 12px 18px;
      border-top: 1px solid var(--border-color);
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 10px;
      background-color: var(--bg-surface);
      border-bottom-left-radius: var(--radius-lg);
      border-bottom-right-radius: var(--radius-lg);
    }

  `]
})
export class UiModalComponent implements OnChanges, OnDestroy {
  private static nextId = 0;
  private static openModalCount = 0;

  readonly titleId = `ui-modal-title-${UiModalComponent.nextId++}`;
  private bodyLocked = false;

  @Input() isOpen: boolean = false;
  @Input() title: string = '';
  @Input() size: 'sm' | 'md' | 'lg' | 'xl' = 'md';
  @Input() hasFooter: boolean = true;
  @Input() dismissible: boolean = true;

  @Output() close = new EventEmitter<void>();

  @HostListener('document:keydown.escape')
  onEscape() {
    if (this.isOpen && this.dismissible) {
      this.close.emit();
    }
  }

  onBackdropClick(event: MouseEvent) {
    if (this.dismissible && (event.target as HTMLElement).classList.contains('modal-backdrop')) {
      this.close.emit();
    }
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['isOpen']) {
      this.syncBodyLock(this.isOpen);
    }
  }

  ngOnDestroy() {
    this.syncBodyLock(false);
  }

  private syncBodyLock(shouldLock: boolean) {
    if (shouldLock === this.bodyLocked) return;

    this.bodyLocked = shouldLock;
    UiModalComponent.openModalCount += shouldLock ? 1 : -1;
    UiModalComponent.openModalCount = Math.max(0, UiModalComponent.openModalCount);
    document.body.classList.toggle('modal-open', UiModalComponent.openModalCount > 0);
  }
}
```

## ui-badge.component.ts

- Source: `apps/web/src/app/shared/ui/ui-badge.component.ts`
- Description: Semantic status badge primitive.

```typescript
import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'ui-badge',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span [class]="'badge badge-' + variant + (dot ? ' has-dot' : '')">
      <span *ngIf="dot" class="dot"></span>
      <ng-content></ng-content>
    </span>
  `,
  styles: [`
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      padding: 2px 8px;
      border-radius: 9999px;
      font-size: 11px;
      font-weight: 500;
      line-height: 1.4;
      white-space: nowrap;
    }

    .dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background-color: currentColor;
    }

    .badge-active, .badge-success {
      background-color: var(--success-bg);
      color: var(--success);
    }

    .badge-passive, .badge-danger {
      background-color: var(--danger-bg);
      color: var(--danger);
    }

    .badge-warning, .badge-high, .badge-urgent {
      background-color: var(--warning-bg);
      color: var(--warning);
    }

    .badge-info, .badge-normal {
      background-color: var(--info-bg);
      color: var(--info);
    }

    .badge-neutral, .badge-low {
      background-color: var(--bg-hover);
      color: var(--text-muted);
    }
  `]
})
export class UiBadgeComponent {
  @Input() variant: string = 'neutral';
  @Input() dot: boolean = false;
}
```

## ui-searchable-select.component.ts

- Source: `apps/web/src/app/shared/ui/ui-searchable-select.component.ts`
- Description: Searchable single-select form control.

```typescript
import { Component, Input, Output, EventEmitter, ElementRef, HostListener, ViewChild, signal } from '@angular/core';
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
      <div class="select-control">
        <button
          #trigger
          type="button"
          class="select-trigger"
          [class.is-open]="isOpen()"
          [class.has-value]="selectedOption() !== null"
          [disabled]="disabled"
          [attr.aria-label]="ariaLabel"
          [attr.aria-expanded]="isOpen()"
          aria-haspopup="listbox"
          [attr.aria-controls]="listboxId"
          (click)="toggleDropdown()"
        >
          <span class="trigger-content">
            <span *ngIf="selectedOption()?.icon" class="material-symbols-outlined trigger-icon" [style.color]="selectedOption()?.color" aria-hidden="true">
              {{ selectedOption()?.icon }}
            </span>
            <span class="trigger-label">
              {{ selectedOption() ? selectedOption()?.label : placeholder }}
            </span>
            <span *ngIf="selectedOption()?.subLabel" class="trigger-sublabel text-muted">
              {{ selectedOption()?.subLabel }}
            </span>
          </span>

          <span class="material-symbols-outlined arrow-icon" aria-hidden="true">
            {{ isOpen() ? 'expand_less' : 'expand_more' }}
          </span>
        </button>
        <button
          *ngIf="allowClear && selectedOption() !== null && !disabled"
          type="button"
          class="clear-btn"
          aria-label="Очистить выбор"
          title="Очистить выбор"
          (click)="clearSelection($event)"
        >
          <span class="material-symbols-outlined" aria-hidden="true">close</span>
        </button>
      </div>

      <!-- Dropdown Popover -->
      <div class="dropdown-popover" *ngIf="isOpen()">
        <!-- Search Input -->
        <div class="search-box">
          <span class="material-symbols-outlined search-ico" aria-hidden="true">search</span>
          <input
            #searchInput
            type="text"
            class="search-input"
            [placeholder]="searchPlaceholder"
            aria-label="Поиск по вариантам"
            [(ngModel)]="searchQuery"
            (click)="$event.stopPropagation()"
          />
          <button *ngIf="searchQuery" type="button" class="mini-clear-btn" aria-label="Очистить поиск" (click)="searchQuery = ''">
            <span class="material-symbols-outlined" aria-hidden="true">close</span>
          </button>
        </div>

        <!-- Options List -->
        <div class="options-list" [id]="listboxId" role="listbox" [attr.aria-label]="ariaLabel">
          <!-- Clear / None Option -->
          <button
            *ngIf="allowClear"
            type="button"
            role="option"
            class="option-item none-option"
            [class.selected]="selectedId === null || selectedId === undefined"
            [attr.aria-selected]="selectedId === null || selectedId === undefined"
            (click)="selectOption(null)"
          >
            <span class="option-label text-muted">{{ emptyLabel }}</span>
          </button>

          <!-- Filtered Options -->
          <button
            *ngFor="let opt of filteredOptions()"
            type="button"
            role="option"
            class="option-item"
            [class.selected]="opt.id === selectedId"
            [attr.aria-selected]="opt.id === selectedId"
            (click)="selectOption(opt.id)"
          >
            <div class="opt-left">
              <span *ngIf="opt.icon" class="material-symbols-outlined opt-icon" [style.color]="opt.color" aria-hidden="true">
                {{ opt.icon }}
              </span>
              <span class="opt-avatar" *ngIf="!opt.icon">
                {{ getInitials(opt.label) }}
              </span>
              <span class="opt-label">{{ opt.label }}</span>
              <span *ngIf="opt.subLabel" class="opt-sublabel text-muted">{{ opt.subLabel }}</span>
            </div>
            <span *ngIf="opt.id === selectedId" class="material-symbols-outlined check-ico" aria-hidden="true">check</span>
          </button>

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

    .select-control {
      position: relative;
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
      transition: all 0.15s ease;
      text-align: left;
    }
    .select-trigger:hover:not(:disabled) {
      border-color: var(--text-muted);
    }
    .select-trigger.is-open {
      border-color: var(--primary);
      box-shadow: 0 0 0 2px var(--primary-subtle);
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

    .clear-btn {
      position: absolute;
      top: 50%;
      right: 28px;
      transform: translateY(-50%);
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
    .select-trigger.has-value { padding-right: 50px; }

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
      width: 100%;
      border: 0;
      background: transparent;
      color: inherit;
      font-family: inherit;
      text-align: left;
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
  private static nextId = 0;

  @Input() options: SelectOption[] = [];
  @Input() selectedId: any = null;
  @Input() placeholder: string = 'Выберите из списка...';
  @Input() searchPlaceholder: string = 'Поиск...';
  @Input() emptyLabel: string = 'Не выбрано / Снять выбор';
  @Input() allowClear: boolean = true;
  @Input() disabled: boolean = false;
  @Input() ariaLabel: string = 'Выбор значения';

  @Output() selectedIdChange = new EventEmitter<any>();

  readonly isOpen = signal<boolean>(false);
  readonly listboxId = `ui-searchable-select-${UiSearchableSelectComponent.nextId++}`;
  searchQuery = '';

  @ViewChild('trigger') private trigger?: ElementRef<HTMLButtonElement>;

  constructor(private elementRef: ElementRef) {}

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    if (!this.elementRef.nativeElement.contains(event.target)) {
      this.isOpen.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (!this.isOpen()) return;
    this.closeAndFocusTrigger();
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
    this.closeAndFocusTrigger();
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

  private closeAndFocusTrigger() {
    this.isOpen.set(false);
    queueMicrotask(() => this.trigger?.nativeElement.focus());
  }
}
```

## ui-user-multi-select.component.ts

- Source: `apps/web/src/app/shared/ui/ui-user-multi-select.component.ts`
- Description: Multi-select control for users with selected tags and search.

```typescript
import { Component, Input, Output, EventEmitter, signal, ElementRef, HostListener, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { User } from '../../core/models/auth.models';

@Component({
  selector: 'ui-user-multi-select',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="user-multi-select-container">
      <!-- Selected Users Tag List -->
      <div class="selected-tags-box">
        <div *ngFor="let u of getSelectedUsers()" class="user-tag">
          <span class="user-avatar-mini">{{ getInitials(u.name) }}</span>
          <span class="user-name">{{ u.name }}</span>
          <button type="button" class="tag-remove-btn" (click)="removeUser(u.id)" [attr.aria-label]="'Удалить ' + u.name" title="Удалить">
            <span class="material-symbols-outlined" aria-hidden="true">close</span>
          </button>
        </div>

        <button
          #trigger
          type="button"
          class="add-user-btn"
          [attr.aria-label]="ariaLabel"
          aria-haspopup="listbox"
          [attr.aria-expanded]="isOpen()"
          [attr.aria-controls]="listboxId"
          (click)="toggleDropdown($event)"
        >
          <span class="material-symbols-outlined ico" aria-hidden="true">person_add</span>
          <span>{{ selectedUserIds.length === 0 ? placeholder : '+ Добавить' }}</span>
        </button>
      </div>

      <!-- Autocomplete Dropdown Panel -->
      <div class="dropdown-panel" *ngIf="isOpen()">
        <div class="search-row">
          <span class="material-symbols-outlined search-ico" aria-hidden="true">search</span>
          <input
            #searchInput
            type="text"
            class="search-input"
            [placeholder]="searchPlaceholder"
            aria-label="Поиск сотрудников"
            [(ngModel)]="searchQuery"
            (click)="$event.stopPropagation()"
          />
          <button *ngIf="searchQuery" type="button" class="clear-btn" aria-label="Очистить поиск" (click)="searchQuery = ''">
            <span class="material-symbols-outlined" aria-hidden="true">close</span>
          </button>
        </div>

        <div class="users-options-list" [id]="listboxId" role="listbox" aria-label="Пользователи" aria-multiselectable="true">
          <button
            *ngFor="let u of filteredUsers()"
            type="button"
            role="option"
            class="user-option"
            [class.selected]="isSelected(u.id)"
            [attr.aria-selected]="isSelected(u.id)"
            (click)="toggleUser(u.id)"
          >
            <div class="user-info-left">
              <span class="user-avatar">{{ getInitials(u.name) }}</span>
              <div class="user-texts">
                <span class="u-name">{{ u.name }}</span>
                <span class="u-login text-muted">&#64;{{ u.login }}</span>
              </div>
            </div>

            <span class="material-symbols-outlined check-ico" *ngIf="isSelected(u.id)" aria-hidden="true">check</span>
          </button>

          <div *ngIf="filteredUsers().length === 0" class="no-options">
            Сотрудники не найдены
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .user-multi-select-container {
      position: relative;
      width: 100%;
    }

    .selected-tags-box {
      min-height: 36px;
      padding: 4px 6px;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px;
      transition: border-color 0.15s ease;
    }
    .selected-tags-box:hover { border-color: var(--primary); }

    .user-tag {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 1px 6px 1px 2px;
      font-size: 11px;
      font-weight: 500;
      color: var(--text-main);
    }
    .user-avatar-mini {
      width: 16px;
      height: 16px;
      border-radius: 50%;
      background-color: var(--primary);
      color: #fff;
      font-size: 9px;
      font-weight: 600;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .user-name { max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

    .tag-remove-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      padding: 0;
      margin-left: 2px;
    }
    .tag-remove-btn:hover { color: var(--danger); }
    .tag-remove-btn .material-symbols-outlined { font-size: 13px; }

    .add-user-btn {
      border: none;
      background: transparent;
      color: var(--primary);
      font-size: 11px;
      font-weight: 500;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 3px;
      padding: 2px 6px;
      border-radius: var(--radius-xs);
    }
    .add-user-btn:hover { background-color: var(--primary-subtle); }
    .add-user-btn .ico { font-size: 14px; }

    /* Dropdown Panel */
    .dropdown-panel {
      position: absolute;
      top: calc(100% + 4px);
      left: 0;
      right: 0;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      box-shadow: var(--shadow-lg);
      z-index: 1050;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .search-row {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 6px 8px;
      background-color: var(--bg-hover);
      border-bottom: 1px solid var(--border-color);
    }
    .search-ico { font-size: 16px; color: var(--text-muted); }
    .search-input {
      flex: 1;
      border: none;
      background: transparent;
      font-size: 12px;
      color: var(--text-main);
    }
    .clear-btn { border: none; background: transparent; color: var(--text-muted); cursor: pointer; display: flex; }
    .clear-btn .material-symbols-outlined { font-size: 14px; }

    .users-options-list {
      max-height: 180px;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
    }

    .user-option {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 6px 10px;
      cursor: pointer;
      transition: background 0.1s ease;
      width: 100%;
      border: 0;
      background: transparent;
      color: inherit;
      font-family: inherit;
      text-align: left;
    }
    .user-option:hover { background-color: var(--bg-hover); }
    .user-option.selected { background-color: rgba(99,102,241,0.08); }

    .user-info-left { display: flex; align-items: center; gap: 8px; }
    .user-avatar {
      width: 22px;
      height: 22px;
      border-radius: 50%;
      background-color: var(--primary);
      color: #fff;
      font-size: 10px;
      font-weight: 600;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .user-texts { display: flex; flex-direction: column; }
    .u-name { font-size: 12px; font-weight: 500; color: var(--text-main); }
    .u-login { font-size: 10px; }

    .check-ico { font-size: 16px; color: var(--primary); }
    .no-options { padding: 12px; text-align: center; color: var(--text-muted); font-size: 12px; }
    .text-muted { color: var(--text-muted); }
  `]
})
export class UiUserMultiSelectComponent {
  private static nextId = 0;

  @Input() users: User[] = [];
  @Input() selectedUserIds: number[] = [];
  @Input() placeholder = 'Добавить наблюдателей...';
  @Input() searchPlaceholder = 'Поиск сотрудника...';
  @Input() ariaLabel = 'Выбрать пользователей';
  @Output() selectedUserIdsChange = new EventEmitter<number[]>();

  isOpen = signal<boolean>(false);
  readonly listboxId = `ui-user-multi-select-${UiUserMultiSelectComponent.nextId++}`;
  searchQuery = '';

  @ViewChild('trigger') private trigger?: ElementRef<HTMLButtonElement>;

  constructor(private elementRef: ElementRef) {}

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    if (!this.elementRef.nativeElement.contains(event.target)) {
      this.isOpen.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (!this.isOpen()) return;
    this.closeAndFocusTrigger();
  }

  toggleDropdown(event: MouseEvent) {
    event.stopPropagation();
    this.isOpen.update(v => !v);
  }

  getSelectedUsers(): User[] {
    const idSet = new Set(this.selectedUserIds);
    return this.users.filter(u => idSet.has(u.id));
  }

  filteredUsers(): User[] {
    // Only active users
    const activeUsers = this.users.filter(u => u.state !== 'P' && !u.name.toLowerCase().includes('deleted user'));
    const q = this.searchQuery.trim().toLowerCase();
    if (!q) return activeUsers;

    return activeUsers.filter(u =>
      u.name.toLowerCase().includes(q) ||
      u.login.toLowerCase().includes(q)
    );
  }

  isSelected(userId: number): boolean {
    return this.selectedUserIds.includes(userId);
  }

  toggleUser(userId: number) {
    const list = [...this.selectedUserIds];
    const idx = list.indexOf(userId);
    if (idx >= 0) {
      list.splice(idx, 1);
    } else {
      list.push(userId);
    }
    this.selectedUserIds = list;
    this.selectedUserIdsChange.emit(list);
  }

  removeUser(userId: number) {
    const list = this.selectedUserIds.filter(id => id !== userId);
    this.selectedUserIds = list;
    this.selectedUserIdsChange.emit(list);
  }

  getInitials(name: string): string {
    if (!name) return 'U';
    const parts = name.trim().split(' ');
    if (parts.length >= 2) {
      return (parts[0][0] + parts[1][0]).toUpperCase();
    }
    return name.substring(0, 2).toUpperCase();
  }

  private closeAndFocusTrigger() {
    this.isOpen.set(false);
    queueMicrotask(() => this.trigger?.nativeElement.focus());
  }
}
```
