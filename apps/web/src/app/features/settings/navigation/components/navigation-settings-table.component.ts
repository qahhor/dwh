import {
  ChangeDetectionStrategy,
  Component,
  Signal,
  TemplateRef,
  computed,
  inject,
  viewChild,
  input,
  output,
} from '@angular/core';
import { NgClass } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { CustomNavigationItem, NavigationTargetType } from '@core/models/navigation.models';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';

/**
 * Custom menu items on the kit table: the whole list is loaded, so a header
 * click sorts it all. Every row action names its item, so a screen reader
 * hears "Edit “Sales report”" rather than a row of identical "Edit" buttons.
 */
@Component({
  selector: 'app-navigation-settings-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, SMTInputComponent, SMTInputValueAccessor, TranslatePipe, UiLocalTableComponent, NgClass],
  template: `
    <!-- Search & Filter Bar -->
    <div class="filter-bar">
      <div class="search-box">
        <smt-input
          class="search-field"
          type="search"
          smtIcon="search"
          clearable
          [smtAriaLabel]="'nav.settings.search_placeholder' | t"
          [ngModel]="searchQuery()"
          (ngModelChange)="searchQueryChange.emit($event)"
          [placeholder]="'nav.settings.search_placeholder' | t"
          (cleared)="clearSearch.emit()"
        />
      </div>
    </div>

    <div class="table-container nav-table">
      <ui-local-table
        [rows]="rows()"
        [config]="config()"
        [sortValues]="sortValues"
        [loading]="isLoading()"
        [emptyTemplate]="emptyState"
      />
    </div>

    <ng-template #iconCell let-item>
      <span class="material-symbols-outlined table-icon" aria-hidden="true">{{ item.icon }}</span>
    </ng-template>
    <ng-template #titleCell let-item>
      <div class="title-main">{{ item.title }}</div>
      <div class="code-sub">{{ item.code }}</div>
    </ng-template>
    <ng-template #typeCell let-item>
      <span class="badge badge-type" [ngClass]="item.targetType.toLowerCase()">{{
        targetTypeLabel(item.targetType)
      }}</span>
    </ng-template>
    <ng-template #sectionCell let-item>
      <span class="badge badge-section">{{ sectionLabel(item.sectionId) }}</span>
    </ng-template>
    <ng-template #targetCell let-item>
      <span class="url-cell" [title]="item.url"
        ><span class="url-text">{{ item.url }}</span></span
      >
    </ng-template>
    <ng-template #orderCell let-item>{{ item.sortOrder }}</ng-template>
    <ng-template #statusCell let-item>
      <button
        type="button"
        class="status-toggle-btn"
        [class.active]="item.state === 'A'"
        [attr.aria-pressed]="item.state === 'A'"
        [attr.aria-label]="'nav.settings.toggle_named' | t: { title: item.title }"
        (click)="toggleItem.emit(item)"
        [title]="item.state === 'A' ? ('common.block' | t) : ('common.unblock' | t)"
      >
        {{ item.state === 'A' ? ('common.active' | t) : ('common.passive' | t) }}
      </button>
    </ng-template>
    <ng-template #actionsCell let-item>
      <div class="actions-cell">
        <button
          type="button"
          class="action-icon-btn preview-btn"
          (click)="previewItem.emit(item)"
          [title]="'nav.settings.open' | t"
          [attr.aria-label]="'nav.settings.open_named' | t: { title: item.title }"
        >
          <span class="material-symbols-outlined" aria-hidden="true">open_in_new</span>
        </button>
        <button
          type="button"
          class="action-icon-btn"
          (click)="editItem.emit(item)"
          [title]="'common.edit' | t"
          [attr.aria-label]="'nav.settings.edit_named' | t: { title: item.title }"
        >
          <span class="material-symbols-outlined" aria-hidden="true">edit</span>
        </button>
        <button
          type="button"
          class="action-icon-btn danger"
          (click)="deleteItem.emit(item)"
          [title]="'common.delete' | t"
          [attr.aria-label]="'nav.settings.delete_named' | t: { title: item.title }"
        >
          <span class="material-symbols-outlined" aria-hidden="true">delete</span>
        </button>
      </div>
    </ng-template>
    <ng-template #emptyState
      ><p class="empty-cell">{{ 'common.no_data' | t }}</p></ng-template
    >
  `,
  styleUrl: './navigation-settings-table.component.css',
})
export class NavigationSettingsTableComponent {
  private readonly uiI18n = inject(I18nService);

