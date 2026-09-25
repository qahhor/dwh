/* Our code: the ngModel bridge for smt-phone-input (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTPhoneInputComponent } from './phone-input.component';

@Directive({
  selector: 'smt-phone-input[ngModel], smt-phone-input[formControl], smt-phone-input[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTPhoneInputValueAccessor), multi: true }],
})
export class SMTPhoneInputValueAccessor extends PickerValueAccessor<string> {
  protected override readonly empty: string = '';

  protected readonly picker = inject(SMTPhoneInputComponent) as unknown as BridgedPicker<string>;
}
