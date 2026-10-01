import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { PermissionService } from './permission.service';

/**
 * How long a list or form description is used before it is read again, so a field another administrator added
 * shows up in an open app without a reload (plan 10/10, item 5.0).
 */
export const META_TTL_MS = 60_000;

/**
 * What every cached description depends on (plan 10/10, item 5.0): the custom fields, which this app changes
 * through `invalidate()`, and the viewer's rights, whose set is replaced whenever it changes. A description read
 * under other rights or before a change of custom fields is read again.
 */
@Injectable({ providedIn: 'root' })
export class MetaCacheState {
  private readonly permissions = inject(PermissionService);

  private generation = 0;

  /** Custom fields changed here: every list and form description is read again on its next use. */
  invalidate(): void {
    this.generation++;
  }

  /** The current stamp; a cached entry with another one is stale. */
  stamp(): { generation: number; rights: ReadonlySet<string> } {
    return { generation: this.generation, rights: this.permissions.permissions() };
  }
}

interface Entry<T> {
  value: Observable<T>;
  generation: number;
  rights: ReadonlySet<string>;
  at: number;
}

/**
 * Descriptions by code, each read once and shared while it is fresh: same custom fields, same rights and younger
 * than `META_TTL_MS`. A failed read is not kept, so the next use tries again.
 */
export class MetaCache<T> {
  private readonly entries = new Map<string, Entry<T>>();

  constructor(private readonly state: MetaCacheState) {}

  get(code: string, load: () => Observable<T>): Observable<T> {
    const stamp = this.state.stamp();
    const now = Date.now();
    const cached = this.entries.get(code);
    if (
      cached &&
      cached.generation === stamp.generation &&
      cached.rights === stamp.rights &&
      now - cached.at < META_TTL_MS
    ) {
      return cached.value;
    }
    const value = load().pipe(shareReplay({ bufferSize: 1, refCount: false }));
    const entry: Entry<T> = { value, generation: stamp.generation, rights: stamp.rights, at: now };
    this.entries.set(code, entry);
    value.subscribe({
      error: () => {
        if (this.entries.get(code) === entry) this.entries.delete(code);
      },
    });
    return value;
  }
}