  readonly isLoading = input(false);
  readonly searchQuery = input('');

  readonly items = input<CustomNavigationItem[]>([]);

  readonly searchQueryChange = output<string>();
  readonly clearSearch = output<void>();
  readonly toggleItem = output<CustomNavigationItem>();
  readonly previewItem = output<CustomNavigationItem>();
  readonly editItem = output<CustomNavigationItem>();
  readonly deleteItem = output<CustomNavigationItem>();

  private readonly iconCell = viewChild.required<TemplateRef<unknown>>('iconCell');
  private readonly titleCell = viewChild.required<TemplateRef<unknown>>('titleCell');
  private readonly typeCell = viewChild.required<TemplateRef<unknown>>('typeCell');
  private readonly sectionCell = viewChild.required<TemplateRef<unknown>>('sectionCell');
  private readonly targetCell = viewChild.required<TemplateRef<unknown>>('targetCell');
  private readonly orderCell = viewChild.required<TemplateRef<unknown>>('orderCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('statusCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');

  readonly rows = computed<CustomNavigationItem[]>(() => this.items() ?? []);

  readonly config = computed<TableConfig<CustomNavigationItem>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.uiI18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, item) => item.id,
      ariaLabel: this.uiI18n.translate('nav.settings.table_label'),
      layout: 'fit',
      columns: {
        icon: { header: header('nav.settings.th_icon'), content: cell(this.iconCell), width: '64px' },
        title: { header: header('nav.settings.th_title'), content: cell(this.titleCell) },
        type: { header: header('nav.settings.th_type'), content: cell(this.typeCell), width: '140px' },
        section: { header: header('nav.settings.th_section'), content: cell(this.sectionCell), width: '150px' },
        target: { header: header('nav.settings.th_target'), content: cell(this.targetCell) },
        order: {
          header: header('nav.settings.th_order'),
          content: cell(this.orderCell),
          width: '90px',
          align: 'center',
        },
        status: { header: header('common.status'), content: cell(this.statusCell), width: '110px', align: 'center' },
        actions: { header: header('common.actions'), content: cell(this.actionsCell), width: '140px', align: 'right' },
      },
      columnsOrder: ['icon', 'title', 'type', 'section', 'target', 'order', 'status', 'actions'],
    };
  });

  readonly sortValues = {
    title: (item: CustomNavigationItem) => item.title,
    type: (item: CustomNavigationItem) => this.targetTypeLabel(item.targetType),
    section: (item: CustomNavigationItem) => this.sectionLabel(item.sectionId),
    target: (item: CustomNavigationItem) => item.url,
    order: (item: CustomNavigationItem) => item.sortOrder,
    status: (item: CustomNavigationItem) => (item.state === 'A' ? 0 : 1),
  };

  targetTypeLabel(type: NavigationTargetType): string {
    switch (type) {
      case 'EMBEDDED_IFRAME':
        return this.uiI18n.translate('nav.settings.type_embedded');
      case 'EXTERNAL_LINK':
        return this.uiI18n.translate('nav.settings.type_external');
      case 'INTERNAL_ROUTE':
        return this.uiI18n.translate('nav.settings.type_internal');
      default:
        return type;
    }
  }

  sectionLabel(sectionId: string): string {
    switch (sectionId) {
      case 'workspace':
        return this.uiI18n.translate('nav.section.workspace');
      case 'iam':
        return this.uiI18n.translate('nav.section.iam');
      case 'administration':
        return this.uiI18n.translate('nav.section.administration');
      case 'custom':
        return this.uiI18n.translate('nav.settings.section_custom');
      default:
        return sectionId;
    }
  }
}
