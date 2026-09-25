/* Our code: the ngModel bridge for smt-time-picker (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTTimePickerComponent } from './time-picker.component';

@Directive({
  selector: 'smt-time-picker[ngModel], smt-time-picker[formControl], smt-time-picker[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTTimePickerValueAccessor), multi: true }],
})
export class SMTTimePickerValueAccessor extends PickerValueAccessor<string | null> {
  protected override readonly empty: string | null = null;

  protected readonly picker = inject(SMTTimePickerComponent) as unknown as BridgedPicker<string | null>;
}
