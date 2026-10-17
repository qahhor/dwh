import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, forkJoin, Subject, Subscription, take } from 'rxjs';
import type { components } from '@core/api/openapi';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { NavigationService } from '@core/services/navigation.service';
import { PermissionService } from '@core/services/permission.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { problemText } from '@shared/ui/problem-text';
import type { SimpleField, SimpleFormDeclaration, SimpleFormPackage } from '../new-form/new-form.models';
import { incompatibleChanges, normalizedDeclaration, versionDraft, type VersionChange } from './form-version.rules';

type FormDetail = components['schemas']['StudioFormDetail'];

/** A problem of the server, by field, with the change it names when it is a refused change. */
export interface VersionIssue {
  path: string;
  message: string;
  change?: string;
}

/**
 * A new version of a live form (ADR-0041): seeded from the active version, checked locally by the same
 * compatibility rules as the server, prepared into a package and activated from the revision that was
 * read. A stale revision (409) keeps the draft and asks to reload.
 */
@Injectable()
export class FormVersionStore {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);
  private readonly navigation = inject(NavigationService);
  private readonly permissions = inject(PermissionService);
  private readonly destroyRef = inject(DestroyRef);

  readonly code = signal('');
  readonly detail = signal<FormDetail | null>(null);
  readonly loading = signal(false);
  readonly loadError = signal('');
  readonly draft = signal<SimpleFormDeclaration | null>(null);
  readonly prepared = signal<SimpleFormPackage | null>(null);
  readonly preparing = signal(false);
  readonly publishing = signal(false);
  readonly confirming = signal(false);
  readonly error = signal('');
  readonly conflict = signal(false);
  readonly issues = signal<VersionIssue[]>([]);
  readonly activated = signal<FormDetail | null>(null);
  private readonly baseline = signal('');

  readonly mayPublish = computed(() => this.permissions.hasPermission('studio.forms', 'publish'));
  readonly active = computed(() => (this.detail()?.declaration ?? null) as SimpleFormDeclaration | null);
  readonly stored = computed(() => new Map((this.active()?.fields ?? []).map((field) => [field.id, field] as const)));
  readonly changes = computed<VersionChange[]>(() => {
    const active = this.active();
    const draft = this.draft();
    return active && draft ? incompatibleChanges(active, normalizedDeclaration(draft)) : [];
  });
  readonly dirty = computed(() => {
    const draft = this.draft();
    return draft !== null && JSON.stringify(draft) !== this.baseline();
  });
  readonly busy = computed(() => this.loading() || this.preparing() || this.publishing() || this.confirming());
  readonly archived = computed(() => this.detail()?.form?.status === 'archived');
  readonly canActivate = computed(
    () =>
      this.mayPublish() &&
      !this.busy() &&
      !this.archived() &&
      this.changes().length === 0 &&
      this.prepared() !== null &&
      this.activated() === null,
  );

  private readonly closing = new Subject<void>();
  private request?: Subscription;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.closing.next();
      this.closing.complete();
    });
  }

  load(code: string): void {
    if (!this.mayPublish()) return;
    this.code.set(code);
    this.loading.set(true);
    this.loadError.set('');
    this.api
      .get<FormDetail>(`/studio/forms/${encodeURIComponent(code)}`, undefined, { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (detail) => this.seed(detail),
        error: (error) => this.loadError.set(problemText(error) || this.i18n.translate('studio.version.load_failed')),
      });
  }

  /** The stored form of a field in the active version, or null for a field added in this version. */
  storedField(id: string): SimpleField | null {
    return this.stored().get(id) ?? null;
  }

  edit(update: (draft: SimpleFormDeclaration) => void): void {
    const current = this.draft();
    if (!current || this.busy() || this.activated()) return;
    const next = structuredClone(current);
    update(next);
    if (JSON.stringify(next) === JSON.stringify(current)) return;
    this.request?.unsubscribe();
    this.prepared.set(null);
    this.clear();
    this.draft.set(next);
  }

  prepare(): void {
    const draft = this.draft();
    if (!draft || this.busy() || this.changes().length > 0) return;
    this.clear();
    this.preparing.set(true);
    this.request = this.api
      .post<SimpleFormPackage>('/form-studio/new-forms/prepare', normalizedDeclaration(draft), { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.preparing.set(false)),
      )
      .subscribe({
        next: (prepared) => this.prepared.set(structuredClone(prepared)),
        error: (error) => this.fail(error, 'studio.version.prepare_failed'),
      });
  }

  /** Activates the prepared version after an explicit confirmation, from the revision that was read. */
  activate(): void {
    const prepared = this.prepared();
    if (!prepared || !this.canActivate()) return;
    this.confirming.set(true);
    this.modal
      .confirm({
        title: this.i18n.translate('studio.version.activate_title'),
        message: this.i18n.translate('studio.version.activate_message', { code: this.code() }),
        yesLabel: this.i18n.translate('studio.version.activate'),
        noLabel: this.i18n.translate('common.cancel'),
        closeWhen: this.closing,
      })
      .pipe(
        take(1),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.confirming.set(false)),
      )
      .subscribe((confirmed) => {
        this.confirming.set(false);
        if (confirmed && this.prepared() === prepared && this.canActivate()) this.send(prepared);
      });
  }

  private send(prepared: SimpleFormPackage): void {
    this.clear();
    this.publishing.set(true);
    const body = new Blob([JSON.stringify(prepared)], { type: 'application/json' });
    this.api
      .post<FormDetail>(`/studio/forms/${encodeURIComponent(this.code())}/versions`, body, {
        notifyError: false,
        ifMatch: this.detail()?.form?.revision ?? null,
      })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.publishing.set(false)),
      )
      .subscribe({
        next: (detail) => {
          this.activated.set(detail);
          this.seed(detail, false);
          const language = this.i18n.currentLang();
          forkJoin([this.i18n.refreshLanguage(language), this.navigation.loadEntityItems()])
            .pipe(take(1), takeUntilDestroyed(this.destroyRef))
            .subscribe({ error: () => this.error.set(this.i18n.translate('studio.version.refresh_failed')) });
        },
        error: (error) => this.fail(error, 'studio.version.activate_failed'),
      });
  }

  private seed(detail: FormDetail, reset = true): void {
    this.detail.set(detail);
    const declaration = detail.declaration as SimpleFormDeclaration | undefined;
    if (!declaration) return;
    const draft = versionDraft(declaration);
    this.baseline.set(JSON.stringify(draft));
    if (reset) {
      this.draft.set(draft);
      this.prepared.set(null);
    }
  }

  private fail(error: unknown, fallback: string): void {
    const problem = (error ?? {}) as { status?: number; errors?: unknown };
    this.conflict.set(problem.status === 409 || problem.status === 428);
    this.error.set(problemText(error) || this.i18n.translate(fallback));
    const errors = Array.isArray(problem.errors) ? problem.errors : [];
    this.issues.set(
      errors.slice(0, 30).flatMap((item: { field?: unknown; message?: unknown; code?: unknown }) =>
        typeof item?.field === 'string'
          ? [
              {
                path: item.field.slice(0, 160),
                message: typeof item.message === 'string' ? item.message.slice(0, 500) : '',
                change:
                  typeof item.code === 'string' && item.code.startsWith('change_') ? item.code.slice(7) : undefined,
              },
            ]
          : [],
      ),
    );
  }

  private clear(): void {
    this.error.set('');
    this.conflict.set(false);
    this.issues.set([]);
  }
}
