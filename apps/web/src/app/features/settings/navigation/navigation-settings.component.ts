import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { RouterModule } from '@angular/router';
import {
  CustomNavigationItem,
  CreateNavigationItemPayload,
  UpdateNavigationItemPayload,
  NavigationTargetType,
} from '@core/models/navigation.models';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
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
  ],
  providers: [NavigationSettingsStore],
  templateUrl: './navigation-settings.component.html',
  styleUrl: './navigation-settings.component.css',
})
export class NavigationSettingsComponent implements OnInit {
  /** The menu items and their requests; the template reads it directly. */
  readonly store = inject(NavigationSettingsStore);

  readonly isModalOpen = signal<boolean>(false);
  readonly editingItem = signal<CustomNavigationItem | null>(null);

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

  formCode = '';
  formTitle = '';
  formTargetType: NavigationTargetType = 'EMBEDDED_IFRAME';
  formSectionId = 'custom';
  formUrl = '';
  formIcon = 'analytics';
  /** Null while the person has emptied the order field. */
  formSortOrder: number | null = 100;
  formRequiredPermission: string | null = null;

  ngOnInit(): void {
    this.store.loadItems();
    this.store.loadPermissionChoices();
  }

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

  onTitleChange(): void {
    if (!this.editingItem() && this.formTitle) {
      this.formCode = transliterateToCode(this.formTitle);
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
    if (this.formUrl) {
      this.formUrl = this.normalizeUrl(this.formUrl, this.formTargetType);
    }
  }

  isFormValid(): boolean {
    return !!(this.formTitle.trim() && this.formCode.trim() && this.formUrl.trim());
  }

  openCreateModal(): void {
    this.editingItem.set(null);
    this.formCode = '';
    this.formTitle = '';
    this.formTargetType = 'EMBEDDED_IFRAME';
    this.formSectionId = 'custom';
    this.formUrl = '';
    this.formIcon = 'analytics';
    this.formSortOrder = (this.store.items().length + 1) * 10;
    this.formRequiredPermission = null;
    this.isModalOpen.set(true);
  }

  openEditModal(item: CustomNavigationItem): void {
    this.editingItem.set(item);
    this.formCode = item.code;
    this.formTitle = item.title;
    this.formTargetType = item.targetType;
    this.formSectionId = item.sectionId;
    this.formUrl = item.url;
    this.formIcon = item.icon;
    this.formSortOrder = item.sortOrder;
    this.formRequiredPermission = item.requiredPermission ?? null;
    this.isModalOpen.set(true);
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

  saveItem(): void {
    if (!this.isFormValid()) return;

    const finalUrl = this.normalizeUrl(this.formUrl, this.formTargetType);
    const finalCode = transliterateToCode(this.formCode.trim()) || this.formCode.trim().toLowerCase();
    const payload: CreateNavigationItemPayload = {
      code: finalCode,
      title: this.formTitle.trim(),
      targetType: this.formTargetType,
      sectionId: this.formSectionId,
      url: finalUrl,
      icon: this.formIcon.trim() || 'bar_chart',
      // An empty order was sent as null, which the server reads as 0.
      sortOrder: this.formSortOrder ?? 0,
      openInIframe: this.formTargetType === 'EMBEDDED_IFRAME',
      requiredPermission: this.formRequiredPermission,
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
      this.store.updateItem(editing.id, update, () => this.closeModal());
    } else {
      this.store.createItem(payload, () => this.closeModal());
    }
  }
}
