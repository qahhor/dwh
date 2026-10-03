import { signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import { ProblemDetail } from '@core/models/common.models';

/**
 * One read of the user's org units panel (the tree, the assignments, the effective scope): its loading, loaded and
 * error state and the request in flight. An answer that arrives for a view no longer on screen is dropped.
 */
export class OrgUnitRead {
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly error = signal<ProblemDetail | null>(null);
  private request?: Subscription;

  /** `changed` tells the host its view must be checked again. */
  constructor(private readonly changed: () => void) {}

  /**
   * Starts the read and cancels the one in flight. `done` takes a current answer and marks the read loaded when it
   * accepts it; `failed` runs after a current failure has been recorded.
   */
  start<T>(source: Observable<T>, current: () => boolean, done: (value: T) => void, failed?: () => void): void {
    this.cancel();
    this.loading.set(true);
    this.loaded.set(false);
    this.error.set(null);
    this.request = source.subscribe({
      next: (value) => {
        if (!current()) return;
        this.loading.set(false);
        done(value);
        this.changed();
      },
      error: (error: ProblemDetail) => {
        if (!current()) return;
        this.loading.set(false);
        this.loaded.set(false);
        this.error.set(error);
        failed?.();
        this.changed();
      },
    });
  }

  cancel(): void {
    this.request?.unsubscribe();
    this.request = undefined;
  }

  reset(): void {
    this.loading.set(false);
    this.loaded.set(false);
    this.error.set(null);
  }
}
