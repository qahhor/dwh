import {
  ChangeDetectionStrategy,
  Component,
  inject,
  Signal,
  TemplateRef,
  computed,
  viewChild,
  input,
  output,
} from '@angular/core';
import { NgClass } from '@angular/common';
import { CustomField } from '../custom-fields.models';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';

import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';

@Component({
  selector: 'app-custom-fields-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTButtonComponent, TranslatePipe, UiLocalTableComponent, NgClass],
  template: `
    <div class="card table-card">
      <div class="table-wrapper" role="region" [attr.aria-label]="'iam.custom_fields.table' | t" tabindex="0">
        <ui-local-table
          [rows]="rows()"
          [config]="config()"
          [sortValues]="sortValues"
          [loading]="isLoading()"
          [emptyTemplate]="emptyState"
        />
      </div>
    </div>

    <ng-template #orderCell let-f
      ><span class="order-cell font-mono">{{ f.orderNo || 0 }}</span></ng-template
    >
    <ng-template #codeCell let-f>
      <div class="code-badge-wrap font-mono">
        <span class="code-text">{{ f.code }}</span>
        <button
          type="button"
          class="copy-code-btn"
          (click)="copyCode.emit(f.code)"
          [attr.aria-label]="'iam.copy_code_named' | t: { code: f.code }"
          [title]="'iam.custom_fields.copy_code' | t"
        >
          <span class="material-symbols-outlined" aria-hidden="true">content_copy</span>
        </button>
      </div>
    </ng-template>
    <ng-template #nameCell let-f
      ><span class="name-cell font-medium">{{ f.name }}</span></ng-template
    >
    <ng-template #entityCell let-f>
      <span class="entity-badge" [ngClass]="getEntityBadgeClass(f.entityType)">
        <span class="material-symbols-outlined entity-icon" aria-hidden="true">{{ getEntityIcon(f.entityType) }}</span>
        <span>{{ getEntityLabel(f.entityType) }}</span>
      </span>
    </ng-template>
    <ng-template #typeCell let-f>
      <span class="type-badge" [ngClass]="'type-' + f.fieldType">
        <span class="material-symbols-outlined type-icon" aria-hidden="true">{{ getTypeIcon(f.fieldType) }}</span>
        <span>{{ getTypeName(f.fieldType) }}</span>
      </span>
    </ng-template>
    <ng-template #requiredCell let-f>
      <span class="status-indicator" [class.active]="f.isRequired">
        <span class="material-symbols-outlined status-icon" aria-hidden="true">{{
          f.isRequired ? 'check_circle' : 'remove_circle_outline'
        }}</span>
        <span>{{ (f.isRequired ? 'common.yes' : 'common.no') | t }}</span>
      </span>
    </ng-template>
    <ng-template #defaultCell let-f
      ><span class="text-muted">{{ f.defaultValue || '—' }}</span></ng-template
    >
    <ng-template #actionsCell let-f>
      <div class="text-right">
        @if (canEdit() || canManage()) {
          <button
            type="button"
            class="action-btn"
            (click)="editField.emit(f)"
            [attr.aria-label]="'iam.edit_named' | t: { name: f.name }"
            [title]="'common.edit' | t"
          >
            <span class="material-symbols-outlined" aria-hidden="true">edit</span>
          </button>
        }
        @if (canDelete() || canManage()) {
          <button
            type="button"
            class="action-btn danger"
            (click)="deleteField.emit(f)"
            [attr.aria-label]="'iam.delete_named' | t: { name: f.name }"
            [title]="'common.delete' | t"
          >
            <span class="material-symbols-outlined" aria-hidden="true">delete</span>
          </button>
        }
      </div>
    </ng-template>
    <ng-template #emptyState>
      @if (searchQuery()) {
        <div class="empty-state">
          <span class="material-symbols-outlined empty-icon" aria-hidden="true">search_off</span>
          <p>
            {{ 'iam.custom_fields.nothing_found_for' | t }}: «<strong>{{ searchQuery() }}</strong
            >»
          </p>
          <button smt-button type="button" smtVariant="secondary" smtSize="sm" (click)="clearSearch.emit()">
            {{ 'iam.custom_fields.reset_filters' | t }}
          </button>
        </div>
      } @else {
        <div class="empty-state">
          <span class="material-symbols-outlined empty-icon" aria-hidden="true">tune</span>
          <p>{{ 'iam.custom_fields.empty' | t }}</p>
          @if (canManage()) {
            <button
              smt-button
              type="button"
              smtVariant="primary"
              smtSize="sm"
              smtIcon="add"
              (click)="createField.emit()"
            >
              {{ 'iam.custom_fields.add_field' | t }}
            </button>
          }
        </div>
      }
    </ng-template>
  `,
  styleUrl: './custom-fields-table.component.css',
})
export class CustomFieldsTableComponent {
  private readonly uiI18n = inject(I18nService);

  readonly isLoading = input(false);

  readonly searchQuery = input('');

  readonly fields = input<CustomField[]>([]);
  readonly canManage = input<boolean>(false);
  readonly canEdit = input<boolean>(false);
  readonly canDelete = input<boolean>(false);

  readonly copyCode = output<string>();
  readonly editField = output<CustomField>();
  readonly deleteField = output<CustomField>();
  readonly clearSearch = output<void>();
  readonly createField = output<void>();

