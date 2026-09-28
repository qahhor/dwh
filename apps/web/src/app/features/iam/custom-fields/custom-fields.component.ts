import { ChangeDetectionStrategy, Component, OnInit, inject, signal, computed } from '@angular/core';

import { CustomFieldsApi } from '../../../core/services/custom-fields.api';
import { ToastService } from '../../../core/services/toast.service';
import { PermissionService } from '../../../core/services/permission.service';
import { SMTButtonComponent } from '../../../shared/ui-kit/components/button';
import { TranslatePipe, I18nService } from '../../../core/services/i18n.service';
import { CustomField, CustomFieldFormData } from './custom-fields.models';
import { CustomFieldsToolbarComponent } from './components/custom-fields-toolbar.component';
import { CustomFieldsTableComponent } from './components/custom-fields-table.component';
import { CustomFieldsModalsComponent } from './components/custom-fields-modals.component';
import { CustomFieldsFormService } from './services/custom-fields-form.service';
import { finalize, tap } from 'rxjs';
import { SMTModalService } from '../../../shared/ui-kit/components/modal';
import { problemText } from '../../../shared/ui/problem-text';

@Component({
  selector: 'app-custom-fields',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTButtonComponent,
    TranslatePipe,
    CustomFieldsToolbarComponent,
    CustomFieldsTableComponent,
    CustomFieldsModalsComponent,
  ],
  template: `
    <div class="custom-fields-page">
      <!-- Header -->
      <div class="view-header">
        <div class="header-left">
          <div class="title-with-badge">
            <h1 class="view-title">{{ 'iam.dinamicheskie_atributy' | t }}</h1>
            <span class="count-badge" [title]="'iam.vsego_poley' | t">{{ filteredFields().length }}</span>
          </div>
          <span class="view-subtitle">{{ 'iam.sohranennye_znacheniya_etogo_atributa_mogut_stat' | t }}</span>
        </div>
        <div class="header-actions">
          <button
            type="button"
            class="icon-refresh-btn"
            [class.spinning]="isLoading()"
            [disabled]="isLoading()"
            (click)="loadFields()"
            [attr.aria-label]="'iam.obnovit_polya' | t"
            [title]="'common.refresh' | t"
          >
            <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
          </button>
          @if (canCreate()) {
            <button smt-button type="button" smtVariant="primary" smtIcon="add" (click)="openCreateModal()">
              {{ 'iam.dobavit_pole' | t }}
            </button>
          }
        </div>
      </div>

      <!-- Toolbar: Filter Tabs and Search -->
      <app-custom-fields-toolbar
        [availableEntities]="availableEntities()"
        [selectedEntity]="selectedEntity()"
        [searchQuery]="searchQuery()"
        [entityCounts]="entityCounts()"
        (entityChange)="filterByEntity($event)"
        (searchQueryChange)="onSearchQueryChange($event)"
        (clearSearch)="clearSearch()"
      ></app-custom-fields-toolbar>

      <!-- Table / Grid -->
      <app-custom-fields-table
        [fields]="filteredFields()"
        [isLoading]="isLoading()"
        [canManage]="canCreate()"
        [canEdit]="canEdit()"
        [canDelete]="canDelete()"
        [searchQuery]="searchQuery()"
        (copyCode)="copyCode($event)"
        (editField)="openEditModal($event)"
        (deleteField)="requestDeleteField($event)"
        (clearSearch)="clearSearch()"
        (createField)="openCreateModal()"
      ></app-custom-fields-table>

      <!-- Modals (Create/Edit & Delete) -->
      <app-custom-fields-modals
        [showModal]="showModal()"
        [editingField]="editingField()"
        [formData]="formData()"
        [formError]="formError()"
        [saving]="saving()"
        (closeModal)="closeModal()"
        (saveField)="saveField()"
        (codeInput)="onCodeInput($event)"
      ></app-custom-fields-modals>
    </div>
  `,
  styleUrl: './custom-fields.component.css',
})
export class CustomFieldsComponent implements OnInit {
  private readonly customFields = inject(CustomFieldsApi);
  private readonly toast = inject(ToastService);
  private readonly permService = inject(PermissionService);
  private readonly uiI18n = inject(I18nService);
  private readonly formService = inject(CustomFieldsFormService);

