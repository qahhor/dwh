import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { NewFormLabelsComponent } from './new-form-labels.component';
import type { SimpleField, SimpleFieldType } from './new-form.models';

/**
 * One field of a simple form. In a new version (`versioning`) a field that the active version already has
 * (`stored`) keeps its key and type, is hidden instead of removed, never takes a shorter text, fewer
 * decimals or another default, and becomes required only with a default (ADR-0041); the reason is shown
 * next to each locked control.
 */
@Component({
  selector: 'app-new-form-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    SMTCheckboxComponent,
    SMTControlComponent,
    SMTInputComponent,
    SMTSelectComponent,
    NewFormLabelsComponent,
  ],
  templateUrl: './new-form-field.component.html',
  styleUrl: './new-form-field.component.css',
})
export class NewFormFieldComponent {
  private readonly i18n = inject(I18nService);
  readonly field = input.required<SimpleField>();
  readonly index = input.required<number>();
  readonly count = input.required<number>();
  readonly disabled = input(false);
  /** A new version is edited: defaults and hiding are offered. */
  readonly versioning = input(false);
  /** The field as the active version stores it, or null for a field added in this version. */
  readonly stored = input<SimpleField | null>(null);
  readonly changed = output<SimpleField>();
  readonly moved = output<number>();
  readonly removed = output<void>();
  readonly locked = computed(() => this.stored() !== null);
  readonly minMaxLength = computed(() => this.stored()?.maxLength ?? 1);
  readonly minScale = computed(() => this.stored()?.scale ?? 0);
  /** An existing optional field without a default cannot become required: its records may be empty. */
  readonly requiredLocked = computed(() => {
    const stored = this.stored();
    return stored !== null && !stored.required && !stored.defaultValue;
  });
  readonly options = computed(() => {
    this.i18n.currentLang();
    return [
      ['text', 'settings.simple_form.types.text'],
      ['textarea', 'settings.simple_form.types.textarea'],
      ['number', 'settings.simple_form.types.number'],
      ['date', 'settings.simple_form.types.date'],
      ['datetime', 'settings.simple_form.types.datetime'],
      ['time', 'settings.simple_form.types.time'],
      ['bool', 'settings.simple_form.types.bool'],
      ['email', 'settings.simple_form.types.email'],
      ['phone', 'settings.simple_form.types.phone'],
      ['url', 'settings.simple_form.types.url'],
    ].map(([id, key]) => ({ id, label: this.i18n.translate(key) }));
  });
  patch(change: Partial<SimpleField>): void {
    this.changed.emit({ ...this.field(), ...change });
  }
  changeType(value: unknown): void {
    if (this.locked() || typeof value !== 'string' || !this.options().some((option) => option.id === value)) return;
    const next = { ...this.field(), type: value as SimpleFieldType };
    delete next.maxLength;
    delete next.scale;
    delete next.defaultValue;
    if (value === 'text' || value === 'textarea') next.maxLength = value === 'text' ? 255 : 1000;
    if (value === 'number') next.scale = 2;
    this.changed.emit(next);
  }
  setLabel(language: 'ru' | 'uz' | 'en', value: string): void {
    this.patch({ labels: { ...this.field().labels, [language]: value } });
  }
  setDefault(value: unknown): void {
    if (this.locked()) return;
    const next = { ...this.field() };
    const text = value === null || value === undefined ? '' : String(value);
    if (text === '') delete next.defaultValue;
    else next.defaultValue = text;
    this.changed.emit(next);
  }
  setHidden(hidden: boolean): void {
    const next = { ...this.field() };
    if (hidden) next.hidden = true;
    else delete next.hidden;
    this.changed.emit(next);
  }
  number(value: unknown): number | undefined {
    return typeof value === 'number' ? value : undefined;
  }
  text(value: unknown): string {
    return value === null ? '' : String(value);
  }
}
