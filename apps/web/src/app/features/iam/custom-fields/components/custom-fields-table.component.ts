import { Component, EventEmitter, Input, Output, inject, Signal, TemplateRef, computed, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { CustomField } from '../custom-fields.models';
import { SMTButtonComponent } from '../../../../shared/ui-kit/components/button';
import { TranslatePipe, I18nService } from '../../../../core/services/i18n.service';

import { UiLocalTableComponent } from '../../../../shared/ui/ui-local-table.component';
import { TableConfig } from '../../../../shared/ui-kit/components/table/table.types';

@Component({
  selector: 'app-custom-fields-table',
  standalone: true,
  imports: [
    CommonModule,
    SMTButtonComponent,
    TranslatePipe, UiLocalTableComponent],
  template: `
    <div class="card table-card">
      <div class="table-wrapper" role="region" [attr.aria-label]="'iam.tablica_dinamicheskih_atributov' | t" tabindex="0">
        <ui-local-table [rows]="rows()" [config]="config()" [sortValues]="sortValues" [loading]="isLoading" [emptyTemplate]="emptyState" />
      </div>
    </div>

    <ng-template #orderCell let-f><span class="order-cell font-mono">{{ f.orderNo || 0 }}</span></ng-template>
    <ng-template #codeCell let-f>
      <div class="code-badge-wrap font-mono">
        <span class="code-text">{{ f.code }}</span>
        <button type="button" class="copy-code-btn" (click)="copyCode.emit(f.code)"
          [attr.aria-label]="'iam.copy_code_named' | t:{ code: f.code }" [title]="'iam.kopirovat_kod' | t">
          <span class="material-symbols-outlined" aria-hidden="true">content_copy</span>
        </button>
      </div>
    </ng-template>
    <ng-template #nameCell let-f><span class="name-cell font-medium">{{ f.name }}</span></ng-template>
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
        <span class="material-symbols-outlined status-icon" aria-hidden="true">{{ f.isRequired ? 'check_circle' : 'remove_circle_outline' }}</span>
        <span>{{ (f.isRequired ? 'common.yes' : 'common.no') | t }}</span>
      </span>
    </ng-template>
    <ng-template #defaultCell let-f><span class="text-muted">{{ f.defaultValue || '—' }}</span></ng-template>
    <ng-template #actionsCell let-f>
      <div class="text-right">
        @if (canEdit || canManage) {
          <button type="button" class="action-btn" (click)="editField.emit(f)"
            [attr.aria-label]="'iam.edit_named' | t:{name: f.name}" [title]="'common.edit' | t">
            <span class="material-symbols-outlined" aria-hidden="true">edit</span>
          </button>
        }
        @if (canDelete || canManage) {
          <button type="button" class="action-btn danger" (click)="deleteField.emit(f)"
            [attr.aria-label]="'iam.delete_named' | t:{name: f.name}" [title]="'common.delete' | t">
            <span class="material-symbols-outlined" aria-hidden="true">delete</span>
          </button>
        }
      </div>
    </ng-template>
    <ng-template #emptyState>
      @if (searchQuery) {
        <div class="empty-state">
          <span class="material-symbols-outlined empty-icon" aria-hidden="true">search_off</span>
          <p>{{ 'iam.nichego_ne_naydeno_po_zaprosu' | t }}: «<strong>{{ searchQuery }}</strong>»</p>
          <button smt-button type="button" smtVariant="secondary" smtSize="sm" (click)="clearSearch.emit()">{{ 'iam.sbrosit_poisk' | t }}</button>
        </div>
      } @else {
        <div class="empty-state">
          <span class="material-symbols-outlined empty-icon" aria-hidden="true">tune</span>
          <p>{{ 'iam.dinamicheskie_polya_ne_naydeny' | t }}</p>
          @if (canManage) {
            <button smt-button type="button" smtVariant="primary" smtSize="sm" smtIcon="add" (click)="createField.emit()">{{ 'iam.dobavit_pole' | t }}</button>
          }
        </div>
      }
    </ng-template>
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
    }

    .card {
      background: var(--bg-surface);
      border-radius: 12px;
      border: 1px solid var(--border-color);
      overflow: hidden;
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
    }

    .table-wrapper {
      overflow-x: auto;
    }


    .code-badge-wrap {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background: var(--bg-hover);
      padding: 3px 8px;
      border-radius: 6px;
      border: 1px solid var(--border-subtle);
    }

    .code-text {
      font-weight: 600;
    }

    .copy-code-btn {
      background: transparent;
      border: none;
      padding: 1px;
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--text-light);
      cursor: pointer;
      border-radius: 4px;
      opacity: 0.7;
      transition: all 0.15s;
    }

    .copy-code-btn:hover {
      opacity: 1;
      color: var(--primary);
      background: rgba(0, 0, 0, 0.05);
    }

    .copy-code-btn .material-symbols-outlined {
      font-size: 14px;
    }

    .name-cell {
      font-weight: 500;
    }

    .entity-badge {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      padding: 4px 9px;
      border-radius: 6px;
      font-size: 12px;
      font-weight: 600;
      letter-spacing: 0.02em;
    }

    .entity-icon {
      font-size: 15px;
    }

    .entity-badge.user { background: var(--primary-subtle); color: var(--primary); }
    .entity-badge.project { background: var(--info-bg); color: var(--info-text); }
    .entity-badge.task { background: var(--success-bg); color: var(--success-text); }
    .entity-badge.note { background: var(--warning-bg); color: var(--warning-text); }
    .entity-badge.custom-entity { background: var(--bg-hover); color: var(--text-main); border: 1px solid var(--border-color); }

    .type-badge {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      padding: 3px 8px;
      border-radius: 6px;
      background: var(--bg-hover);
      border: 1px solid var(--border-subtle);
      font-size: 12px;
      color: var(--text-main);
      font-weight: 500;
    }

    .type-icon {
      font-size: 15px;
      color: var(--text-light);
    }

    .status-indicator {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      padding: 3px 8px;
      border-radius: 6px;
      font-size: 12px;
      font-weight: 500;
      background: var(--bg-hover);
      color: var(--text-muted);
    }

    .status-indicator.active {
      background: var(--success-bg);
      color: var(--success-text);
    }

    .status-icon {
      font-size: 15px;
    }

    .action-btn {
      width: 32px;
      height: 32px;
      padding: 0;
      border-radius: 6px;
      border: 1px solid transparent;
      background: transparent;
      color: var(--text-light);
      display: inline-flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.15s;
    }

    .action-btn:hover {
      background: var(--bg-hover);
      color: var(--text-main);
    }

    .action-btn.danger:hover {
      background: var(--danger-bg);
      color: var(--danger-text);
    }

    .loading-cell {
      padding: 60px 16px !important;
      text-align: center;
    }

    .loading-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 12px;
      color: var(--text-light);
      font-size: 14px;
    }

    .spin-icon {
      font-size: 32px;
      color: var(--primary);
      animation: spin 1s linear infinite;
    }

    @keyframes spin {
      100% { transform: rotate(360deg); }
    }

    .empty-row {
      text-align: center;
      padding: 56px 16px !important;
    }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 12px;
      color: var(--text-light);
    }

    .empty-icon {
      font-size: 48px;
      opacity: 0.4;
      color: var(--text-light);
    }

    .empty-state p {
      margin: 0;
      font-size: 14px;
    }
  `]
})
export class CustomFieldsTableComponent {
  private readonly uiI18n = inject(I18nService);

