import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../../core/services/api.service';
import { ToastService } from '../../../core/services/toast.service';
import { PermissionService } from '../../../core/services/permission.service';
import { CustomField } from '../../../core/models/custom-field.models';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';

@Component({
  selector: 'app-custom-fields',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    UiButtonComponent,
    UiModalComponent
  ],
  template: `
    <div class="custom-fields-page">
      <!-- Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">Динамические атрибуты</h1>
          <span class="view-subtitle">Настройка произвольных полей для пользователей, проектов и задач</span>
        </div>
        <div class="header-actions">
          <button class="icon-refresh-btn" (click)="loadFields()" title="Обновить">
            <span class="material-symbols-outlined">refresh</span>
          </button>
          <ui-button
            *ngIf="canManage()"
            variant="primary"
            icon="add"
            (clicked)="openCreateModal()"
          >
            Добавить поле
          </ui-button>
        </div>
      </div>

      <!-- Entity Type Filter Tabs -->
      <div class="filter-tabs">
        <button
          *ngFor="let ent of ['ALL', 'USER', 'PROJECT', 'TASK']"
          class="tab-btn"
          [class.active]="selectedEntity === ent"
          (click)="filterByEntity(ent)"
        >
          {{ getEntityLabel(ent) }}
        </button>
      </div>

      <!-- Grid / Table -->
      <div class="card table-card">
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th>Код поля</th>
                <th>Название</th>
                <th>Сущность</th>
                <th>Тип данных</th>
                <th>Обязательное</th>
                <th>Значение по умолчанию</th>
                <th *ngIf="canManage()" class="text-right">Действия</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let f of filteredFields">
                <td class="code-cell font-mono">{{ f.code }}</td>
                <td class="name-cell font-medium">{{ f.name }}</td>
                <td>
                  <span class="entity-badge" [ngClass]="f.entityType.toLowerCase()">
                    {{ f.entityType }}
                  </span>
                </td>
                <td>
                  <span class="type-badge">{{ getTypeName(f.fieldType) }}</span>
                </td>
                <td>
                  <span class="status-indicator" [class.active]="f.isRequired">
                    {{ f.isRequired ? 'Да' : 'Нет' }}
                  </span>
                </td>
                <td class="text-muted">{{ f.defaultValue || '—' }}</td>
                <td *ngIf="canManage()" class="text-right">
                  <button class="action-btn" (click)="openEditModal(f)" title="Редактировать">
                    <span class="material-symbols-outlined">edit</span>
                  </button>
                  <button class="action-btn danger" (click)="deleteField(f)" title="Удалить">
                    <span class="material-symbols-outlined">delete</span>
                  </button>
                </td>
              </tr>

              <tr *ngIf="filteredFields.length === 0">
                <td colspan="7" class="empty-row">
                  <div class="empty-state">
                    <span class="material-symbols-outlined empty-icon">tune</span>
                    <p>Динамические поля не найдены</p>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Create / Edit Modal -->
      <ui-modal
        *ngIf="showModal"
        [title]="editingField ? 'Редактирование поля' : 'Новое динамическое поле'"
        (closed)="closeModal()"
      >
        <div class="modal-form">
          <div class="form-group" *ngIf="!editingField">
            <label class="form-label">Целевая сущность <span class="req">*</span></label>
            <select class="form-select" [(ngModel)]="formData.entityType">
              <option value="USER">Пользователь (USER)</option>
              <option value="PROJECT">Проект (PROJECT)</option>
              <option value="TASK">Задача (TASK)</option>
            </select>
          </div>

          <div class="form-row">
            <div class="form-group flex-1">
              <label class="form-label">Код поля (slug) <span class="req">*</span></label>
              <input
                type="text"
                class="form-input font-mono"
                [(ngModel)]="formData.code"
                [disabled]="!!editingField"
                placeholder="например: inn, budget"
              />
            </div>

            <div class="form-group flex-1">
              <label class="form-label">Название поля <span class="req">*</span></label>
              <input
                type="text"
                class="form-input"
                [(ngModel)]="formData.name"
                placeholder="например: ИНН, Бюджет проекта"
              />
            </div>
          </div>

          <div class="form-row" *ngIf="!editingField">
            <div class="form-group flex-1">
              <label class="form-label">Тип данных <span class="req">*</span></label>
              <select class="form-select" [(ngModel)]="formData.fieldType">
                <option value="string">Текст (string)</option>
                <option value="number">Число (number)</option>
                <option value="boolean">Логический переключатель (boolean)</option>
                <option value="date">Дата (date)</option>
                <option value="select">Выпадающий список (select)</option>
              </select>
            </div>

            <div class="form-group flex-1">
              <label class="form-label">Значение по умолчанию</label>
              <input
                type="text"
                class="form-input"
                [(ngModel)]="formData.defaultValue"
                placeholder="Не обязательно"
              />
            </div>
          </div>

          <div class="form-group checkbox-group">
            <label class="checkbox-label">
              <input type="checkbox" [(ngModel)]="formData.isRequired" />
              <span>Обязательное для заполнения</span>
            </label>
          </div>

          <div class="modal-actions">
            <ui-button variant="secondary" (clicked)="closeModal()">Отмена</ui-button>
            <ui-button variant="primary" [loading]="saving" (clicked)="saveField()">Сохранить</ui-button>
          </div>
        </div>
      </ui-modal>
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
      align-items: center;
      margin-bottom: 20px;
    }

    .view-title {
      font-size: 24px;
      font-weight: 700;
      color: var(--text-primary, #ffffff);
      margin: 0 0 4px 0;
    }

    .view-subtitle {
      font-size: 14px;
      color: var(--text-secondary, #94a3b8);
    }

    .header-actions {
      display: flex;
      gap: 12px;
      align-items: center;
    }

    .icon-refresh-btn {
      width: 36px;
      height: 36px;
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 8px;
      color: var(--text-secondary, #94a3b8);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.2s;
    }

    .icon-refresh-btn:hover {
      background: rgba(255, 255, 255, 0.1);
      color: #ffffff;
    }

    .filter-tabs {
      display: flex;
      gap: 8px;
      margin-bottom: 20px;
      background: rgba(255, 255, 255, 0.03);
      padding: 4px;
      border-radius: 10px;
      border: 1px solid rgba(255, 255, 255, 0.06);
      width: fit-content;
    }

    .tab-btn {
      padding: 8px 16px;
      border-radius: 8px;
      border: none;
      background: transparent;
      color: var(--text-secondary, #94a3b8);
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.2s;
    }

    .tab-btn.active {
      background: var(--primary, #3b82f6);
      color: #ffffff;
    }

    .card {
      background: var(--bg-card, #1e293b);
      border-radius: 12px;
      border: 1px solid rgba(255, 255, 255, 0.08);
      overflow: hidden;
    }

    .data-table {
      width: 100%;
      border-collapse: collapse;
      text-align: left;
    }

    .data-table th {
      padding: 14px 16px;
      background: rgba(0, 0, 0, 0.2);
      font-size: 12px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-secondary, #94a3b8);
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);
    }

    .data-table td {
      padding: 14px 16px;
      border-bottom: 1px solid rgba(255, 255, 255, 0.04);
      font-size: 14px;
      color: var(--text-primary, #ffffff);
    }

    .data-table tr:hover td {
      background: rgba(255, 255, 255, 0.02);
    }

    .code-cell {
      color: #38bdf8;
      font-size: 13px;
    }

    .entity-badge {
      display: inline-block;
      padding: 3px 8px;
      border-radius: 6px;
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.04em;
    }

    .entity-badge.user { background: rgba(168, 85, 247, 0.15); color: #c084fc; }
    .entity-badge.project { background: rgba(59, 130, 246, 0.15); color: #60a5fa; }
    .entity-badge.task { background: rgba(34, 197, 94, 0.15); color: #4ade80; }

    .type-badge {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 4px;
      background: rgba(255, 255, 255, 0.06);
      font-size: 12px;
      color: #cbd5e1;
    }

    .status-indicator {
      display: inline-block;
      padding: 2px 6px;
      border-radius: 4px;
      font-size: 12px;
    }

    .status-indicator.active {
      color: #f59e0b;
      font-weight: 600;
    }

    .action-btn {
      background: transparent;
      border: none;
      color: var(--text-secondary, #94a3b8);
      cursor: pointer;
      padding: 4px;
      border-radius: 6px;
      transition: all 0.2s;
    }

    .action-btn:hover { color: #ffffff; background: rgba(255, 255, 255, 0.1); }
    .action-btn.danger:hover { color: #ef4444; background: rgba(239, 68, 68, 0.1); }

    .text-right { text-align: right; }
    .text-muted { color: #64748b; }
    .font-mono { font-family: monospace; }
    .font-medium { font-weight: 500; }

    .empty-state {
      padding: 40px 20px;
      text-align: center;
      color: #64748b;
    }

    .empty-icon {
      font-size: 40px;
      margin-bottom: 8px;
      opacity: 0.5;
    }

    .modal-form {
      display: flex;
      flex-direction: column;
      gap: 16px;
      padding: 4px 0;
    }

    .form-row {
      display: flex;
      gap: 16px;
    }

    .flex-1 { flex: 1; }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .form-label {
      font-size: 13px;
      font-weight: 500;
      color: #cbd5e1;
    }

    .req { color: #ef4444; }

    .form-input, .form-select {
      background: rgba(0, 0, 0, 0.2);
      border: 1px solid rgba(255, 255, 255, 0.12);
      border-radius: 8px;
      padding: 10px 12px;
      color: #ffffff;
      font-size: 14px;
      outline: none;
      transition: border-color 0.2s;
    }

    .form-input:focus, .form-select:focus {
      border-color: var(--primary, #3b82f6);
    }

    .checkbox-group {
      margin-top: 4px;
    }

    .checkbox-label {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 14px;
      color: #cbd5e1;
      cursor: pointer;
    }

    .modal-actions {
      display: flex;
      justify-content: flex-end;
      gap: 12px;
      margin-top: 16px;
      padding-top: 16px;
      border-top: 1px solid rgba(255, 255, 255, 0.08);
    }
  `]
})
export class CustomFieldsComponent implements OnInit {
  fields: CustomField[] = [];
  filteredFields: CustomField[] = [];
  selectedEntity: string = 'ALL';

