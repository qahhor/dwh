import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { FormulaInputComponent } from './formula-input.component';
import {
  COMPUTED_TYPES,
  DEFAULTED_TYPES,
  FIELD_FORMULA_KEYS,
  type FieldFormulaKey,
  type SimpleField,
} from './new-form.models';

/**
 * The formulas of one field of a live form (ADR-0042): a computed value — the field is then read-only, optional and
 * has nothing else — or, for an entered field, the default a new record takes, when the field is shown, when an
 * optional field is required and when a saved record's field is read-only. In a new version a field whose records
 * hold entered values never becomes computed; a computed field may become an entered one.
 */
@Component({
  selector: 'app-new-form-field-logic',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTControlComponent, SMTSelectComponent, FormulaInputComponent],
  template: `
    <fieldset class="field-logic" [attr.data-testid]="'field-logic-' + index()">
      <legend>{{ 'studio.formula.logic' | t }}</legend>
      @if (computable()) {
        <smt-control
          [smtLabel]="'studio.formula.value_mode' | t"
          [smtHint]="(computedLocked() ? 'studio.formula.computed_locked' : 'studio.formula.value_mode_hint') | t"
        >
          <smt-select
            [options]="modes()"
            [value]="isComputed() ? 'computed' : 'entered'"
            [allowClear]="false"
            [disabled]="disabled() || (computedLocked() && !isComputed())"
            [attr.data-testid]="'field-mode-' + index()"
            (valueChange)="setMode($event)"
          />
        </smt-control>
      }
      @if (isComputed()) {
        <app-formula-input
          kind="formula"
          [label]="'studio.formula.formula' | t"
          [hint]="'studio.formula.formula_hint' | t"
          [value]="field().formula"
          [field]="field().id"
          [fields]="fields()"
          [disabled]="disabled()"
          [testId]="'field-formula-' + index()"
          (valueChange)="set('formula', $event)"
        />
      } @else {
        @if (defaultable()) {
          <app-formula-input
            kind="defaultFormula"
            [label]="'studio.formula.default' | t"
            [hint]="(field().defaultValue ? 'studio.formula.default_fixed' : 'studio.formula.default_hint') | t"
            [value]="field().defaultFormula"
            [field]="field().id"
            [fields]="fields()"
            [disabled]="disabled() || !!field().defaultValue"
            [testId]="'field-default-formula-' + index()"
            (valueChange)="set('defaultFormula', $event)"
          />
        }
        <app-formula-input
          kind="visibleIf"
          [label]="'studio.formula.visible' | t"
          [hint]="'studio.formula.visible_hint' | t"
          [value]="field().visibleIf"
          [field]="field().id"
          [fields]="fields()"
          [disabled]="disabled()"
          [testId]="'field-visible-' + index()"
          (valueChange)="set('visibleIf', $event)"
        />
        @if (!field().required) {
          <app-formula-input
            kind="requiredIf"
            [label]="'studio.formula.required' | t"
            [hint]="'studio.formula.required_hint' | t"
            [value]="field().requiredIf"
            [field]="field().id"
            [fields]="fields()"
            [disabled]="disabled()"
            [testId]="'field-required-' + index()"
            (valueChange)="set('requiredIf', $event)"
          />
        }
        <app-formula-input
          kind="readonlyIf"
          [label]="'studio.formula.readonly' | t"
          [hint]="'studio.formula.readonly_hint' | t"
          [value]="field().readonlyIf"
          [field]="field().id"
          [fields]="fields()"
          [disabled]="disabled()"
          [testId]="'field-readonly-' + index()"
          (valueChange)="set('readonlyIf', $event)"
        />
      }
    </fieldset>
  `,
  styles: [
    ':host { display: block; min-width: 0; }',
    '.field-logic { display: grid; gap: 12px; min-width: 0; margin: 0; padding: 12px; border: 1px dashed var(--border-color); border-radius: 8px; }',
    '.field-logic legend { color: var(--text-main); font-size: 13px; font-weight: 600; }',
  ],
})
export class NewFormFieldLogicComponent {
  private readonly i18n = inject(I18nService);
  readonly field = input.required<SimpleField>();
  readonly index = input.required<number>();
  /** Every field of the form: the formulas read the shown ones. */
  readonly fields = input<readonly SimpleField[]>([]);
  readonly disabled = input(false);
  /** The field as the active version stores it, or null for a field added in this version. */
  readonly stored = input<SimpleField | null>(null);
  readonly changed = output<SimpleField>();
  readonly isComputed = computed(() => this.field().formula !== undefined);
  readonly computable = computed(() => COMPUTED_TYPES.includes(this.field().type));
  readonly defaultable = computed(() => DEFAULTED_TYPES.includes(this.field().type));
  /** A stored field whose records hold entered values never becomes computed: the next save would replace them. */
  readonly computedLocked = computed(() => {
    const stored = this.stored();
    return stored !== null && !stored.formula;
  });
  readonly modes = computed(() => {
    this.i18n.currentLang();
    return [
      { id: 'entered', label: this.i18n.translate('studio.formula.mode_entered') },
      { id: 'computed', label: this.i18n.translate('studio.formula.mode_computed') },
    ];
  });

  /** A computed field is optional and has no default, condition or rule of its own; an entered one has no formula. */
  setMode(mode: unknown): void {
    const next: SimpleField = { ...this.field() };
    if (mode === 'computed') {
      if (this.computedLocked()) return;
      FIELD_FORMULA_KEYS.forEach((key) => delete next[key]);
      delete next.defaultValue;
      next.required = false;
      next.formula = this.field().formula ?? '';
    } else if (mode === 'entered') {
      delete next.formula;
    } else {
      return;
    }
    this.changed.emit(next);
  }

  set(key: FieldFormulaKey, value: string | undefined): void {
    const next: SimpleField = { ...this.field() };
    if (key === 'formula') next.formula = value ?? '';
    else if (value === undefined) delete next[key];
    else next[key] = value;
    this.changed.emit(next);
  }
}
