import { Injectable, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { ApiService } from '../../../core/services/api.service';
import { Task, TaskMember } from '../../../core/models/task.models';
import { User } from '../../../core/models/auth.models';
import { KeysetPage } from '../../../core/models/common.models';
import { SelectOption } from '../../../shared/ui/ui-searchable-select.component';
import { mergeOptions, mergeUserResults } from '../tasks.models';

@Injectable({
  providedIn: 'root'
})
export class TaskLookupsService {
  private readonly api = inject(ApiService);

  readonly parentTaskOptions = signal<SelectOption[]>([]);
  readonly responsibleUsers = signal<User[]>([]);
  readonly observerUsers = signal<User[]>([]);

  readonly parentLookupLoading = signal(false);
  readonly parentLookupError = signal(false);
  readonly parentLookupHasMore = signal(false);

  readonly responsibleLookupLoading = signal(false);
  readonly responsibleLookupError = signal(false);
  readonly responsibleLookupHasMore = signal(false);

  readonly observerLookupLoading = signal(false);
  readonly observerLookupError = signal(false);
  readonly observerLookupHasMore = signal(false);

  private parentLookupRequest?: Subscription;
  private responsibleLookupRequest?: Subscription;
  private observerLookupRequest?: Subscription;

  private parentLookupRequestId = 0;
  private responsibleLookupRequestId = 0;
  private observerLookupRequestId = 0;

  private parentSearchTimer?: ReturnType<typeof setTimeout>;
  private responsibleSearchTimer?: ReturnType<typeof setTimeout>;
  private observerSearchTimer?: ReturnType<typeof setTimeout>;

  private parentLookupQuery = '';
  private responsibleLookupQuery = '';
  private observerLookupQuery = '';

  private parentLookupCursor: string | null = null;
  private responsibleLookupCursor: string | null = null;
  private observerLookupCursor: string | null = null;

  private parentLastReset = true;
  private responsibleLastReset = true;
  private observerLastReset = true;

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

  syncSelectedUsers(responsibleId: number | null, observerIds: number[]): void {
    const selectedResp = responsibleId == null ? [] : [responsibleId];
    this.responsibleUsers.set(mergeUserResults(this.responsibleUsers(), [], selectedResp, this.retainedUsers));
    this.observerUsers.set(mergeUserResults(this.observerUsers(), [], observerIds, this.retainedUsers));
  }

  onParentSearch(query: string, getSelectedParentId: () => number | null): void {
    this.parentLookupQuery = query.trim();
    clearTimeout(this.parentSearchTimer);
    this.parentLookupRequestId++;
    this.parentLookupRequest?.unsubscribe();
    this.parentLookupCursor = null;
    this.parentLookupHasMore.set(false);
    this.parentLookupLoading.set(true);
    this.parentLookupError.set(false);
    this.parentSearchTimer = setTimeout(() => this.loadParentTasks(true, getSelectedParentId), 300);
  }

  loadMoreParents(getSelectedParentId: () => number | null): void {
    if (this.parentLookupCursor && !this.parentLookupLoading()) {
      this.loadParentTasks(false, getSelectedParentId);
    }
  }

  retryParentLookup(getSelectedParentId: () => number | null): void {
    this.loadParentTasks(this.parentLastReset, getSelectedParentId);
  }

  loadParentTasks(reset: boolean, getSelectedParentId: () => number | null): void {
    this.parentLastReset = reset;
    if (reset) this.parentLookupCursor = null;
    const requestId = ++this.parentLookupRequestId;
    this.parentLookupRequest?.unsubscribe();
    this.parentLookupLoading.set(true);
    this.parentLookupError.set(false);

    this.parentLookupRequest = this.api.get<KeysetPage<Task>>('/tasks', {
      limit: 50,
      cursor: this.parentLookupCursor || undefined,
      search: this.parentLookupQuery || undefined
    }).subscribe({
      next: page => {
        if (requestId !== this.parentLookupRequestId) return;
        const incoming = (page.items || []).map(item => {
          const option = { id: item.id, label: `#${item.id} ${item.title}`, icon: 'task_alt' };
          this.retainedParentOptions.set(item.id, option);
          return option;
        });
        const selectedId = getSelectedParentId();
        const retained = selectedId == null ? [] : [this.retainedParentOptions.get(selectedId)].filter((item): item is SelectOption => !!item);
        this.parentTaskOptions.set(mergeOptions(reset ? retained : this.parentTaskOptions(), incoming));
        this.parentLookupCursor = page.nextCursor;
        this.parentLookupHasMore.set(page.hasMore);
        this.parentLookupLoading.set(false);
      },
      error: () => {
        if (requestId !== this.parentLookupRequestId) return;
        this.parentLookupLoading.set(false);
        this.parentLookupError.set(true);
      }
    });
  }

  onResponsibleSearch(query: string, getSelectedUserId: () => number | null): void {
    this.responsibleLookupQuery = query.trim();
    clearTimeout(this.responsibleSearchTimer);
    this.responsibleLookupRequestId++;
    this.responsibleLookupRequest?.unsubscribe();
    this.responsibleLookupCursor = null;
    this.responsibleLookupHasMore.set(false);
    this.responsibleLookupLoading.set(true);
    this.responsibleLookupError.set(false);
    this.responsibleSearchTimer = setTimeout(() => this.loadResponsibleUsers(true, getSelectedUserId), 300);
  }

  loadMoreResponsibleUsers(getSelectedUserId: () => number | null): void {
    if (this.responsibleLookupCursor && !this.responsibleLookupLoading()) {
      this.loadResponsibleUsers(false, getSelectedUserId);
    }
  }

  retryResponsibleLookup(getSelectedUserId: () => number | null): void {
    this.loadResponsibleUsers(this.responsibleLastReset, getSelectedUserId);
  }

  loadResponsibleUsers(reset: boolean, getSelectedUserId: () => number | null): void {
    this.responsibleLastReset = reset;
    if (reset) this.responsibleLookupCursor = null;
    const requestId = ++this.responsibleLookupRequestId;
    this.responsibleLookupRequest?.unsubscribe();
    this.responsibleLookupLoading.set(true);
    this.responsibleLookupError.set(false);

    this.responsibleLookupRequest = this.api.get<KeysetPage<User>>('/iam/users', {
      limit: 50,
      cursor: this.responsibleLookupCursor || undefined,
      search: this.responsibleLookupQuery || undefined,
      state: 'A'
    }).subscribe({
      next: page => {
        if (requestId !== this.responsibleLookupRequestId) return;
        const selectedId = getSelectedUserId();
        this.responsibleUsers.set(mergeUserResults(
          reset ? [] : this.responsibleUsers(),
          page.items || [],
          selectedId == null ? [] : [selectedId],
          this.retainedUsers
        ));
        this.responsibleLookupCursor = page.nextCursor;
        this.responsibleLookupHasMore.set(page.hasMore);
        this.responsibleLookupLoading.set(false);
      },
      error: () => {
        if (requestId !== this.responsibleLookupRequestId) return;
        this.responsibleLookupLoading.set(false);
        this.responsibleLookupError.set(true);
      }
    });
  }

  onObserverSearch(query: string, getSelectedObserverIds: () => number[]): void {
    this.observerLookupQuery = query.trim();
    clearTimeout(this.observerSearchTimer);
    this.observerLookupRequestId++;
    this.observerLookupRequest?.unsubscribe();
    this.observerLookupCursor = null;
    this.observerLookupHasMore.set(false);
    this.observerLookupLoading.set(true);
    this.observerLookupError.set(false);
    this.observerSearchTimer = setTimeout(() => this.loadObserverUsers(true, getSelectedObserverIds), 300);
  }

  loadMoreObservers(getSelectedObserverIds: () => number[]): void {
    if (this.observerLookupCursor && !this.observerLookupLoading()) {
      this.loadObserverUsers(false, getSelectedObserverIds);
    }
  }

  retryObserverLookup(getSelectedObserverIds: () => number[]): void {
    this.loadObserverUsers(this.observerLastReset, getSelectedObserverIds);
  }

  loadObserverUsers(reset: boolean, getSelectedObserverIds: () => number[]): void {
    this.observerLastReset = reset;
    if (reset) this.observerLookupCursor = null;
    const requestId = ++this.observerLookupRequestId;
    this.observerLookupRequest?.unsubscribe();
    this.observerLookupLoading.set(true);
    this.observerLookupError.set(false);

    this.observerLookupRequest = this.api.get<KeysetPage<User>>('/iam/users', {
      limit: 50,
      cursor: this.observerLookupCursor || undefined,
      search: this.observerLookupQuery || undefined,
      state: 'A'
    }).subscribe({
      next: page => {
        if (requestId !== this.observerLookupRequestId) return;
        const selectedIds = getSelectedObserverIds();
        this.observerUsers.set(mergeUserResults(
          reset ? [] : this.observerUsers(),
          page.items || [],
          selectedIds,
          this.retainedUsers
        ));
        this.observerLookupCursor = page.nextCursor;
        this.observerLookupHasMore.set(page.hasMore);
        this.observerLookupLoading.set(false);
      },
      error: () => {
        if (requestId !== this.observerLookupRequestId) return;
        this.observerLookupLoading.set(false);
        this.observerLookupError.set(true);
      }
    });
  }

  cleanup(): void {
    this.parentLookupRequestId++;
    this.responsibleLookupRequestId++;
    this.observerLookupRequestId++;
    clearTimeout(this.parentSearchTimer);
    clearTimeout(this.responsibleSearchTimer);
    clearTimeout(this.observerSearchTimer);
    this.parentLookupRequest?.unsubscribe();
    this.responsibleLookupRequest?.unsubscribe();
    this.observerLookupRequest?.unsubscribe();
  }
}
