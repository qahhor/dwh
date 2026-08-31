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

@Component({
  selector: 'ui-modal',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div *ngIf="isOpen" class="modal-backdrop" (click)="onBackdropClick($event)">
      <div
        [class]="'modal-dialog modal-' + size"
        role="dialog"
        aria-modal="true"
        [attr.aria-labelledby]="titleId"
      >
        <div class="modal-header">
          <h3 class="modal-title" [id]="titleId">{{ title }}</h3>
          <button *ngIf="dismissible" type="button" class="modal-close" (click)="close.emit()" aria-label="Закрыть">
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
      animation: modalEnter 0.15s ease-out;
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
      transition: color 0.12s ease;
    }
    .modal-close:hover {
      color: var(--text-main);
      background-color: var(--bg-hover);
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
      background-color: var(--bg-hover);
      border-bottom-left-radius: var(--radius-lg);
      border-bottom-right-radius: var(--radius-lg);
    }

    @keyframes modalEnter {
      from {
        opacity: 0;
        transform: scale(0.96) translateY(-8px);
      }
      to {
        opacity: 1;
        transform: scale(1) translateY(0);
      }
    }
  `]
})
export class UiModalComponent implements OnChanges, OnDestroy {
  private static nextId = 0;

  @Input() isOpen: boolean = false;
  @Input() title: string = '';
  @Input() size: 'sm' | 'md' | 'lg' | 'xl' = 'md';
  @Input() dismissible: boolean = true;
  @Input() closeOnBackdrop: boolean = true;
  @Input() hasFooter: boolean = true;

  @Output() close = new EventEmitter<void>();

  readonly titleId = `ui-modal-title-${UiModalComponent.nextId++}`;

  @HostListener('window:keydown.escape')
  onEscape(): void {
    if (this.isOpen && this.dismissible) {
      this.close.emit();
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['isOpen']) {
      this.syncBodyScrollLock();
    }
  }

  ngOnDestroy(): void {
    document.body.classList.remove('modal-open');
  }

  onBackdropClick(event: MouseEvent): void {
    if (this.closeOnBackdrop && this.dismissible && (event.target as HTMLElement).classList.contains('modal-backdrop')) {
      this.close.emit();
    }
  }

  private syncBodyScrollLock(): void {
    if (this.isOpen) {
      document.body.classList.add('modal-open');
    } else {
      document.body.classList.remove('modal-open');
    }
  }
}
