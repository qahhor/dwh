import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { NavigationService } from '../../../core/services/navigation.service';
import {
  CustomNavigationItem,
  CreateNavigationItemPayload,
  UpdateNavigationItemPayload,
  NavigationTargetType
} from '../../../core/models/navigation.models';
import { ToastService } from '../../../core/services/toast.service';
import { TranslatePipe, I18nService } from '../../../core/services/i18n.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { transliterateToCode } from '../../../core/utils/transliteration';
import { NavigationSettingsStatsComponent } from './components/navigation-settings-stats.component';
import { NavigationSettingsTableComponent } from './components/navigation-settings-table.component';
import { NavigationSettingsModalComponent } from './components/navigation-settings-modal.component';
import { tap } from 'rxjs';
import { SMTModalService } from '../../../shared/ui-kit/components/modal';
import { problemText } from '../../../shared/ui/problem-text';

@Component({
  selector: 'app-navigation-settings',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterModule,
    TranslatePipe,
    UiButtonComponent,
    NavigationSettingsStatsComponent,
    NavigationSettingsTableComponent,
    NavigationSettingsModalComponent
  ],
  template: `
    <div class="nav-settings-page">
      <!-- Header -->
      <div class="page-header">
        <div class="header-titles">
          <h1 class="page-title">{{ 'nav.settings.navigation_title' | t }}</h1>
          <p class="page-subtitle">{{ 'nav.settings.navigation_subtitle' | t }}</p>
        </div>
        <div class="header-actions">
          <ui-button
            variant="primary"
            icon="add"
            (onClick)="openCreateModal()"
          >
            {{ 'nav.settings.add_item' | t }}
          </ui-button>
        </div>
      </div>

      <!-- Stats Summary -->
      <app-navigation-settings-stats
        [totalCount]="items().length"
        [activeCount]="activeCount()"
        [embeddedCount]="embeddedCount()"
        [externalCount]="externalCount()"
      ></app-navigation-settings-stats>

      <!-- Table & Filters -->
      <app-navigation-settings-table
        [items]="filteredItems()"
        [isLoading]="isLoading()"
        [searchQuery]="searchQuery"
        (searchQueryChange)="searchQuery = $event"
        (clearSearch)="searchQuery = ''"
        (toggleItem)="toggleItem($event)"
        (previewItem)="previewItem($event)"
        (editItem)="openEditModal($event)"
        (deleteItem)="confirmDelete($event)"
      ></app-navigation-settings-table>

      <!-- Modals (Create/Edit & Delete) -->
      <app-navigation-settings-modal
        [isModalOpen]="isModalOpen()"
        [editingItem]="editingItem()"
        [isSubmitting]="isSubmitting()"
        [isFormValid]="isFormValid()"
        [formTitle]="formTitle"
        [formCode]="formCode"
        [formTargetType]="formTargetType"
        [formSectionId]="formSectionId"
        [formSortOrder]="formSortOrder"
        [formUrl]="formUrl"
        [formIcon]="formIcon"
        [popularIcons]="popularIcons"
        (formTitleChange)="formTitle = $event"
        (formCodeChange)="formCode = $event"
        (formTargetTypeChange)="formTargetType = $event"
        (formSectionIdChange)="formSectionId = $event"
        (formSortOrderChange)="formSortOrder = $event"
        (formUrlChange)="formUrl = $event"
        (formIconChange)="formIcon = $event"
        (titleChange)="onTitleChange()"
        (urlBlur)="onUrlBlur()"
        (closeModal)="closeModal()"
        (saveItem)="saveItem()"
      ></app-navigation-settings-modal>
    </div>
  `,
  styles: [`
    .nav-settings-page {
      padding: 24px;
      max-width: 1200px;
      margin: 0 auto;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }

    .page-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
    }

    .page-title {
      font-size: 22px;
      font-weight: 700;
      color: var(--text-main);
      margin: 0 0 4px 0;
    }

    .page-subtitle {
      font-size: 13px;
      color: var(--text-muted);
      margin: 0;
    }
  `]
})
export class NavigationSettingsComponent implements OnInit {
  private readonly navService = inject(NavigationService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

  readonly items = signal<CustomNavigationItem[]>([]);
  readonly isLoading = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);
  readonly isModalOpen = signal<boolean>(false);
  readonly editingItem = signal<CustomNavigationItem | null>(null);

  readonly activeCount = computed(() => this.items().filter(i => i.state === 'A').length);
  readonly embeddedCount = computed(() => this.items().filter(i => i.targetType === 'EMBEDDED_IFRAME').length);
  readonly externalCount = computed(() => this.items().filter(i => i.targetType === 'EXTERNAL_LINK').length);

  searchQuery = '';

  readonly popularIcons = [
    'analytics', 'bar_chart', 'pie_chart', 'table_view', 'dashboard',
    'insights', 'monitoring', 'database', 'language', 'open_in_new'
  ];

