import { computed, DestroyRef, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Subscription } from 'rxjs';
import type { ApiSchema } from '@core/api/api-schema';
import type { User } from '@core/models/auth.models';
import { AuthService } from '@core/services/auth.service';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { problemText } from '@shared/ui/problem-text';
import { NewFormStore } from './new-form.store';
import type { SimpleFormDeclaration } from './new-form.models';

interface AssistantAccess {
  user: User | null;
  userId: number | null;
  version: number;
  permissions: ReadonlySet<string>;
  loggingOut: boolean;
}

/** Suggestions stay separate from the editor until the author applies one (ADR-0039). */
@Injectable()
export class NewFormAssistantStore {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);
  private readonly permissions = inject(PermissionService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly source = inject(NewFormStore);

  readonly prompt = signal('');
  readonly proposal = signal<SimpleFormDeclaration | null>(null);
  readonly error = signal('');
  readonly available = signal(false);
  readonly checking = signal(false);
  readonly busy = signal(false);

  readonly mayAssist = computed(
    () =>
      this.permissions.hasPermission('studio.forms', 'assist') &&
      this.permissions.hasPermission('md.settings', 'update'),
  );
  readonly length = computed(() => [...this.prompt()].length);
  readonly valid = computed(() => this.prompt().trim().length > 0 && this.length() <= 2000);
  readonly context = computed(() => JSON.stringify([this.source.delivery(), this.source.draft()]));
  readonly canGenerate = computed(
    () =>
      this.mayAssist() &&
      this.available() &&
      this.availableAccess === this.accessContext() &&
      !this.accessContext().loggingOut &&
      this.valid() &&
      !this.busy() &&
      !this.checking() &&
      !this.source.locked() &&
      !this.source.importing() &&
      !this.source.busy(),
  );
  readonly canApply = computed(
    () =>
      this.mayAssist() &&
      this.proposal() !== null &&
      this.requestAccess === this.accessContext() &&
      !this.accessContext().loggingOut &&
      this.context() === this.requestContext &&
      !this.source.locked() &&
      !this.source.importing() &&
      !this.source.busy(),
  );
  private readonly accessContext = computed<AssistantAccess>(() => ({
    user: this.auth.currentUser(),
    userId: this.auth.currentUser()?.id ?? null,
    version: this.permissions.permissionVersion(),
    permissions: this.permissions.permissions(),
    loggingOut: this.auth.isLoggingOut(),
  }));

  private request?: Subscription;
  private statusRequest?: Subscription;
  private operation = 0;
  private requestContext = '';
  private requestAccess: AssistantAccess | null = null;
  private availableAccess: AssistantAccess | null = null;
  private observedAccess: AssistantAccess | null = null;
  private statusOperation = 0;

  constructor() {
    effect(() => {
      const allowed = this.mayAssist();
      const access = this.accessContext();
      untracked(() => {
        const previous = this.observedAccess;
        this.observedAccess = access;
        this.cancel();
        ++this.statusOperation;
        this.statusRequest?.unsubscribe();
        this.checking.set(false);
        this.available.set(false);
        if (previous && previous.userId !== access.userId) {
          this.prompt.set('');
          this.error.set('');
        }
        if (allowed && !access.loggingOut) this.load();
      });
    });
    effect(() => {
      const context = this.context();
      untracked(() => {
        if (this.requestContext && context !== this.requestContext) this.cancel();
      });
    });
  }

  load(): void {
    if (!this.mayAssist() || this.checking() || this.accessContext().loggingOut) return;
    const operation = ++this.statusOperation;
    const access = this.accessContext();
    const current = () => operation === this.statusOperation && access === this.accessContext() && this.mayAssist();
    this.available.set(false);
    this.checking.set(true);
    this.error.set('');
    this.statusRequest = this.api
      .get<ApiSchema<'StudioAssistantAvailability'>>('/studio/assistant', undefined, { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (current()) this.checking.set(false);
        }),
      )
      .subscribe({
        next: (status) => {
          if (current()) {
            this.availableAccess = access;
            this.available.set(status.available === true);
          }
        },
        error: (error) => {
          if (current()) this.fail(error);
        },
      });
  }
  setPrompt(value: string): void {
    this.cancel();
    this.prompt.set(value);
    this.error.set('');
  }
  generate(): void {
    if (!this.canGenerate()) return;
    this.cancel();
    const operation = this.operation;
    this.requestContext = this.context();
    this.requestAccess = this.accessContext();
    this.error.set('');
    this.busy.set(true);
    this.request = this.api
      .post<SimpleFormDeclaration>(
        '/studio/assistant/drafts',
        { prompt: this.prompt() } satisfies ApiSchema<'StudioAssistantRequest'>,
        { notifyError: false },
      )
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (operation === this.operation) this.busy.set(false);
        }),
      )
      .subscribe({
        next: (proposal) => {
          if (
            operation === this.operation &&
            this.mayAssist() &&
            this.requestAccess === this.accessContext() &&
            this.context() === this.requestContext
          )
            this.proposal.set(structuredClone(proposal));
        },
        error: (error) => {
          if (operation === this.operation && this.mayAssist() && this.requestAccess === this.accessContext())
            this.fail(error);
        },
      });
  }
  apply(proposal: SimpleFormDeclaration): boolean {
    if (!this.canApply() || this.proposal() !== proposal) return false;
    this.source.applySuggested(proposal);
    this.cancel();
    return true;
  }
  cancel(): void {
    ++this.operation;
    this.request?.unsubscribe();
    this.busy.set(false);
    this.proposal.set(null);
    this.requestContext = '';
    this.requestAccess = null;
  }
  private fail(error: unknown): void {
    this.error.set(problemText(error) || this.i18n.translate('error.studio.assistant_unavailable'));
  }
}
