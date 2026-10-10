import { ChangeDetectionStrategy, Component, inject, signal, computed } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { form } from '@angular/forms/signals';

import { CustomFieldsApi } from '@core/services/custom-fields.api';
import { ToastService } from '@core/services/toast.service';
import { PermissionService } from '@core/services/permission.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { CustomField, CustomFieldFormData } from './custom-fields.models';
import { CustomFieldsToolbarComponent } from './components/custom-fields-toolbar.component';
import { CustomFieldsTableComponent } from './components/custom-fields-table.component';
import { CustomFieldsModalsComponent } from './components/custom-fields-modals.component';
import { CustomFieldsFormService } from './services/custom-fields-form.service';
import { catchError, finalize, map, of, tap } from 'rxjs';
import { lastLoaded } from '@features/iam/last-loaded';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { problemText } from '@shared/ui/problem-text';
import { SaveErrorNotifier } from '@shared/ui/save-errors';
import { confirmDiscard, formChanged } from '@shared/ui/confirm-discard';
import { problemFieldErrors, ProblemFieldErrors } from '@shared/ui/problem-fields';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

/** The fields of the dialog, as the server names them (`options` is shown as `optionsText`). */
const FIELD_NAMES = ['entityType', 'code', 'name', 'fieldType', 'isRequired', 'defaultValue', 'orderNo', 'optionsText'];

@Component({
  selector: 'app-custom-fields',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    UiPageHeaderComponent,
    SMTButtonComponent,
    TranslatePipe,
    CustomFieldsToolbarComponent,
    CustomFieldsTableComponent,
    CustomFieldsModalsComponent,
  ],
  template: `
    <div class="custom-fields-page">
      <!-- Header -->
      <ui-page-header
        [title]="'iam.custom_fields.title' | t"
        [subtitle]="'iam.custom_fields.delete_warning' | t"
        [count]="filteredFields().length"
        [countLabel]="'iam.custom_fields.total_fields' | t"
      >
        <button
          type="button"
          class="icon-refresh-btn"
          [class.spinning]="isLoading()"
          [disabled]="isLoading()"
          (click)="loadFields()"
          [attr.aria-label]="'iam.custom_fields.refresh' | t"
          [title]="'common.refresh' | t"
        >
          <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
        </button>
        @if (canCreate()) {
          <button smt-button type="button" smtVariant="primary" smtIcon="add" (click)="openCreateModal()">
            {{ 'iam.custom_fields.add_field' | t }}
          </button>
        }
      </ui-page-header>

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
        [fieldForm]="fieldForm"
        [serverErrors]="serverErrors()"
        [saving]="saving()"
        (closeModal)="requestCloseModal()"
        (saveField)="saveField()"
        (codeInput)="onCodeInput($event)"
      ></app-custom-fields-modals>
    </div>
  `,
  styleUrl: './custom-fields.component.css',
})
export class CustomFieldsComponent {
  private readonly customFields = inject(CustomFieldsApi);
  private readonly toast = inject(ToastService);
  private readonly permService = inject(PermissionService);
  private readonly uiI18n = inject(I18nService);
  private readonly formService = inject(CustomFieldsFormService);

  private readonly modal = inject(SMTModalService);
  private readonly saveErrors = inject(SaveErrorNotifier);

  readonly selectedEntity = signal('ALL');
  readonly searchQuery = signal('');
  readonly showModal = signal(false);
  readonly editingField = signal<CustomField | null>(null);
  readonly saving = signal(false);
  readonly isDeleting = signal(false);
  /** What the server said about the fields of a refused save: messages by field, and the others. */
  readonly serverErrors = signal<ProblemFieldErrors>({ fields: {}, other: [] });
  readonly formData = signal<CustomFieldFormData>(this.formService.createInitialFormData('USER', 0));

  // --- Non-signal UI state ---
  readonly isLoading = computed(() => this.fieldsRead.isLoading());

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

  readonly fieldForm = form(
    this.formData,
    this.formService.schema(() => !!this.editingField()),
  );
  /** The values the dialog opened with: closing asks only when they changed. */
  private initialData: CustomFieldFormData = this.formData();

  /** The definitions; a failed load says so and keeps the table on screen. */
  private readonly fieldsRead = rxResource({
    stream: () =>
      this.customFields.list().pipe(
        map((data) => data || []),
        catchError(() => {
          this.toast.error(this.uiI18n.translate('iam.custom_fields.load_failed'));
          return of(null);
        }),
      ),
  });

