import { Injectable, computed, inject, signal } from '@angular/core';
import { TaskMember } from '../../../core/models/task.models';
import { LookupSources, TaskRef, UserRef } from '../../../shared/lookups/lookup-sources';

/**
 * What the task dialogs pick from and what they already know (roadmap item 35).
 *
 * The pickers are smt-data-selects over `users` and `tasks`: they load,
 * search, page and retry by themselves. This service only remembers the
 * people and parents a task's card brought — its members and its parent —
 * so the pickers name them without asking the server, and so a user-typed
 * custom field can show a name instead of a number. A name it does not know
 * yet is asked for once through the same source.
 */
@Injectable({ providedIn: 'root' })
export class TaskLookupsService {
  private readonly sources = inject(LookupSources);

  private readonly knownUsers = signal<ReadonlyMap<number, UserRef>>(new Map());

  private readonly knownParents = signal<ReadonlyMap<number, TaskRef>>(new Map());

  readonly knownUserRows = computed(() => [...this.knownUsers().values()]);

  readonly knownParentRows = computed(() => [...this.knownParents().values()]);

  readonly users = this.sources.activeUsers;

  readonly tasks = this.sources.tasks;

  /** Users whose names were asked for and are not (yet) known. */
  private readonly requested = new Set<number>();

  /** A member of a task's card; a fresher card replaces an older name. */
  retainTaskMember(member: TaskMember): void {
    this.rememberUsers([{ id: member.userId, name: member.userName, login: member.userLogin }]);
  }

  retainParentOption(id: number, title: string): void {
    const next = new Map(this.knownParents());
    next.set(id, { id, title });
    this.knownParents.set(next);
  }

  /** The user's name, or null while it is unknown. Reactive. */
  nameOf(id: number): string | null {
    const user = this.knownUsers().get(id);
    return user ? user.name || user.login : null;
  }

  /** Asks once for the names of users the screen shows by id, such as user-typed custom fields. */
  resolveUserNames(ids: Iterable<number>): void {
    const missing = [...new Set(ids)].filter(id => Number.isSafeInteger(id) && id > 0 && !this.knownUsers().has(id) && !this.requested.has(id));
    if (missing.length === 0 || !this.users.resolve) return;
    missing.forEach(id => this.requested.add(id));
    this.users.resolve(missing).subscribe({
      next: users => this.rememberUsers(users),
      error: () => undefined,
    });
  }

  private rememberUsers(users: readonly UserRef[]): void {
    if (users.length === 0) return;
    const next = new Map(this.knownUsers());
    for (const user of users) next.set(user.id, { id: user.id, name: user.name, login: user.login });
    this.knownUsers.set(next);
  }
}
