import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService } from '../../../../core/services/api.service';
import { User } from '../../../../core/models/auth.models';
import { KeysetPage } from '../../../../core/models/common.models';
import { SelectOption } from '../../../../shared/ui/ui-searchable-select.component';
import { LookupChannel } from '../../../../shared/paging/lookup-channel';

/**
 * The users the screen knows about, independent of the page on screen.
 *
 * A manager is usually not on the page that shows their reports, so the
 * manager's name is looked up by id once and remembered, and the manager
 * picker searches the server instead of offering only the rows loaded so far.
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
  private readonly options = new Map<number, SelectOption>();

  /** Active users the manager search returned. */
  private readonly managers = signal<User[]>([]);
  private readonly managerChannel = new LookupChannel<User, null>(
    (search, cursor, limit) => this.api.get<KeysetPage<User>>('/iam/users', {
      limit,
      cursor: cursor ?? undefined,
      search: search || undefined,
      state: 'A'
    }),
    (rows, append) => {
      this.remember(rows);
      this.managers.set(append ? [...this.managers(), ...rows] : rows);
    },
    null
  );
  readonly managerLookupLoading = this.managerChannel.loading;
  readonly managerLookupError = this.managerChannel.error;
  readonly managerLookupHasMore = this.managerChannel.hasMore;

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

  /**
   * Options for the manager picker of `userId` (null when creating a user).
   * The current manager stays first, whatever the search returned, so the
   * picker can show who is selected; its name arrives through `resolve`.
   * Until then, or when the server will not return that user, the manager
   * shows by id: the picker must never read "no manager" while one is set.
   */
  managerOptions(userId: number | null, selectedId: number | null): SelectOption[] {
    const options = this.withSelected(this.managers(), selectedId)
      .filter(user => user.id !== userId)
      .map(user => this.option(user));
    if (selectedId != null && !this.known().has(selectedId)) options.unshift(this.unnamedOption(selectedId));
    return options;
  }

  /** Starts a picker from the current selection alone, before the first search lands. */
  openManagerPicker(selectedId: number | null): void {
    this.managerChannel.cancel();
    this.managers.set([]);
    this.resolve([selectedId]);
  }

  searchManagers(query: string): void { this.managerChannel.search(query, () => null); }
  loadMoreManagers(): void { this.managerChannel.load(false, () => null); }
  retryManagers(): void { this.managerChannel.retry(() => null); }

  cancel(): void {
    this.managerChannel.cancel();
  }

  /** The same object for the same user, so the list does not re-render (and drop focus) on every check. */
  private option(user: User): SelectOption {
    const cached = this.options.get(user.id);
    if (cached && cached.label === user.name && cached.subLabel === `@${user.login}`) return cached;
    const option = { id: user.id, label: user.name, subLabel: `@${user.login}` };
    this.options.set(user.id, option);
    return option;
  }

  private unnamedOption(id: number): SelectOption {
    const label = `ID: #${id}`;
    const cached = this.options.get(id);
    if (cached && cached.label === label) return cached;
    const option = { id, label };
    this.options.set(id, option);
    return option;
  }

  private withSelected(rows: readonly User[], selectedId: number | null): User[] {
    const merged = new Map<number, User>();
    const selected = selectedId == null ? undefined : this.known().get(selectedId);
    if (selected) merged.set(selected.id, selected);
    for (const row of rows) merged.set(row.id, row);
    return [...merged.values()];
  }
}
