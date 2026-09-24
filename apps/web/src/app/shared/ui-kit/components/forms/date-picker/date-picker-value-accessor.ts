/* Our code: the ngModel bridges for the date pickers (see
 * ../picker-value-accessor.ts for why they exist and how they behave). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTDatePickerComponent } from './date-picker.component';
import { SMTDateRangePickerComponent } from './date-range-picker.component';
import type { DateRange } from './date-utils';

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
