/* Our code: the ngModel bridge for smt-range-slider (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTRange, SMTRangeSliderComponent } from './range-slider.component';

@Directive({
  selector: 'smt-range-slider[ngModel], smt-range-slider[formControl], smt-range-slider[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTRangeSliderValueAccessor), multi: true }],
})
export class SMTRangeSliderValueAccessor extends PickerValueAccessor<SMTRange | null> {
  protected readonly picker = inject(SMTRangeSliderComponent) as unknown as BridgedPicker<SMTRange | null>;
}
