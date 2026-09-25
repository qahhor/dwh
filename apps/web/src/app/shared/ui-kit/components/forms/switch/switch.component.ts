/* Our code, after the idea of the kit's `smt-switch` (smartup-ui-kit@6472beb,
 * components/forms/switch). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it hid a checkbox and drew the switch in a span,
 * so assistive technology met a checkbox, not an on/off switch; its colours
 * were a palette per theme, not tokens.
 *
 * This one is a button with role="switch" and aria-checked (WAI-ARIA APG
 * switch): Space or Enter flips it, and its name is the `smtLabel` written
 * beside it, or the smt-control label around it. A switch changes something
 * at once, so it takes no "required" — a setting that must be accepted is a
 * checkbox.
 *
 * Signal Forms: <smt-switch [formField]="form.enabled" [smtLabel]="'…' | t" />
 * Plain:        <smt-switch [(checked)]="enabled" [smtLabel]="…" /> */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  linkedSignal,
  model,
  output,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import type { FormCheckboxControl, ValidationError, WithOptionalFieldTree } from '@angular/forms/signals';
import { SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES } from '../../../forms/field-registry';
import { shouldShowSMTFormControlError } from '../../../forms/form-control-validation';

export type SMTSwitchSize = 'sm' | 'md';

let nextSwitchId = 0;

@Component({
  selector: 'smt-switch',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  templateUrl: './switch.component.html',
  styleUrl: './switch.scss',
  host: {
    class: 'smt-switch',
    '[class.smt-switch--sm]': "size() === 'sm'",
    '[class.smt-switch--on]': 'checked()',
    '[class.smt-switch--disabled]': 'isDisabled()',
    '[class.smt-switch--invalid]': 'hasError()',
  },
  hostDirectives: [...SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES],
})
export class SMTSwitchComponent implements FormCheckboxControl {
  /** The inner field's id, for an outside label; not `id`, which would also land on the host element. */
  readonly customId = input<string | null>(null, { alias: 'smtFieldId' });

  /** Written beside the switch and names it; leave empty inside a labelled smt-control. */
  readonly label = input('', { alias: 'smtLabel' });

  /** Id of a heading elsewhere that names the switch, as in a settings row. */
  readonly externalLabelledBy = input('', { alias: 'smtLabelledBy' });

  /** Ids of text elsewhere that describes what the switch does. */
  readonly describedBy = input('', { alias: 'smtDescribedBy' });

  /** A name for a switch with no visible label, such as one in a table row. */
  readonly ariaLabel = input('', { alias: 'smtAriaLabel' });

  readonly size = input<SMTSwitchSize>('md', { alias: 'smtSize' });

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly invalid = input(false, { transform: booleanAttribute });

  readonly errors = input<readonly WithOptionalFieldTree<ValidationError>[]>([]);

  /** Bound by Signal Forms; the field's touched state. */
  readonly touched = input(false, { transform: booleanAttribute });

  /** Fired only when the person flips it, not when the value is set from outside. */
  readonly userChange = output<boolean>({ alias: 'smtUserChange' });

  /** Tells Signal Forms the person has left the field (Angular 22 reads `touch`, not `touchedChange`). */
  readonly touch = output<void>();

  readonly checked = model(false);

  /** Touched here or by the form; follows the form again when it resets `touched`. */
  protected readonly wasTouched = linkedSignal(() => this.touched());

  /** Disabled by a reactive form or ngModel through the value accessor. */
  private readonly formsDisabled = signal(false);

  readonly isDisabled = computed(() => this.disabled() || this.formsDisabled());

  readonly hasError = computed(() =>
    shouldShowSMTFormControlError({ invalid: this.invalid(), errors: this.errors(), touched: this.wasTouched() })
  );

  readonly labelledBy = computed(() => (this.label() ? this.labelId : this.externalLabelledBy().trim() || null));

  readonly resolvedAriaLabel = computed(() => (!this.labelledBy() && this.ariaLabel().trim()) || null);

  readonly buttonId = `smt-switch-${nextSwitchId++}`;

  readonly labelId = `${this.buttonId}-label`;

  toggle(): void {
    if (this.isDisabled() || this.readonly()) return;
    const next = !this.checked();
    this.checked.set(next);
    this.markTouched();
    this.userChange.emit(next);
  }

  onBlur(): void {
    this.markTouched();
  }

  /** The person left the field. */
  markTouched(): void {
    this.wasTouched.set(true);
    this.touch.emit();
  }

  /** Called by SMTSwitchValueAccessor. */
  setDisabledFromForms(disabled: boolean): void {
    this.formsDisabled.set(disabled);
  }
}
