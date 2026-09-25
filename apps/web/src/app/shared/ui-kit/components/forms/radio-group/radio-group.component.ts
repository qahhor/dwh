/* Our code, after the idea of the kit's `smt-radio-group` and `smt-radio`
 * (smartup-ui-kit@6472beb, components/forms/radio-group, radio). See
 * ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: the group only passed a name to projected native
 * radios and left each in the tab order, so arrows did not move between
 * them; it had no group name for assistive technology, and its colours were
 * a palette per theme, not tokens.
 *
 * This one follows the WAI-ARIA APG radio group: role="radiogroup" with
 * role="radio" items, one tab stop (the chosen item, else the first
 * available), arrows move and choose, Space chooses; the group is named by
 * the smt-control label around it or `smtAriaLabel`. Items come from
 * `options`, so a form field of any type — string, number, enum — binds as is.
 *
 * Signal Forms: <smt-control [smtLabel]="…"><smt-radio-group [formField]="form.kind" [options]="kinds" /></smt-control>
 * Plain:        <smt-radio-group [(value)]="kind" [options]="kinds" smtAriaLabel="…" /> */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  input,
  linkedSignal,
  model,
  output,
  signal,
  viewChildren,
  ViewEncapsulation,
} from '@angular/core';
import type { FormValueControl, ValidationError, WithOptionalFieldTree } from '@angular/forms/signals';
import { SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES } from '../../../forms/field-registry';
import { shouldShowSMTFormControlError } from '../../../forms/form-control-validation';

export interface SMTRadioOption<T> {
  readonly value: T;
  readonly label: string;
  /** A line under the label, read as the item's description. */
  readonly hint?: string;
  readonly disabled?: boolean;
}

let nextGroupId = 0;

@Component({
  selector: 'smt-radio-group',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  templateUrl: './radio-group.component.html',
  styleUrl: './radio-group.scss',
  host: {
    class: 'smt-radio-group',
    '[class.smt-radio-group--horizontal]': "orientation() === 'horizontal'",
    '[class.smt-radio-group--invalid]': 'hasError()',
    '[class.smt-radio-group--cards]': "appearance() === 'cards'",
  },
  hostDirectives: [...SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES],
})
export class SMTRadioGroupComponent<T> implements FormValueControl<T | null> {
  /** The inner field's id, for an outside label; not `id`, which would also land on the host element. */
  readonly customId = input<string | null>(null, { alias: 'smtFieldId' });

  readonly options = input<readonly SMTRadioOption<T>[]>([]);

  readonly orientation = input<'vertical' | 'horizontal'>('vertical', { alias: 'smtOrientation' });

  /** `cards` frames each item, for a choice whose items carry a description worth reading. */
  readonly appearance = input<'plain' | 'cards'>('plain', { alias: 'smtAppearance' });

  /** Names the group when no smt-control label does. */
  readonly ariaLabel = input('', { alias: 'smtAriaLabel' });

  /** How two values are compared; by identity unless the values are objects rebuilt on each load. */
  readonly compareWith = input<(a: T | null, b: T | null) => boolean>(Object.is);

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly required = input(false, { transform: booleanAttribute });

  readonly invalid = input(false, { transform: booleanAttribute });

  readonly errors = input<readonly WithOptionalFieldTree<ValidationError>[]>([]);

  /** Bound by Signal Forms; the field's touched state. */
  readonly touched = input(false, { transform: booleanAttribute });

  /** Tells Signal Forms the person has left the field (Angular 22 reads `touch`, not `touchedChange`). */
  readonly touch = output<void>();

  readonly value = model<T | null>(null);

  private readonly items = viewChildren<ElementRef<HTMLElement>>('item');

  /** Touched here or by the form; follows the form again when it resets `touched`. */
  protected readonly wasTouched = linkedSignal(() => this.touched());

  /** Disabled by a reactive form or ngModel through the value accessor. */
  private readonly formsDisabled = signal(false);

  readonly isDisabled = computed(() => this.disabled() || this.formsDisabled());

  readonly selectedIndex = computed(() => {
    const value = this.value();
    const same = this.compareWith();
    return this.options().findIndex(option => same(option.value, value));
  });

  /** The one item in the tab order. */
  readonly tabStop = computed(() => {
    const selected = this.selectedIndex();
    if (selected >= 0 && this.available(selected)) return selected;
    return this.options().findIndex((_, index) => this.available(index));
  });

  readonly hasError = computed(() =>
    shouldShowSMTFormControlError({
      invalid: this.invalid(),
      errors: this.errors(),
      touched: this.wasTouched(),
      required: this.required(),
      empty: this.selectedIndex() < 0,
    })
  );

  readonly groupId = `smt-radio-group-${nextGroupId++}`;

  itemId(index: number): string {
    return `${this.groupId}-item-${index}`;
  }

  itemDisabled(index: number): boolean {
    return this.isDisabled() || !!this.options()[index]?.disabled;
  }

  choose(index: number): void {
    if (!this.available(index) || this.readonly()) return;
    this.value.set(this.options()[index].value);
    this.markTouched();
  }

  onKeydown(event: KeyboardEvent, index: number): void {
    const step = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[event.key];
    if (event.key === ' ') {
      event.preventDefault();
      this.choose(index);
      return;
    }
    if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault();
      const order = this.options().map((_, i) => i);
      const target = (event.key === 'Home' ? order : order.reverse()).find(i => this.available(i));
      if (target !== undefined) this.moveTo(target);
      return;
    }
    if (step === undefined) return;
    event.preventDefault();
    const count = this.options().length;
    for (let offset = 1; offset <= count; offset++) {
      const next = (index + step * offset + count * offset) % count;
      if (this.available(next)) {
        this.moveTo(next);
        return;
      }
    }
  }

  onFocusOut(event: FocusEvent): void {
    const host = event.currentTarget as HTMLElement;
    if (!host.contains(event.relatedTarget as Node | null)) this.markTouched();
  }

  /** The person left the field. */
  markTouched(): void {
    this.wasTouched.set(true);
    this.touch.emit();
  }

  /** Called by SMTRadioGroupValueAccessor. */
  setDisabledFromForms(disabled: boolean): void {
    this.formsDisabled.set(disabled);
  }

  /** Arrows choose as they move (APG); read-only only moves the focus. */
  private moveTo(index: number): void {
    this.items()[index]?.nativeElement.focus();
    if (!this.readonly()) this.choose(index);
  }

  private available(index: number): boolean {
    return index >= 0 && index < this.options().length && !this.itemDisabled(index);
  }
}
