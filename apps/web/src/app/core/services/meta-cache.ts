import { Injectable } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';

/**
 * How long a list or form description is used before it is read again, so a field another administrator added
 * shows up in an open app without a reload (plan 10/10, item 5.0).
 */
export const META_TTL_MS = 60_000;

/**
 * What every cached description depends on (plan 10/10, item 5.0): the custom fields, which this app changes, and
 * the viewer's rights, which `PermissionService` reports when they change. After either, every description is
 * read again on its next use.
 */
@Injectable({ providedIn: 'root' })
export class MetaCacheState {
  private current = 0;

  /** Custom fields or the viewer's rights changed: every list and form description goes stale. */
  invalidate(): void {
    this.current++;
  }

  /** The current generation; a cached entry of another one is stale. */
  generation(): number {
    return this.current;
  }
}

interface Entry<T> {
  value: Observable<T>;
  generation: number;
  at: number;
}

/**
 * Descriptions by code, each read once and shared while it is fresh: same generation (custom fields and rights)
 * and younger than `META_TTL_MS`. A failed read is not kept, so the next use tries again.
 */
export class MetaCache<T> {
  private readonly entries = new Map<string, Entry<T>>();

  constructor(private readonly state: MetaCacheState) {}

  get(code: string, load: () => Observable<T>): Observable<T> {
    const generation = this.state.generation();
    const now = Date.now();
    const cached = this.entries.get(code);
    if (cached && cached.generation === generation && now - cached.at < META_TTL_MS) {
      return cached.value;
    }
    const value = load().pipe(shareReplay({ bufferSize: 1, refCount: false }));
    const entry: Entry<T> = { value, generation, at: now };
    this.entries.set(code, entry);
    value.subscribe({
      error: () => {
        if (this.entries.get(code) === entry) this.entries.delete(code);
      },
    });
    return value;
  }
}