  private readonly modal = inject(SMTModalService);

  // --- Reactive state via signals ---
  readonly fields = signal<CustomField[]>([]);
  readonly selectedEntity = signal('ALL');
  readonly searchQuery = signal('');

  // --- Non-signal UI state ---
  readonly isLoading = signal(false);
  readonly showModal = signal(false);
  readonly editingField = signal<CustomField | null>(null);
  readonly saving = signal(false);
  readonly isDeleting = signal(false);
  readonly formError = signal('');
  readonly formData = signal<CustomFieldFormData>(this.formService.createInitialFormData('USER', 0));

  readonly availableEntities = computed(() => {
    const base = ['ALL', 'USER', 'PROJECT', 'TASK', 'NOTE'];
    const dynamic = this.fields()
      .map((f) => f.entityType)
      .filter((t) => t && !base.includes(t));
    return [...base, ...Array.from(new Set(dynamic))];
  });

  readonly entityCounts = computed<Record<string, number>>(() => {
    const counts: Record<string, number> = { ALL: this.fields().length };
    for (const f of this.fields()) {
      if (f.entityType) {
        counts[f.entityType] = (counts[f.entityType] || 0) + 1;
      }
    }
    return counts;
  });

  readonly filteredFields = computed(() => {
    const q = this.searchQuery().trim().toLowerCase();
    const entity = this.selectedEntity();
    let result = [...this.fields()];

    if (entity !== 'ALL') {
      result = result.filter((f) => f.entityType === entity);
    }
    if (q) {
      result = result.filter(
        (f) =>
          (f.code && f.code.toLowerCase().includes(q)) ||
          (f.name && f.name.toLowerCase().includes(q)) ||
          (f.defaultValue && f.defaultValue.toLowerCase().includes(q)) ||
          (f.fieldType && f.fieldType.toLowerCase().includes(q)),
      );
    }
    result.sort((a, b) => {
      const orderDiff = (a.orderNo || 0) - (b.orderNo || 0);
      if (orderDiff !== 0) return orderDiff;
      return (a.name || '').localeCompare(b.name || '');
    });
    return result;
  });

  sortColumn = 'orderNo';
  sortDirection: 'asc' | 'desc' = 'asc';

  ngOnInit() {
    this.loadFields();
  }

  // --- Granular RBAC ---
  canCreate(): boolean {
    return (
      this.permService.hasPermission('md.custom_fields', 'create') ||
      this.permService.hasPermission('system.custom_fields', 'create')
    );
  }

  canEdit(): boolean {
    return (
      this.permService.hasPermission('md.custom_fields', 'update') ||
      this.permService.hasPermission('system.custom_fields', 'update') ||
      this.canCreate()
    );
  }

  canDelete(): boolean {
    return (
      this.permService.hasPermission('md.custom_fields', 'delete') ||
      this.permService.hasPermission('system.custom_fields', 'delete') ||
      this.canCreate()
    );
  }

  /** @deprecated Use canCreate/canEdit/canDelete for granular RBAC; kept for toolbar backward compat */
  canManage(): boolean {
    return this.canCreate() || this.canEdit() || this.canDelete();
  }

  getEntityCount(ent: string): number {
    if (ent === 'ALL') return this.fields().length;
    return this.fields().filter((f) => f.entityType === ent).length;
  }

  loadFields() {
    this.isLoading.set(true);
    this.customFields.list().subscribe({
      next: (data) => {
        this.fields.set(data || []);
        this.isLoading.set(false);
      },
      error: () => {
        this.isLoading.set(false);
        this.toast.error(this.uiI18n.translate('iam.oshibka_zagruzki_dinamicheskih_poley'));
      },
    });
  }

  filterByEntity(entity: string) {
    this.selectedEntity.set(entity);
  }