  private readonly orderCell = viewChild.required<TemplateRef<unknown>>('orderCell');
  private readonly codeCell = viewChild.required<TemplateRef<unknown>>('codeCell');
  private readonly nameCell = viewChild.required<TemplateRef<unknown>>('nameCell');
  private readonly entityCell = viewChild.required<TemplateRef<unknown>>('entityCell');
  private readonly typeCell = viewChild.required<TemplateRef<unknown>>('typeCell');
  private readonly requiredCell = viewChild.required<TemplateRef<unknown>>('requiredCell');
  private readonly defaultCell = viewChild.required<TemplateRef<unknown>>('defaultCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');

  readonly rows = signal<CustomField[]>([]);
  private readonly rights = signal({ manage: false, edit: false, remove: false });

  readonly config = computed<TableConfig<CustomField>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: key === '#' ? '#' : this.uiI18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    const columns: TableConfig<CustomField>['columns'] = {
      orderNo: { header: header('#'), content: cell(this.orderCell), width: '70px' },
      code: { header: header('iam.kod_polya'), content: cell(this.codeCell) },
      name: { header: header('iam.nazvanie'), content: cell(this.nameCell) },
      entityType: { header: header('iam.suschnost'), content: cell(this.entityCell) },
      fieldType: { header: header('iam.tip_dannyh'), content: cell(this.typeCell) },
      isRequired: { header: header('iam.obyazatelnoe'), content: cell(this.requiredCell), width: '130px' },
      defaultValue: { header: header('iam.znachenie_po_umolchaniyu'), content: cell(this.defaultCell) }
    };
    const order = ['orderNo', 'code', 'name', 'entityType', 'fieldType', 'isRequired', 'defaultValue'];
    if (this.canManage || this.canEdit || this.canDelete) {
      columns['actions'] = { header: header('common.actions'), content: cell(this.actionsCell), width: '110px', align: 'right' };
      order.push('actions');
    }
    return {
      trackBy: (_index, f) => f.id ?? f.code,
      ariaLabel: this.uiI18n.translate('iam.dinamicheskie_atributy'),
      layout: 'fit',
      columns,
      columnsOrder: order
    };
  });

  @Input() isLoading = false;
  @Input() searchQuery = '';

  @Output() copyCode = new EventEmitter<string>();
  @Output() editField = new EventEmitter<CustomField>();
  @Output() deleteField = new EventEmitter<CustomField>();
  @Output() clearSearch = new EventEmitter<void>();
  @Output() createField = new EventEmitter<void>();

  /** Every field is loaded, so a header click (by keyboard too) sorts the whole list. */
  readonly sortValues = {
    orderNo: (f: CustomField) => f.orderNo ?? 0,
    code: (f: CustomField) => f.code,
    name: (f: CustomField) => f.name,
    entityType: (f: CustomField) => this.getEntityLabel(f.entityType),
    fieldType: (f: CustomField) => this.getTypeName(f.fieldType),
    isRequired: (f: CustomField) => (f.isRequired ? 0 : 1)
  };

  @Input() set fields(fields: CustomField[]) {
    this.rows.set(fields ?? []);
  }
  @Input() set canManage(value: boolean) { this.rights.update(r => ({ ...r, manage: value })); }
  get canManage(): boolean { return this.rights().manage; }
  @Input() set canEdit(value: boolean) { this.rights.update(r => ({ ...r, edit: value })); }
  get canEdit(): boolean { return this.rights().edit; }
  @Input() set canDelete(value: boolean) { this.rights.update(r => ({ ...r, remove: value })); }
  get canDelete(): boolean { return this.rights().remove; }

  getEntityLabel(ent: string): string {
    switch ((ent || '').toUpperCase()) {
      case 'USER': return this.uiI18n.translate('nav.users');
      case 'PROJECT': return this.uiI18n.translate('nav.projects');
      case 'TASK': return this.uiI18n.translate('nav.tasks');
      case 'NOTE': return this.uiI18n.translate('iam.zametka_note');
      case 'ORGANIZATION_UNIT': return this.uiI18n.translate('nav.org_units') || ent;
      default: return ent;
    }
  }

  getEntityIcon(ent: string): string {
    switch (ent.toUpperCase()) {
      case 'ALL': return 'apps';
      case 'USER': return 'person';
      case 'PROJECT': return 'folder';
      case 'TASK': return 'task_alt';
      case 'NOTE': return 'description';
      case 'ORGANIZATION_UNIT': return 'corporate_fare';
      default: return 'data_object';
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
      case 'string': return this.uiI18n.translate('iam.tekst');
      case 'number': return this.uiI18n.translate('iam.chislo');
      case 'boolean': return this.uiI18n.translate('iam.da_net');
      case 'date': return this.uiI18n.translate('iam.data');
      case 'select': return this.uiI18n.translate('projects.spisok');
      case 'user_ref': return this.uiI18n.translate('iam.user_ref');
      default: return type;
    }
  }

  getTypeIcon(type: string): string {
    switch (type) {
      case 'string': return 'format_quote';
      case 'number': return 'tag';
      case 'boolean': return 'toggle_on';
      case 'date': return 'calendar_today';
      case 'select': return 'list';
      case 'user_ref': return 'person';
      default: return 'help';
    }
  }
}
