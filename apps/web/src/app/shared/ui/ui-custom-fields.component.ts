import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CustomField } from '../../core/models/custom-field.models';
import { I18nService } from '../../core/services/i18n.service';
import { LookupSources } from '../lookups/lookup-sources';
import { SMTDynamicFieldComponent, SMTDynamicFieldDef } from '../ui-kit/components/forms/dynamic-field';
import type { SMTSelectOption } from '../ui-kit/components/forms/select';

/**
 * A record's custom fields, each drawn by smt-dynamic-field from its
 * definition (roadmap item 36): the label, the required mark and the error
 * come from smt-control for every type, a list field is a searchable select,
 * a yes/no field a switch, and a person is searched on the server — the old
 * dropdown offered only the first hundred users.
 */
@Component({
  selector: 'ui-custom-fields',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTDynamicFieldComponent],
  template: `
    @if (fields.length > 0) {
      <div class="custom-fields-grid">
        @for (field of definitions(); track field.code) {
          <smt-dynamic-field
            [field]="field"
            [userSource]="users"
            [value]="values[field.code] ?? null"
            (valueChange)="onValueChange(field.code, $event)" />
        }
      </div>
    }
  `,
  styles: [`
    .custom-fields-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
      gap: 12px;
      margin-top: 8px;
    }
  `]
})
export class UiCustomFieldsComponent {
  private readonly i18n = inject(I18nService);

  readonly users = inject(LookupSources).activeUsers;

  @Input() fields: CustomField[] = [];

  @Input() values: Record<string, unknown> = {};

  @Output() valuesChange = new EventEmitter<Record<string, unknown>>();

  private cache: { fields: CustomField[]; lang: string; definitions: SMTDynamicFieldDef[] } | null = null;

  /** The fields as definitions; the same array while the fields and the language stay the same. */
  definitions(): SMTDynamicFieldDef[] {
    const lang = this.i18n.currentLang();
    if (this.cache?.fields !== this.fields || this.cache.lang !== lang) {
      this.cache = { fields: this.fields, lang, definitions: this.fields.map(field => this.definitionOf(field)) };
    }
    return this.cache.definitions;
  }

  onValueChange(code: string, value: unknown): void {
    this.values = { ...this.values, [code]: value };
    this.valuesChange.emit(this.values);
  }

  private definitionOf(field: CustomField): SMTDynamicFieldDef {
    const base = { code: field.code, label: field.name, required: field.isRequired };
    switch (field.fieldType) {
      case 'number':
        return { ...base, type: 'number', placeholder: field.defaultValue || '0' };
      case 'boolean':
        return { ...base, type: 'boolean' };
      case 'date':
        return { ...base, type: 'date' };
      case 'select':
        return { ...base, type: 'select', options: selectOptions(field), placeholder: this.i18n.translate('ui.custom_fields.vyberite_znachenie') };
      case 'user_ref':
        return { ...base, type: 'user_ref', placeholder: this.i18n.translate('ui.custom_fields.vyberite_polzovatelya') };
      default:
        return { ...base, type: 'string', placeholder: field.defaultValue || this.i18n.translate('ui.custom_fields.text_value_placeholder') };
    }
  }
}

/** A list field's choices from its JSON: plain values or `{ value, label }` objects. */
export function selectOptions(field: CustomField): SMTSelectOption<string | number | boolean>[] {
  if (!field.optionsJson) return [];
  try {
    const options: unknown = JSON.parse(field.optionsJson);
    if (!Array.isArray(options)) return [];
    return options.flatMap(option => {
      if (typeof option === 'string' || typeof option === 'number' || typeof option === 'boolean') {
        return [{ id: option, label: String(option) }];
      }
      if (option && typeof option === 'object' && 'value' in option) {
        const value = (option as { value: unknown }).value;
        const label = 'label' in option ? (option as { label: unknown }).label : value;
        if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
          return [{ id: value, label: String(label) }];
        }
      }
      return [];
    });
  } catch {
    return [];
  }
}
