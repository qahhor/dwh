import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { ToastService, ToastMessage } from '@core/services/toast.service';
import { TranslatePipe } from '@core/services/i18n.service';

@Component({
  selector: 'ui-toast-container',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe],
  template: `
    @if (toastService.toasts().length > 0) {
      <div class="toast-container">
        @for (toast of toastService.toasts(); track toast) {
          <div
            [class]="'toast-item toast-' + toast.type"
            [attr.role]="toast.type === 'error' ? 'alert' : null"
            (mouseenter)="toastService.pause(toast.id)"
            (mouseleave)="toastService.resume(toast.id)"
            (focusin)="toastService.pause(toast.id)"
            (focusout)="toastService.resume(toast.id)"
          >
            <!-- An error is announced by its role="alert". Other toasts are
             announced by ToastService through LiveAnnouncerService, because a
             polite live region inserted with its text is often not read. -->
            <span class="material-symbols-outlined toast-icon" aria-hidden="true">
              {{ getIcon(toast.type) }}
            </span>
            <div class="toast-content">
              @if (toast.title) {
                <div class="toast-title">{{ toast.title }}</div>
              }
              <div class="toast-message">{{ toast.message }}</div>
              @if (toast.action; as action) {
                <button type="button" class="toast-action" (click)="toastService.runAction(toast.id)">
                  {{ action.label }}
                </button>
              }
            </div>
            <button
              type="button"
              class="toast-close"
              [attr.aria-label]="'ui.toast.close' | t"
              (click)="toastService.dismiss(toast.id)"
            >
              <span class="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          </div>
        }
      </div>
    }
  `,
  styleUrl: './ui-toast.component.css',
})
export class UiToastContainerComponent {
  toastService = inject(ToastService);

  getIcon(type: ToastMessage['type']): string {
    switch (type) {
      case 'success':
        return 'check_circle';
      case 'error':
        return 'error';
      case 'warning':
        return 'warning';
      case 'info':
        return 'info';
      default:
        return 'info';
    }
  }
}
