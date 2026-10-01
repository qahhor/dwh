import { Injectable, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { Observable, catchError, map, of, tap } from 'rxjs';
import {
  CreateNavigationItemPayload,
  CustomNavigationItem,
  NavigationPermissionChoice,
  UpdateNavigationItemPayload,
} from '@core/models/navigation.models';
import { I18nService } from '@core/services/i18n.service';
import { NavigationService } from '@core/services/navigation.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { problemText } from '@shared/ui/problem-text';
import { SaveErrorNotifier } from '@shared/ui/save-errors';

/**
 * The custom menu items of the navigation settings screen and the requests
 * that change them. Provided by the screen; the item form stays there.
 */
@Injectable()
export class NavigationSettingsStore {
  private readonly navService = inject(NavigationService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);
  private readonly saveErrors = inject(SaveErrorNotifier);

  /** A failed reload keeps the list on screen. */
  readonly items = linkedSignal<CustomNavigationItem[] | undefined, CustomNavigationItem[]>({
    source: () => this.itemsResource.value(),
    computation: (loaded, previous) => loaded ?? previous?.value ?? [],
  });
  readonly isSubmitting = signal<boolean>(false);

  readonly isLoading = computed(() => this.itemsResource.isLoading());
  readonly activeCount = computed(() => this.items().filter((i) => i.state === 'A').length);
  readonly embeddedCount = computed(() => this.items().filter((i) => i.targetType === 'EMBEDDED_IFRAME').length);
  readonly externalCount = computed(() => this.items().filter((i) => i.targetType === 'EXTERNAL_LINK').length);

  /** Without the list the item keeps its right: the select shows the stored pair only. */
  readonly permissionChoices = toSignal(
    this.navService.loadPermissionChoices().pipe(
      map((choices) => choices || []),
      catchError(() => of<NavigationPermissionChoice[]>([])),
    ),
    { initialValue: [] },
  );

  private readonly itemsResource = rxResource({
    stream: () =>
      this.navService.loadAllItems().pipe(
        map((data) => data || []),
        catchError((err: unknown) => {
          this.showError(err);
          return of(undefined);
        }),
      ),
  });

  loadItems(): void {
    this.itemsResource.reload();
  }

  /** `onSaved` runs before the list reloads. */
  createItem(payload: CreateNavigationItemPayload, onSaved: () => void): void {
    this.submit(this.navService.createItem(payload), onSaved);
  }

  /**
   * `onSaved` runs before the list reloads. A save refused over a newer revision offers to read the list again; that
   * closes the form as `onSaved` does, so the item is opened from what is saved now.
   */
  updateItem(
    id: number,
    payload: UpdateNavigationItemPayload,
    revision: number | undefined,
    onSaved: () => void,
  ): void {
    this.submit(this.navService.updateItem(id, payload, revision), onSaved, () => {
      onSaved();
      this.loadItems();
    });
  }

  toggleItem(item: CustomNavigationItem): void {
    this.navService.setActive(item.id, item.state !== 'A').subscribe({
      next: () => this.loadItems(),
      error: (err: unknown) => this.showError(err),
    });
  }

  /** Asks before deleting a menu item; the dialog stays open until the server answers. */
  confirmDelete(item: CustomNavigationItem): void {
    this.modal
      .confirm({
        title: this.i18n.translate('nav.settings.delete_modal_title'),
        message: this.i18n.translate('nav.settings.delete_confirm', { title: item.title }),
        yesLabel: this.i18n.translate('common.delete'),
        noLabel: this.i18n.translate('common.cancel'),
        destructive: true,
        action: () =>
          this.navService.deleteItem(item.id, { notifyError: false }).pipe(
            tap(() => {
              this.toast.success(this.i18n.translate('common.saved'));
              this.loadItems();
            }),
          ),
        actionError: (error) => problemText(error) || this.i18n.translate('common.error'),
      })
      .subscribe();
  }

  private submit(request: Observable<unknown>, onSaved: () => void, reload?: () => void): void {
    this.isSubmitting.set(true);
    request.subscribe({
      next: () => {
        this.toast.success(this.i18n.translate('common.saved'));
        this.isSubmitting.set(false);
        onSaved();
        this.loadItems();
      },
      error: (err: unknown) => {
        this.saveErrors.show(err, { fallbackKey: 'common.error', reload });
        this.isSubmitting.set(false);
      },
    });
  }

  private showError(err: unknown): void {
    this.toast.error(problemText(err) || this.i18n.translate('common.error'));
  }
}