  showModal: boolean = false;
  editingField: CustomField | null = null;
  saving: boolean = false;

  formData: any = {
    entityType: 'USER',
    code: '',
    name: '',
    fieldType: 'string',
    isRequired: false,
    defaultValue: '',
    orderNo: 0
  };

  constructor(
    private api: ApiService,
    private toast: ToastService,
    private permService: PermissionService
  ) {}

  ngOnInit() {
    this.loadFields();
  }

  canManage(): boolean {
    return this.permService.hasPermission('md.custom_fields', 'create') ||
           this.permService.hasPermission('md.custom_fields', 'update') ||
           this.permService.hasPermission('system.custom_fields', 'create');
  }

  loadFields() {
    this.api.get<CustomField[]>('/custom-fields').subscribe({
      next: data => {
        this.fields = data;
        this.applyFilter();
      },
      error: err => this.toast.error('Ошибка загрузки динамических полей')
    });
  }

  filterByEntity(entity: string) {
    this.selectedEntity = entity;
    this.applyFilter();
  }

  applyFilter() {
    if (this.selectedEntity === 'ALL') {
      this.filteredFields = [...this.fields];
    } else {
      this.filteredFields = this.fields.filter(f => f.entityType === this.selectedEntity);
    }
  }

  getEntityLabel(ent: string): string {
    switch (ent) {
      case 'ALL': return 'Все сущности';
      case 'USER': return 'Пользователи';
      case 'PROJECT': return 'Проекты';
      case 'TASK': return 'Задачи';
      default: return ent;
    }
  }

