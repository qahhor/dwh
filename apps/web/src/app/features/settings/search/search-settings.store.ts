import { DestroyRef, Injectable, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import {
  Observable,
  Subscription,
  catchError,
  defer,
  map,
  of,
  repeat,
  retry,
  switchMap,
  tap,
  throwError,
  timer,
} from 'rxjs';
import { ProblemDetail } from '@core/models/common.models';
import { SearchCategory } from '@core/models/search.models';
import {
  SearchEntityType,
  SearchFieldPolicy,
  SearchJobAction,
  SearchJobStatus,
  SearchManagementStatus,
  SearchPreviewResult,
  SearchQueryPolicy,
  SearchSettingsSnapshot,
} from '@core/models/search-management.models';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { SearchManagementService } from '@core/services/search-management.service';
import {
  Loaded,
  MaintenanceConfirmation,
  PendingMutation,
  SEARCH_POLL_INTERVAL_MS,
  SEARCH_POLL_MAX_RETRIES,
  cloneSearchPolicy,
  rateLimitedRetryDelayMs,
  searchPollRetryDelayMs,
  cloneSearchSnapshot,
  toProblemDetail,
  validateSearchPolicy,
} from './search-settings.models';

/**
 * The state and server conversations of the search settings screen: status,
 * the editable policy, preview with its cooldown, job history, and the
 * maintenance jobs with their polling. Provided by the screen, so every
 * request and timer stops when it closes.
 */
@Injectable()
export class SearchSettingsStore {
  private readonly management = inject(SearchManagementService);
  private readonly permissions = inject(PermissionService);
  private readonly i18n = inject(I18nService);

  /** The last status the server returned; a failed refresh keeps it on screen. */
  readonly status = linkedSignal<Loaded<SearchManagementStatus> | undefined, SearchManagementStatus | null>({
    source: () => this.statusResource.value(),
    computation: (loaded, previous) => loaded?.value ?? previous?.value ?? null,
  });
  /** The last answer decides: a refresh in flight keeps the gate as it was. */
  readonly statusAuthorized = linkedSignal<Loaded<SearchManagementStatus> | undefined, boolean>({
    source: () => this.statusResource.value(),
    computation: (loaded, previous) => (loaded ? loaded.failure === undefined : (previous?.value ?? false)),
  });
  /** The clean baseline: every load and every successful save replace it; a failed load invents none. */
  readonly savedSettings = linkedSignal<Loaded<SearchSettingsSnapshot> | undefined, SearchSettingsSnapshot | null>({
    source: () => this.settingsResource.value(),
    computation: (loaded, previous) => (loaded?.value ? cloneSearchSnapshot(loaded.value) : (previous?.value ?? null)),
  });
  readonly draft = linkedSignal<Loaded<SearchSettingsSnapshot> | undefined, SearchQueryPolicy | null>({
    source: () => this.settingsResource.value(),
    computation: (loaded, previous) =>
      loaded?.value ? cloneSearchPolicy(loaded.value.policy) : (previous?.value ?? null),
  });
  readonly savePending = signal(false);
  readonly saveError = signal<ProblemDetail | null>(null);
  readonly saveAttempted = signal(false);
  readonly history = signal<SearchJobStatus[]>([]);
  readonly historyLoading = signal(false);
  readonly historyError = signal<ProblemDetail | null>(null);
  readonly historyCursor = signal<string | null>(null);
  readonly historyHasMore = signal(false);
  readonly previewPending = signal(false);
  readonly previewResult = signal<SearchPreviewResult | null>(null);
  readonly previewError = signal<ProblemDetail | null>(null);
  readonly previewCooldownSeconds = signal(0);
  readonly mutationPending = signal(false);
  readonly mutationError = signal<ProblemDetail | null>(null);
  readonly uncertainMutation = signal<PendingMutation | null>(null);
  readonly activeJob = signal<SearchJobStatus | null>(null);
  readonly activeJobId = signal<string | null>(null);
  readonly pollError = signal<ProblemDetail | null>(null);
  /** A poll the server refused or lost is asked again later; the screen says it is updating, not that it failed. */
  readonly pollDelayed = signal(false);
  readonly confirmation = signal<MaintenanceConfirmation | null>(null);
  readonly historyRetryNextPage = signal(false);
  /** The names of the entities and their fields; an entity the administrator may not view keeps its code. */
  readonly categories = signal<SearchCategory[]>([]);

  /**
   * Each refresh is a new request rather than a reload: a reload is ignored while one
   * is in flight, and a refresh must replace an older answer, as the job end needs.
   */
  private readonly statusRequests = signal(0);
  private readonly settingsRequests = signal(0);

  readonly statusLoading = computed(() => this.statusResource.isLoading());
  readonly statusError = computed(() => this.statusResource.value()?.failure ?? null);
  readonly settingsLoading = computed(() => this.settingsResource.isLoading());
  readonly settingsError = computed(() => this.settingsResource.value()?.failure ?? null);
  readonly activeOperation = computed(() => this.activeJobId() !== null);
  /**
   * The entities the search indexes, as the policy the server answers names them (ADR-0032, 10.3); without a readable
   * policy, the entities the administrator may search.
   */
  readonly entities = computed<SearchEntityType[]>(() => {
    const draft = this.draft();
    return draft ? Object.keys(draft.fields) : this.categories().map((category) => category.code);
  });
  readonly policyErrors = computed(() => validateSearchPolicy(this.draft(), this.entities()));
  readonly dirty = computed(() => {
    const saved = this.savedSettings();
    const draft = this.draft();
    return Boolean(saved && draft && JSON.stringify(saved.policy) !== JSON.stringify(draft));
  });
  readonly rebuildRequired = computed(() => {
    const current = this.status();
    const saved = this.savedSettings();
    return (
      current?.rebuildRequired === true ||
      Boolean(saved && current?.activeProfile && saved.policy.schemaProfile !== current.activeProfile)
    );
  });
  readonly atCapacity = computed(() => (this.status()?.generations.length ?? 0) >= 4);
  readonly displayedJobs = computed(() => (this.history().length ? this.history() : (this.status()?.jobs ?? [])));

  /** Status also reopens the maintenance gate for a job still running. */
  private readonly statusResource = rxResource({
    params: () => (this.canSearch() ? this.statusRequests() : undefined),
    stream: () =>
      this.loaded(this.retryRateLimited(() => this.management.status())).pipe(
        tap((loaded) => {
          if (loaded.value) this.restoreActiveOperation(loaded.value);
        }),
      ),
  });
  private readonly settingsResource = rxResource({
    params: () => (this.canSearch() && this.canReadSettings() ? this.settingsRequests() : undefined),
    stream: () =>
      this.loaded(this.management.settings()).pipe(
        tap((loaded) => {
          if (!loaded.value) return;
          this.saveError.set(null);
          this.saveAttempted.set(false);
        }),
      ),
  });

  private historyRequest?: Subscription;
  private categoriesRequest?: Subscription;
  private saveRequest?: Subscription;
  private previewRequest?: Subscription;
  private mutationRequest?: Subscription;
  private pollRequest?: Subscription;
  private cooldownRequest?: Subscription;

  constructor() {
    // Leaving the screen stops polling locally; it never asks the server to cancel a job.
    // The resources stop with the screen by themselves.
    inject(DestroyRef).onDestroy(() => {
      this.historyRequest?.unsubscribe();
      this.categoriesRequest?.unsubscribe();
      this.saveRequest?.unsubscribe();
      this.previewRequest?.unsubscribe();
      this.mutationRequest?.unsubscribe();
      this.pollRequest?.unsubscribe();
      this.cooldownRequest?.unsubscribe();
    });
  }

  /** Status and settings load by themselves once the screen is drawn; history is paged by hand. */
  init(): void {
    this.refreshHistory();
    this.loadCategories();
  }

  /** The name of an entity: its dictionary key from the categories, or its code. */
  entityLabelKey(entity: SearchEntityType): string {
    return this.categories().find((category) => category.code === entity)?.labelKey ?? entity;
  }

  /** The name of a searched field: its dictionary key from the categories, or its key. */
  fieldLabelKey(entity: SearchEntityType, field: string): string {
    const category = this.categories().find((candidate) => candidate.code === entity);
    return category?.fields.find((candidate) => candidate.key === field)?.labelKey ?? field;
  }

  canSearch(): boolean {
    return this.permissions.hasPermission('search', 'view');
  }

  canReadSettings(): boolean {
    return this.permissions.hasPermission('md.settings', 'view');
  }

  canMaintain(): boolean {
    return this.statusAuthorized() && this.permissions.hasPermission('md.settings', 'update');
  }

  canSave(): boolean {
    return (
      this.canMaintain() &&
      this.canReadSettings() &&
      this.savedSettings() !== null &&
      this.dirty() &&
      this.policyErrors().length === 0 &&
      !this.savePending() &&
      !this.mutationPending() &&
      !this.activeOperation()
    );
  }

  fields(entity: SearchEntityType): SearchFieldPolicy[] {
    return this.draft()?.fields[entity] ?? [];
  }

  refreshOperationalData(): void {
    this.refreshStatus();
    this.refreshHistory();
  }

  refreshStatus(): void {
    this.statusRequests.update((count) => count + 1);
  }

  refreshHistory(): void {
    if (!this.canSearch()) return;
    this.historyRequest?.unsubscribe();
    this.historyLoading.set(true);
    this.historyError.set(null);
    this.historyRetryNextPage.set(false);
    this.historyRequest = this.management.jobs(20).subscribe({
      next: (page) => {
        this.history.set(page.items);
        this.historyCursor.set(page.nextCursor ?? null);
        this.historyHasMore.set(page.hasMore);
        this.historyLoading.set(false);
      },
      error: (error) => {
        this.historyError.set(this.problem(error));
        this.historyLoading.set(false);
      },
    });
  }

  loadMoreHistory(): void {
    const cursor = this.historyCursor();
    if (!cursor || !this.historyHasMore() || this.historyLoading()) return;
    this.historyRequest?.unsubscribe();
    this.historyLoading.set(true);
    this.historyError.set(null);
    this.historyRetryNextPage.set(true);
    this.historyRequest = this.management.jobs(20, cursor).subscribe({
      next: (page) => {
        const known = new Set(this.history().map((job) => job.id));
        this.history.update((current) => [...current, ...page.items.filter((job) => !known.has(job.id))]);
        this.historyCursor.set(page.nextCursor ?? null);
        this.historyHasMore.set(page.hasMore);
        this.historyLoading.set(false);
        this.historyRetryNextPage.set(false);
      },
      error: (error) => {
        this.historyError.set(this.problem(error));
        this.historyLoading.set(false);
      },
    });
  }

  retryHistory(): void {
    if (this.historyRetryNextPage()) this.loadMoreHistory();
    else this.refreshHistory();
  }

  /** Asks the server for the policy again; the answer replaces the draft. */
  loadSettings(): void {
    this.settingsRequests.update((count) => count + 1);
  }

  updatePolicyNumber(key: 'globalLimit' | 'requestsPerMinute' | 'burst', value: string | number | null): void {
    const parsed = typeof value === 'number' ? value : Number(value);
    this.draft.update((current) => (current ? { ...current, [key]: parsed } : current));
  }

  updateSchemaProfile(value: string): void {
    if (value !== 'MIXED' && value !== 'RU') return;
    this.draft.update((current) => (current ? { ...current, schemaProfile: value } : current));
  }

  updateFieldNumber(
    entity: SearchEntityType,
    index: number,
    key: 'weight' | 'numTypos',
    value: string | number | null,
  ): void {
    const parsed = typeof value === 'number' ? value : Number(value);
    this.updateField(entity, index, (field) => ({ ...field, [key]: parsed }));
  }

  updateFieldPrefix(entity: SearchEntityType, index: number, enabled: boolean): void {
    this.updateField(entity, index, (field) => ({ ...field, prefix: enabled }));
  }

  save(): void {
    this.saveAttempted.set(true);
    if (this.savePending() || !this.canSave()) return;
    const baseline = this.savedSettings();
    const policy = this.draft();
    if (!baseline || !policy) return;
    this.savePending.set(true);
    this.saveError.set(null);
    this.saveRequest = this.management
      .save({ version: baseline.version, policy: cloneSearchPolicy(policy) })
      .subscribe({
        next: (saved) => {
          this.savedSettings.set(cloneSearchSnapshot(saved));
          this.draft.set(cloneSearchPolicy(saved.policy));
          this.savePending.set(false);
          this.saveAttempted.set(false);
        },
        error: (error) => {
          this.saveError.set(this.problem(error));
          this.savePending.set(false);
        },
      });
  }

  /** Previews `rawQuery` against the unsaved draft when one is readable, else against the saved policy. */
  preview(rawQuery: string, entity: SearchEntityType | ''): void {
    const policy = this.draft();
    const query = rawQuery.trim();
    if (
      !this.statusAuthorized() ||
      !query ||
      (policy !== null && this.policyErrors().length > 0) ||
      this.previewCooldownSeconds() > 0
    )
      return;
    this.previewRequest?.unsubscribe();
    this.previewPending.set(true);
    this.previewError.set(null);
    this.previewResult.set(null);
    this.previewRequest = this.management
      .preview({
        q: query,
        ...(entity ? { entity } : {}),
        ...(policy ? { policy: cloneSearchPolicy(policy) } : {}),
      })
      .subscribe({
        next: (result) => {
          this.previewResult.set(result);
          this.previewPending.set(false);
        },
        error: (error) => {
          const failure = this.problem(error);
          this.previewError.set(failure);
          this.previewPending.set(false);
          if (failure.retryAfterSeconds !== undefined) this.startCooldown(failure.retryAfterSeconds);
        },
      });
  }

  requestMaintenance(action: SearchJobAction, generationId?: string): void {
    if (!this.canMaintain() || this.mutationPending() || this.savePending() || this.activeOperation()) return;
    if (action === 'REBUILD') {
      if (this.atCapacity()) return;
      this.confirmation.set({ action });
      return;
    }
    if (action === 'ROLLBACK') {
      if (!generationId || !this.status()?.rollbackTargets.some((target) => target.id === generationId)) return;
      this.confirmation.set({ action, generationId });
      return;
    }
    this.executeMutation({
      kind: 'start',
      request: { requestId: this.newRequestId(), action, ...(generationId ? { generationId } : {}) },
    });
  }

  confirmMaintenance(): void {
    const confirmation = this.confirmation();
    this.confirmation.set(null);
    if (!confirmation || this.activeOperation()) return;
    this.executeMutation({
      kind: 'start',
      request: {
        requestId: this.newRequestId(),
        action: confirmation.action,
        ...(confirmation.generationId ? { generationId: confirmation.generationId } : {}),
      },
    });
  }

  retryJob(job: SearchJobStatus): void {
    if (
      !this.canMaintain() ||
      this.mutationPending() ||
      this.savePending() ||
      this.activeOperation() ||
      !['FAILED', 'CANCELLED'].includes(job.state)
    )
      return;
    this.executeMutation({ kind: 'retry', jobId: job.id, request: { requestId: this.newRequestId() } });
  }

  cancelJob(job: SearchJobStatus): void {
    if (!this.canMaintain() || this.mutationPending() || this.savePending() || !this.canCancel(job)) return;
    this.executeMutation({ kind: 'cancel', jobId: job.id });
  }

  /** Repeats a mutation whose outcome is unknown with the same request identity, so the server can deduplicate it. */
  retryUncertainMutation(): void {
    const pending = this.uncertainMutation();
    if (pending && !this.mutationPending() && (!this.activeOperation() || pending.kind === 'cancel'))
      this.executeMutation(pending);
  }

  resumePolling(): void {
    const id = this.activeJobId();
    if (id && !this.pollRequest) this.startPolling(id);
  }

  canCancel(job: SearchJobStatus): boolean {
    return ['QUEUED', 'RUNNING', 'VERIFYING'].includes(job.state);
  }

  isTerminal(job: SearchJobStatus): boolean {
    return ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(job.state);
  }

  private loadCategories(): void {
    if (!this.canSearch()) return;
    this.categoriesRequest?.unsubscribe();
    this.categoriesRequest = this.management
      .categories()
      .pipe(catchError(() => of([] as SearchCategory[])))
      .subscribe((categories) => this.categories.set(categories));
  }

  private updateField(
    entity: SearchEntityType,
    index: number,
    update: (field: SearchFieldPolicy) => SearchFieldPolicy,
  ): void {
    this.draft.update((current) => {
      if (!current || !current.fields[entity] || !current.fields[entity][index]) return current;
      const rows = current.fields[entity].map((field, row) => (row === index ? update(field) : field));
      return { ...current, fields: { ...current.fields, [entity]: rows } };
    });
  }

  private executeMutation(action: PendingMutation): void {
    if (this.mutationPending() || this.savePending() || (action.kind !== 'cancel' && this.activeOperation())) return;
    this.mutationPending.set(true);
    this.mutationError.set(null);
    this.uncertainMutation.set(null);
    const request =
      action.kind === 'start'
        ? this.management.startJob(action.request)
        : action.kind === 'retry'
          ? this.management.retry(action.jobId, action.request)
          : this.management.cancel(action.jobId);
    this.mutationRequest = request.subscribe({
      next: (receipt) => {
        this.mutationPending.set(false);
        this.activeJobId.set(receipt.id);
        if (receipt.state === 'CANCELLED') {
          const current = this.activeJob();
          if (current?.id === receipt.id) this.activeJob.set({ ...current, state: 'CANCELLED' });
          this.finishPolling();
        } else {
          this.startPolling(receipt.id);
        }
      },
      error: (error) => {
        const failure = this.problem(error);
        this.mutationPending.set(false);
        this.mutationError.set(failure);
        if (failure.status === 0 || failure.status >= 500) this.uncertainMutation.set(action);
      },
    });
  }

  /**
   * Polls at once and then ten seconds after each answer, so a slow answer, or a poll waiting to be retried, never
   * overlaps the next one. A refused or lost poll is retried with a growing pause that honours Retry-After; only a
   * final failure stops polling and offers to resume it.
   */
  private startPolling(jobId: string): void {
    if (this.activeJobId() === jobId && this.pollRequest) return;
    this.pollRequest?.unsubscribe();
    this.pollRequest = undefined;
    this.activeJobId.set(jobId);
    this.pollError.set(null);
    this.pollDelayed.set(false);
    // The first poll leaves this call first, so the subscription is kept before any answer can end it; each
    // retry and each repeat asks the service again rather than replaying the last answer.
    this.pollRequest = timer(0)
      .pipe(
        switchMap(() =>
          defer(() => this.management.job(jobId)).pipe(
            retry({ count: SEARCH_POLL_MAX_RETRIES, delay: (error, attempt) => this.pollRetry(error, attempt) }),
            repeat({ delay: SEARCH_POLL_INTERVAL_MS }),
          ),
        ),
      )
      .subscribe({
        next: (job) => {
          this.pollDelayed.set(false);
          this.activeJob.set(job);
          if (this.isTerminal(job)) this.finishPolling();
        },
        error: (error) => {
          this.pollDelayed.set(false);
          this.pollError.set(this.problem(error));
          this.pollRequest?.unsubscribe();
          this.pollRequest = undefined;
        },
      });
  }

  private pollRetry(error: unknown, attempt: number): Observable<number> {
    const delay = searchPollRetryDelayMs(this.problem(error), attempt);
    if (delay === null) return throwError(() => error);
    this.pollDelayed.set(true);
    return timer(delay);
  }

  /** A read refused as too frequent is repeated once its Retry-After has passed, at most twice. */
  private retryRateLimited<T>(request: () => Observable<T>): Observable<T> {
    return defer(request).pipe(
      retry({
        count: 2,
        delay: (error: unknown) => {
          const delay = rateLimitedRetryDelayMs(this.problem(error));
          return delay === null ? throwError(() => error) : timer(delay);
        },
      }),
    );
  }

  private finishPolling(): void {
    this.pollRequest?.unsubscribe();
    this.pollRequest = undefined;
    this.activeJobId.set(null);
    this.refreshOperationalData();
  }

  /** A job still running when the screen opens keeps the maintenance gate closed until it ends. */
  private restoreActiveOperation(current: SearchManagementStatus): void {
    const running = current.jobs.find((job) => !this.isTerminal(job));
    if (!running || (this.activeJobId() !== null && this.activeJobId() !== running.id)) return;
    this.activeJob.set(running);
    this.activeJobId.set(running.id);
    if (!this.pollRequest) this.startPolling(running.id);
  }

  private startCooldown(seconds: number): void {
    this.cooldownRequest?.unsubscribe();
    this.previewCooldownSeconds.set(seconds);
    if (seconds === 0) return;
    this.cooldownRequest = timer(1000, 1000).subscribe(() => {
      const remaining = Math.max(0, this.previewCooldownSeconds() - 1);
      this.previewCooldownSeconds.set(remaining);
      if (remaining === 0) {
        this.cooldownRequest?.unsubscribe();
        this.cooldownRequest = undefined;
      }
    });
  }

  private newRequestId(): string {
    return globalThis.crypto.randomUUID();
  }

  /** The answer or the failure as a value, so the resource never holds the API's problem wrapped in its own error. */
  private loaded<T>(request: Observable<T>): Observable<Loaded<T>> {
    return request.pipe(
      map((value): Loaded<T> => ({ value })),
      catchError((error: unknown) => of<Loaded<T>>({ failure: this.problem(error) })),
    );
  }

  private problem(error: unknown): ProblemDetail {
    return toProblemDetail(
      error,
      this.i18n.translate('common.error'),
      this.i18n.translate('settings.search.error.request_failed'),
    );
  }
}
