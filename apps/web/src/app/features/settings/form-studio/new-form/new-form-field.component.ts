import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import type { StudioLanguage } from '../form-studio.models';
import type { StudioTarget } from '../studio-targets.store';
import type { FormulaDocument } from '../studio-formulas.api';
import { FormulaInputComponent } from './formula-input.component';
import { NewFormFieldLogicComponent } from './new-form-field-logic.component';
import { NewFormLabelsComponent } from './new-form-labels.component';
import { LIVE_FIELD_TYPES, type SimpleField, type SimpleFieldOption, type SimpleFieldType } from './new-form.models';

/** The choices of one select (server limit). */
export const MAX_FIELD_OPTIONS = 50;

/** The type of each field kind, by its catalog key. */
const TYPE_KEYS: Readonly<Record<SimpleFieldType, string>> = {
  text: 'settings.simple_form.types.text',
  textarea: 'settings.simple_form.types.textarea',
  number: 'settings.simple_form.types.number',
  date: 'settings.simple_form.types.date',
  datetime: 'settings.simple_form.types.datetime',
  time: 'settings.simple_form.types.time',
  bool: 'settings.simple_form.types.bool',
  email: 'settings.simple_form.types.email',
  phone: 'settings.simple_form.types.phone',
  url: 'settings.simple_form.types.url',
  select: 'settings.simple_form.types.select',
  ref: 'settings.simple_form.types.ref',
  user: 'settings.simple_form.types.user',
};
const BASE_TYPES = (Object.keys(TYPE_KEYS) as SimpleFieldType[]).filter((type) => !LIVE_FIELD_TYPES.includes(type));

