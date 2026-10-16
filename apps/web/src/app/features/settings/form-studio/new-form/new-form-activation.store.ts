import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, forkJoin, Subject, switchMap, take } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import type { components } from '@core/api/openapi';
import { AuthService } from '@core/services/auth.service';
import { I18nService } from '@core/services/i18n.service';
import { ModuleService } from '@core/services/module.service';
import { NavigationService } from '@core/services/navigation.service';
import { PermissionService } from '@core/services/permission.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { problemText } from '@shared/ui/problem-text';
import type { SimpleFormPackage } from './new-form.models';
import { NewFormStore } from './new-form.store';

type ActivationAvailability = Required<components['schemas']['StudioAvailability']>;
type ActivatedForm = Required<components['schemas']['StudioActivatedForm']>;

/** Explicit create-only activation; an uncertain response can retry the exact package (ADR-0038). */
@Injectable()
export class NewFormActivationStore {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);
  private readonly modules = inject(ModuleService);
  private readonly navigation = inject(NavigationService);
  private readonly permissions = inject(PermissionService);
  private readonly modal = inject(SMTModalService);
  private readonly source = inject(NewFormStore);
  private readonly destroyRef = inject(DestroyRef);
  readonly availability = signal<ActivationAvailability | null>(null);
  readonly availabilityLoading = signal(false);
  readonly availabilityError = signal('');
  readonly error = signal('');
  readonly refreshError = signal('');
  readonly publishing = signal(false);
  readonly confirming = signal(false);
  readonly refreshing = signal(false);
  private readonly activated = signal<ActivatedForm | null>(null);
  readonly mayPublish = computed(() => this.permissions.hasPermission('studio.forms', 'publish'));
  readonly busy = computed(() => this.publishing() || this.confirming() || this.refreshing());
  readonly result = computed(() => {
    const result = this.activated();
    return result && result.checksum === this.source.prepared()?.checksum && this.source.delivery() === 'live'
      ? result
      : null;
  });
  readonly canOpen = computed(() => this.result() !== null && this.permissions.canView(this.result()!.entityCode));
  readonly canActivate = computed(
    () =>
      this.mayPublish() &&
      this.source.delivery() === 'live' &&
      !this.busy() &&
      !this.source.busy() &&
      !this.availabilityLoading() &&
      this.availability()?.enabled === true &&
      this.availability()?.namespace === 'studio' &&
      this.source.prepared()?.declaration.module.code === 'studio' &&
      !this.result(),
  );

  private readonly closing = new Subject<void>();

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.closing.next();
      this.closing.complete();
    });
  }

  loadAvailability(): void {
    if (!this.mayPublish() || this.availabilityLoading() || this.busy()) return;
    this.availability.set(null);
    this.availabilityError.set('');
    this.availabilityLoading.set(true);
    this.api
      .get<ActivationAvailability>('/studio/activation', undefined, { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.availabilityLoading.set(false)),
      )
      .subscribe({
        next: (status) => this.availability.set(status),
        error: (error) =>
          this.availabilityError.set(
            problemText(error) || this.i18n.translate('settings.simple_form.activation_status_failed'),
          ),
      });
  }

  activate(): void {
    const prepared = this.source.prepared();
    if (!prepared || !this.canActivate()) return;
    this.confirming.set(true);
    this.modal
      .confirm({
        title: this.i18n.translate('settings.simple_form.activate_title'),
        message: this.i18n.translate('settings.simple_form.activate_message', {
          code: `studio.${prepared.declaration.entity.name}`,
        }),
        yesLabel: this.i18n.translate('settings.simple_form.activate'),
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
        if (confirmed && this.source.prepared() === prepared && this.canActivate()) this.send(prepared);
      });
  }

  refresh(): void {
    if (!this.result() || this.refreshing()) return;
    this.refreshError.set('');
    this.refreshing.set(true);
    this.auth
      .refreshMe()
      .pipe(
        switchMap(() =>
          forkJoin([
            this.i18n.refreshLanguage('ru'),
            this.modules.loadActiveModules(),
            this.navigation.loadEntityItems(),
          ]),
        ),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.refreshing.set(false)),
      )
      .subscribe({
        error: () => this.refreshError.set(this.i18n.translate('settings.simple_form.activation_refresh_failed')),
      });
  }

  private send(prepared: SimpleFormPackage): void {
    this.error.set('');
    this.refreshError.set('');
    this.publishing.set(true);
    this.source.locked.set(true);
    const body = new Blob([JSON.stringify(prepared)], { type: 'application/json' });
    this.api
      .post<ActivatedForm>('/studio/forms', body, { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.publishing.set(false);
          this.source.locked.set(false);
        }),
      )
      .subscribe({
        next: (result) => {
          if (
            result.entityCode !== `studio.${prepared.declaration.entity.name}` ||
            result.checksum !== prepared.checksum
          ) {
            this.error.set(this.i18n.translate('settings.simple_form.activation_unconfirmed'));
            return;
          }
          this.activated.set(result);
          this.source.markDownloaded();
          this.refresh();
        },
        error: (error) =>
          this.error.set(problemText(error) || this.i18n.translate('settings.simple_form.activation_failed')),
      });
  }
}
