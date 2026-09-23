import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../../core/services/api.service';
import { Task, TaskMember } from '../../../core/models/task.models';
import { User } from '../../../core/models/auth.models';
import { KeysetPage } from '../../../core/models/common.models';
import { SelectOption } from '../../../shared/ui/ui-searchable-select.component';
import { KeysetPager } from '../../../shared/paging/keyset-pager';
import { mergeOptions, mergeUserResults } from '../tasks.models';

const SEARCH_DELAY_MS = 300;
const LOOKUP_PAGE_SIZE = 50;

/**
 * One searchable, growing lookup list over a keyset endpoint. The pager
 * cancels a superseded request and appends "load more" pages; the channel
 * adds the typing pause and remembers who is selected, so the owner can keep
 * selected entries in the list whatever the search returns.
 */
class LookupChannel<T, S> {
  private query = '';
  private timer?: ReturnType<typeof setTimeout>;
  private selected: () => S;
  private readonly pager: KeysetPager<T>;

  readonly loading: ReturnType<typeof computed<boolean>>;
  readonly error: ReturnType<typeof computed<boolean>>;
  readonly hasMore: ReturnType<typeof computed<boolean>>;

  constructor(
    fetch: (query: string, cursor: string | null) => Observable<KeysetPage<T>>,
    apply: (rows: T[], append: boolean, selected: S) => void,
    noSelection: S
  ) {
    this.selected = () => noSelection;
    this.pager = new KeysetPager<T>(cursor => fetch(this.query, cursor), {
      pageSize: LOOKUP_PAGE_SIZE,
      onLoaded: (rows, append) => apply(rows, append, this.selected()),
    });
    this.loading = computed(() => this.pager.loading() || this.pager.loadingMore());
    this.error = computed(() => this.pager.failed() || this.pager.loadMoreFailed());
    this.hasMore = this.pager.canGoForward;
  }

  search(query: string, selected: () => S): void {
    this.query = query.trim();
    this.selected = selected;
    clearTimeout(this.timer);
    this.pager.invalidate();
    this.timer = setTimeout(() => this.pager.first(), SEARCH_DELAY_MS);
  }

  load(reset: boolean, selected: () => S): void {
    this.selected = selected;
    if (reset) this.pager.first();
    else this.pager.loadMore();
  }

  retry(selected: () => S): void {
    this.selected = selected;
    this.pager.retry();
  }

  cancel(): void {
    clearTimeout(this.timer);
    this.pager.cancel();
  }
}

@Injectable({
  providedIn: 'root'
})
export class TaskLookupsService {
  private readonly api = inject(ApiService);

  readonly parentTaskOptions = signal<SelectOption[]>([]);
  readonly responsibleUsers = signal<User[]>([]);
  readonly executorUsers = signal<User[]>([]);
  readonly observerUsers = signal<User[]>([]);

  readonly retainedParentOptions = new Map<number, SelectOption>();
  readonly retainedUsers = new Map<number, User>();

  responsibleUserOptions(): SelectOption[] {
    return this.responsibleUsers().map(user => ({
      id: user.id,
      label: user.name,
      subLabel: `@${user.login}`
    }));
  }

  getAvailableParentTaskOptions(currentTaskId: number): SelectOption[] {
    return this.parentTaskOptions().filter(option => Number(option.id) !== currentTaskId);
  }

  retainTaskMember(member: TaskMember): void {
    const existing = this.retainedUsers.get(member.userId);
    this.retainedUsers.set(member.userId, {
      id: member.userId,
      name: member.userName,
      login: member.userLogin,
      email: member.userEmail || existing?.email || '',
      state: 'A',
      language: existing?.language || 'ru',
      timezone: existing?.timezone || 'UTC',
      attributes: existing?.attributes || {},
      is2faEnabled: existing?.is2faEnabled || false,
      forcePasswordChange: existing?.forcePasswordChange || false,
      createdAt: existing?.createdAt || '',
      modifiedAt: existing?.modifiedAt || ''
    });
  }

  retainParentOption(id: number, title: string): void {
    const option = { id, label: `#${id} ${title}`, icon: 'task_alt' };
    this.retainedParentOptions.set(id, option);
    this.parentTaskOptions.set(mergeOptions(this.parentTaskOptions(), [option]));
  }

  syncSelectedUsers(responsibleId: number | null, executorIds: number[], observerIds: number[]): void {
    const selectedResp = responsibleId == null ? [] : [responsibleId];
    this.responsibleUsers.set(mergeUserResults(this.responsibleUsers(), [], selectedResp, this.retainedUsers));
    this.executorUsers.set(mergeUserResults(this.executorUsers(), [], executorIds, this.retainedUsers));
    this.observerUsers.set(mergeUserResults(this.observerUsers(), [], observerIds, this.retainedUsers));
  }