  onSearchQueryChange(query: string) {
    this.searchQuery.set(query);
  }

  clearSearch() {
    this.searchQuery.set('');
  }

  onSortChange(column: string) {
    if (this.sortColumn === column) {
      this.sortDirection = this.sortDirection === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortColumn = column;
      this.sortDirection = 'asc';
    }
  }

  copyCode(code: string) {
    if (navigator?.clipboard?.writeText) {
      navigator.clipboard
        .writeText(code)
        .then(() => {
          this.toast.success(this.uiI18n.translate('iam.kod_skopirovan'));
        })
        .catch(() => {});
    }
  }

  onCodeInput(event: Event) {
    const input = event.target as HTMLInputElement;
    if (!input) return;
    this.formData.update((data) => ({ ...data, code: this.formService.sanitizeCode(input.value) }));
  }

  openCreateModal() {
    this.editingField.set(null);
    this.formData.set(this.formService.createInitialFormData(this.selectedEntity(), this.fields().length));
    this.formError.set('');
    this.showModal.set(true);
  }

  openEditModal(f: CustomField) {
    this.editingField.set(f);
    this.formData.set(this.formService.fromCustomField(f));
    this.formError.set('');
    this.showModal.set(true);
  }

  closeModal() {
    this.showModal.set(false);
    this.editingField.set(null);
    this.formError.set('');
  }

  saveField() {
    const validation = this.formService.validateForm(this.formData(), !!this.editingField());
    if (!validation.isValid) {
      this.formError.set(validation.errorMessage || '');
      this.toast.error(this.formError());
      return;
    }

    this.formError.set('');
    this.saving.set(true);
    const options = this.formService.parseOptionsText(this.formData().optionsText);

    const editing = this.editingField();
    if (editing) {
      this.customFields
        .update(editing.id, {
          name: this.formData().name,
          isRequired: this.formData().isRequired,
          defaultValue: this.formData().defaultValue,
          options,
          orderNo: Number(this.formData().orderNo) || 0,
        })
        .subscribe({
          next: () => {
            this.saving.set(false);
            this.toast.success(this.uiI18n.translate('iam.pole_uspeshno_obnovleno'));
            this.closeModal();
            this.loadFields();
          },
          error: () => {
            this.saving.set(false);
            this.toast.error(this.uiI18n.translate('iam.oshibka_sohraneniya_polya'));
          },
        });
    } else {
      this.customFields
        .create({
          entityType: this.formData().entityType,
          code: this.formData().code,
          name: this.formData().name,
          fieldType: this.formData().fieldType,
          isRequired: this.formData().isRequired,
          defaultValue: this.formData().defaultValue,
          orderNo: Number(this.formData().orderNo) || 0,
          options,
        })
        .subscribe({
          next: () => {
            this.saving.set(false);
            this.toast.success(this.uiI18n.translate('iam.pole_uspeshno_sozdano'));
            this.closeModal();
            this.loadFields();
          },
          error: () => {
            this.saving.set(false);
            this.toast.error(this.uiI18n.translate('iam.oshibka_sozdaniya_polya'));
          },
        });
    }
  }

  /** Asks before deleting; the dialog stays open until the server answers and shows why it refused. */
  requestDeleteField(field: CustomField) {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    this.modal
      .confirm({
        title: t('iam.udalenie_dinamicheskogo_polya'),
        message: `${t('iam.delete_custom_field_question', { name: field.name, code: field.code })}\n${t('iam.sohranennye_znacheniya_etogo_atributa_mogut_stat')}`,
        yesLabel: t('common.delete'),
        noLabel: t('common.cancel'),
        destructive: true,
        action: () => {
          this.isDeleting.set(true);
          return this.customFields.remove(field.id).pipe(
            tap(() => {
              this.toast.success(t('iam.pole_udaleno'));
              this.loadFields();
            }),
            finalize(() => {
              this.isDeleting.set(false);
            }),
          );
        },
        actionError: (error) => problemText(error) || t('iam.oshibka_udaleniya_polya'),
      })
      .subscribe();
  }
}
