/* Our code, after the idea of the kit's `smt-phone-input` (smartup-ui-kit@6472beb,
 * components/phone-input). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it loaded every country's flag from a third-party
 * site — this application ships its own assets and runs in closed networks —
 * and carried a 1 700-line country list with a custom dropdown.
 *
 * This one is a native country list for the markets the product serves
 * (and "other" for any number) beside a telephone field that writes the
 * digits into the country's mask as they are typed. The value is stored in
 * E.164, `+998901234567`; an incomplete number is said under the field once
 * it is left. The country list is a part of the field, so an smt-control
 * label names the number itself.
 *
 * <smt-control [smtLabel]="…"><smt-phone-input [(ngModel)]="user.phone" name="phone" /></smt-control> */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  model,
  output,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import type { FormValueControl, ValidationError, WithOptionalFieldTree } from '@angular/forms/signals';
import { SMTI18nService } from '../../../i18n';
import { SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES } from '../../../forms/field-registry';
import { shouldShowSMTFormControlError } from '../../../forms/form-control-validation';
import { formatNational, joinPhone, nationalLength, SMT_PHONE_COUNTRIES, splitPhone, type SMTPhoneCountry } from './phone-utils';

const OTHER = 'other';
/** E.164 allows fifteen digits in all. */
const MAX_DIGITS = 15;

let nextPhoneId = 0;

@Component({
  selector: 'smt-phone-input',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './phone-input.scss',
  host: {
    class: 'smt-phone-input',
    '[class.smt-phone-input--invalid]': 'hasError()',
  },
  hostDirectives: [...SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES],
  template: `
    <div class="smt-phone-input__control">
      <select
        class="smt-phone-input__country"
        data-smt-field-part
        [attr.aria-label]="i18n.messages().phone.country"
        [disabled]="isDisabled() || readonly()"
        [value]="countryKey()"
        (change)="onCountry($event)">
        @for (country of countries; track country.iso) {
          <option [value]="country.iso">{{ country.iso }} +{{ country.code }}</option>
        }
        <option [value]="other">{{ i18n.messages().phone.other }}</option>
      </select>
      <input
        class="smt-phone-input__number"
        type="tel"
        inputmode="tel"
        [autocomplete]="country() ? 'tel-national' : 'tel'"
        [id]="customId() || fieldId"
        [attr.name]="name() || null"
        [placeholder]="placeholder()"
        [value]="display()"
        [disabled]="isDisabled()"
        [readOnly]="readonly()"
        [required]="required()"
        [attr.aria-describedby]="incomplete() ? problemId : null"
        [attr.aria-invalid]="hasError() ? 'true' : null"
        (input)="onInput($event)"
        (blur)="onBlur()" />
    </div>
    @if (incomplete()) {
      <p class="smt-phone-input__problem" [id]="problemId">{{ i18n.messages().phone.incomplete }}</p>
    }
  `,
})
export class SMTPhoneInputComponent implements FormValueControl<string> {
  readonly i18n = inject(SMTI18nService);

  /** The number field's id, for an outside label; not `id`, which would also land on the host element. */
  readonly customId = input<string | null>(null, { alias: 'smtFieldId' });

  readonly name = input('');

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly required = input(false, { transform: booleanAttribute });

  readonly invalid = input(false, { transform: booleanAttribute });

  readonly errors = input<readonly WithOptionalFieldTree<ValidationError>[]>([]);

  /** Bound by Signal Forms; the field's touched state. */
  readonly touched = input(false, { transform: booleanAttribute });

  /** Tells Signal Forms the person has left the field. */
  readonly touch = output<void>();

  /** E.164 (`+998901234567`) or '' for no number. */
  readonly value = model<string>('');

  /** The chosen country; follows the value, keeping a choice the value cannot tell apart (+7). */
  readonly country = linkedSignal<string, SMTPhoneCountry | null>({
    source: () => this.value() ?? '',
    // "Other" chosen by the person stays, even when the digits happen to start like a listed code.
    computation: (value, previous) => (value && previous && previous.value === null && this.otherChosen
      ? null
      : splitPhone(value, previous?.value ?? null).country),
  });

  protected readonly wasTouched = linkedSignal(() => this.touched());

  /** Disabled by a reactive form or ngModel through the value accessor. */
  private readonly formsDisabled = signal(false);

  readonly isDisabled = computed(() => this.disabled() || this.formsDisabled());

  readonly digits = computed(() => {
    const country = this.country();
    const all = (this.value() ?? '').replace(/\D/g, '');
    return country && all.startsWith(country.code) ? all.slice(country.code.length) : all;
  });

  readonly display = computed(() => formatNational(this.digits(), this.country()));

  readonly countryKey = computed(() => this.country()?.iso ?? OTHER);

  /** The mask with dashes for digits, e.g. "(__) ___-__-__". */
  readonly placeholder = computed(() => this.country()?.mask.replace(/0/g, '_') ?? '+');

  /** Typed but short of the country's length, once the field was left. */
  readonly incomplete = computed(() => {
    const country = this.country();
    const digits = this.digits();
    return this.wasTouched() && !!country && digits.length > 0 && digits.length < nationalLength(country);
  });

  readonly hasError = computed(() =>
    shouldShowSMTFormControlError({
      invalid: this.invalid(),
      errors: this.errors(),
      touched: this.wasTouched(),
      required: this.required(),
      empty: !this.value(),
      localError: this.incomplete(),
    })
  );

  private otherChosen = false;

  readonly countries = SMT_PHONE_COUNTRIES;

  readonly other = OTHER;

  readonly fieldId = `smt-phone-input-${nextPhoneId++}`;

  readonly problemId = `${this.fieldId}-problem`;

  onInput(event: Event): void {
    const country = this.country();
    const limit = country ? nationalLength(country) : MAX_DIGITS;
    const digits = (event.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, limit);
    (event.target as HTMLInputElement).value = formatNational(digits, country);
    this.value.set(joinPhone(country, digits));
  }

  onCountry(event: Event): void {
    const key = (event.target as HTMLSelectElement).value;
    const country = SMT_PHONE_COUNTRIES.find(item => item.iso === key) ?? null;
    const digits = this.digits();
    this.otherChosen = country === null;
    this.country.set(country);
    this.value.set(joinPhone(country, country ? digits.slice(0, nationalLength(country)) : digits));
  }

  onBlur(): void {
    this.wasTouched.set(true);
    this.touch.emit();
  }

  /** Called by SMTPhoneInputValueAccessor. */
  setDisabledFromForms(disabled: boolean): void {
    this.formsDisabled.set(disabled);
  }
}
