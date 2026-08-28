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
        (click)="toastService.dismiss(toast.id)"
      >
        <span class="material-symbols-outlined toast-icon">
          {{ getIcon(toast.type) }}
        </span>
        <div class="toast-content">
          <div *ngIf="toast.title" class="toast-title">{{ toast.title }}</div>
          <div class="toast-message">{{ toast.message }}</div>
        </div>
        <button class="toast-close" (click)="toastService.dismiss(toast.id); $event.stopPropagation()">
          <span class="material-symbols-outlined">close</span>
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
      max-width: 400px;
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
      cursor: pointer;
      animation: toastIn 0.2s ease-out;
      transition: transform 0.15s ease;
    }

    .toast-item:hover {
      transform: translateY(-2px);
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

    @keyframes toastIn {
      from {
        opacity: 0;
        transform: translateY(10px);
      }
      to {
        opacity: 1;
        transform: translateY(0);
      }
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
