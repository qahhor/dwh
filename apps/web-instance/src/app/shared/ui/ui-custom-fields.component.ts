import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CustomField } from '../../core/models/custom-field.models';

@Component({
  selector: 'ui-custom-fields',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="custom-fields-grid" *ngIf="fields && fields.length > 0">
      <div class="field-item" *ngFor="let f of fields">
        <div class="field-header">
          <label class="field-label">{{ f.name }}</label>
          <span *ngIf="f.isRequired" class="req-tag">Обязательно</span>
        </div>

        <!-- String Input -->
        <input
          *ngIf="f.fieldType === 'string'"
          type="text"
          class="form-control"
          [ngModel]="values[f.code]"
          (ngModelChange)="onValueChange(f.code, $event)"
          [placeholder]="f.defaultValue || 'Текстовое значение...'"
        />

        <!-- Number Input -->
        <input
          *ngIf="f.fieldType === 'number'"
          type="number"
          class="form-control"
          [ngModel]="values[f.code]"
          (ngModelChange)="onValueChange(f.code, $event)"
          [placeholder]="f.defaultValue || '0'"
        />

        <!-- Date Input -->
        <input
          *ngIf="f.fieldType === 'date'"
          type="date"
          class="form-control"
          [ngModel]="values[f.code]"
          (ngModelChange)="onValueChange(f.code, $event)"
        />

        <!-- Boolean Toggle -->
        <label *ngIf="f.fieldType === 'boolean'" class="checkbox-label">
          <input
            type="checkbox"
            [ngModel]="values[f.code] === true || values[f.code] === 'true'"
            (ngModelChange)="onValueChange(f.code, $event)"
          />
          <span>Включено</span>
        </label>
      </div>
    </div>
  `,
  styles: [`
    .custom-fields-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
      gap: 12px;
      margin-top: 8px;
    }

    .field-item {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .field-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .field-label {
      font-size: 11px;
      font-weight: 500;
      color: var(--text-muted);
    }

    .req-tag {
      font-size: 9px;
      font-weight: 500;
      color: var(--danger);
      background-color: var(--danger-bg);
      padding: 1px 4px;
      border-radius: 3px;
    }

    .form-control {
      height: 32px;
      padding: 4px 8px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      font-family: inherit;
      outline: none;
      transition: border-color 0.15s ease;
    }

    .form-control:focus {
      border-color: var(--primary);
    }

    .checkbox-label {
      display: flex;
      align-items: center;
      gap: 8px;
      height: 32px;
      cursor: pointer;
      font-size: 13px;
      color: var(--text-main);
    }
  `]
})
export class UiCustomFieldsComponent {
  @Input() fields: CustomField[] = [];
  @Input() values: Record<string, any> = {};

  @Output() valuesChange = new EventEmitter<Record<string, any>>();

  onValueChange(code: string, value: any) {
    this.values[code] = value;
    this.valuesChange.emit({ ...this.values });
  }
}
