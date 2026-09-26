/* Our code, after the idea of the kit's `smt-input` (smartup-ui-kit@6472beb,
 * components/forms/input). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it pulled in ngx-mask, Biruni's number shortcuts,
 * per-user pinned values, tooltips and a palette per theme instead of tokens,
 * and drew its own label and messages next to smt-control's.
 *
 * This one is only the field; smt-control around it gives the label, hint and
 * error. It covers what the application's screens type into: text, email, url,
 * tel, search, password (with a show/hide button whose name says which) and
 * number (the model is a number, or null when empty). An optional icon sits
 * before the text, `clearable` adds a named clear button once there is text.
 * Keys pressed in the field bubble to the host, so `(keydown.enter)` on
 * <smt-input> works; leaving the field emits `touch`.
 *
 * Signal Forms: <smt-control [smtLabel]="…"><smt-input [formField]="form.name" /></smt-control>
 * Plain:        <smt-input type="search" clearable [(value)]="query" smtIcon="search" /> */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  linkedSignal,
  model,
  numberAttribute,
  output,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import type { FormValueControl, ValidationError, WithOptionalFieldTree } from '@angular/forms/signals';
import { SMTI18nService } from '../../../i18n';
import { SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES } from '../../../forms/field-registry';
import { shouldShowSMTFormControlError } from '../../../forms/form-control-validation';

/** The field types a person types into; everything else has its own control. */
export type SMTInputType = 'text' | 'email' | 'url' | 'tel' | 'search' | 'password' | 'number';

/** Text, or for `type="number"` a number (null when empty). */
export type SMTInputValue = string | number | null;

let nextInputId = 0;

function optionalNumber(value: unknown): number | undefined {
  return value === undefined || value === null || value === '' ? undefined : numberAttribute(value);
}

@Component({
  selector: 'smt-input',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  templateUrl: './input.component.html',
  styleUrl: './input.scss',
  host: {
    class: 'smt-input',
    '[class.smt-input--invalid]': 'hasError()',
    '[class.smt-input--disabled]': 'isDisabled()',
    '[class.smt-input--sm]': "size() === 'sm'",
  },
  hostDirectives: [...SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES],
})
export class SMTInputComponent implements FormValueControl<SMTInputValue> {
  readonly i18n = inject(SMTI18nService);

  readonly type = input<SMTInputType>('text');

  /** The inner field's id, for an outside label; not `id`, which would also land on the host element. */
  readonly customId = input<string | null>(null, { alias: 'smtFieldId' });

  readonly name = input('');

  /** data-testid of the inner field, so a test types where the person types. */
  readonly testId = input<string | null>(null, { alias: 'smtTestId' });

  readonly placeholder = input('');

  /** The field's name where no label names it (a search box in a toolbar). */
  readonly ariaLabel = input('', { alias: 'smtAriaLabel' });

  /** Ids of hints outside any smt-control that describe the field. */
  readonly describedBy = input<string | null>('', { alias: 'smtDescribedBy' });

  /** Material Symbols ligature shown before the text. */
  readonly icon = input('', { alias: 'smtIcon' });

  /** A button that empties the field, shown once there is text. */
  readonly clearable = input(false, { transform: booleanAttribute });

  /** Where a CDK focus trap (a dialog) puts focus first: the inner field gets `cdkFocusInitial`. */
  readonly focusInitial = input(false, { alias: 'smtFocusInitial', transform: booleanAttribute });

  readonly size = input<'sm' | 'md'>('md', { alias: 'smtSize' });

  readonly autocomplete = input<string | null>(null);

  readonly inputmode = input<string | null>(null);

  readonly spellcheck = input<boolean | null>(null);

  /** The native pattern attribute; not `pattern`, which Signal Forms fills with its RegExp validators. */
  readonly htmlPattern = input<string | null>(null, { alias: 'smtPattern' });

  readonly maxLength = input<number | undefined, unknown>(undefined, { transform: optionalNumber });

  readonly minLength = input<number | undefined, unknown>(undefined, { transform: optionalNumber });

  /** Bounds and step of a number; not `min`/`max`, which Signal Forms keeps for its own validators. */
  readonly numberMin = input<number | undefined, unknown>(undefined, { alias: 'smtMin', transform: optionalNumber });

  readonly numberMax = input<number | undefined, unknown>(undefined, { alias: 'smtMax', transform: optionalNumber });

  readonly step = input<number | undefined, unknown>(undefined, { alias: 'smtStep', transform: optionalNumber });

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly required = input(false, { transform: booleanAttribute });

  readonly invalid = input(false, { transform: booleanAttribute });

  /** An error the screen decides itself (after a failed save), shown at once; Signal Forms fills `invalid`. */
  readonly screenInvalid = input(false, { alias: 'smtInvalid', transform: booleanAttribute });

  readonly errors = input<readonly WithOptionalFieldTree<ValidationError>[]>([]);

  /** Bound by Signal Forms; the field's touched state. */
  readonly touched = input(false, { transform: booleanAttribute });

  /** The person left the field (Angular 22 reads `touch`, not `touchedChange`). */
  readonly touch = output<void>();

  /** Every edit by the person, even one that leaves the value as it was — what ngModel reports, like a native field. */
  readonly edited = output<SMTInputValue>();

  /** The clear button emptied the field — a screen that searches at once on clearing listens here. */
  readonly cleared = output<void>();

  readonly value = model<SMTInputValue>('');

  private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');

  /** The password is shown as text while the person asks for it. */
  readonly revealed = signal(false);

  /** Touched here or by the form; follows the form again when it resets `touched`. */
  protected readonly wasTouched = linkedSignal(() => this.touched());

  /** Disabled by a reactive form or ngModel through the value accessor. */
  private readonly formsDisabled = signal(false);

  readonly isDisabled = computed(() => this.disabled() || this.formsDisabled());

  readonly text = computed(() => {
    const value = this.value();
    return value === null || value === undefined ? '' : String(value);
  });

  readonly nativeType = computed(() => (this.type() === 'password' && this.revealed() ? 'text' : this.type()));

  readonly showClear = computed(() => this.clearable() && this.text() !== '' && !this.isDisabled() && !this.readonly());

  readonly hasError = computed(() =>
    shouldShowSMTFormControlError({
      invalid: this.invalid(),
      legacyInvalid: this.screenInvalid(),
      errors: this.errors(),
      touched: this.wasTouched(),
      required: this.required(),
      empty: this.text().trim() === '',
    })
  );

  readonly fieldId = `smt-input-${nextInputId++}`;

  onInput(event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    this.write(this.type() !== 'number' ? raw : this.toNumber(raw));
  }

  onBlur(): void {
    this.markTouched();
  }

  /** The person left the field. */
  markTouched(): void {
    this.wasTouched.set(true);
    this.touch.emit();
  }

  clear(): void {
    this.write(this.type() === 'number' ? null : '');
    this.cleared.emit();
    this.field().nativeElement.focus();
  }

  toggleReveal(): void {
    this.revealed.update(shown => !shown);
  }

  focus(): void {
    this.field().nativeElement.focus();
  }

  /** Called by SMTInputValueAccessor. */
  setDisabledFromForms(disabled: boolean): void {
    this.formsDisabled.set(disabled);
  }

  private write(value: SMTInputValue): void {
    this.value.set(value);
    this.edited.emit(value);
  }

  private toNumber(raw: string): number | null {
    const number = raw.trim() === '' ? null : Number(raw);
    return number === null || Number.isNaN(number) ? null : number;
  }
}