  private readonly parents = new LookupChannel<Task, number | null>(
    (search, cursor) => this.api.get<KeysetPage<Task>>('/tasks', { limit: LOOKUP_PAGE_SIZE, cursor: cursor ?? undefined, search: search || undefined }),
    (items, append, selectedId) => {
      const incoming = items.map(item => {
        const option = { id: item.id, label: `#${item.id} ${item.title}`, icon: 'task_alt' };
        this.retainedParentOptions.set(item.id, option);
        return option;
      });
      const retained = selectedId == null ? [] : [this.retainedParentOptions.get(selectedId)].filter((item): item is SelectOption => !!item);
      this.parentTaskOptions.set(mergeOptions(append ? this.parentTaskOptions() : retained, incoming));
    },
    null
  );
  private readonly responsible = this.userChannel<number | null>(this.responsibleUsers, id => (id == null ? [] : [id]), null);
  private readonly executors = this.userChannel<number[]>(this.executorUsers, ids => ids, []);
  private readonly observers = this.userChannel<number[]>(this.observerUsers, ids => ids, []);

  readonly parentLookupLoading = this.parents.loading;
  readonly parentLookupError = this.parents.error;
  readonly parentLookupHasMore = this.parents.hasMore;
  readonly responsibleLookupLoading = this.responsible.loading;
  readonly responsibleLookupError = this.responsible.error;
  readonly responsibleLookupHasMore = this.responsible.hasMore;
  readonly executorLookupLoading = this.executors.loading;
  readonly executorLookupError = this.executors.error;
  readonly executorLookupHasMore = this.executors.hasMore;
  readonly observerLookupLoading = this.observers.loading;
  readonly observerLookupError = this.observers.error;
  readonly observerLookupHasMore = this.observers.hasMore;

  /** Active users, with the selected ones always kept in the list. */
  private userChannel<S>(list: ReturnType<typeof signal<User[]>>, selectedIds: (selected: S) => number[], none: S) {
    return new LookupChannel<User, S>(
      (search, cursor) => this.api.get<KeysetPage<User>>('/iam/users', { limit: LOOKUP_PAGE_SIZE, cursor: cursor ?? undefined, search: search || undefined, state: 'A' }),
      (items, append, selected) => list.set(mergeUserResults(append ? list() : [], items, selectedIds(selected), this.retainedUsers)),
      none
    );
  }

  onParentSearch(query: string, getSelectedParentId: () => number | null): void { this.parents.search(query, getSelectedParentId); }
  loadMoreParents(getSelectedParentId: () => number | null): void { this.parents.load(false, getSelectedParentId); }
  retryParentLookup(getSelectedParentId: () => number | null): void { this.parents.retry(getSelectedParentId); }
  loadParentTasks(reset: boolean, getSelectedParentId: () => number | null): void { this.parents.load(reset, getSelectedParentId); }

  onResponsibleSearch(query: string, getSelectedUserId: () => number | null): void { this.responsible.search(query, getSelectedUserId); }
  loadMoreResponsibleUsers(getSelectedUserId: () => number | null): void { this.responsible.load(false, getSelectedUserId); }
  retryResponsibleLookup(getSelectedUserId: () => number | null): void { this.responsible.retry(getSelectedUserId); }
  loadResponsibleUsers(reset: boolean, getSelectedUserId: () => number | null): void { this.responsible.load(reset, getSelectedUserId); }

  onExecutorSearch(query: string, getSelectedExecutorIds: () => number[]): void { this.executors.search(query, getSelectedExecutorIds); }
  loadMoreExecutors(getSelectedExecutorIds: () => number[]): void { this.executors.load(false, getSelectedExecutorIds); }
  retryExecutorLookup(getSelectedExecutorIds: () => number[]): void { this.executors.retry(getSelectedExecutorIds); }
  loadExecutorUsers(reset: boolean, getSelectedExecutorIds: () => number[]): void { this.executors.load(reset, getSelectedExecutorIds); }

  onObserverSearch(query: string, getSelectedObserverIds: () => number[]): void { this.observers.search(query, getSelectedObserverIds); }
  loadMoreObservers(getSelectedObserverIds: () => number[]): void { this.observers.load(false, getSelectedObserverIds); }
  retryObserverLookup(getSelectedObserverIds: () => number[]): void { this.observers.retry(getSelectedObserverIds); }
  loadObserverUsers(reset: boolean, getSelectedObserverIds: () => number[]): void { this.observers.load(reset, getSelectedObserverIds); }

  cleanup(): void {
    for (const channel of [this.parents, this.responsible, this.executors, this.observers]) channel.cancel();
  }
}