  private readonly orderCell = viewChild.required<TemplateRef<unknown>>('orderCell');
  private readonly codeCell = viewChild.required<TemplateRef<unknown>>('codeCell');
  private readonly nameCell = viewChild.required<TemplateRef<unknown>>('nameCell');
  private readonly entityCell = viewChild.required<TemplateRef<unknown>>('entityCell');
  private readonly typeCell = viewChild.required<TemplateRef<unknown>>('typeCell');
  private readonly requiredCell = viewChild.required<TemplateRef<unknown>>('requiredCell');
  private readonly defaultCell = viewChild.required<TemplateRef<unknown>>('defaultCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');

  readonly rows = computed<CustomField[]>(() => this.fields() ?? []);

  readonly config = computed<TableConfig<CustomField>>(() => {
    const header = (key: string) => ({
      type: 'primitive' as const,
      value: key === '#' ? '#' : this.uiI18n.translate(key),
    });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    const columns: TableConfig<CustomField>['columns'] = {
      orderNo: { header: header('#'), content: cell(this.orderCell), width: '70px' },
      code: { header: header('iam.custom_fields.field_code'), content: cell(this.codeCell) },
      name: { header: header('iam.custom_fields.name'), content: cell(this.nameCell) },
      entityType: { header: header('iam.custom_fields.entity'), content: cell(this.entityCell) },
      fieldType: { header: header('iam.custom_fields.data_type'), content: cell(this.typeCell) },
      isRequired: { header: header('iam.custom_fields.required'), content: cell(this.requiredCell), width: '130px' },
      defaultValue: { header: header('iam.custom_fields.default_value'), content: cell(this.defaultCell) },
    };
    const order = ['orderNo', 'code', 'name', 'entityType', 'fieldType', 'isRequired', 'defaultValue'];
    if (this.canManage() || this.canEdit() || this.canDelete()) {
      columns['actions'] = {
        header: header('common.actions'),
        content: cell(this.actionsCell),
        width: '110px',
        align: 'right',
      };
      order.push('actions');
    }
    return {
      trackBy: (_index, f) => f.id ?? f.code,
      ariaLabel: this.uiI18n.translate('iam.custom_fields.title'),
      layout: 'fit',
      columns,
      columnsOrder: order,
    };
  });
  private readonly rights = computed(() => ({
    manage: this.canManage(),
    edit: this.canEdit(),
    remove: this.canDelete(),
  }));

  /** Every field is loaded, so a header click (by keyboard too) sorts the whole list. */
  readonly sortValues = {
    orderNo: (f: CustomField) => f.orderNo ?? 0,
    code: (f: CustomField) => f.code,
    name: (f: CustomField) => f.name,
    entityType: (f: CustomField) => this.getEntityLabel(f.entityType),
    fieldType: (f: CustomField) => this.getTypeName(f.fieldType),
    isRequired: (f: CustomField) => (f.isRequired ? 0 : 1),
  };

  getEntityLabel(ent: string): string {
    switch ((ent || '').toUpperCase()) {
      case 'USER':
        return this.uiI18n.translate('nav.users');
      case 'PROJECT':
        return this.uiI18n.translate('nav.projects');
      case 'TASK':
        return this.uiI18n.translate('nav.tasks');
      case 'NOTE':
        return this.uiI18n.translate('iam.custom_fields.entity_note');
      case 'ORGANIZATION_UNIT':
        return this.uiI18n.translate('nav.org_units') || ent;
      default:
        return ent;
    }
  }

  getEntityIcon(ent: string): string {
    switch (ent.toUpperCase()) {
      case 'ALL':
        return 'apps';
      case 'USER':
        return 'person';
      case 'PROJECT':
        return 'folder';
      case 'TASK':
        return 'task_alt';
      case 'NOTE':
        return 'description';
      case 'ORGANIZATION_UNIT':
        return 'corporate_fare';
      default:
        return 'data_object';
    }
  }

  getEntityBadgeClass(entity: string): string {
    const ent = (entity || '').toLowerCase();
    if (['user', 'project', 'task', 'note'].includes(ent)) {
      return ent;
    }
    return 'custom-entity';
  }

  getTypeName(type: string): string {
    switch (type) {
      case 'string':
        return this.uiI18n.translate('iam.custom_fields.text');
      case 'number':
        return this.uiI18n.translate('iam.custom_fields.number');
      case 'boolean':
        return this.uiI18n.translate('iam.custom_fields.type_yes_no');
      case 'date':
        return this.uiI18n.translate('iam.data');
      case 'datetime':
        return this.uiI18n.translate('iam.custom_fields.editor.type_datetime');
      case 'time':
        return this.uiI18n.translate('iam.custom_fields.editor.type_time');
      case 'select':
        return this.uiI18n.translate('projects.common.list');
      case 'user_ref':
        return this.uiI18n.translate('iam.user_ref');
      default:
        return type;
    }
  }

  getTypeIcon(type: string): string {
    switch (type) {
      case 'string':
        return 'format_quote';
      case 'number':
        return 'tag';
      case 'boolean':
        return 'toggle_on';
      case 'date':
        return 'calendar_today';
      case 'datetime':
        return 'event';
      case 'time':
        return 'schedule';
      case 'select':
        return 'list';
      case 'user_ref':
        return 'person';
      default:
        return 'help';
    }
  }
}
