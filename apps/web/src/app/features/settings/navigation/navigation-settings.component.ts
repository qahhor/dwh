import { ChangeDetectionStrategy, Component, Injector, effect, inject, signal } from '@angular/core';
import { form } from '@angular/forms/signals';

import { RouterModule } from '@angular/router';
import {
  CustomNavigationItem,
  CreateNavigationItemPayload,
  UpdateNavigationItemPayload,
  NavigationTargetType,
} from '@core/models/navigation.models';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { focusFirstInvalid } from '@shared/ui/focus-first-invalid';
import {
  NavigationItemForm,
  blankNavigationItem,
  navigationItemForm,
  navigationItemRules,
} from './navigation-item-form';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { transliterateToCode } from '@core/utils/transliteration';
import { NavigationSettingsStatsComponent } from './components/navigation-settings-stats.component';
import { NavigationSettingsTableComponent } from './components/navigation-settings-table.component';
import { NavigationSettingsModalComponent } from './components/navigation-settings-modal.component';
import { NavigationSettingsStore } from './navigation-settings.store';

@Component({
  selector: 'app-navigation-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterModule,
    TranslatePipe,
    SMTButtonComponent,
    NavigationSettingsStatsComponent,
    NavigationSettingsTableComponent,
    NavigationSettingsModalComponent,
    UiPageHeaderComponent,
  ],
  providers: [NavigationSettingsStore],
  templateUrl: './navigation-settings.component.html',
  styleUrl: './navigation-settings.component.css',
})
export class NavigationSettingsComponent {
  /** The menu items, loaded as the screen opens, and their requests; the template reads it directly. */
  readonly store = inject(NavigationSettingsStore);
  private readonly injector = inject(Injector);

  readonly isModalOpen = signal<boolean>(false);
  readonly editingItem = signal<CustomNavigationItem | null>(null);

  /** The item the dialog edits; Signal Forms bind its fields (forms standard, section 1). */
  readonly itemModel = signal<NavigationItemForm>(blankNavigationItem(100));

  /** The server's field errors are drawn first; then focus goes to the first of them. */
  private readonly focusServerErrors = effect(() => {
    if (Object.keys(this.store.fieldErrors()).length === 0) return;
    const formElement = document.getElementById('nav-item-form');
    if (formElement) focusFirstInvalid(formElement, this.injector);
  });

  private readonly askDiscard = discardChangesQuestion();

  searchQuery = '';

  readonly popularIcons = [
    'analytics',
    'bar_chart',
    'pie_chart',
    'table_view',
    'dashboard',
    'insights',
    'monitoring',
    'database',
    'language',
    'open_in_new',
  ];
  readonly itemForm = form(this.itemModel, navigationItemRules);
  /** The item as the dialog opened, so closing asks only when something changed. */
  private openedWith = JSON.stringify(this.itemModel());

  filteredItems(): CustomNavigationItem[] {
    const q = this.searchQuery.trim().toLowerCase();
    if (!q) return this.store.items();
    return this.store
      .items()
      .filter(
        (item) =>
          item.title.toLowerCase().includes(q) ||
          item.code.toLowerCase().includes(q) ||
          item.url.toLowerCase().includes(q) ||
          item.sectionId.toLowerCase().includes(q),
      );
  }

  /** A new item takes its code from the title as it is typed; an existing one keeps its code. */
  onTitleChange(title: string): void {
    if (!this.editingItem() && title) {
      this.itemModel.update((item) => ({ ...item, code: transliterateToCode(title) }));
    }
  }

  normalizeUrl(url: string, targetType: NavigationTargetType): string {
    const trimmed = url.trim();
    if (!trimmed) return '';
    if (targetType === 'INTERNAL_ROUTE') {
      return trimmed.startsWith('/') ? trimmed : '/' + trimmed;
    }
    if (!trimmed.match(/^(https?:\/\/|\/)/i)) {
      return 'https://' + trimmed;
    }
    return trimmed;
  }

  onUrlBlur(): void {
    const { url, targetType } = this.itemModel();
    if (url) {
      this.itemModel.update((item) => ({ ...item, url: this.normalizeUrl(url, targetType) }));
    }
  }

  openCreateModal(): void {
    this.editingItem.set(null);
    this.openWith(blankNavigationItem((this.store.items().length + 1) * 10));
  }

  openEditModal(item: CustomNavigationItem): void {
    this.editingItem.set(item);
    this.openWith(navigationItemForm(item));
  }

  /** Escape, the backdrop, the cross and "Cancel" ask before a changed item is dropped (forms standard, 8). */
  requestCloseModal(): void {
    if (this.store.isSubmitting()) return;
    this.askDiscard(JSON.stringify(this.itemModel()) !== this.openedWith).subscribe((discard) => {
      if (discard) this.closeModal();
    });
  }

  closeModal(): void {
    this.isModalOpen.set(false);
    this.editingItem.set(null);
  }

  previewItem(item: CustomNavigationItem): void {
    if (item.targetType === 'EMBEDDED_IFRAME') {
      window.open(`/embed/${item.code}`, '_blank');
    } else if (item.targetType === 'EXTERNAL_LINK') {
      window.open(item.url, '_blank', 'noopener,noreferrer');
    } else {
      window.open(item.url, '_blank');
    }
  }

  /** Enter and the primary button land here; errors show under the fields, and one request runs at a time. */
  saveItem(): void {
    if (this.store.isSubmitting()) return;
    markSMTFormFieldsTouched(this.itemForm);
    const item = this.itemModel();
    if (!this.itemForm().valid() || !item.title.trim() || !item.code.trim() || !item.url.trim()) return;

    const finalUrl = this.normalizeUrl(item.url, item.targetType);
    const finalCode = transliterateToCode(item.code.trim()) || item.code.trim().toLowerCase();
    const payload: CreateNavigationItemPayload = {
      code: finalCode,
      title: item.title.trim(),
      targetType: item.targetType,
      sectionId: item.sectionId,
      url: finalUrl,
      icon: item.icon.trim() || 'bar_chart',
      // An empty order was sent as null, which the server reads as 0.
      sortOrder: item.sortOrder ?? 0,
      openInIframe: item.targetType === 'EMBEDDED_IFRAME',
      requiredPermission: item.requiredPermission,
    };

    const editing = this.editingItem();
    if (editing) {
      // An edit keeps what the form does not show: the parent, the title key and the state.
      const update: UpdateNavigationItemPayload = {
        ...payload,
        parentId: editing.parentId ?? null,
        titleKey: editing.titleKey ?? null,
        state: editing.state,
      };
      this.store.updateItem(editing.id, update, editing.revision, () => this.closeModal());
    } else {
      this.store.createItem(payload, () => this.closeModal());
    }
  }

  private openWith(item: NavigationItemForm): void {
    // A new opening starts untouched, so no field shows an error before it is used.
    this.itemForm().reset(item);
    this.openedWith = JSON.stringify(item);
    this.store.fieldErrors.set({});
    this.isModalOpen.set(true);
  }
}
