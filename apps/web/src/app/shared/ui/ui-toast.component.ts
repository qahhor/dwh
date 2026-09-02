import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ToastService, ToastMessage } from '../../core/services/toast.service';

@Component({
  selector: 'ui-toast-container',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="toast-container" *ngIf="toastService.toasts().length > 0">
      <div
        *ngFor="let toast of toastService.toasts()"
        [class]="'toast-item toast-' + toast.type"
        [attr.role]="toast.type === 'error' ? 'alert' : 'status'"
        [attr.aria-live]="toast.type === 'error' ? 'assertive' : 'polite'"
      >
        <span class="material-symbols-outlined toast-icon" aria-hidden="true">
          {{ getIcon(toast.type) }}
        </span>
        <div class="toast-content">
          <div *ngIf="toast.title" class="toast-title">{{ toast.title }}</div>
          <div class="toast-message">{{ toast.message }}</div>
        </div>
        <button type="button" class="toast-close" aria-label="Закрыть уведомление" (click)="toastService.dismiss(toast.id)">
          <span class="material-symbols-outlined" aria-hidden="true">close</span>
        </button>
      </div>
    </div>
  `,
  styles: [`
    .toast-container {
      position: fixed;
      bottom: 20px;
      right: 20px;
      z-index: 2000;
      display: flex;
      flex-direction: column;
      gap: 8px;
      width: min(400px, calc(100vw - 32px));
      pointer-events: none;
    }

    .toast-item {
      pointer-events: auto;
      display: flex;
      align-items: flex-start;
      gap: 10px;
      padding: 12px 14px;
      border-radius: var(--radius-md);
      box-shadow: var(--shadow-overlay);
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
    }

    .toast-icon {
      font-size: 20px;
      margin-top: 1px;
    }

    .toast-success .toast-icon { color: var(--success); }
    .toast-error .toast-icon { color: var(--danger); }
    .toast-warning .toast-icon { color: var(--warning); }
    .toast-info .toast-icon { color: var(--info); }

    .toast-content {
      flex: 1;
      min-width: 0;
    }

    .toast-title {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-main);
      margin-bottom: 2px;
    }

    .toast-message {
      font-size: 12px;
      color: var(--text-muted);
      word-break: break-word;
    }

    .toast-close {
      background: transparent;
      border: none;
      color: var(--text-light);
      cursor: pointer;
      padding: 2px;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .toast-close:hover {
      color: var(--text-main);
    }

  `]
})
export class UiToastContainerComponent {
  constructor(public toastService: ToastService) {}

  getIcon(type: ToastMessage['type']): string {
    switch (type) {
      case 'success': return 'check_circle';
      case 'error': return 'error';
      case 'warning': return 'warning';
      case 'info': return 'info';
      default: return 'info';
    }
  }
}
