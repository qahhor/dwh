import { Injectable, signal } from '@angular/core';

export interface ToastMessage {
  id: string;
  type: 'success' | 'error' | 'warning' | 'info';
  title?: string;
  message: string;
  durationMs?: number;
}

@Injectable({
  providedIn: 'root'
})
export class ToastService {
  readonly toasts = signal<ToastMessage[]>([]);

  show(type: ToastMessage['type'], message: string, title?: string, durationMs: number = 4000) {
    const id = Math.random().toString(36).substring(2, 9);
    const toast: ToastMessage = { id, type, title, message, durationMs };
    
    this.toasts.update(current => [...current, toast]);

    if (durationMs > 0) {
      setTimeout(() => {
        this.dismiss(id);
      }, durationMs);
    }
  }

  success(message: string, title?: string) {
    this.show('success', message, title, 3500);
  }

  error(message: string, title?: string) {
    this.show('error', message, title || 'Ошибка', 6000);
  }

  warning(message: string, title?: string) {
    this.show('warning', message, title, 4500);
  }

  info(message: string, title?: string) {
    this.show('info', message, title, 3500);
  }

  dismiss(id: string) {
    this.toasts.update(current => current.filter(t => t.id !== id));
  }
}
