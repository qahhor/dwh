/* Our code, after the idea of the kit's `smt-weekday-toggle` (smartup-ui-kit@6472beb,
 * components/weekday-toggle). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: its day names were English constants for the host
 * to translate, and it painted two variants per theme.
 *
 * Days of the week to pick several of (delivery days, a schedule): a named
 * group of toggle buttons, each with aria-pressed, its short name shown and
 * its full name read. The names come from Intl in the product's language,
 * Monday first; the value is the ISO day numbers chosen, 1 (Monday) to 7,
 * in week order.
 *
 * Signal Forms: <smt-weekday-toggle [formField]="route.days" smtAriaLabel="Delivery days" />
 * Plain:        <smt-weekday-toggle [(value)]="days" /> */
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

/** ISO day of the week: 1 is Monday, 7 is Sunday. */
export type SMTWeekday = 1 | 2 | 3 | 4 | 5 | 6 | 7;

export const SMT_WEEKDAYS: readonly SMTWeekday[] = [1, 2, 3, 4, 5, 6, 7];

/** 2024-01-01 was a Monday; the day after it is Tuesday, and so on. */
const MONDAY = Date.UTC(2024, 0, 1);

@Component({
  selector: 'smt-weekday-toggle',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './weekday-toggle.scss',
  host: {
    class: 'smt-weekday-toggle',
    role: 'group',
    '[attr.aria-label]': 'ariaLabel() || i18n.messages().weekday.name',
    '[class.smt-weekday-toggle--disabled]': 'isDisabled()',
    '[class.smt-weekday-toggle--invalid]': 'hasError()',
  },
  hostDirectives: [...SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES],
  template: `
    @for (day of days(); track day.value) {
      <button
        type="button"
        class="smt-weekday-toggle__day"
        [class.smt-weekday-toggle__day--on]="isOn(day.value)"
        [attr.aria-pressed]="isOn(day.value) ? 'true' : 'false'"
        [attr.aria-label]="day.name"
        [disabled]="isDisabled()"
        (click)="toggle(day.value)"
        (blur)="markTouched()">{{ day.short }}</button>
    }
  `,
})
export class SMTWeekdayToggleComponent implements FormValueControl<readonly SMTWeekday[]> {
  readonly i18n = inject(SMTI18nService);

  readonly ariaLabel = input('', { alias: 'smtAriaLabel' });

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly required = input(false, { transform: booleanAttribute });

  readonly invalid = input(false, { transform: booleanAttribute });

  readonly errors = input<readonly WithOptionalFieldTree<ValidationError>[]>([]);

  /** Bound by Signal Forms; the field's touched state. */
  readonly touched = input(false, { transform: booleanAttribute });

  /** The person left the group (Angular 22 reads `touch`). */
  readonly touch = output<void>();

  /** The days chosen, in week order. */
  readonly value = model<readonly SMTWeekday[]>([]);

  protected readonly wasTouched = linkedSignal(() => this.touched());

  private readonly formsDisabled = signal(false);

  readonly isDisabled = computed(() => this.disabled() || this.formsDisabled());

  /** Short and full names in the product's language. */
  readonly days = computed(() => {
    const locale = this.i18n.locale();
    const short = new Intl.DateTimeFormat(locale, { weekday: 'short', timeZone: 'UTC' });
    const long = new Intl.DateTimeFormat(locale, { weekday: 'long', timeZone: 'UTC' });
    return SMT_WEEKDAYS.map(value => {
      const date = new Date(MONDAY + (value - 1) * 86_400_000);
      return { value, short: short.format(date), name: long.format(date) };
    });
  });

  readonly hasError = computed(() =>
    shouldShowSMTFormControlError({
      invalid: this.invalid(),
      errors: this.errors(),
      touched: this.wasTouched(),
      required: this.required(),
      empty: (this.value() ?? []).length === 0,
    })
  );

  isOn(day: SMTWeekday): boolean {
    return (this.value() ?? []).includes(day);
  }

  toggle(day: SMTWeekday): void {
    if (this.isDisabled() || this.readonly()) return;
    const chosen = new Set(this.value() ?? []);
    if (chosen.has(day)) chosen.delete(day);
    else chosen.add(day);
    this.value.set(SMT_WEEKDAYS.filter(value => chosen.has(value)));
  }

  markTouched(): void {
    this.wasTouched.set(true);
    this.touch.emit();
  }

  /** Called by SMTWeekdayToggleValueAccessor. */
  setDisabledFromForms(disabled: boolean): void {
    this.formsDisabled.set(disabled);
  }
}
