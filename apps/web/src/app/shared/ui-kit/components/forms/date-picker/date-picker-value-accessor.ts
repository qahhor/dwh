/* Our code. The thin ControlValueAccessor bridge chosen in the forms
 * decision (variant A): new screens bind the pickers with Signal Forms
 * `[formField]`; screens still on `ngModel` or reactive forms go through
 * this directive. It applies only when one of those directives is present,
 * so it never competes with `[formField]`. */
import { Directive, forwardRef, inject } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { SMTDatePickerComponent } from './date-picker.component';
import { SMTDateRangePickerComponent } from './date-range-picker.component';
import type { DateRange } from './date-utils';

interface BridgedPicker<T> {
  value: { set(value: T): void; subscribe(callback: (value: T) => void): { unsubscribe(): void } };
  touch: { subscribe(callback: () => void): { unsubscribe(): void } };
  setDisabledFromForms(disabled: boolean): void;
}

abstract class PickerValueAccessor<T> implements ControlValueAccessor {
  protected abstract readonly picker: BridgedPicker<T>;

  /** True while the form writes a value in, so that write is not reported back as a change. */
  private writing = false;

  writeValue(value: T): void {
    this.writing = true;
    this.picker.value.set(value);
    this.writing = false;
  }

  registerOnChange(onChange: (value: T) => void): void {
    this.picker.value.subscribe(value => {
      if (!this.writing) onChange(value);
    });
  }

  registerOnTouched(onTouched: () => void): void {
    this.picker.touch.subscribe(() => onTouched());
  }

  setDisabledState(disabled: boolean): void {
    this.picker.setDisabledFromForms(disabled);
  }
}

@Directive({
  selector: 'smt-date-picker[ngModel], smt-date-picker[formControl], smt-date-picker[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTDatePickerValueAccessor), multi: true }],
})
export class SMTDatePickerValueAccessor extends PickerValueAccessor<string | null> {
  protected readonly picker = inject(SMTDatePickerComponent) as unknown as BridgedPicker<string | null>;
}

@Directive({
  selector:
    'smt-date-range-picker[ngModel], smt-date-range-picker[formControl], smt-date-range-picker[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTDateRangePickerValueAccessor), multi: true }],
})
export class SMTDateRangePickerValueAccessor extends PickerValueAccessor<DateRange | null> {
  protected readonly picker = inject(SMTDateRangePickerComponent) as unknown as BridgedPicker<DateRange | null>;
}