  // --- Reactive state via signals ---
  readonly fields = lastLoaded<CustomField[]>(() => this.fieldsRead.value(), []);

  sortColumn = 'orderNo';
  sortDirection: 'asc' | 'desc' = 'asc';

  // --- Granular RBAC ---
  canCreate(): boolean {
    return this.permService.hasPermission('md.custom_fields', 'create');
  }

  canEdit(): boolean {
    return this.permService.hasPermission('md.custom_fields', 'update') || this.canCreate();
  }

  canDelete(): boolean {
    return this.permService.hasPermission('md.custom_fields', 'delete') || this.canCreate();
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
    this.fieldsRead.reload();
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
          this.toast.success(this.uiI18n.translate('iam.custom_fields.code_copied'));
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
    this.openWith(this.formService.createInitialFormData(this.selectedEntity(), this.fields().length));
  }

  openEditModal(f: CustomField) {
    this.editingField.set(f);
    this.openWith(this.formService.fromCustomField(f));
  }

  /** Escape, the backdrop, the close button and Cancel: changes are lost only after a question. */
  requestCloseModal() {
    if (this.saving()) return;
    confirmDiscard(this.modal, this.uiI18n, formChanged(this.initialData, this.formData())).subscribe((discard) => {
      if (discard) this.closeModal();
    });
  }

  closeModal() {
    this.showModal.set(false);
    this.editingField.set(null);
    this.serverErrors.set({ fields: {}, other: [] });
  }

  saveField() {
    // One request at a time: a second press (or Enter) while saving must not send the field again.
    if (this.saving()) return;
    markSMTFormFieldsTouched(this.fieldForm);
    this.serverErrors.set({ fields: {}, other: [] });
    if (!this.fieldForm().valid()) return;

    this.saving.set(true);
    const options = this.formService.parseOptionsText(this.formData().optionsText);

    const editing = this.editingField();
    if (editing) {
      this.customFields
        .update(
          editing.id,
          {
            name: this.formData().name,
            isRequired: this.formData().isRequired,
            defaultValue: this.formData().defaultValue,
            options,
            orderNo: Number(this.formData().orderNo) || 0,
          },
          editing.revision,
        )
        .subscribe({
          next: () => {
            this.saving.set(false);
            this.toast.success(this.uiI18n.translate('iam.custom_fields.updated'));
            this.closeModal();
            this.loadFields();
          },
          error: (err: unknown) => {
            this.saving.set(false);
            if (this.showFieldErrors(err)) return;
            // A newer revision: the list is read again and the field is opened from it.
            this.saveErrors.show(err, {
              fallbackKey: 'iam.custom_fields.save_failed',
              reload: () => {
                this.closeModal();
                this.loadFields();
              },
            });
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
            this.toast.success(this.uiI18n.translate('iam.custom_fields.created'));
            this.closeModal();
            this.loadFields();
          },
          error: (err: unknown) => {
            this.saving.set(false);
            if (this.showFieldErrors(err)) return;
            this.saveErrors.show(err, { fallbackKey: 'iam.custom_fields.create_failed' });
          },
        });
    }
  }

  /** Asks before deleting; the dialog stays open until the server answers and shows why it refused. */
  requestDeleteField(field: CustomField) {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    this.modal
      .confirm({
        title: t('iam.custom_fields.delete_title'),
        message: `${t('iam.delete_custom_field_question', { name: field.name, code: field.code })}\n${t('iam.custom_fields.delete_warning')}`,
        yesLabel: t('common.delete'),
        noLabel: t('common.cancel'),
        destructive: true,
        action: () => {
          this.isDeleting.set(true);
          return this.customFields.remove(field.id).pipe(
            tap(() => {
              this.toast.success(t('iam.custom_fields.deleted'));
              this.loadFields();
            }),
            finalize(() => {
              this.isDeleting.set(false);
            }),
          );
        },
        actionError: (error) => problemText(error) || t('iam.custom_fields.delete_failed'),
      })
      .subscribe();
  }

  private openWith(data: CustomFieldFormData): void {
    this.formData.set(data);
    this.initialData = data;
    this.fieldForm().reset();
    this.serverErrors.set({ fields: {}, other: [] });
    this.showModal.set(true);
  }

  /** Puts the server's field messages under the fields; false when the refusal named no field. */
  private showFieldErrors(err: unknown): boolean {
    const errors = problemFieldErrors(err, { known: FIELD_NAMES, rename: { options: 'optionsText' } });
    if (Object.keys(errors.fields).length === 0 && errors.other.length === 0) return false;
    this.serverErrors.set(errors);
    return true;
  }
}