  getTypeName(type: string): string {
    switch (type) {
      case 'string': return 'Текст';
      case 'number': return 'Число';
      case 'boolean': return 'Да / Нет';
      case 'date': return 'Дата';
      case 'select': return 'Список';
      default: return type;
    }
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
      orderNo: 0
    };
    this.showModal = true;
  }

  openEditModal(f: CustomField) {
    this.editingField = f;
    this.formData = {
      ...f
    };
    this.showModal = true;
  }

  closeModal() {
    this.showModal = false;
    this.editingField = null;
  }

  saveField() {
    if (!this.formData.name || !this.formData.code) {
      this.toast.error('Заполните обязательные поля');
      return;
    }

    this.saving = true;
    if (this.editingField) {
      this.api.patch(`/custom-fields/${this.editingField.id}`, {
        name: this.formData.name,
        isRequired: this.formData.isRequired,
        defaultValue: this.formData.defaultValue,
        orderNo: this.formData.orderNo
      }).subscribe({
        next: () => {
          this.saving = false;
          this.toast.success('Поле успешно обновлено');
          this.closeModal();
          this.loadFields();
        },
        error: () => {
          this.saving = false;
          this.toast.error('Ошибка сохранения поля');
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
        orderNo: this.formData.orderNo,
        options: []
      }).subscribe({
        next: () => {
          this.saving = false;
          this.toast.success('Поле успешно создано');
          this.closeModal();
          this.loadFields();
        },
        error: () => {
          this.saving = false;
          this.toast.error('Ошибка создания поля');
        }
      });
    }
  }

  deleteField(f: CustomField) {
    if (confirm(`Удалить динамическое поле "${f.name}" (${f.code})?`)) {
      this.api.delete(`/custom-fields/${f.id}`).subscribe({
        next: () => {
          this.toast.success('Поле удалено');
          this.loadFields();
        },
        error: () => this.toast.error('Ошибка удаления поля')
      });
    }
  }
}
