import { Injectable, inject, signal } from '@angular/core';
import { I18nService } from './i18n.service';
import { LiveAnnouncerService } from './live-announcer.service';

export interface ToastMessage {
  id: string;
  type: 'success' | 'error' | 'warning' | 'info';
  title?: string;
  message: string;
  durationMs?: number;
}

/** More than this on screen at once only buries the newest; the oldest go first. */
export const MAX_VISIBLE_TOASTS = 5;

interface ToastTimer {
  remainingMs: number;
  startedAt: number;
  handle: ReturnType<typeof setTimeout> | null;
}

@Injectable({
  providedIn: 'root'
})
export class ToastService {
  readonly toasts = signal<ToastMessage[]>([]);
  private readonly i18n = inject(I18nService);
  private readonly announcer = inject(LiveAnnouncerService);
  private readonly timers = new Map<string, ToastTimer>();

  /**
   * Shows a toast and has screen readers announce it. An error toast carries
   * role="alert", which screen readers announce when it is inserted. Other
   * toasts are announced politely through LiveAnnouncerService, whose live
   * region is already in the page: a polite region inserted together with
   * its text, as the toast element is, is often not read at all. Showing the
   * same message again restarts the visible one instead of stacking a copy.
   */
  show(type: ToastMessage['type'], message: string, title?: string, durationMs: number = 4000): string {
    const duplicate = this.toasts().find(toast =>
      toast.type === type && toast.message === message && toast.title === title);
    const id = duplicate?.id ?? Math.random().toString(36).substring(2, 9);

    if (!duplicate) {
      const toast: ToastMessage = { id, type, title, message, durationMs };
      const next = [...this.toasts(), toast];
      for (const dropped of next.slice(0, Math.max(0, next.length - MAX_VISIBLE_TOASTS))) {
        this.clearTimer(dropped.id);
      }
      this.toasts.set(next.slice(-MAX_VISIBLE_TOASTS));
    }

    this.clearTimer(id);
    if (durationMs > 0) {
      this.timers.set(id, { remainingMs: durationMs, startedAt: Date.now(), handle: null });
      this.startTimer(id);
    }

    if (type !== 'error') {
      this.announcer.announce(title ? `${title}. ${message}` : message);
    }
    return id;
  }

  success(message: string, title?: string) {
    this.show('success', message, title, 3500);
  }

  error(message: string, title?: string) {
    this.show('error', message, title || this.i18n.translate('common.error'), 6000);
  }

  warning(message: string, title?: string) {
    this.show('warning', message, title, 4500);
  }

  info(message: string, title?: string) {
    this.show('info', message, title, 3500);
  }

  dismiss(id: string) {
    this.clearTimer(id);
    this.toasts.update(current => current.filter(t => t.id !== id));
  }

  /** Stops the dismiss countdown while the user reads or reaches the toast (WCAG 2.2.1). */
  pause(id: string) {
    const timer = this.timers.get(id);
    if (!timer?.handle) return;
    clearTimeout(timer.handle);
    timer.handle = null;
    timer.remainingMs = Math.max(0, timer.remainingMs - (Date.now() - timer.startedAt));
  }

  /** Continues a paused countdown with the time it had left. */
  resume(id: string) {
    const timer = this.timers.get(id);
    if (!timer || timer.handle) return;
    timer.startedAt = Date.now();
    this.startTimer(id);
  }

  private startTimer(id: string) {
    const timer = this.timers.get(id);
    if (!timer) return;
    timer.handle = setTimeout(() => this.dismiss(id), timer.remainingMs);
  }

  private clearTimer(id: string) {
    const timer = this.timers.get(id);
    if (timer?.handle) clearTimeout(timer.handle);
    this.timers.delete(id);
  }
}