  formCode = '';
  formTitle = '';
  formTargetType: NavigationTargetType = 'EMBEDDED_IFRAME';
  formSectionId = 'custom';
  formUrl = '';
  formIcon = 'analytics';
  formSortOrder = 100;

  ngOnInit(): void {
    this.loadItems();
  }

  loadItems(): void {
    this.isLoading.set(true);
    this.navService.loadAllItems().subscribe({
      next: data => {
        this.items.set(data || []);
        this.isLoading.set(false);
      },
      error: (err: any) => {
        const msg = err?.error?.detail || err?.error?.message || this.i18n.translate('common.error');
        this.toast.error(msg);
        this.isLoading.set(false);
      }
    });
  }

  filteredItems(): CustomNavigationItem[] {
    const q = this.searchQuery.trim().toLowerCase();
    if (!q) return this.items();
    return this.items().filter(item =>
      item.title.toLowerCase().includes(q) ||
      item.code.toLowerCase().includes(q) ||
      item.url.toLowerCase().includes(q) ||
      item.sectionId.toLowerCase().includes(q)
    );
  }

  targetTypeLabel(type: NavigationTargetType): string {
    switch (type) {
      case 'EMBEDDED_IFRAME': return this.i18n.translate('nav.settings.type_embedded');
      case 'EXTERNAL_LINK': return this.i18n.translate('nav.settings.type_external');
      case 'INTERNAL_ROUTE': return this.i18n.translate('nav.settings.type_internal');
    }
  }

  sectionLabel(sectionId: string): string {
    switch (sectionId) {
      case 'custom': return this.i18n.translate('nav.settings.section_custom');
      case 'workspace': return this.i18n.translate('nav.section.workspace');
      case 'iam': return this.i18n.translate('nav.section.iam');
      case 'administration': return this.i18n.translate('nav.section.administration');
      default: return sectionId;
    }
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
    this.formSortOrder = (this.items().length + 1) * 10;
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
    this.isSubmitting.set(true);

    const finalUrl = this.normalizeUrl(this.formUrl, this.formTargetType);
    const finalCode = transliterateToCode(this.formCode.trim()) || this.formCode.trim().toLowerCase();

    const editing = this.editingItem();
    if (editing) {
      const payload: UpdateNavigationItemPayload = {
        code: finalCode,
        title: this.formTitle.trim(),
        targetType: this.formTargetType,
        sectionId: this.formSectionId,
        url: finalUrl,
        icon: this.formIcon.trim() || 'bar_chart',
        sortOrder: this.formSortOrder,
        openInIframe: this.formTargetType === 'EMBEDDED_IFRAME',
        state: editing.state
      };
      this.navService.updateItem(editing.id, payload).subscribe({
        next: () => {
          this.toast.success(this.i18n.translate('common.saved'));
          this.isSubmitting.set(false);
          this.closeModal();
          this.loadItems();
        },
        error: (err: any) => {
          const msg = err?.error?.detail || err?.error?.message || this.i18n.translate('common.error');
          this.toast.error(msg);
          this.isSubmitting.set(false);
        }
      });
    } else {
      const payload: CreateNavigationItemPayload = {
        code: finalCode,
        title: this.formTitle.trim(),
        targetType: this.formTargetType,
        sectionId: this.formSectionId,
        url: finalUrl,
        icon: this.formIcon.trim() || 'bar_chart',
        sortOrder: this.formSortOrder,
        openInIframe: this.formTargetType === 'EMBEDDED_IFRAME'
      };
      this.navService.createItem(payload).subscribe({
        next: () => {
          this.toast.success(this.i18n.translate('common.saved'));
          this.isSubmitting.set(false);
          this.closeModal();
          this.loadItems();
        },
        error: (err: any) => {
          const msg = err?.error?.detail || err?.error?.message || this.i18n.translate('common.error');
          this.toast.error(msg);
          this.isSubmitting.set(false);
        }
      });
    }
  }

  toggleItem(item: CustomNavigationItem): void {
    this.navService.toggleItem(item.id).subscribe({
      next: () => this.loadItems(),
      error: (err: any) => {
        const msg = err?.error?.detail || err?.error?.message || this.i18n.translate('common.error');
        this.toast.error(msg);
      }
    });
  }

  /** Asks before deleting a menu item; the dialog stays open until the server answers. */
  confirmDelete(item: CustomNavigationItem): void {
    this.modal.confirm({
      title: this.i18n.translate('nav.settings.delete_modal_title'),
      message: this.i18n.translate('nav.settings.delete_confirm', { title: item.title }),
      yesLabel: this.i18n.translate('common.delete'),
      noLabel: this.i18n.translate('common.cancel'),
      destructive: true,
      action: () => this.navService.deleteItem(item.id, { notifyError: false }).pipe(
        tap(() => {
          this.toast.success(this.i18n.translate('common.saved'));
          this.loadItems();
        })
      ),
      actionError: error => problemText(error) || this.i18n.translate('common.error')
    }).subscribe();
  }
}
