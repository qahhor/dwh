import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Subject, take } from 'rxjs';
import type { components } from '@core/api/openapi';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { NavigationService } from '@core/services/navigation.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { problemText } from '@shared/ui/problem-text';

export type StudioFormSummary = Required<Omit<components['schemas']['StudioFormSummary'], 'broken'>> & {
  broken?: components['schemas']['StudioFormSummary']['broken'];
};
type FormPage = components['schemas']['KeysetPageStudioFormSummary'];

/** The live forms of the publisher, a page at a time, with archive and restore from the revision seen (ADR-0041). */
@Injectable()
export class MyFormsStore {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);
  private readonly navigation = inject(NavigationService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);

  readonly forms = signal<StudioFormSummary[]>([]);
  readonly loading = signal(false);
  readonly loadingMore = signal(false);
  readonly error = signal('');
  readonly actionError = signal('');
  readonly changing = signal<string | null>(null);
  readonly nextCursor = signal<string | null>(null);
  readonly mayPublish = computed(() => this.permissions.hasPermission('studio.forms', 'publish'));
  private readonly closing = new Subject<void>();

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.closing.next();
      this.closing.complete();
    });
  }

  load(): void {
    if (!this.mayPublish() || this.loading()) return;
    this.loading.set(true);
    this.error.set('');
    this.api
      .get<FormPage>('/studio/forms', { limit: 50 }, { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (page) => {
          this.forms.set((page.items ?? []) as StudioFormSummary[]);
          this.nextCursor.set(page.hasMore ? (page.nextCursor ?? null) : null);
        },
        error: (error) => this.error.set(problemText(error) || this.i18n.translate('studio.forms.load_failed')),
      });
  }

  loadMore(): void {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore()) return;
    this.loadingMore.set(true);
    this.api
      .get<FormPage>('/studio/forms', { limit: 50, cursor }, { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loadingMore.set(false)),
      )
      .subscribe({
        next: (page) => {
          this.forms.update((forms) => [...forms, ...((page.items ?? []) as StudioFormSummary[])]);
          this.nextCursor.set(page.hasMore ? (page.nextCursor ?? null) : null);
        },
        error: (error) => this.error.set(problemText(error) || this.i18n.translate('studio.forms.load_failed')),
      });
  }

  /** Archives or restores after an explicit confirmation, from the revision the list shows. */
  changeLifecycle(form: StudioFormSummary, archive: boolean): void {
    if (!this.mayPublish() || this.changing()) return;
    const title = this.title(form);
    this.modal
      .confirm({
        title: this.i18n.translate(archive ? 'studio.forms.archive_title' : 'studio.forms.restore_title'),
        message: this.i18n.translate(archive ? 'studio.forms.archive_message' : 'studio.forms.restore_message', {
          name: title,
        }),
        yesLabel: this.i18n.translate(archive ? 'studio.forms.archive' : 'studio.forms.restore'),
        noLabel: this.i18n.translate('common.cancel'),
        destructive: archive,
        closeWhen: this.closing,
      })
      .pipe(take(1), takeUntilDestroyed(this.destroyRef))
      .subscribe((confirmed) => {
        if (confirmed) this.send(form, archive);
      });
  }

  /** The title of the form in the language of the interface. */
  title(form: StudioFormSummary): string {
    const language = this.i18n.currentLang();
    return form.title[language === 'uz' || language === 'en' ? language : 'ru'];
  }

  private send(form: StudioFormSummary, archive: boolean): void {
    this.actionError.set('');
    this.changing.set(form.entityCode);
    const code = encodeURIComponent(form.entityCode);
    const options = { notifyError: false, ifMatch: form.revision };
    const request = archive
      ? this.api.post<StudioFormSummary>(`/studio/forms/${code}/archive`, null, options)
      : this.api.post<StudioFormSummary>(`/studio/forms/${code}/restore`, null, options);
    request
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.changing.set(null)),
      )
      .subscribe({
        next: (changed) => {
          this.forms.update((forms) => forms.map((one) => (one.entityCode === changed.entityCode ? changed : one)));
          this.toast.success(this.i18n.translate(archive ? 'studio.forms.archived' : 'studio.forms.restored'));
          this.navigation.loadEntityItems().pipe(take(1), takeUntilDestroyed(this.destroyRef)).subscribe();
        },
        error: (error) => {
          this.actionError.set(
            problemText(error) ||
              this.i18n.translate(archive ? 'studio.forms.archive_failed' : 'studio.forms.restore_failed'),
          );
          // A stale revision means another publisher changed the form: show its state now.
          if ((error as { status?: number })?.status === 409) this.load();
        },
      });
  }
}