/**
 * One field of a simple form. In a new version (`versioning`) a field that the active version already has
 * (`stored`) keeps its key and type, is hidden instead of removed, never takes a shorter text, fewer
 * decimals or another default, and becomes required only with a default (ADR-0041); the reason is shown
 * next to each locked control. A live form (`live`) also offers a select with fixed choices, a reference to
 * a reference list or another live form and a user; a stored select keeps every choice records may hold and
 * a stored reference keeps its target. A live form's field has its formulas and conditions (ADR-0042). A column of a
 * document's table of lines (`line`, ADR-0043) has no list flag, default or condition: it is entered or computed by a
 * formula over its row and the header's entered fields.
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
    FormulaInputComponent,
    NewFormFieldLogicComponent,
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
  /** A live form: the field types of format 3 are offered. */
  readonly live = input(false);
  /** The reference lists and the live forms a reference may name. */
  readonly lists = input<StudioTarget[]>([]);
  readonly forms = input<StudioTarget[]>([]);
  /** The field as the active version stores it, or null for a field added in this version. */
  readonly stored = input<SimpleField | null>(null);
  /** Every field of the form: the formulas of a live form read the shown ones (ADR-0042). */
  readonly fields = input<readonly SimpleField[]>([]);
  /** A column of a table of lines (ADR-0043). */
  readonly line = input(false);
  /** What a column's formula reads: the document's tables, this table among them (ADR-0043). */
  readonly document = input<FormulaDocument | null>(null);
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
    const types =
      this.live() || LIVE_FIELD_TYPES.includes(this.field().type) ? [...BASE_TYPES, ...LIVE_FIELD_TYPES] : BASE_TYPES;
    return types.map((id) => ({ id, label: this.i18n.translate(TYPE_KEYS[id]) }));
  });
  readonly targetKinds = computed(() => {
    this.i18n.currentLang();
    return [
      { id: 'list', label: this.i18n.translate('studio.field.target_kind_list') },
      { id: 'form', label: this.i18n.translate('studio.field.target_kind_form') },
    ];
  });
  readonly targetOptions = computed(() => {
    const language = this.language();
    const target = this.field().target;
    const known = target?.kind === 'form' ? this.forms() : this.lists();
    const offered = known.map((item) => ({ id: item.code, label: `${item.labels[language]} (${item.code})` }));
    if (target?.code && !offered.some((item) => item.id === target.code))
      offered.unshift({ id: target.code, label: target.code });
    return offered;
  });
  readonly defaultOptions = computed(() => {
    const language = this.language();
    return (this.field().options ?? []).map((option) => ({
      id: option.code,
      label: option.labels[language] || option.code,
    }));
  });
  private readonly language = computed<StudioLanguage>(() => {
    const language = this.i18n.currentLang();
    return language === 'uz' || language === 'en' ? language : 'ru';
  });
  readonly maxOptions = MAX_FIELD_OPTIONS;

  patch(change: Partial<SimpleField>): void {
    this.changed.emit({ ...this.field(), ...change });
  }
  changeType(value: unknown): void {
    if (this.locked() || typeof value !== 'string' || !this.options().some((option) => option.id === value)) return;
    const next = { ...this.field(), type: value as SimpleFieldType };
    delete next.maxLength;
    delete next.scale;
    delete next.defaultValue;
    delete next.options;
    delete next.target;
    if (value === 'text' || value === 'textarea') next.maxLength = value === 'text' ? 255 : 1000;
    if (value === 'number') next.scale = 2;
    if (value === 'select') next.options = [{ code: 'option1', labels: { ru: '', uz: '', en: '' } }];
    if (value === 'ref') next.target = { kind: 'list', code: '' };
    this.changed.emit(next);
  }
  setLabel(language: StudioLanguage, value: string): void {
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
  /** A column becomes computed with an empty formula, or entered again (ADR-0043). */
  setComputed(computed: boolean): void {
    const next = { ...this.field() };
    if (computed) {
      next.formula = '';
      next.required = false;
    } else delete next.formula;
    this.changed.emit(next);
  }
  setFormula(value: string | undefined): void {
    this.patch({ formula: value ?? '' });
  }
  setHidden(hidden: boolean): void {
    const next = { ...this.field() };
    if (hidden) next.hidden = true;
    else delete next.hidden;
    this.changed.emit(next);
  }
  /** A choice the active version already has: records may hold its code, so it stays. */
  optionLocked(option: SimpleFieldOption): boolean {
    return (this.stored()?.options ?? []).some((stored) => stored.code === option.code);
  }
  addOption(): void {
    const options = this.field().options ?? [];
    if (options.length >= MAX_FIELD_OPTIONS) return;
    let number = options.length + 1;
    while (options.some((option) => option.code === `option${number}`)) number++;
    this.patch({ options: [...options, { code: `option${number}`, labels: { ru: '', uz: '', en: '' } }] });
  }
  setOptionCode(index: number, value: unknown): void {
    const options = [...(this.field().options ?? [])];
    if (!options[index] || this.optionLocked(options[index])) return;
    options[index] = { ...options[index], code: this.text(value) };
    this.patch({ options });
  }
  setOptionLabel(index: number, language: StudioLanguage, value: string): void {
    const options = [...(this.field().options ?? [])];
    if (!options[index]) return;
    options[index] = { ...options[index], labels: { ...options[index].labels, [language]: value } };
    this.patch({ options });
  }
  removeOption(index: number): void {
    const options = [...(this.field().options ?? [])];
    if (!options[index] || this.optionLocked(options[index]) || options.length <= 1) return;
    options.splice(index, 1);
    this.patch({ options });
  }
  setTargetKind(value: unknown): void {
    if (this.locked() || (value !== 'list' && value !== 'form')) return;
    this.patch({ target: { kind: value, code: '' } });
  }
  setTargetCode(value: unknown): void {
    if (this.locked()) return;
    this.patch({ target: { kind: this.field().target?.kind ?? 'list', code: this.text(value ?? '') } });
  }
  number(value: unknown): number | undefined {
    return typeof value === 'number' ? value : undefined;
  }
  text(value: unknown): string {
    return value === null ? '' : String(value);
  }
}
