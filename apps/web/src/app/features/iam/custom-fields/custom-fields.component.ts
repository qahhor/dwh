import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../../core/services/api.service';
import { ToastService } from '../../../core/services/toast.service';
import { PermissionService } from '../../../core/services/permission.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { TranslatePipe, I18nService } from '../../../core/services/i18n.service';
import { CustomField, CustomFieldFormData } from './custom-fields.models';
import { CustomFieldsToolbarComponent } from './components/custom-fields-toolbar.component';
import { CustomFieldsTableComponent } from './components/custom-fields-table.component';
import { CustomFieldsModalsComponent } from './components/custom-fields-modals.component';

@Component({
  selector: 'app-custom-fields',
  standalone: true,
  imports: [
    CommonModule,
    UiButtonComponent,
    TranslatePipe,
    CustomFieldsToolbarComponent,
    CustomFieldsTableComponent,
    CustomFieldsModalsComponent
  ],
  template: `
    <div class="custom-fields-page">
      <!-- Header -->
      <div class="view-header">
        <div class="header-left">
          <div class="title-with-badge">
            <h1 class="view-title">{{ 'iam.dinamicheskie_atributy' | t }}</h1>
            <span class="count-badge" [title]="'iam.vsego_poley' | t">{{ filteredFields.length }}</span>
          </div>
          <span class="view-subtitle">{{ 'iam.sohranennye_znacheniya_etogo_atributa_mogut_stat' | t }}</span>
        </div>
        <div class="header-actions">
          <button
            type="button"
            class="icon-refresh-btn"
            [class.spinning]="isLoading"
            [disabled]="isLoading"
            (click)="loadFields()"
            [attr.aria-label]="'iam.obnovit_polya' | t"
            [title]="'common.refresh' | t"
          >
            <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
          </button>
          <ui-button
            *ngIf="canManage()"
            variant="primary"
            icon="add"
            (onClick)="openCreateModal()"
          >
            {{ 'iam.dobavit_pole' | t }}
          </ui-button>
        </div>
      </div>

      <!-- Toolbar: Filter Tabs and Search -->
      <app-custom-fields-toolbar
        [availableEntities]="availableEntities"
        [selectedEntity]="selectedEntity"
        [searchQuery]="searchQuery"
        [entityCounts]="entityCounts"
        (entityChange)="filterByEntity($event)"
        (searchQueryChange)="onSearchQueryChange($event)"
        (clearSearch)="clearSearch()"
      ></app-custom-fields-toolbar>

      <!-- Table / Grid -->
      <app-custom-fields-table
        [fields]="filteredFields"
        [isLoading]="isLoading"
        [canManage]="canManage()"
        [searchQuery]="searchQuery"
        (copyCode)="copyCode($event)"
        (editField)="openEditModal($event)"
        (deleteField)="requestDeleteField($event)"
        (clearSearch)="clearSearch()"
        (createField)="openCreateModal()"
      ></app-custom-fields-table>

      <!-- Modals (Create/Edit & Delete) -->
      <app-custom-fields-modals
        [showModal]="showModal"
        [editingField]="editingField"
        [fieldToDelete]="fieldToDelete"
        [formData]="formData"
        [formError]="formError"
        [saving]="saving"
        [isDeleting]="isDeleting"
        (closeModal)="closeModal()"
        (saveField)="saveField()"
        (cancelDelete)="fieldToDelete = null"
        (confirmDelete)="confirmDeleteField()"
        (codeInput)="onCodeInput($event)"
      ></app-custom-fields-modals>
    </div>
  `,
  styles: [`
    .custom-fields-page {
      padding: 0;
      max-width: 1400px;
      margin: 0 auto;
    }

    .view-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      margin-bottom: 20px;
      gap: 16px;
    }

    .title-with-badge {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .view-title {
      font-size: 24px;
      font-weight: 700;
      color: var(--text-main);
      margin: 0;
      letter-spacing: -0.02em;
    }

    .count-badge {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 2px 10px;
      border-radius: 12px;
      background: var(--primary-subtle, rgba(59, 130, 246, 0.1));
      color: var(--primary);
      font-size: 13px;
      font-weight: 600;
    }

    .view-subtitle {
      display: block;
      font-size: 13px;
      color: var(--text-light);
      margin-top: 4px;
    }

    .header-actions {
      display: flex;
      gap: 12px;
      align-items: center;
    }

    .icon-refresh-btn {
      width: 38px;
      height: 38px;
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      color: var(--text-light);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.2s;
    }

    .icon-refresh-btn:hover:not(:disabled) {
      background: var(--bg-hover);
      color: var(--text-main);
      border-color: var(--border-strong, var(--border-color));
    }

    .icon-refresh-btn:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }

    .icon-refresh-btn.spinning .material-symbols-outlined {
      animation: spin 1s linear infinite;
    }

    @keyframes spin {
      100% { transform: rotate(360deg); }
    }

    @media (max-width: 768px) {
      .view-header {
        flex-direction: column;
        align-items: stretch;
      }
    }
  `]
})
export class CustomFieldsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly permService = inject(PermissionService);
  private readonly uiI18n = inject(I18nService);

  fields: CustomField[] = [];
  filteredFields: CustomField[] = [];
  selectedEntity: string = 'ALL';
  searchQuery: string = '';

  isLoading: boolean = false;
  showModal: boolean = false;
  editingField: CustomField | null = null;
  saving: boolean = false;
  isDeleting: boolean = false;
  formError: string = '';
  fieldToDelete: CustomField | null = null;

  formData: CustomFieldFormData = {
    entityType: 'USER',
    code: '',
    name: '',
    fieldType: 'string',
    isRequired: false,
    defaultValue: '',
    orderNo: 0,
    optionsText: ''
  };

  ngOnInit() {
    this.loadFields();
  }

  canManage(): boolean {
    return this.permService.hasPermission('md.custom_fields', 'create') ||
           this.permService.hasPermission('md.custom_fields', 'update') ||
           this.permService.hasPermission('system.custom_fields', 'create');
  }

  get availableEntities(): string[] {
    const base = ['ALL', 'USER', 'PROJECT', 'TASK', 'NOTE'];
    const dynamic = this.fields
      .map(f => f.entityType)
      .filter(t => t && !base.includes(t));
    return [...base, ...Array.from(new Set(dynamic))];
  }

  get entityCounts(): Record<string, number> {
    const counts: Record<string, number> = {
      ALL: this.fields.length
    };
    for (const f of this.fields) {
      if (f.entityType) {
        counts[f.entityType] = (counts[f.entityType] || 0) + 1;
      }
    }
    return counts;
  }

  getEntityCount(ent: string): number {
    if (ent === 'ALL') return this.fields.length;
    return this.fields.filter(f => f.entityType === ent).length;
  }

  loadFields() {
    this.isLoading = true;
    this.api.get<CustomField[]>('/custom-fields').subscribe({
      next: data => {
        this.fields = data || [];
        this.applyFilter();
        this.isLoading = false;
      },
      error: () => {
        this.isLoading = false;
        this.toast.error(this.uiI18n.translate('iam.oshibka_zagruzki_dinamicheskih_poley'));
      }
    });
  }

  filterByEntity(entity: string) {
    this.selectedEntity = entity;
    this.applyFilter();
  }

  onSearchQueryChange(query: string) {
    this.searchQuery = query;
    this.applyFilter();
  }

  clearSearch() {
    this.searchQuery = '';
    this.applyFilter();
  }

  applyFilter() {
    const q = (this.searchQuery || '').trim().toLowerCase();
    const entity = this.selectedEntity;

    let result = [...this.fields];

    if (entity !== 'ALL') {
      result = result.filter(f => f.entityType === entity);
    }

    if (q) {
      result = result.filter(f =>
        (f.code && f.code.toLowerCase().includes(q)) ||
        (f.name && f.name.toLowerCase().includes(q)) ||
        (f.defaultValue && f.defaultValue.toLowerCase().includes(q)) ||
        (f.fieldType && f.fieldType.toLowerCase().includes(q))
      );
    }

    // Sort by orderNo, then name
    result.sort((a, b) => {
      const orderDiff = (a.orderNo || 0) - (b.orderNo || 0);
      if (orderDiff !== 0) return orderDiff;
      return (a.name || '').localeCompare(b.name || '');
    });

    this.filteredFields = result;
  }

  copyCode(code: string) {
    if (navigator?.clipboard?.writeText) {
      navigator.clipboard.writeText(code).then(() => {
        this.toast.success(this.uiI18n.translate('iam.kod_skopirovan'));
      }).catch(() => {});
    }
  }

  onCodeInput(event: Event) {
    const input = event.target as HTMLInputElement;
    if (!input) return;
    const sanitized = input.value.toLowerCase().replace(/\s+/g, '_');
    this.formData.code = sanitized;
  }

  openCreateModal() {
    this.editingField = null;
    this.formData = {
      entityType: this.selectedEntity !== 'ALL' ? this.selectedEntity : 'USER',
      code: '',
      name: '',
      fieldType: 'string',
      isRequired: false,
      defaultValue: '',
      orderNo: (this.fields.length + 1) * 10,
      optionsText: ''
    };
    this.formError = '';
    this.showModal = true;
  }

  openEditModal(f: CustomField) {
    this.editingField = f;
    this.formData = {
      entityType: f.entityType,
      code: f.code,
      name: f.name,
      fieldType: f.fieldType,
      isRequired: f.isRequired,
      defaultValue: f.defaultValue || '',
      orderNo: f.orderNo || 0,
      optionsText: this.optionsToText(f.optionsJson)
    };
    this.formError = '';
    this.showModal = true;
  }

  closeModal() {
    this.showModal = false;
    this.editingField = null;
    this.formError = '';
  }

  saveField() {
    if (!this.formData.name || !this.formData.code) {
      this.formError = this.uiI18n.translate('iam.zapolnite_obyazatelnye_polya');
      this.toast.error(this.uiI18n.translate('iam.zapolnite_obyazatelnye_polya'));
      return;
    }

    if (!this.editingField) {
      const codePattern = /^[a-z][a-z0-9_]{1,63}$/;
      if (!codePattern.test(this.formData.code)) {
        this.formError = this.uiI18n.translate('iam.invalid_code_slug');
        this.toast.error(this.uiI18n.translate('iam.invalid_code_slug'));
        return;
      }
    }

    const options = this.parseOptionsText(this.formData.optionsText);
    if (this.formData.fieldType === 'select' && options.length === 0) {
      this.formError = this.uiI18n.translate('iam.dobavte_hotya_by_odin_variant_spiska');
      return;
    }

    this.formError = '';
    this.saving = true;
    if (this.editingField) {
      this.api.patch(`/custom-fields/${this.editingField.id}`, {
        name: this.formData.name,
        isRequired: this.formData.isRequired,
        defaultValue: this.formData.defaultValue,
        options,
        orderNo: Number(this.formData.orderNo) || 0
      }).subscribe({
        next: () => {
          this.saving = false;
          this.toast.success(this.uiI18n.translate('iam.pole_uspeshno_obnovleno'));
          this.closeModal();
          this.loadFields();
        },
        error: () => {
          this.saving = false;
          this.toast.error(this.uiI18n.translate('iam.oshibka_sohraneniya_polya'));
        }
      });
    } else {
      this.api.post('/custom-fields', {
        entityType: this.formData.entityType,
        code: this.formData.code,
        name: this.formData.name,
        fieldType: this.formData.fieldType,
        isRequired: this.formData.isRequired,
        defaultValue: this.formData.defaultValue,
        orderNo: Number(this.formData.orderNo) || 0,
        options
      }).subscribe({
        next: () => {
          this.saving = false;
          this.toast.success(this.uiI18n.translate('iam.pole_uspeshno_sozdano'));
          this.closeModal();
          this.loadFields();
        },
        error: () => {
          this.saving = false;
          this.toast.error(this.uiI18n.translate('iam.oshibka_sozdaniya_polya'));
        }
      });
    }
  }

  requestDeleteField(field: CustomField) {
    this.fieldToDelete = field;
  }

  confirmDeleteField() {
    if (!this.fieldToDelete) return;
    const field = this.fieldToDelete;
    this.isDeleting = true;
    this.api.delete(`/custom-fields/${field.id}`).subscribe({
      next: () => {
        this.isDeleting = false;
        this.fieldToDelete = null;
        this.toast.success(this.uiI18n.translate('iam.pole_udaleno'));
        this.loadFields();
      },
      error: () => {
        this.isDeleting = false;
        this.toast.error(this.uiI18n.translate('iam.oshibka_udaleniya_polya'));
      }
    });
  }

  private parseOptionsText(value: string | undefined): Array<string | { value: string; label: string }> {
    return (value || '')
      .split(/\r?\n/)
      .map(option => option.trim())
      .filter((option, index, all) => option.length > 0 && all.indexOf(option) === index)
      .map(option => {
        const separatorIndex = option.indexOf('|');
        if (separatorIndex < 0) return option;

        const optionValue = option.slice(0, separatorIndex).trim();
        const optionLabel = option.slice(separatorIndex + 1).trim();
        return optionValue && optionLabel
          ? { value: optionValue, label: optionLabel }
          : option;
      });
  }

  private optionsToText(optionsJson: string | undefined): string {
    if (!optionsJson) return '';
    try {
      const options: unknown = JSON.parse(optionsJson);
      if (!Array.isArray(options)) return '';
      return options
        .map(option => {
          if (typeof option !== 'object' || option === null) return String(option);
          if (!('value' in option)) return '';

          const optionValue = String((option as { value: unknown }).value);
          const optionLabel = 'label' in option
            ? String((option as { label: unknown }).label)
            : optionValue;
          return optionValue === optionLabel ? optionValue : `${optionValue} | ${optionLabel}`;
        })
        .filter(Boolean)
        .join('\n');
    } catch {
      return '';
    }
  }
}
