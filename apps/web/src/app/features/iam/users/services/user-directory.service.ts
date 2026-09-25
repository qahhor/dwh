import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService } from '../../../../core/services/api.service';
import { User } from '../../../../core/models/auth.models';

/**
 * The users the screen knows about, independent of the page on screen.
 *
 * A manager is usually not on the page that shows their reports, so the
 * manager's name is looked up by id once and remembered. The manager picker
 * itself is an smt-data-select over LookupSources.activeUsers.
 * Provided by the users screen, so what it remembers lives as long as the
 * screen does.
 */
@Injectable()
export class UserDirectoryService {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly known = signal<ReadonlyMap<number, User>>(new Map());
  /** Ids the server would not return (deleted, or outside the viewer's scope). */
  private readonly unavailable = new Set<number>();
  private readonly requested = new Set<number>();

  remember(users: readonly User[]): void {
    if (users.length === 0) return;
    const next = new Map(this.known());
    for (const user of users) {
      next.set(user.id, user);
      this.unavailable.delete(user.id);
    }
    this.known.set(next);
  }

  /** The user's name, or null while it is unknown. Reactive. */
  nameOf(id: number): string | null {
    return this.known().get(id)?.name ?? null;
  }

  /** Looks up, once each, the users the screen names but has not loaded. */
  resolve(ids: Iterable<number | null | undefined>): void {
    for (const id of ids) {
      if (id == null || this.known().has(id) || this.requested.has(id) || this.unavailable.has(id)) continue;
      this.requested.add(id);
      // Bound to the screen: a lookup still in flight when it closes is dropped.
      this.api.get<User>(`/iam/users/${id}`, undefined, { notifyError: false }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: user => {
          this.requested.delete(id);
          if (user?.id === id) this.remember([user]);
          else this.unavailable.add(id);
        },
        error: () => {
          this.requested.delete(id);
          this.unavailable.add(id);
        }
      });
    }
  }
}
